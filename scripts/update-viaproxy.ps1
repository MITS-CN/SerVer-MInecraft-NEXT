param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$TargetPath
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Repository = 'ViaVersion/ViaProxy'
$TargetPath = [System.IO.Path]::GetFullPath($TargetPath)
$TargetDirectory = Split-Path -Parent $TargetPath
$StatePath = "$TargetPath.update-state.json"
$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("nekoproxy-viaproxy-" + [guid]::NewGuid().ToString('N'))
$Headers = @{
    'User-Agent' = 'NekoProxy-AutoUpdater'
    'Accept' = 'application/vnd.github+json'
}

try {
    if ((Test-Path $TargetPath -PathType Leaf) -and (Test-Path $StatePath -PathType Leaf)) {
        try {
            $state = Get-Content -Raw -Path $StatePath | ConvertFrom-Json
            $lastCheck = [DateTimeOffset]::Parse($state.last_checked_utc)
            if (([DateTimeOffset]::UtcNow - $lastCheck).TotalHours -lt 24) {
                Write-Host '[ViaProxy] 最近 24 小时已检查过更新。'
                exit 0
            }
        } catch {
            # Invalid or old state is harmless; perform a fresh check.
        }
    }

    Write-Host '[ViaProxy] 正在查询 ViaProxy 官方最新 Release...'
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/releases/latest" -Headers $Headers
    if (-not $release -or -not $release.tag_name) {
        throw '无法获取 ViaProxy 官方最新 Release 信息。'
    }

    $asset = $release.assets |
        Where-Object { $_.name -match '^ViaProxy-.+\.jar$' -and $_.name -notmatch '\+java8\.jar$' } |
        Select-Object -First 1

    if (-not $asset -or -not $asset.browser_download_url) {
        throw "官方 Release $($release.tag_name) 中没有找到 Java 17+ ViaProxy JAR。"
    }

    if (Test-Path $StatePath -PathType Leaf) {
        try {
            $state = Get-Content -Raw -Path $StatePath | ConvertFrom-Json
            if ($state.version -eq $asset.name -and $state.release_tag -eq $release.tag_name -and (Test-Path $TargetPath -PathType Leaf)) {
                $state.last_checked_utc = [DateTimeOffset]::UtcNow.ToString('o')
                $state | ConvertTo-Json | Set-Content -Encoding UTF8 -Path $StatePath
                Write-Host "[ViaProxy] 已是最新官方版本 ($($release.tag_name))。"
                exit 0
            }
        } catch {
            # Rebuild state from the successful update below.
        }
    }

    New-Item -ItemType Directory -Force -Path $TempRoot | Out-Null
    $jarPath = Join-Path $TempRoot $asset.name
    Write-Host "[ViaProxy] 正在下载 $($asset.name)..."
    Invoke-WebRequest -Uri $asset.browser_download_url -Headers @{ 'User-Agent' = $Headers['User-Agent'] } -OutFile $jarPath

    if ($asset.digest -match '^sha256:(.+)$') {
        $expectedDigest = $Matches[1].ToLowerInvariant()
        $actualDigest = (Get-FileHash -Algorithm SHA256 -Path $jarPath).Hash.ToLowerInvariant()
        if ($actualDigest -ne $expectedDigest) {
            throw '官方 ViaProxy JAR 的 SHA-256 校验失败。'
        }
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $jarArchive = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        if (-not $jarArchive.GetEntry('net/raphimc/viaproxy/ViaProxy.class')) {
            throw '下载的 JAR 未通过 ViaProxy 主类校验。'
        }
    } finally {
        $jarArchive.Dispose()
    }

    New-Item -ItemType Directory -Force -Path $TargetDirectory | Out-Null
    if (Test-Path $TargetPath -PathType Leaf) {
        $backupPath = "$TargetPath.backup.$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))"
        Copy-Item -Path $TargetPath -Destination $backupPath
        Write-Host "[ViaProxy] 已备份旧版本: $backupPath"
    }

    $stagedPath = "$TargetPath.download"
    Copy-Item -Path $jarPath -Destination $stagedPath -Force
    Move-Item -Path $stagedPath -Destination $TargetPath -Force

    @{
        release_tag = $release.tag_name
        version = $asset.name
        last_checked_utc = [DateTimeOffset]::UtcNow.ToString('o')
    } | ConvertTo-Json | Set-Content -Encoding UTF8 -Path $StatePath

    Write-Host "[ViaProxy] 已更新到官方 Release: $($release.tag_name) ($($asset.name))"
    Write-Host '[ViaProxy] 新版本将在本次启动中生效。'
} finally {
    if (Test-Path $TempRoot) {
        Remove-Item -Path $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
