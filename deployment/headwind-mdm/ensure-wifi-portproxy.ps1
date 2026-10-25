# Rebind the Windows Wi-Fi HTTPS listener to Podman's localhost-published port.
# Register this script as an elevated AtStartup scheduled task.

[CmdletBinding()]
param(
    [string]$ListenAddress = '192.168.1.5',
    [int]$ListenPort = 8443,
    [string]$ConnectAddress = '127.0.0.1',
    [int]$ConnectPort = 8443,
    [int]$WaitSeconds = 900
)

$ErrorActionPreference = 'Stop'

$deadline = (Get-Date).AddSeconds($WaitSeconds)
do {
    $addressReady = Get-NetIPAddress -AddressFamily IPv4 -IPAddress $ListenAddress -ErrorAction SilentlyContinue
    $targetReady = Get-NetTCPConnection -State Listen -LocalAddress $ConnectAddress -LocalPort $ConnectPort -ErrorAction SilentlyContinue
    if ($addressReady -and $targetReady) { break }
    Start-Sleep -Seconds 3
} while ((Get-Date) -lt $deadline)

if (-not $addressReady) {
    throw "Wi-Fi address $ListenAddress is not assigned. Reserve this address or pass the current Wi-Fi address with -ListenAddress."
}
if (-not $targetReady) {
    throw "Podman has not published $ConnectAddress`:$ConnectPort after $WaitSeconds seconds. Start the SmartBIS compose stack and rerun this script."
}

$service = Get-Service -Name iphlpsvc
if ($service.Status -ne 'Running') {
    Start-Service -Name iphlpsvc
    (Get-Service -Name iphlpsvc).WaitForStatus('Running', [TimeSpan]::FromSeconds(30))
}

& netsh.exe interface portproxy delete v4tov4 listenaddress=$ListenAddress listenport=$ListenPort 2>$null | Out-Null
& netsh.exe interface portproxy add v4tov4 listenaddress=$ListenAddress listenport=$ListenPort connectaddress=$ConnectAddress connectport=$ConnectPort
if ($LASTEXITCODE -ne 0) {
    throw "netsh failed to add $ListenAddress`:$ListenPort -> $ConnectAddress`:$ConnectPort (exit $LASTEXITCODE)."
}

$bound = Get-NetTCPConnection -State Listen -LocalAddress $ListenAddress -LocalPort $ListenPort -ErrorAction SilentlyContinue
if (-not $bound) {
    throw "The portproxy rule was stored, but Windows did not bind $ListenAddress`:$ListenPort. Check IP Helper and port conflicts."
}

Write-Output "Ready: $ListenAddress`:$ListenPort -> $ConnectAddress`:$ConnectPort"
