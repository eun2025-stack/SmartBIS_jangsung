param(
    [string]$ApiBaseUrl = 'https://192.168.1.5:8443/api',
    [int]$VersionCode = 13,
    [string]$VersionName = '1.12',
    [string]$DeviceSerial
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = $PSScriptRoot
$RepositoryRoot = Split-Path -Parent $ProjectRoot
$env:GRADLE_USER_HOME = Join-Path $ProjectRoot '.gradle-user'
$GradleWrapper = Join-Path $ProjectRoot 'gradlew.bat'
$ApkPath = Join-Path $ProjectRoot 'vehicle-number\build\outputs\apk\debug\vehicle-number-debug.apk'
$ReleaseDirectory = Join-Path $ProjectRoot 'releases'
$ServerCa = Join-Path $RepositoryRoot 'server\nginx\certs\rootCA.crt'
$BundledCa = Join-Path $ProjectRoot 'vehicle-number\src\main\res\raw\smartbis_root_ca.crt'
$DeviceCaPath = '/sdcard/Download/SmartBIS_Root_CA.crt'

if ($ApiBaseUrl -notmatch '^https://[^/]+/api$') {
    throw 'API 주소는 https://서버[:포트]/api 형식이어야 합니다.'
}
if ($VersionCode -lt 1) { throw 'VersionCode must be a positive integer.' }
if (-not (Test-Path -LiteralPath $ServerCa -PathType Leaf)) { throw "서버 Root CA 파일이 없습니다: $ServerCa" }
if (-not (Test-Path -LiteralPath $BundledCa -PathType Leaf)) { throw "앱에 포함할 Root CA 파일이 없습니다: $BundledCa" }

$ServerCertificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($ServerCa)
$BundledCertificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($BundledCa)
if ($ServerCertificate.Thumbprint -ne $BundledCertificate.Thumbprint) {
    throw '서버 Root CA와 앱 번들 CA가 다릅니다. 인증서 불일치 상태로 빌드하지 않습니다.'
}

Write-Host "차량번호 앱 빌드: versionName=$VersionName versionCode=$VersionCode"
Write-Host "CA SHA-1 thumbprint: $($ServerCertificate.Thumbprint)"
Push-Location $ProjectRoot
try {
    & $GradleWrapper ':vehicle-number:assembleDebug' '--no-daemon' `
        "-PsmartbisApiBaseUrl=$ApiBaseUrl" `
        "-PvehicleNumberVersionCode=$VersionCode" `
        "-PvehicleNumberVersionName=$VersionName"
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw "Build completed but APK is missing: $ApkPath" }
New-Item -ItemType Directory -Force -Path $ReleaseDirectory | Out-Null
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$VersionedApk = Join-Path $ReleaseDirectory "vehicle-number-$VersionName-code$VersionCode-$Stamp-debug.apk"
Copy-Item -LiteralPath $ApkPath -Destination $VersionedApk
Write-Host "APK: $VersionedApk"
Write-Host "SHA256: $((Get-FileHash -Algorithm SHA256 -LiteralPath $VersionedApk).Hash)"

$Adb = Get-Command adb.exe -ErrorAction SilentlyContinue
if (-not $Adb) {
    $SdkAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $SdkAdb -PathType Leaf) { $AdbPath = $SdkAdb }
} else {
    $AdbPath = $Adb.Source
}

if (-not $AdbPath) {
    Write-Warning 'APK는 빌드됐지만 ADB를 찾지 못해 Root CA를 Android에 복사하지 못했습니다.'
    exit 0
}

$DeviceLines = @(& $AdbPath devices)
if ($LASTEXITCODE -ne 0) { throw 'adb devices failed.' }
$OnlineDevices = @($DeviceLines | Select-Object -Skip 1 | ForEach-Object {
    if ($_ -match '^\s*(\S+)\s+device\s*$') { $Matches[1] }
})
if ($DeviceSerial) {
    if ($OnlineDevices -notcontains $DeviceSerial) {
        throw "지정한 Android 기기가 ADB online 상태가 아닙니다: $DeviceSerial"
    }
    $TargetDevice = $DeviceSerial
} elseif ($OnlineDevices.Count -eq 1) {
    $TargetDevice = $OnlineDevices[0]
} elseif ($OnlineDevices.Count -gt 1) {
    throw "ADB 기기가 여러 대 연결됐습니다. -DeviceSerial로 대상 기기를 지정하세요. online=$($OnlineDevices -join ', ')"
} else {
    Write-Warning 'APK는 빌드됐지만 연결된 ADB 기기가 없어 Root CA 복사를 건너뛰었습니다. 기기를 연결한 뒤 이 스크립트를 다시 실행하세요.'
    exit 0
}

Write-Host "Root CA를 Android Download 폴더로 복사합니다: $TargetDevice"
& $AdbPath -s $TargetDevice push $ServerCa $DeviceCaPath
if ($LASTEXITCODE -ne 0) { throw 'Root CA 파일 복사에 실패했습니다.' }
& $AdbPath -s $TargetDevice shell ls -l $DeviceCaPath
if ($LASTEXITCODE -ne 0) { throw 'Android에서 복사된 Root CA 파일을 확인하지 못했습니다.' }
Write-Host '완료: APK 빌드 및 Root CA 파일 복사.'
Write-Host '참고: 파일 복사는 Android 시스템 인증서 저장소에 자동 설치하는 작업이 아닙니다. 차량번호 앱은 빌드에 포함된 동일 CA를 앱 전용으로 신뢰합니다.'
