param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$TargetPath
)

$ErrorActionPreference = 'Stop'
$Repository = 'ViaVersion/ViaProxy'
$WorkflowId = 72929512
$TargetPath = [System.IO.Path]::GetFullPath($TargetPath)
$TargetDirectory = Split-Path -Parent $TargetPath
$StatePath = "$TargetPath.update-state.json"
$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("nekoproxy-viaproxy-" + [guid]::NewGuid().ToString('N'))
$Headers = @{
    'User-Agent' = 'NekoProxy-AutoUpdater'
    'Accept' = 'application/vnd.github+json'
}

try {
    if (Test-Path $TargetPath -PathType Leaf -and (Test-Path $StatePath -PathType Leaf)) {
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

    Write-Host '[ViaProxy] 正在查询 ViaProxy 官方最新成功构建...'
    $runsResponse = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/actions/runs?per_page=100" -Headers $Headers
    $run = $runsResponse.workflow_runs |
        Where-Object {
            $_.workflow_id -eq $WorkflowId -and
            $_.head_branch -eq 'main' -and
            $_.status -eq 'completed' -and
            $_.conclusion -eq 'success'
        } |
        Sort-Object created_at -Descending |
        Select-Object -First 1

    if (-not $run) {
        Write-Warning '[ViaProxy] 没有找到官方成功构建，保留当前版本。'
        exit 0
    }

    if (Test-Path $StatePath -PathType Leaf) {
        try {
            $state = Get-Content -Raw -Path $StatePath | ConvertFrom-Json
            if ($state.commit -eq $run.head_sha -and (Test-Path $TargetPath -PathType Leaf)) {
                $state.last_checked_utc = [DateTimeOffset]::UtcNow.ToString('o')
                $state | ConvertTo-Json | Set-Content -Encoding UTF8 -Path $StatePath
                Write-Host "[ViaProxy] 已是最新官方构建 ($($run.head_sha.Substring(0, 8)))。"
                exit 0
            }
        } catch {
            # Rebuild state from the successful update below.
        }
    }

    $artifactsResponse = Invoke-RestMethod -Uri $run.artifacts_url -Headers $Headers
    $artifact = $artifactsResponse.artifacts |
        Where-Object { $_.name -eq 'Artifacts' -and -not $_.expired } |
        Select-Object -First 1

    if (-not $artifact) {
        Write-Warning '[ViaProxy] 最新构建的下载产物不存在或已过期，保留当前版本。'
        exit 0
    }

    New-Item -ItemType Directory -Force -Path $TempRoot | Out-Null
    $archivePath = Join-Path $TempRoot 'viaproxy-artifacts.zip'
    Invoke-WebRequest -Uri $artifact.archive_download_url -Headers $Headers -OutFile $archivePath

    if ($artifact.digest -match '^sha256:(.+)$') {
        $actualDigest = (Get-FileHash -Algorithm SHA256 -Path $archivePath).Hash.ToLowerInvariant()
        if ($actualDigest -ne $Matches[1].ToLowerInvariant()) {
            throw "官方构建产物 SHA-256 校验失败。"
        }
    }

    $extractPath = Join-Path $TempRoot 'extracted'
    Expand-Archive -Path $archivePath -DestinationPath $extractPath -Force
    $jar = Get-ChildItem -Path $extractPath -Filter '*.jar' -File -Recurse |
        Where-Object { $_.Name -notmatch '\+java8\.jar$' } |
        Select-Object -First 1

    if (-not $jar) {
        throw '官方构建产物中没有找到适用于 Java 17+ 的 ViaProxy JAR。'
    }

    Add-Type -AssemblyName System.IO.Compression
    $jarArchive = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
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
    Copy-Item -Path $jar.FullName -Destination $stagedPath -Force
    Move-Item -Path $stagedPath -Destination $TargetPath -Force

    @{
        commit = $run.head_sha
        version = $jar.Name
        last_checked_utc = [DateTimeOffset]::UtcNow.ToString('o')
    } | ConvertTo-Json | Set-Content -Encoding UTF8 -Path $StatePath

    Write-Host "[ViaProxy] 已更新到官方构建: $($jar.Name)"
    Write-Host '[ViaProxy] 新版本将在本次启动中生效。'
} finally {
    if (Test-Path $TempRoot) {
        Remove-Item -Path $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
