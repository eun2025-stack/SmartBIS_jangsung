param([switch]$Prepare,[switch]$AfterReboot)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$root=Split-Path -Parent $PSScriptRoot
$logs=Join-Path $root '.tmp'
$timeoutExe='C:\Program Files\Git\usr\bin\timeout.exe'
$beforeFile=Join-Path $logs 'gate-close-reboot-before.json'
$afterFile=Join-Path $logs 'gate-close-reboot-after.json'
function Bounded([string[]]$Arguments) {
    $result=& $timeoutExe 20 @Arguments 2>&1
    if($LASTEXITCODE -ne 0) { throw 'BOUNDED_LOCAL_CHECK_FAILED' }
    return ($result -join "`n")
}
function BootTime { return (Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().ToString('o') }
if($Prepare) {
    if(Test-Path -LiteralPath $beforeFile) { throw 'REBOOT_TRIAL_ALREADY_PREPARED_INSPECT_BEFORE_RETRY' }
    $registered=$false
    foreach($runKey in @('HKCU:\Software\Microsoft\Windows\CurrentVersion\Run','HKLM:\Software\Microsoft\Windows\CurrentVersion\Run')) {
        if(Test-Path $runKey) {
            foreach($property in (Get-ItemProperty $runKey).PSObject.Properties) {
                if(($property.Name+' '+$property.Value) -match 'podman') { $registered=$true }
            }
        }
    }
    foreach($startup in @([Environment]::GetFolderPath('Startup'),[Environment]::GetFolderPath('CommonStartup'))) {
        if($startup -and (Test-Path -LiteralPath $startup)) {
            if(@(Get-ChildItem -LiteralPath $startup -Name | Where-Object { $_ -match 'podman' }).Count -gt 0) { $registered=$true }
        }
    }
    $inspection=Bounded -Arguments @('podman','inspect','smartbis_jangsung-postgres-db-1','--format','json') | ConvertFrom-Json
    $policy=$inspection[0].HostConfig.RestartPolicy.Name
    Write-Output ('Podman startup registration verified: '+$registered)
    Write-Output ('PostgreSQL restart policy supported: '+($policy -in @('always','unless-stopped')))
    if(-not $registered -or $policy -notin @('always','unless-stopped')) { throw 'AUTOSTART_PREREQUISITE_NOT_VERIFIED' }
    $python=Bounded -Arguments @('python','-c','import sys; print(sys.executable)')
    $state=[ordered]@{bootTime=BootTime;preparedAt=[DateTimeOffset]::UtcNow.ToString('o');podmanLoginStartupRegistered=$registered;postgresRestartPolicy=$policy;pythonExecutable=$python.Trim();trial=1}
    $script=Join-Path $PSScriptRoot 'local_collection_reboot_check.ps1'
    $command='"'+$timeoutExe+'" 600 powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+$script+'" -AfterReboot'
    if($command.Length -gt 260) { throw 'RUNONCE_COMMAND_TOO_LONG' }
    $runOnce='HKCU:\Software\Microsoft\Windows\CurrentVersion\RunOnce'
    if(-not (Test-Path $runOnce)) { New-Item $runOnce -Force | Out-Null }
    New-ItemProperty -Path $runOnce -Name 'SmartBISCollectionGateVerify' -Value $command -PropertyType String -Force | Out-Null
    $state | ConvertTo-Json | Set-Content -LiteralPath $beforeFile -Encoding UTF8
    Write-Output 'Pre-reboot startup checks and one-time verifier registration: complete'
    Write-Output 'Collector startup registration: none'
    exit 0
}
if($AfterReboot) {
    $before=Get-Content -Raw -LiteralPath $beforeFile | ConvertFrom-Json
    $nowBoot=BootTime
    $state=[ordered]@{checkedAt=[DateTimeOffset]::UtcNow.ToString('o');bootChanged=($nowBoot -ne $before.bootTime);machineRunning=$false;postgresRunning=$false;databaseVerified=$false;collectorStarted=$false;result='FAIL'}
    if(-not $state.bootChanged) { $state.result='BOOT_NOT_CONFIRMED' }
    else {
        $watch=[Diagnostics.Stopwatch]::StartNew()
        while($watch.Elapsed.TotalSeconds -lt 240) {
            try {
                $machines=Bounded -Arguments @('podman','machine','list','--format','json') | ConvertFrom-Json
                $state.machineRunning=(@($machines | Where-Object { $_.Running -eq $true -or $_.State -eq 'running' }).Count -gt 0)
                $pg=Bounded -Arguments @('podman','ps','--filter','name=smartbis_jangsung-postgres-db-1','--format','json') | ConvertFrom-Json
                $state.postgresRunning=(@($pg | Where-Object { @($_.Names) -contains 'smartbis_jangsung-postgres-db-1' }).Count -gt 0)
                if($state.machineRunning -and $state.postgresRunning) { break }
            } catch { }
            [Threading.Thread]::Sleep(5000)
        }
        if($state.machineRunning -and $state.postgresRunning) {
            $admin=Join-Path $PSScriptRoot 'local_collection_admin.py'
            & $timeoutExe 90 $before.pythonExecutable -B -X utf8 $admin --resume-reboot *> (Join-Path $logs 'gate-close-post-reboot-db.txt')
            $state.databaseVerified=($LASTEXITCODE -eq 0)
            if($state.databaseVerified) { $state.result='PASS' }
        }
    }
    $state | ConvertTo-Json | Set-Content -LiteralPath $afterFile -Encoding UTF8
    Write-Output ('Post-reboot verification: '+$state.result)
    exit 0
}
throw 'SPECIFY_PREPARE_OR_AFTER_REBOOT'
