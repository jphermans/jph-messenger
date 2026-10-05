@echo off
REM JPH Messenger - Build Script for Windows
REM Requires: JDK 6 32-bit, BlackBerry Java SDK 7.1
REM
REM Usage:
REM   1. Install JDK 6 32-bit, set JAVA_HOME
REM   2. Install BB Java SDK 7.1, set BB_SDK
REM   3. Run this script

REM === CONFIGURATION ===
set JAVA_HOME=C:\Program Files (x86)\Java\jdk1.6.0_45
set BB_SDK=C:\Program Files (x86)\BlackBerry\Java_SDK_7.1
set PROJECT_ROOT=%~dp0..

REM Add JDK to PATH
set PATH=%JAVA_HOME%\bin;%PATH%

REM === BUILD ===
echo.
echo === Compiling Java sources ===

REM Create build output directory
if not exist build mkdir build

REM Compile with BB API
"%JAVA_HOME%\bin\javac" -source 1.3 -target 1.3 \
    -bootclasspath "%BB_SDK%\lib\net_rim_api.jar" \
    -d build \
    -classpath "%BB_SDK%\lib\net_rim_api.jar" \
    src\com\jph\*.java

if errorlevel 1 (
    echo.
    echo *** COMPILATION FAILED ***
    pause
    exit /b 1
)

echo.
echo === Packaging with RAPC ===

REM Package with RAPC
"%BB_SDK%\bin\rapc.exe" \
    -codename=JPHMessenger \
    -output=JPHMessenger \
    -midlet=True \
    -icon=icon.png \
    build\com\jph\*.class

if errorlevel 1 (
    echo.
    echo *** RAPC PACKAGING FAILED ***
    pause
    exit /b 1
)

echo.
echo === Build complete ===

echo Output files:
dir /b JPHMessenger.*

echo.
echo Next steps:
echo   1. Copy JPHMessenger.jad and JPHMessenger-*.cod to your web server
echo   2. Or use JavaLoader: javaloader.exe load -u JPHMessenger.cod
echo.
pause
