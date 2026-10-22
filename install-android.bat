@echo off
setlocal
rem Builds the release-signed APK and installs it on the USB-connected phone.
rem Needs: JAVA_HOME pointing to JDK 17-21, ANDROID_HOME (or ANDROID_SDK_ROOT) set, USB debugging on.

cd /d "%~dp0"

if "%ANDROID_HOME%"=="" set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if "%ANDROID_HOME%"=="" (
    echo ERROR: ANDROID_HOME is not set.
    exit /b 1
)
if "%JAVA_HOME%"=="" (
    echo ERROR: JAVA_HOME is not set. Use JDK 17-21.
    exit /b 1
)

rem Gradle on newer Windows fails with "Unable to establish loopback connection" without a short unix-domain temp dir.
if not exist "%SystemDrive%\tmpjava" mkdir "%SystemDrive%\tmpjava"
set "JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=%SystemDrive%\tmpjava"
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo ERROR: no phone found. Connect it with USB and allow USB debugging.
    exit /b 1
)

call "%~dp0gradlew.bat" assembleRelease --no-daemon -q
if errorlevel 1 (
    echo ERROR: build failed.
    exit /b 1
)

copy /y app\build\outputs\apk\release\app-release.apk LoadBalancer.apk >nul
"%ADB%" install -r LoadBalancer.apk
if errorlevel 1 (
    echo ERROR: install failed.
    exit /b 1
)
"%ADB%" shell am start -n com.asra.loadbalancer/com.loadbalancer.app.MainActivity
echo DONE
endlocal
