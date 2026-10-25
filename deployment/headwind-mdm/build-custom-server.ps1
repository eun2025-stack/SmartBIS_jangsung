param(
    [string]$MavenImage = "maven:3.9.9-eclipse-temurin-17"
)

$ErrorActionPreference = "Stop"
$deploymentRoot = $PSScriptRoot
$sourceRoot = Join-Path $deploymentRoot "hmdm-server-v5.41.1"
$serverRoot = Join-Path $sourceRoot "server"
$buildProperties = Join-Path $serverRoot "build.properties"
$rootXmlPath = Join-Path $deploymentRoot "volumes/hmdm-config/ROOT.xml"
$examplePath = Join-Path $serverRoot "build.properties.example"
$releaseRoot = Join-Path $deploymentRoot "releases"

if (-not (Test-Path $rootXmlPath)) { throw "HMDM runtime config is missing: $rootXmlPath" }
if (-not (Test-Path $examplePath)) { throw "Build properties template is missing: $examplePath" }
if (Test-Path $buildProperties) { throw "Refusing to overwrite existing build.properties: $buildProperties" }

$properties = [ordered]@{}
Get-Content $examplePath | ForEach-Object {
    if ($_ -match '^\s*([^#!\s][^=]*)=(.*)$') { $properties[$matches[1].Trim()] = $matches[2] }
}

[xml]$rootXml = Get-Content $rootXmlPath -Raw
foreach ($parameter in $rootXml.SelectNodes("//Parameter")) {
    $name = $parameter.GetAttribute("name")
    $value = $parameter.GetAttribute("value")
    if ($name -match '^JDBC\.(driver|url|username|password)$') {
        $name = "jdbc." + $matches[1].ToLowerInvariant()
    }
    if ($name) { $properties[$name] = $value }
}

$required = @("jdbc.url", "jdbc.driver", "jdbc.username", "jdbc.password", "base.url", "hash.secret")
$missing = @($required | Where-Object { [string]::IsNullOrWhiteSpace([string]$properties[$_]) })
if ($missing.Count -gt 0) { throw "Runtime config lacks required properties: $($missing -join ', ')" }

# The deployed ROOT.xml does not carry JWT build-time values. Keep the key private and
# generate a fresh high-entropy value for this one WAR build.
$jwtBytes = New-Object byte[] 32
$jwtGenerator = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
try { $jwtGenerator.GetBytes($jwtBytes) } finally { $jwtGenerator.Dispose() }
$properties["jwt.secretkey"] = ([BitConverter]::ToString($jwtBytes)).Replace('-', '').ToLowerInvariant()

function ConvertTo-PropertyValue([string]$value) {
    return $value.Replace('\', '\\').Replace("`r", '\r').Replace("`n", '\n')
}

$lines = foreach ($key in $properties.Keys) { "$key=$(ConvertTo-PropertyValue ([string]$properties[$key]))" }
[System.IO.File]::WriteAllLines($buildProperties, $lines, [System.Text.UTF8Encoding]::new($false))

try {
    New-Item -ItemType Directory -Path $releaseRoot -Force | Out-Null
    $mountPath = $sourceRoot.Replace('\', '/')
    podman run --rm --volume "${mountPath}:/workspace:rw" --workdir /workspace $MavenImage `
        mvn -B -pl server -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven WAR build failed with exit code $LASTEXITCODE" }

    $builtWar = Join-Path $serverRoot "target/launcher.war"
    if (-not (Test-Path $builtWar) -or (Get-Item $builtWar).Length -lt 1000000) {
        throw "Maven reported success but WAR is missing or unexpectedly small: $builtWar"
    }

    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $releaseWar = Join-Path $releaseRoot "hmdm-server-5.41.1-smartbis-$stamp.war"
    Copy-Item -LiteralPath $builtWar -Destination $releaseWar
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $releaseWar).Hash.ToLowerInvariant()
    Write-Output "WAR created: $releaseWar"
    Write-Output "SHA-256: $hash"
    Write-Warning "WAR contains deployment configuration. Treat the release file as a secret; do not commit or share it publicly."
}
finally {
    Remove-Item -LiteralPath $buildProperties -Force -ErrorAction SilentlyContinue
}
