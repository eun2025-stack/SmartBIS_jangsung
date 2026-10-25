param([string]$AvdName = 'Jangsung_Bus_24inch')
$ErrorActionPreference = 'Stop'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$releases = Join-Path $project 'client-android\releases'
foreach ($tool in @($adb, $emulator)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Missing tool: $tool" }
}
function Find-ReviewDevice {
    $lines = & $adb devices
    foreach ($line in $lines) {
        if ($line -match '^(emulator-\d+)\s+device\s*$') {
            $candidate = $Matches[1]
            $name = @(& $adb -s $candidate emu avd name 2>$null)
            if ($name -contains $AvdName) { return $candidate }
        }
    }
    return $null
}
& $adb start-server
if ($LASTEXITCODE -ne 0) { throw 'ADB could not start.' }
$serial = Find-ReviewDevice
if (-not $serial) {
    $avds = @(& $emulator -list-avds)
    if ($avds -notcontains $AvdName) { throw "AVD not found: $AvdName" }
    Start-Process -FilePath $emulator -ArgumentList @('-avd', $AvdName)
}
Write-Host "Waiting for $AvdName (up to 180 seconds)..."
$deadline = (Get-Date).AddSeconds(180)
do {
    if (-not $serial) { $serial = Find-ReviewDevice }
    if ($serial) {
        $boot = (& $adb -s $serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        if ($boot -eq '1') { break }
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)
if (-not $serial -or $boot -ne '1') { throw 'Emulator boot timeout. No physical device was modified.' }

foreach ($app in @(
    @{ Prefix = 'smartbis-client'; Package = 'com.smartbis.client' },
    @{ Prefix = 'vehicle-number'; Package = 'com.smartbis.vehiclenumber' }
)) {
    $pattern = '^' + [regex]::Escape($app.Prefix) + '-\d+(?:\.\d+)*-code(\d+)-\d{8}-\d{6}-debug\.apk$'
    $apk = Get-ChildItem -LiteralPath $releases -Filter '*.apk' | Where-Object {
        $_.Name -match $pattern
    } | Sort-Object @{ Expression = { [int]([regex]::Match($_.Name, '-code(\d+)-').Groups[1].Value) }; Descending = $true }, LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) { throw "No versioned release APK found: $($app.Prefix)" }
    $code = [int]([regex]::Match($apk.Name, '-code(\d+)-').Groups[1].Value)
    $installed = (& $adb -s $serial shell dumpsys package $app.Package 2>$null | Out-String)
    $installedCode = [regex]::Match($installed, 'versionCode=(\d+)')
    if (-not $installedCode.Success -or [int]$installedCode.Groups[1].Value -lt $code) {
        Write-Host "Installing on $serial : $($apk.Name)"
        & $adb -s $serial install -r $apk.FullName
        if ($LASTEXITCODE -ne 0) { throw "APK install failed: $($apk.Name). Existing app data was not cleared." }
    } else {
        Write-Host "$($app.Package) already installed, code $($installedCode.Groups[1].Value)."
    }
}
& $adb -s $serial shell am start -n com.smartbis.client/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'SmartBIS Client launch failed.' }
Write-Host 'Ready. This launcher does not register an MDM device or change vehicle assignments.'
