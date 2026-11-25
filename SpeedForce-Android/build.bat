@echo off
rem Double-click this file to rebuild Speed Force. All tools live in D:\ap\tools.
set JAVA_HOME=D:\ap\tools\jdk-17.0.20.1+1
set ANDROID_HOME=D:\ap\tools\android-sdk
set ANDROID_SDK_ROOT=D:\ap\tools\android-sdk
set ANDROID_USER_HOME=D:\ap\tools\home\.android
set ANDROID_PREFS_ROOT=D:\ap\tools\home
set ANDROID_EMULATOR_HOME=D:\ap\tools\android-home
set GRADLE_USER_HOME=D:\ap\tools\gradle-home
set USERPROFILE=D:\ap\tools\home
set TEMP=D:\ap\tools\tmp
set TMP=D:\ap\tools\tmp
set JAVA_OPTS=-Djava.net.preferIPv4Stack=true -Duser.home=D:/ap/tools/home
set GRADLE_OPTS=-Duser.home=D:/ap/tools/home
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%
if not exist D:\ap\tools\home mkdir D:\ap\tools\home
if not exist D:\ap\tools\tmp mkdir D:\ap\tools\tmp

cd /d D:\ap\SpeedForce-Android
echo Building Speed Force (takes 1-3 minutes)...
call D:\ap\SpeedForce-Android\gradlew.bat testReleaseUnitTest assembleRelease --console=plain
if errorlevel 1 (
  echo.
  echo BUILD FAILED - see the messages above.
  pause
  exit /b 1
)
copy /Y app\build\outputs\apk\release\app-arm64-v8a-release.apk SpeedForce-Android-arm64.apk >nul
copy /Y app\build\outputs\apk\release\app-universal-release.apk SpeedForce-Android-universal.apk >nul
call D:\ap\SpeedForce-Android\gradlew.bat --stop >nul
echo.
echo DONE. New APK: D:\ap\SpeedForce-Android\SpeedForce-Android-arm64.apk
pause
