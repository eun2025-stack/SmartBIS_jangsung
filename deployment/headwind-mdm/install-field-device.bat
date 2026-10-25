@echo off
setlocal EnableExtensions

rem Field-device staging helper for the SmartBIS RK3566 / Android 11 units.
rem Requires an already-authorized ADB connection. Does not wipe or uninstall.
rem AnyDesk unattended password is intentionally configured on-device, not stored here.

set "ROOT=%~dp0..\.."
for %%I in ("%ROOT%") do set "ROOT=%%~fI"
set "DROP=D:\PROJECT"
set "CERT=%~dp0..\..\server\nginx\certs\rootCA.crt"
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=C:\Users\eun\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

rem Resolve the newest versioned release artifacts instead of requiring manual copies.
set "APK_RESOLVER=%~dp0resolve-latest-field-apks.ps1"
if not exist "%APK_RESOLVER%" (
  echo Missing APK resolver: %APK_RESOLVER%
  exit /b 1
)
for /f "tokens=1,* delims==" %%A in ('powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%APK_RESOLVER%" -Root "%ROOT%"') do set "%%A=%%B"
if not defined AGENT_APK (
  echo No versioned Headwind Agent APK found under %ROOT%\deployment\headwind-mdm\releases.
  exit /b 1
)
if not defined CLIENT_APK (
  echo No versioned SmartBIS Client APK found under %ROOT%\client-android\releases.
  exit /b 1
)
if not defined VEHICLE_APK (
  echo No versioned vehicle-number APK found under %ROOT%\client-android\releases.
  exit /b 1
)
echo Auto-selected release APKs:
echo   Headwind Agent: %AGENT_APK%
echo   Vehicle app:    %VEHICLE_APK%
echo   SmartBIS Client:%CLIENT_APK%
if not exist "%DROP%\anydesk.apk" (
  echo Missing: %DROP%\anydesk.apk
  exit /b 1
)
if not exist "%DROP%\adcontrol-ad1.apk" (
  echo Missing: %DROP%\adcontrol-ad1.apk
  exit /b 1
)
if not exist "%AGENT_APK%" (echo Missing: %AGENT_APK% & exit /b 1)
if not exist "%CLIENT_APK%" (echo Missing: %CLIENT_APK% & exit /b 1)
if not exist "%VEHICLE_APK%" (echo Missing: %VEHICLE_APK% & exit /b 1)
if not exist "%CERT%" (
  echo Missing CA certificate: %CERT%
  exit /b 1
)

if /I "%ADB%"=="adb" where adb >nul 2>nul
if /I "%ADB%"=="adb" if errorlevel 1 (
  echo adb.exe not found. Install Android SDK Platform-Tools or add adb.exe to PATH.
  exit /b 1
)

echo Connected ADB devices:
"%ADB%" devices -l
echo.
set "SERIAL="
set /p "SERIAL=Enter the exact authorized device serial shown above: "
if not defined SERIAL (
  echo No serial entered. Nothing changed.
  exit /b 1
)

set "STATE_FILE=%TEMP%\smartbis-adb-state-%RANDOM%.txt"
set "STATE="
"%ADB%" -s "%SERIAL%" get-state > "%STATE_FILE%" 2>nul
if exist "%STATE_FILE%" set /p "STATE="<"%STATE_FILE%"
del "%STATE_FILE%" >nul 2>nul
if not "%STATE%"=="device" (
  echo ADB state for %SERIAL%: [%STATE%]
  echo Devices visible to this Windows ADB server:
  "%ADB%" devices -l
  echo.
  echo If Git Bash shows this device but this list does not, Bash and this window are not using the same ADB server/session.
  echo No firewall port change is needed when the serial already appears as "device" above.
  echo If it appears as "offline" or "unauthorized", reconnect/authorize it in Android Wireless debugging.
  exit /b 1
)

set "MODEL_FILE=%TEMP%\smartbis-adb-model-%RANDOM%.txt"
set "MODEL="
"%ADB%" -s "%SERIAL%" shell getprop ro.product.model > "%MODEL_FILE%" 2>nul
if exist "%MODEL_FILE%" set /p "MODEL="<"%MODEL_FILE%"
del "%MODEL_FILE%" >nul 2>nul
set "VERSION_FILE=%TEMP%\smartbis-adb-version-%RANDOM%.txt"
set "ANDROID_VERSION="
"%ADB%" -s "%SERIAL%" shell getprop ro.build.version.release > "%VERSION_FILE%" 2>nul
if exist "%VERSION_FILE%" set /p "ANDROID_VERSION="<"%VERSION_FILE%"
del "%VERSION_FILE%" >nul 2>nul
echo.
echo Target: %SERIAL%  Model: %MODEL%  Android: %ANDROID_VERSION%
set "CONFIRM="
set /p "CONFIRM=Type the exact serial again to confirm this device: "
if not "%CONFIRM%"=="%SERIAL%" (
  echo Serial confirmation did not match. Nothing changed.
  exit /b 1
)

echo [1/9] Copy SmartBIS Root CA to device Download folder...
"%ADB%" -s "%SERIAL%" push "%CERT%" /sdcard/Download/SmartBIS_Root_CA.crt
if errorlevel 1 goto :failed
"%ADB%" -s "%SERIAL%" shell test -s /sdcard/Download/SmartBIS_Root_CA.crt
if errorlevel 1 goto :failed

set "POLICY_FILE=%TEMP%\smartbis-device-policy-%RANDOM%.txt"
"%ADB%" -s "%SERIAL%" shell dumpsys device_policy > "%POLICY_FILE%"
findstr /C:"Device Owner:" "%POLICY_FILE%" >nul
if not errorlevel 1 (
  findstr /C:"com.hmdm.launcher/com.hmdm.launcher.AdminReceiver" "%POLICY_FILE%" >nul
  if errorlevel 1 (
    del "%POLICY_FILE%" >nul 2>nul
    echo A different Device Owner is already set. Refusing to change it.
    exit /b 1
  )
)

echo.
echo [2/10] Install custom Headwind Agent...
"%ADB%" -s "%SERIAL%" install -r "%AGENT_APK%"
if errorlevel 1 goto :failed

findstr /C:"com.hmdm.launcher/com.hmdm.launcher.AdminReceiver" "%POLICY_FILE%" >nul
if errorlevel 1 (
  echo [3/10] Set Headwind Agent as Device Owner...
  "%ADB%" -s "%SERIAL%" shell dpm set-device-owner com.hmdm.launcher/.AdminReceiver
  if errorlevel 1 goto :failed
) else (
  echo [3/10] Headwind Agent is already Device Owner.
)

echo [4/10] Verify Device Owner...
"%ADB%" -s "%SERIAL%" shell dumpsys device_policy | findstr /C:"com.hmdm.launcher/com.hmdm.launcher.AdminReceiver" >nul
if errorlevel 1 (
  echo Device Owner verification failed.
  goto :failed
)

echo [5/10] Launch Headwind Agent to start automatic ID allocation and server enrollment...
"%ADB%" -s "%SERIAL%" shell monkey -p com.hmdm.launcher -c android.intent.category.LAUNCHER 1
if errorlevel 1 goto :failed
timeout /t 5 /nobreak >nul

echo [6/10] Install AnyDesk...
"%ADB%" -s "%SERIAL%" install -r "%DROP%\anydesk.apk"
if errorlevel 1 goto :failed

echo [7/10] Install AnyDesk Control Plugin AD1...
"%ADB%" -s "%SERIAL%" install -r "%DROP%\adcontrol-ad1.apk"
if errorlevel 1 goto :failed

echo [8/10] Install SmartBIS vehicle-number app...
"%ADB%" -s "%SERIAL%" install -r "%VEHICLE_APK%"
if errorlevel 1 goto :failed

echo [9/10] Install SmartBIS Client...
"%ADB%" -s "%SERIAL%" install -r "%CLIENT_APK%"
if errorlevel 1 goto :failed

echo [10/14] Disable battery optimization for AnyDesk...
"%ADB%" -s "%SERIAL%" shell dumpsys deviceidle whitelist +com.anydesk.anydeskandroid
if errorlevel 1 echo WARNING: Could not add AnyDesk to the device-idle whitelist.

echo [11/14] Apply the requested overlay-deny policy...
"%ADB%" -s "%SERIAL%" shell appops set com.anydesk.anydeskandroid SYSTEM_ALERT_WINDOW ignore
if errorlevel 1 echo WARNING: Could not deny AnyDesk overlay permission.
"%ADB%" -s "%SERIAL%" shell appops set com.hmdm.launcher SYSTEM_ALERT_WINDOW ignore
if errorlevel 1 echo WARNING: Could not deny Headwind Agent overlay permission.

echo [12/14] Enable AnyDesk AD1 accessibility service, preserving other enabled services...
set "ACCESSIBILITY_FILE=%TEMP%\smartbis-accessibility-%RANDOM%.txt"
set "ACCESSIBILITY_SERVICES="
"%ADB%" -s "%SERIAL%" shell settings get secure enabled_accessibility_services > "%ACCESSIBILITY_FILE%"
if exist "%ACCESSIBILITY_FILE%" set /p "ACCESSIBILITY_SERVICES="<"%ACCESSIBILITY_FILE%"
del "%ACCESSIBILITY_FILE%" >nul 2>nul
echo %ACCESSIBILITY_SERVICES% | findstr /I /C:"com.anydesk.adcontrol.AccService" >nul
if errorlevel 1 (
  if defined ACCESSIBILITY_SERVICES (
    set "ACCESSIBILITY_SERVICES=%ACCESSIBILITY_SERVICES%:com.anydesk.adcontrol.ad1/com.anydesk.adcontrol.AccService"
  ) else (
    set "ACCESSIBILITY_SERVICES=com.anydesk.adcontrol.ad1/com.anydesk.adcontrol.AccService"
  )
)
"%ADB%" -s "%SERIAL%" shell settings put secure enabled_accessibility_services "%ACCESSIBILITY_SERVICES%"
if errorlevel 1 echo WARNING: Could not enable the AD1 accessibility service.
"%ADB%" -s "%SERIAL%" shell settings put secure accessibility_enabled 1
if errorlevel 1 echo WARNING: Could not enable Android accessibility services.

echo [13/14] Select Quickstep as Home only if this device exposes a Quickstep HOME activity...
set "HOME_FILE=%TEMP%\smartbis-home-%RANDOM%.txt"
set "HOME_COMPONENT="
"%ADB%" -s "%SERIAL%" shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "%HOME_FILE%" 2>nul
for /f "usebackq delims=" %%A in (`findstr /I "Quickstep" "%HOME_FILE%"`) do set "HOME_COMPONENT=%%A"
del "%HOME_FILE%" >nul 2>nul
if defined HOME_COMPONENT (
  "%ADB%" -s "%SERIAL%" shell cmd package set-home-activity "%HOME_COMPONENT%"
  if errorlevel 1 echo WARNING: Quickstep could not be set as Home; select it in Android Home app settings.
) else (
  echo WARNING: This firmware did not report a Quickstep HOME activity; Home selection was left unchanged.
)

echo [14/14] Verify installed packages...
"%ADB%" -s "%SERIAL%" shell pm list packages | findstr /I "com.hmdm.launcher com.anydesk.anydeskandroid com.anydesk.adcontrol.ad1 com.smartbis.vehiclenumber com.smartbis.client"

del "%POLICY_FILE%" >nul 2>nul
echo.
echo APK installation and Device Owner verification completed.
echo Applied by ADB: AnyDesk battery-optimization exemption, requested overlay-deny policy, AD1 Accessibility, and Quickstep when exposed by firmware.
echo Manual Android step: set 한국어 as the first language in System - Languages.
echo Manual AnyDesk steps: Access - Never show incoming session requests; enable saved login information; select Unattended Access profile; Input - enable automatic keyboard disable and mouse-to-touch translation.
echo Verify AnyDesk and Headwind overlay permission is denied, and verify Quickstep is the selected Home app.
echo No password was requested, saved, or written to a log by this script.
exit /b 0

:failed
del "%POLICY_FILE%" >nul 2>nul
echo.
echo Staging stopped on an error. The script did not factory-reset or uninstall anything.
exit /b 1
