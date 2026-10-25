param(
    [Parameter(Mandatory = $true)]
    [string]$Root
)

$ErrorActionPreference = 'Stop'

function Get-LatestCodeRelease {
    param(
        [string]$Directory,
        [string]$Pattern,
        [string]$Regex
    )

    Get-ChildItem -LiteralPath $Directory -File -Filter $Pattern |
        ForEach-Object {
            if ($_.Name -match $Regex) {
                [pscustomobject]@{
                    Path = $_.FullName
                    Code = [int]$Matches[2]
                    Version = [version]$Matches[1]
                    Name = $_.Name
                }
            }
        } |
        Sort-Object Code, Version, Name -Descending |
        Select-Object -First 1
}

$agentDir = Join-Path $Root 'deployment\headwind-mdm\releases'
$clientDir = Join-Path $Root 'client-android\releases'
$agent = Get-ChildItem -LiteralPath $agentDir -File -Filter 'hmdm-agent-*-*-debug.apk' |
    ForEach-Object {
        if ($_.Name -match '^hmdm-agent-(\d+(?:\.\d+)?)-(\d{8})-(\d{6})-debug\.apk$') {
            [pscustomobject]@{
                Path = $_.FullName
                Version = [version]$Matches[1]
                BuildDate = $Matches[2]
                BuildTime = $Matches[3]
                Name = $_.Name
            }
        }
    } |
    Sort-Object Version, BuildDate, BuildTime -Descending |
    Select-Object -First 1
$client = Get-LatestCodeRelease -Directory $clientDir -Pattern 'smartbis-client-*-code*-*.apk' -Regex '^smartbis-client-(\d+(?:\.\d+)?)-code(\d+)-.*\.apk$'
$vehicle = Get-LatestCodeRelease -Directory $clientDir -Pattern 'vehicle-number-*-code*-*.apk' -Regex '^vehicle-number-(\d+(?:\.\d+)?)-code(\d+)-.*\.apk$'

if (-not $agent) { throw "No standard timestamped Headwind Agent release found in $agentDir" }
if (-not $client) { throw "No versioned SmartBIS Client release found in $clientDir" }
if (-not $vehicle) { throw "No versioned vehicle-number release found in $clientDir" }

Write-Output "AGENT_APK=$($agent.Path)"
Write-Output "CLIENT_APK=$($client.Path)"
Write-Output "VEHICLE_APK=$($vehicle.Path)"
