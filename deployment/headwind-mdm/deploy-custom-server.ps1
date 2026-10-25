param(
    [Parameter(Mandatory = $true)]
    [string]$WarPath,
    [int]$StartupTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$deploymentRoot = $PSScriptRoot
$webappsRoot = Join-Path $deploymentRoot "volumes/webapps"
$liveWar = Join-Path $webappsRoot "ROOT.war"
$backupRoot = Join-Path $deploymentRoot "backups"
$absoluteWar = (Resolve-Path -LiteralPath $WarPath).Path
$containerName = "smartbis-headwind-mdm-hmdm-1"

if (-not (Test-Path $liveWar)) { throw "Live WAR mount is missing: $liveWar" }
if ((Get-Item $absoluteWar).Length -lt 1000000) { throw "Refusing to deploy an unexpectedly small WAR: $absoluteWar" }
podman inspect $containerName *> $null
if ($LASTEXITCODE -ne 0) { throw "HMDM container is not available: $containerName" }

New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupWar = Join-Path $backupRoot "ROOT.war.before-custom-$stamp.war"
Copy-Item -LiteralPath $liveWar -Destination $backupWar

$stagedWar = "$liveWar.new"
Copy-Item -LiteralPath $absoluteWar -Destination $stagedWar
Move-Item -LiteralPath $stagedWar -Destination $liveWar -Force

try {
    Push-Location $deploymentRoot
    try {
        podman compose restart hmdm
        if ($LASTEXITCODE -ne 0) { throw "Could not restart the HMDM container." }
    }
    finally { Pop-Location }

    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    $healthy = $false
    while ((Get-Date) -lt $deadline) {
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            $probeOutput = (& podman exec $containerName sh -c 'curl -sS -o /dev/null -w "%{http_code}" --max-time 5 http://127.0.0.1:8080/' 2>&1 | Out-String).Trim()
            $probeExitCode = $LASTEXITCODE
        }
        finally { $ErrorActionPreference = $previousErrorActionPreference }
        if ($probeExitCode -eq 0 -and $probeOutput -match '(?m)^(200|302)$') {
            $healthy = $true
            break
        }
        Start-Sleep -Seconds 5
    }
    if (-not $healthy) { throw "HMDM did not return HTTP 200/302 within $StartupTimeoutSeconds seconds." }
    Write-Output "Custom WAR deployed and HMDM initialization succeeded."
    Write-Output "Backup WAR: $backupWar"
    Write-Output "Deployed WAR: $absoluteWar"
    Write-Warning "Verify dashboard login and a test device check-in before using this build with other devices."
}
catch {
    Write-Warning "Deployment verification failed; restoring the previous WAR."
    Copy-Item -LiteralPath $backupWar -Destination $liveWar -Force
    Push-Location $deploymentRoot
    try { podman compose restart hmdm | Out-Host }
    finally { Pop-Location }
    throw
}
