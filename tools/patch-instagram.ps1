param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Instagram,
    [switch]$Install,
    [string]$Keystore
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = Split-Path -Parent $PSScriptRoot
$ToolsDir = Join-Path $RepoRoot ".tools"
$OutDir = Join-Path $RepoRoot "out"
$OutputApk = Join-Path $OutDir "Instagram-Piko-test.apk"

function Resolve-FullPath([string]$Path) {
    return [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $Path))
}

function Get-MorpheJar {
    New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null
    $latest = Invoke-RestMethod -Uri "https://api.github.com/repos/MorpheApp/morphe-desktop/releases/latest" -Headers @{ "User-Agent" = "Piko-Local-Patcher" }
    $asset = $latest.assets | Where-Object { $_.name -like "morphe-desktop-*-all.jar" } | Select-Object -First 1
    if (-not $asset) { throw "Could not find the Morphe Desktop CLI jar in the latest release." }
    $jar = Join-Path $ToolsDir $asset.name
    if (-not (Test-Path $jar)) {
        Write-Host "Downloading Morphe Desktop $($latest.tag_name)..."
        Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $jar
    }
    return $jar
}

$InstagramPath = Resolve-FullPath $Instagram
if (-not (Test-Path $InstagramPath)) { throw "Instagram input not found: $InstagramPath" }
$ext = [System.IO.Path]::GetExtension($InstagramPath).ToLowerInvariant()
if ($ext -notin @(".apk", ".apkm")) { Write-Warning "Expected an .apk or .apkm input. Got: $ext" }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw "Java is not installed or not in PATH. Install Java 17+ first." }

Push-Location $RepoRoot
try {
    Write-Host "Building current Piko patches..."
    & .\gradlew.bat buildAndroid
    if ($LASTEXITCODE -ne 0) { throw "Piko build failed." }

    $mpp = Get-ChildItem -Path (Join-Path $RepoRoot "patches\build\libs") -Filter "*.mpp" | Where-Object { $_.Name -notmatch "sources|javadoc" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $mpp) { throw "No Piko .mpp bundle was produced." }

    $MorpheJar = Get-MorpheJar
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    if (Test-Path $OutputApk) { Remove-Item $OutputApk -Force }

    $args = @("-Xms1024m", "-jar", $MorpheJar, "patch", "--patches", $mpp.FullName, "--out", $OutputApk)

    if ($Keystore) {
        $KeyPath = Resolve-FullPath $Keystore
        if (-not (Test-Path $KeyPath)) { throw "Keystore not found: $KeyPath" }
        $args += @("--keystore", $KeyPath, "--keystore-entry-alias", "Morphe", "--keystore-entry-password", "Morphe")
    }

    if ($Install) { $args += "--install" }
    $args += $InstagramPath

    Write-Host "Patching Instagram..."
    & java @args
    if ($LASTEXITCODE -ne 0) { throw "Morphe patching failed." }
    if (-not (Test-Path $OutputApk)) { throw "Patching finished but the output APK was not found." }

    Write-Host ""
    Write-Host "Done:"
    Write-Host $OutputApk
    if (-not $Install) { Write-Host "Copy that APK to your phone and install it." }
}
finally {
    Pop-Location
}