@echo off
rem Builds Speed Force into D:\ap\SpeedForce-Windows\dist. "build.bat test" adds the picture/log test mode.
rem The compiler lives in D:\ap\tools\cpp; the video engine files come from engine\ (kept next to this file).
set PATH=D:\ap\tools\cpp\w64devkit\bin;%PATH%
cd /d D:\ap\SpeedForce-Windows
set EXTRA=
if "%1"=="test" set EXTRA=-DSF_TEST
windres app.rc -O coff -o app.res.o || exit /b 1
g++ -std=c++20 -Os %EXTRA% -Wno-unused-variable -Isrc src\main.cpp app.res.o -o SpeedForce.exe -static -mwindows -municode -s -ffunction-sections -fdata-sections -Wl,--gc-sections -lole32 -loleaut32 -luuid -lshlwapi -lshell32 -ldwmapi || exit /b 1
if exist dist rmdir /s /q dist
mkdir dist\ui dist\assets\start dist\engine
copy /y SpeedForce.exe dist\ >nul
copy /y WebView2Loader.dll dist\ >nul
copy /y ui\* dist\ui\ >nul
copy /y assets\*.txt dist\assets\ >nul
copy /y assets\*.js dist\assets\ >nul
copy /y assets\start\* dist\assets\start\ >nul
copy /y engine\yt-dlp.exe dist\engine\ >nul
copy /y engine\ffmpeg.exe dist\engine\ >nul
echo DONE: D:\ap\SpeedForce-Windows\dist\SpeedForce.exe
