$ErrorActionPreference = 'Stop'
$version = '9.1.0'
$sha256 = 'a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806'
$root = Split-Path -Parent $PSScriptRoot

# Prefer a Gradle installation already available on PATH.
# Select one command explicitly: Get-Command may return both gradle.bat
# and the extensionless Unix launcher from a Gradle distribution.
$systemGradle = Get-Command gradle.bat -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $systemGradle) {
    $systemGradle = Get-Command gradle -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
}

if ($systemGradle) {
    $gradle = $systemGradle.Source
    Write-Host "[ViaProxy] Using system Gradle: $gradle"
} else {
    $cache = Join-Path $env:LOCALAPPDATA 'NekoGate\gradle'
    $install = Join-Path $cache "gradle-$version"
    $gradle = Join-Path $install "gradle-$version\bin\gradle.bat"
    if (-not (Test-Path $gradle)) {
        New-Item -ItemType Directory -Force -Path $cache | Out-Null
        $zip = Join-Path $cache "gradle-$version-bin.zip"
        $url = "https://services.gradle.org/distributions/gradle-$version-bin.zip"
        Write-Host "[ViaProxy] Downloading Gradle $version..."
        Invoke-WebRequest -Uri $url -OutFile $zip
        $actual = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLowerInvariant()
        if ($actual -ne $sha256) { throw "Gradle SHA-256 mismatch: expected $sha256, got $actual" }
        if (Test-Path $install) { Remove-Item -Recurse -Force $install }
        Expand-Archive -Path $zip -DestinationPath $install -Force
    }
}
& $gradle -p (Join-Path $root 'viaproxy-plugin') build
exit $LASTEXITCODE
