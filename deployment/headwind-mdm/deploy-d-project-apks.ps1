[CmdletBinding()]
param(
    [string]$ServerUrl = 'https://192.168.1.5:8443',
    [string]$ConfigurationName = 'Common - Minimal',
    # One stable staging folder is used for every environment. Put the current
    # APKs in D:\PROJECT (or pass -ProjectDrop explicitly when needed).
    [string]$ProjectDrop = 'D:\PROJECT',
    [string]$AaptPath = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\aapt.exe",
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw 'PowerShell 7 이상(pwsh)에서 실행하세요. TLS 인증서 검증을 끄지 않습니다.'
}

$ServerUrl = $ServerUrl.TrimEnd('/')
$RepositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$items = @(
    [pscustomobject]@{ File = 'anydesk.apk'; Package = 'com.anydesk.anydeskandroid'; Label = 'AnyDesk' },
    [pscustomobject]@{ File = 'adcontrol-ad1.apk'; Package = 'com.anydesk.adcontrol.ad1'; Label = 'AnyDesk Control Plugin AD1' },
    [pscustomobject]@{ File = 'smartbis-client-1.4-code5-debug.apk'; Package = 'com.smartbis.client'; Label = 'SmartBIS Client' }
)

if (-not (Test-Path -LiteralPath $AaptPath -PathType Leaf)) {
    throw "aapt.exe를 찾을 수 없습니다: $AaptPath`nAndroid SDK Build-Tools를 설치하거나 -AaptPath로 경로를 지정하세요."
}

function Get-ApkMetadata {
    param([Parameter(Mandatory)][string]$Path)

    $badging = & $AaptPath dump badging $Path 2>&1
    if ($LASTEXITCODE -ne 0) { throw "APK 메타데이터를 읽지 못했습니다: $Path`n$badging" }

    $packageLine = $badging | Where-Object { $_ -match "^package: name='" } | Select-Object -First 1
    $labelLine = $badging | Where-Object { $_ -match "^application-label:" } | Select-Object -First 1
    if (-not $packageLine -or $packageLine -notmatch "name='([^']+)' versionCode='([^']+)' versionName='([^']*)'") {
        throw "패키지명/버전 정보를 해석하지 못했습니다: $Path"
    }

    [pscustomobject]@{
        Package = $Matches[1]
        VersionCode = [int]$Matches[2]
        Version = $Matches[3]
        ApkLabel = if ($labelLine -match "^application-label:'(.*)'$") { $Matches[1] } else { $null }
    }
}

function Get-ApiData {
    param([Parameter(Mandatory)]$Response, [Parameter(Mandatory)][string]$Operation)
    if ($null -eq $Response -or $Response.status -ne 'OK') {
        $message = if ($Response.message) { $Response.message } else { '상세 메시지 없음' }
        throw "$Operation 실패: $message"
    }
    return $Response.data
}

function Invoke-Api {
    param(
        [Parameter(Mandatory)][string]$Path,
        [ValidateSet('GET','POST','PUT')][string]$Method = 'GET',
        $Body,
        [switch]$Multipart
    )
    $uri = "$ServerUrl$Path"
    if ($Multipart) {
        return Invoke-RestMethod -Uri $uri -Method Post -Form $Body -WebSession $script:webSession `
            -Headers @{ Accept = 'application/json' } -TimeoutSec 120
    }
    $args = @{
        Uri = $uri; Method = $Method; WebSession = $script:webSession; TimeoutSec = 120
        Headers = @{ Accept = 'application/json' }
    }
    if ($PSBoundParameters.ContainsKey('Body')) {
        $args.ContentType = 'application/json; charset=utf-8'
        $args.Body = ConvertTo-Json -InputObject $Body -Depth 20 -Compress
    }
    return Invoke-RestMethod @args
}

function Protect-HeadwindPassword {
    param([Parameter(Mandatory)][string]$Password, [string]$PublicKey)
    if ($PublicKey) {
        $rsa = [System.Security.Cryptography.RSA]::Create()
        try {
            $keyBytes = [Convert]::FromBase64String($PublicKey)
            $bytesRead = 0
            $rsa.ImportSubjectPublicKeyInfo($keyBytes, [ref]$bytesRead)
            $encrypted = $rsa.Encrypt([Text.Encoding]::UTF8.GetBytes($Password), [System.Security.Cryptography.RSAEncryptionPadding]::Pkcs1)
            return [Convert]::ToBase64String($encrypted)
        } finally { $rsa.Dispose() }
    }

    $md5 = [System.Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes($Password))
    return ([Convert]::ToHexString($md5))
}

# Resolve the newest vehicle-number APK by its embedded versionCode, not by a
# stale hard-coded filename. This keeps test and production deployments on the
# same staging convention while retaining every versioned artifact in releases/.
$vehicleSearchDirectories = @($ProjectDrop, $RepositoryRoot) | Select-Object -Unique
$vehicleFiles = foreach ($directory in $vehicleSearchDirectories) {
    if (Test-Path -LiteralPath $directory -PathType Container) {
        Get-ChildItem -LiteralPath $directory -File -Filter 'vehicle-number-*-debug.apk'
    }
}
$vehicleCandidates = foreach ($candidate in $vehicleFiles) {
    try {
        $candidateMetadata = Get-ApkMetadata -Path $candidate.FullName
        if ($candidateMetadata.Package -eq 'com.smartbis.vehiclenumber') {
            [pscustomobject]@{
                Path = $candidate.FullName
                File = $candidate.Name
                Package = $candidateMetadata.Package
                Version = $candidateMetadata.Version
                VersionCode = $candidateMetadata.VersionCode
                LastWriteTime = $candidate.LastWriteTime
                Label = 'SmartBIS 차량번호 등록'
            }
        }
    } catch {
        Write-Warning "차량번호 APK 후보를 건너뜁니다: $($candidate.Name) ($($_.Exception.Message))"
    }
}
if (-not $vehicleCandidates) {
    throw "차량번호 APK가 없습니다. 검색 위치: $($vehicleSearchDirectories -join ', ')"
}
$vehicleApk = $vehicleCandidates | Sort-Object VersionCode, LastWriteTime -Descending | Select-Object -First 1
$items += [pscustomobject]@{ Path = $vehicleApk.Path; File = $vehicleApk.File; Package = 'com.smartbis.vehiclenumber'; Label = 'SmartBIS 차량번호 등록' }
Write-Host "선택된 차량번호 APK: $($vehicleApk.File) (version $($vehicleApk.Version), code $($vehicleApk.VersionCode))"

# Validate the local source set before making any network request.
$validated = foreach ($item in $items) {
    $path = if ($item.Path) { $item.Path } else { Join-Path $ProjectDrop $item.File }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "APK 파일이 없습니다: $path" }
    $metadata = Get-ApkMetadata -Path $path
    if ($metadata.Package -ne $item.Package) {
        throw "잘못된 APK입니다: $($item.File)`n기대 패키지: $($item.Package)`n실제 패키지: $($metadata.Package)"
    }
    [pscustomobject]@{
        Path = (Resolve-Path -LiteralPath $path).Path
        File = $item.File
        Package = $metadata.Package
        Name = $item.Label
        Version = $metadata.Version
        VersionCode = $metadata.VersionCode
        SizeMB = [math]::Round((Get-Item -LiteralPath $path).Length / 1MB, 1)
    }
}

Write-Host "Headwind: $ServerUrl"
Write-Host "대상 구성: $ConfigurationName"
Write-Host '등록/배포 APK:'
$validated | Format-Table Name, Package, Version, VersionCode, SizeMB -AutoSize | Out-Host

if (-not $Apply) {
    Write-Host '검증 완료: 서버 변경 없음. 적용하려면 같은 명령에 -Apply를 추가하세요.' -ForegroundColor Yellow
    return
}

Write-Warning "적용하면 '$ConfigurationName' 구성에 등록된 기기들에 위 APK $($validated.Count)개가 설치 대상으로 배정됩니다."
$confirmation = Read-Host "계속하려면 구성명을 그대로 입력하세요 ($ConfigurationName)"
if ($confirmation -cne $ConfigurationName) { throw '확인 문자열이 일치하지 않아 아무것도 변경하지 않았습니다.' }

$credential = Get-Credential -Message 'Headwind 관리자 계정으로 로그인합니다. 비밀번호는 저장하거나 출력하지 않습니다.'
$passwordPtr = [IntPtr]::Zero
$password = $null
try {
    $passwordPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($credential.Password)
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPtr)

    $script:webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $options = Get-ApiData (Invoke-RestMethod -Uri "$ServerUrl/rest/public/auth/options" -Method Get -WebSession $script:webSession `
        -Headers @{ Accept = 'application/json' } -TimeoutSec 30) '로그인 옵션 조회'
    $protectedPassword = Protect-HeadwindPassword -Password $password -PublicKey $options.publicKey
    $login = Invoke-RestMethod -Uri "$ServerUrl/rest/public/auth/login" -Method Post -WebSession $script:webSession `
        -ContentType 'application/json; charset=utf-8' -Headers @{ Accept = 'application/json' } `
        -Body (ConvertTo-Json -Compress @{ login = $credential.UserName; password = $protectedPassword }) -TimeoutSec 30
    $null = Get-ApiData $login 'Headwind 로그인'
} finally {
    if ($passwordPtr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPtr) }
    $password = $null
    $protectedPassword = $null
}

$encodedConfiguration = [Uri]::EscapeDataString($ConfigurationName)
$configurations = @(Get-ApiData (Invoke-Api "/rest/private/configurations/search/$encodedConfiguration") '구성 검색')
$configuration = @($configurations | Where-Object { $_.name -ceq $ConfigurationName }) | Select-Object -First 1
if (-not $configuration) { throw "Headwind에서 구성을 찾지 못했습니다: $ConfigurationName" }

$serverApps = @(Get-ApiData (Invoke-Api '/rest/private/applications/search') '앱 목록 조회')

foreach ($apk in $validated) {
    $samePackage = @($serverApps | Where-Object { $_.pkg -ceq $apk.Package })
    if ($samePackage.Count -gt 1) { throw "같은 패키지의 앱이 여러 개 등록되어 자동 선택을 중단합니다: $($apk.Package)" }
    $app = $samePackage | Select-Object -First 1
    if ($app -and [int]$app.versionCode -gt $apk.VersionCode) {
        throw "서버의 $($apk.Package) 버전($($app.versionCode))이 로컬 APK($($apk.VersionCode))보다 높아 다운그레이드를 중단합니다."
    }

    $versions = @()
    if ($app) {
        $versions = @(Get-ApiData (Invoke-Api "/rest/private/applications/$($app.id)/versions") '앱 버전 조회')
    }
    $versionRecord = @($versions | Where-Object { [int]$_.versionCode -eq $apk.VersionCode }) | Select-Object -First 1

    if (-not $versionRecord) {
        Write-Host "업로드: $($apk.Name) $($apk.Version) ($($apk.SizeMB) MB)"
        $upload = Get-ApiData (Invoke-Api '/rest/private/web-ui-files' -Method POST -Multipart -Body @{ file = Get-Item -LiteralPath $apk.Path }) "APK 업로드($($apk.Name))"
        $filePath = $upload.serverPath
        if (-not $filePath) { $filePath = $upload.name }
        if (-not $filePath) { $filePath = $upload.filePath }
        if (-not $filePath) { throw "업로드 응답에서 서버 파일 경로를 찾지 못했습니다: $($apk.Name)" }

        if (-not $app) {
            $createBody = @{
                name = $apk.Name; pkg = $apk.Package; version = $apk.Version; versionCode = $apk.VersionCode
                filePath = $filePath; showIcon = $true; runAfterInstall = $false; runAtBoot = $false
                type = 'app'; action = 1
            }
            $app = Get-ApiData (Invoke-Api '/rest/private/applications/android' -Method PUT -Body $createBody) "앱 등록($($apk.Name))"
            if (-not $app.id) { throw "앱 등록 응답에 ID가 없습니다: $($apk.Name)" }
            $versionRecord = [pscustomobject]@{ id = $app.latestVersion; version = $apk.Version; versionCode = $apk.VersionCode }
        } else {
            $versionBody = @{
                applicationId = [int]$app.id; version = $apk.Version; versionCode = $apk.VersionCode
                filePath = $filePath; split = $false; type = 'app'
            }
            $versionRecord = Get-ApiData (Invoke-Api '/rest/private/applications/versions' -Method PUT -Body $versionBody) "앱 버전 등록($($apk.Name))"
        }
    } else {
        Write-Host "재사용: $($apk.Name) $($apk.Version) (이미 서버에 등록됨)"
    }

    if (-not $versionRecord.id) {
        $versions = @(Get-ApiData (Invoke-Api "/rest/private/applications/$($app.id)/versions") '등록 버전 재조회')
        $versionRecord = @($versions | Where-Object { [int]$_.versionCode -eq $apk.VersionCode }) | Select-Object -First 1
    }
    if (-not $versionRecord -or -not $versionRecord.id) { throw "등록한 앱 버전 ID를 확인할 수 없습니다: $($apk.Name)" }

    # Headwind installs a specific APK version through the version-level endpoint.
    # The application-level endpoint can leave configurationapplications pointing
    # at an older version (or silently fail to create a new link).
    $versionLinks = @(Get-ApiData (Invoke-Api "/rest/private/applications/version/$($versionRecord.id)/configurations") '앱 버전 구성 연결 조회')
    $existingTarget = @($versionLinks | Where-Object { [int]$_.configurationId -eq [int]$configuration.id }) | Select-Object -First 1
    if ($existingTarget -and [int]$existingTarget.action -eq 1) {
        Write-Host "버전 구성 연결 유지: $($apk.Name) → $ConfigurationName"
        continue
    }

    # Preserve any other configuration links already attached to this APK version.
    $versionConfigPayload = @(
        foreach ($oldLink in $versionLinks) {
            if ([int]$oldLink.configurationId -eq [int]$configuration.id) { continue }
            @{
                customerId = [int]$oldLink.customerId
                configurationId = [int]$oldLink.configurationId
                configurationName = $oldLink.configurationName
                applicationId = [int]$oldLink.applicationId
                applicationName = $oldLink.applicationName
                applicationVersionId = [int]$versionRecord.id
                versionText = [int]$oldLink.versionText
                showIcon = [bool]$oldLink.showIcon
                screenOrder = $oldLink.screenOrder
                keyCode = $oldLink.keyCode
                bottom = [bool]$oldLink.bottom
                longTap = [bool]$oldLink.longTap
                action = [int]$oldLink.action
                remove = [bool]$oldLink.remove
                notify = $false
            }
        }
    )
    $versionConfigPayload += @{
        customerId = [int]$configuration.customerId
        configurationId = [int]$configuration.id
        configurationName = $configuration.name
        applicationId = [int]$app.id
        applicationName = $app.name
        applicationVersionId = [int]$versionRecord.id
        versionText = [int]$apk.VersionCode
        showIcon = $true
        screenOrder = $null
        keyCode = $null
        bottom = $false
        longTap = $false
        action = 1
        remove = $false
        notify = $true
    }
    $versionLinkRequest = @{ applicationVersionId = [int]$versionRecord.id; configurations = @($versionConfigPayload) }
    $null = Get-ApiData (Invoke-Api '/rest/private/applications/version/configurations' -Method POST -Body $versionLinkRequest) "버전 구성 배정($($apk.Name))"
    Write-Host "최신 버전을 설치 대상으로 배정: $($apk.Name) $($apk.Version) → $ConfigurationName" -ForegroundColor Green
}

Write-Host '완료: 앱 등록/버전 확인 및 구성 배정이 끝났습니다. 각 기기의 체크인/동기화 후 설치 상태를 Headwind 기기 화면에서 확인하세요.' -ForegroundColor Green
