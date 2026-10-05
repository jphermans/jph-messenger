@echo off
REM =============================================================================
REM JPH Messenger - BlackBerry Build Script
REM =============================================================================
REM
REM PREREQUISITES:
REM   1. JDK 6 32-bit installed (NOT JDK 7 or 8)
REM      Download: https://adoptium.net/temurin/archive/versions/?version=jdk6
REM      Path example: C:\Program Files (x86)\Java\jdk1.6.0_45
REM
REM   2. BlackBerry Java SDK 7.1 extracted
REM      Download: https://archive.org/details/java-for-blackberryos
REM      File: BlackBerry_JDE_7.1.0.exe (extract with 7-Zip)
REM      Path example: C:\bb-sdk\Java_SDK_7.1
REM
REM USAGE:
REM   1. Edit JAVA_HOME and BB_SDK paths below
REM   2. Put your .java source files in src\com\jph\
REM   3. Run this script
REM
REM =============================================================================

REM === EDIT THESE PATHS ===
set "JAVA_HOME=C:\Program Files (x86)\Java\jdk1.6.0_45"
set "BB_SDK=C:\bb-sdk\Java_SDK_7.1"
REM ========================

REM Validate paths
if not exist "%JAVA_HOME%\bin\javac.exe" (
    echo ERROR: JDK not found at %JAVA_HOME%
    echo Please edit JAVA_HOME in this script
    pause
    exit /b 1
)

if not exist "%BB_SDK%\bin\rapc.exe" (
    echo ERROR: BlackBerry SDK not found at %BB_SDK%
    echo Please edit BB_SDK in this script
    pause
    exit /b 1
)

REM Setup environment
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM Create output directories
if not exist build mkdir build
if not exist dist mkdir dist

echo.
echo === Compiling Java sources ===
"%JAVA_HOME%\bin\javac" -source 1.3 -target 1.3 ^
    -bootclasspath "%BB_SDK%\lib\net_rim_api.jar" ^
    -d build ^
    -classpath "%BB_SDK%\lib\net_rim_api.jar" ^
    src\com\jph\*.java

if errorlevel 1 (
    echo.
    echo *** COMPILATION FAILED ***
    pause
    exit /b 1
)

echo.
echo === Packaging JAR ===
cd build
"%JAVA_HOME%\bin\jar" cf ..\build\JPHMessenger.jar com\jph\*.class
cd ..

echo.
echo === Calculating JAR size ===
for %%A in (build\JPHMessenger.jar) do set JARSIZE=%%~zA

echo JAR size: %JARSIZE% bytes

echo.
echo === Creating JAD ===
(
echo MIDlet-Name: JPH Messenger
echo MIDlet-Version: 0.1.0
echo MIDlet-Vendor: JPH
echo MIDlet-1: JPH Messenger,,com.jph.JPHMessenger
echo MIDlet-Jar-URL: JPHMessenger.jar
echo MIDlet-Jar-Size: %JARSIZE%
echo MicroEdition-Profile: MIDP-2.0
echo MicroEdition-Configuration: CLDC-1.1
) > dist\JPHMessenger.jad

echo.
echo === Running RAPC ===
"%BB_SDK%\bin\rapc.exe" ^
    -codename=JPHMessenger ^
    -output=dist\JPHMessenger ^
    -midlet=True ^
    import="%BB_SDK%\lib\net_rim_api.jar" ^
    build\JPHMessenger.jar

if errorlevel 1 (
    echo.
    echo *** RAPC FAILED ***
    pause
    exit /b 1
)

echo.
echo === BUILD COMPLETE ===
echo.
echo Output files:
dir /b dist\JPHMessenger.*
echo.
echo Next steps:
echo   1. Copy all files from dist/ to a web server
echo      (or use JavaLoader: javaloader.exe load -u JPHMessenger.cod)
echo   2. On Bold 9790, open the JAD URL in Browser
echo.
pause
