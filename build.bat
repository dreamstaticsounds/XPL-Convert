@echo off
rem Build XPLConvert.jar from src\.  Needs a JDK (17 or newer) on PATH.
setlocal
cd /d "%~dp0"
if not exist build\classes mkdir build\classes
dir /s /b src\*.java > build\sources.txt
javac -encoding UTF-8 -d build\classes @build\sources.txt
if errorlevel 1 exit /b 1
jar cfm XPLConvert.jar MANIFEST.MF -C build\classes .
if errorlevel 1 exit /b 1
echo built XPLConvert.jar
