@echo off
setlocal

rem Check if java is already in PATH
where java >nul 2>nul
if %ERRORLEVEL% equ 0 goto RUN_SERVER

rem Check installed user JDK
if exist "%LOCALAPPDATA%\Programs\Java\jdk17.0.20_12\bin\java.exe" (
    set "JAVA_HOME=%LOCALAPPDATA%\Programs\Java\jdk17.0.20_12"
    set "PATH=%LOCALAPPDATA%\Programs\Java\jdk17.0.20_12\bin;%PATH%"
    goto RUN_SERVER
)

rem Check other common Java locations
if exist "%JAVA_HOME%\bin\java.exe" (
    set "PATH=%JAVA_HOME%\bin;%PATH%"
    goto RUN_SERVER
)

echo [ERROR] Java 17 could not be found.
pause
exit /b 1

:RUN_SERVER
echo =============================================================
echo   Starting Student Management System Web Server...
echo =============================================================
echo   Local Website URL: http://localhost:8080
echo =============================================================

cd /d "%~dp0prototype"
javac *.java
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Compilation failed.
    pause
    exit /b %ERRORLEVEL%
)

rem Open website in default web browser
start http://localhost:8080

rem Start Java Web Server
java WebServer
