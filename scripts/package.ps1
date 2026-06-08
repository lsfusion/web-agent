# Build a native installer/app-image for the current OS using jpackage.
# Run from the project root:  pwsh scripts\package.ps1 [-Type app-image|msi|exe]
# Requires JDK 17+ on PATH (or set $env:JAVA_HOME).

[CmdletBinding()]
param(
    [ValidateSet('app-image', 'msi', 'exe')]
    [string]$Type = 'app-image',

    [string]$AppVersion = '0.1.0'
)

$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot\.."
Set-Location $root

Write-Host "==> mvn package" -ForegroundColor Cyan
& mvn -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

$jar = Join-Path $root 'target\web-agent.jar'
if (-not (Test-Path $jar)) { throw "Expected fat jar at $jar" }

$staging = Join-Path $root 'target\jpackage-input'
$dist    = Join-Path $root 'dist'
Remove-Item -Recurse -Force $staging, $dist -ErrorAction SilentlyContinue | Out-Null
New-Item -ItemType Directory -Force -Path $staging, $dist | Out-Null
Copy-Item $jar $staging

$jpackage = 'jpackage'
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
    if (Test-Path $candidate) { $jpackage = $candidate }
}

Write-Host "==> jpackage --type $Type" -ForegroundColor Cyan
& $jpackage `
    --type $Type `
    --name web-agent `
    --app-version $AppVersion `
    --input $staging `
    --main-jar web-agent.jar `
    --main-class com.lsfusion.webagent.Main `
    --dest $dist `
    --java-options '-Xms32m' `
    --java-options '-Xmx256m'

if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

Write-Host "==> Done. Output in $dist" -ForegroundColor Green
Get-ChildItem $dist
