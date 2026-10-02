import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebServer {

    private static final int PORT = 8080;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Serve main web page
        server.createContext("/", new StaticFileHandler());

        // API routes
        server.createContext("/api/students", new StudentsApiHandler());
        server.createContext("/api/stats", new StatsApiHandler());
        server.createContext("/api/login", new LoginApiHandler());
        server.createContext("/api/export", new ExportCsvHandler());

        server.setExecutor(null); // default executor
        server.start();

        System.out.println("=================================================");
        System.out.println("  Student Management System Web Server Started!  ");
        System.out.println("=================================================");
        System.out.println("  URL: http://localhost:" + PORT);
        System.out.println("  Data file: " + StudentDataManager.getCsvFile().getAbsolutePath());
        System.out.println("=================================================");
    }

    // Static File Handler (serves index.html or other static assets)
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            String path = exchange.getRequestURI().getPath();

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // Route / to index.html
            if (path == null || path.equals("/") || path.equals("/index.html")) {
                File htmlFile = findIndexHtmlFile();
                if (htmlFile != null && htmlFile.exists()) {
                    byte[] bytes = readFileBytes(htmlFile);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                    return;
                } else {
                    String fallback = "<html><body><h1>Student Management System</h1><p>index.html not found.</p></body></html>";
                    byte[] bytes = fallback.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                    return;
                }
            }

            // 404 for other static paths
            String notFound = "404 Not Found";
            exchange.sendResponseHeaders(404, notFound.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(notFound.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    // API: /api/students (GET, POST, PUT, DELETE)
    static class StudentsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            String method = exchange.getRequestMethod();

            if ("OPTIONS".equalsIgnoreCase(method)) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            // Path could be /api/students or /api/students/{id}
            String[] segments = path.split("/");
            String subPath = segments.length > 3 ? segments[3] : "";

            if ("GET".equalsIgnoreCase(method)) {
                handleGet(exchange);
            } else if ("POST".equalsIgnoreCase(method)) {
                // Can be Add, or update/delete with action in body/subPath
                if ("update".equalsIgnoreCase(subPath)) {
                    handleUpdate(exchange);
                } else if ("delete".equalsIgnoreCase(subPath)) {
                    handleDelete(exchange);
                } else {
                    handleAdd(exchange);
                }
            } else if ("PUT".equalsIgnoreCase(method)) {
                handleUpdate(exchange);
            } else if ("DELETE".equalsIgnoreCase(method)) {
                handleDelete(exchange);
            } else {
                sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            }
        }

        private void handleGet(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            String q = null;
            if (query != null && query.contains("q=")) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length > 1 && "q".equalsIgnoreCase(pair[0])) {
                        q = URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name());
                        break;
                    }
                }
            }

            List<Student> list = (q != null && !q.trim().isEmpty())
                    ? StudentDataManager.searchStudents(q.trim())
                    : StudentDataManager.loadStudents();

            sendJsonResponse(exchange, 200, studentsListToJson(list));
        }

        private void handleAdd(HttpExchange exchange) throws IOException {
            String body = readRequestBody(exchange);
            int id = getJsonInt(body, "id", -1);
            String name = getJsonString(body, "name");
            int age = getJsonInt(body, "age", -1);
            String dept = getJsonString(body, "department");
            String phone = getJsonString(body, "phone");
            String email = getJsonString(body, "email");

            // Validation
            if (id <= 0) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Student ID must be a positive number.\"}");
                return;
            }
            if (name.isEmpty() || dept.isEmpty() || phone.isEmpty() || email.isEmpty()) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"All fields are required.\"}");
                return;
            }
            if (age < 5 || age > 120) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Age must be between 5 and 120.\"}");
                return;
            }
            if (!email.contains("@") || !email.contains(".")) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Please provide a valid email address.\"}");
                return;
            }

            // Duplicate check
            if (StudentDataManager.getStudentById(id) != null) {
                sendJsonResponse(exchange, 409, "{\"success\":false,\"error\":\"Student with ID " + id + " already exists.\"}");
                return;
            }

            Student s = new Student(id, name, age, dept, phone, email);
            boolean ok = StudentDataManager.addStudent(s);
            if (ok) {
                sendJsonResponse(exchange, 201, "{\"success\":true,\"message\":\"Student added successfully.\",\"student\":" + studentToJson(s) + "}");
            } else {
                sendJsonResponse(exchange, 500, "{\"success\":false,\"error\":\"Failed to save student record.\"}");
            }
        }

        private void handleUpdate(HttpExchange exchange) throws IOException {
            String body = readRequestBody(exchange);
            int id = getJsonInt(body, "id", -1);
            String name = getJsonString(body, "name");
            int age = getJsonInt(body, "age", -1);
            String dept = getJsonString(body, "department");
            String phone = getJsonString(body, "phone");
            String email = getJsonString(body, "email");

            if (id <= 0) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Invalid Student ID.\"}");
                return;
            }
            if (name.isEmpty() || dept.isEmpty() || phone.isEmpty() || email.isEmpty()) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"All fields are required.\"}");
                return;
            }
            if (age < 5 || age > 120) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Age must be between 5 and 120.\"}");
                return;
            }

            Student s = new Student(id, name, age, dept, phone, email);
            boolean ok = StudentDataManager.updateStudent(s);
            if (ok) {
                sendJsonResponse(exchange, 200, "{\"success\":true,\"message\":\"Student updated successfully.\",\"student\":" + studentToJson(s) + "}");
            } else {
                sendJsonResponse(exchange, 404, "{\"success\":false,\"error\":\"Student with ID " + id + " not found.\"}");
            }
        }

        private void handleDelete(HttpExchange exchange) throws IOException {
            String body = readRequestBody(exchange);
            int id = getJsonInt(body, "id", -1);

            // Also check query param ?id=... or URL segment
            if (id <= 0) {
                String query = exchange.getRequestURI().getQuery();
                if (query != null && query.contains("id=")) {
                    for (String param : query.split("&")) {
                        String[] pair = param.split("=");
                        if (pair.length > 1 && "id".equalsIgnoreCase(pair[0])) {
                            try {
                                id = Integer.parseInt(pair[1]);
                            } catch (NumberFormatException ignored) {}
                            break;
                        }
                    }
                }
            }

            if (id <= 0) {
                String[] segments = exchange.getRequestURI().getPath().split("/");
                if (segments.length > 3) {
                    try {
                        id = Integer.parseInt(segments[3]);
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (id <= 0) {
                sendJsonResponse(exchange, 400, "{\"success\":false,\"error\":\"Invalid Student ID for deletion.\"}");
                return;
            }

            boolean ok = StudentDataManager.deleteStudent(id);
            if (ok) {
                sendJsonResponse(exchange, 200, "{\"success\":true,\"message\":\"Student " + id + " deleted successfully.\"}");
            } else {
                sendJsonResponse(exchange, 404, "{\"success\":false,\"error\":\"Student " + id + " not found.\"}");
            }
        }
    }

    // API: /api/stats (metrics)
    static class StatsApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            List<Student> students = StudentDataManager.loadStudents();
            int total = students.size();
            Set<String> depts = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            double totalAge = 0;
            Map<String, Integer> deptCounts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

            for (Student s : students) {
                depts.add(s.getDepartment().toUpperCase());
                totalAge += s.getAge();
                String d = s.getDepartment().toUpperCase();
                deptCounts.put(d, deptCounts.getOrDefault(d, 0) + 1);
            }

            double avgAge = total > 0 ? (totalAge / total) : 0;
            String avgStr = String.format(Locale.US, "%.1f", avgAge);

            StringBuilder deptJson = new StringBuilder("{");
            int idx = 0;
            for (Map.Entry<String, Integer> entry : deptCounts.entrySet()) {
                if (idx++ > 0) deptJson.append(",");
                deptJson.append("\"").append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
            }
            deptJson.append("}");

            String json = String.format(
                "{\"total\":%d,\"departmentsCount\":%d,\"avgAge\":%s,\"deptBreakdown\":%s}",
                total, depts.size(), avgStr, deptJson.toString()
            );

            sendJsonResponse(exchange, 200, json);
        }
    }

    // API: /api/login
    static class LoginApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String body = readRequestBody(exchange);
            String user = getJsonString(body, "username");
            String pass = getJsonString(body, "password");

            if (("admin".equalsIgnoreCase(user) && "admin".equals(pass)) ||
                ("admin".equalsIgnoreCase(user) && "1234".equals(pass))) {
                sendJsonResponse(exchange, 200, "{\"success\":true,\"username\":\"" + escapeJson(user) + "\",\"role\":\"Administrator\"}");
            } else {
                sendJsonResponse(exchange, 401, "{\"success\":false,\"error\":\"Invalid credentials. Use admin / admin\"}");
            }
        }
    }

    // API: /api/export (download students.csv)
    static class ExportCsvHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            File csv = StudentDataManager.getCsvFile();
            if (csv.exists()) {
                byte[] bytes = readFileBytes(csv);
                exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=UTF-8");
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"students.csv\"");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                sendJsonResponse(exchange, 404, "{\"error\":\"CSV file not found\"}");
            }
        }
    }

    // Helper utilities
    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int read;
            while ((read = is.read(buf)) != -1) {
                baos.write(buf, 0, read);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static byte[] readFileBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int read;
            while ((read = fis.read(buf)) != -1) {
                baos.write(buf, 0, read);
            }
            return baos.toByteArray();
        }
    }

    private static File findIndexHtmlFile() {
        File f1 = new File("index.html");
        if (f1.exists()) return f1;
        File f2 = new File("../index.html");
        if (f2.exists()) return f2;
        File cur = new File(".").getAbsoluteFile();
        if (cur.getName().equalsIgnoreCase("prototype")) {
            File parentIndex = new File(cur.getParentFile(), "index.html");
            if (parentIndex.exists()) return parentIndex;
        }
        return null;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String studentToJson(Student s) {
        return String.format(
            "{\"id\":%d,\"name\":\"%s\",\"age\":%d,\"department\":\"%s\",\"phone\":\"%s\",\"email\":\"%s\"}",
            s.getId(),
            escapeJson(s.getName()),
            s.getAge(),
            escapeJson(s.getDepartment()),
            escapeJson(s.getPhone()),
            escapeJson(s.getEmail())
        );
    }

    private static String studentsListToJson(List<Student> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(studentToJson(list.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String getJsonString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\").trim();
        }
        return "";
    }

    private static int getJsonInt(String json, String key, int defaultValue) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"?(\\d+)\"?");
        Matcher m = p.matcher(json);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}
