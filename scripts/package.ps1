# Build the web-agent Windows package.
# Run from the project root:  pwsh scripts\package.ps1 [-Type msix|app-image]
#   msix (default): jpackage app-image -> AppxManifest + assets -> MakeAppx pack
#                   -> dist\web-agent-<v4>.msix + dist\web-agent.appinstaller.
#                   Unsigned - CI signs it. Needs the Windows 10/11 SDK for
#                   MakeAppx.exe (or point $env:MAKEAPPX at it directly).
#   app-image: stops after jpackage - a runnable dist\web-agent\web-agent.exe
#              with the trimmed runtime, no Windows SDK needed.
# Requires JDK 17+ on PATH (or set $env:JAVA_HOME).

[CmdletBinding()]
param(
    [ValidateSet('msix', 'app-image')]
    [string]$Type = 'msix',

    # Overrides the version from pom.xml.
    [string]$AppVersion
)

$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot\.."
Set-Location $root

if (-not $AppVersion) {
    # jpackage and MSIX only accept numeric versions, so strip -SNAPSHOT/-rc qualifiers.
    $AppVersion = ([xml](Get-Content (Join-Path $root 'pom.xml'))).project.version -replace '-.*$', ''
}
Write-Host "==> version $AppVersion" -ForegroundColor Cyan

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

$iconArgs = @()
$icon = Join-Path $root 'packaging\windows\web-agent.ico'
if (Test-Path $icon) { $iconArgs = @('--icon', $icon) }

# Trim the bundled runtime to just the modules the agent needs. Baseline from:
#   jdeps --multi-release 21 --print-module-deps --ignore-missing-deps target\web-agent.jar
# plus service-loaded modules jdeps cannot see:
#   jdk.charsets  - extended encodings (cp1251/cp866/...) for print's Charset.forName
#   jdk.crypto.ec - SunEC provider, TLS handshakes for https downloads in writeFile
#                   (merged into java.base in JDK 22+; drop when the build JDK moves on)
$modules = 'java.base,java.desktop,java.sql,jdk.httpserver,jdk.charsets,jdk.crypto.ec'

# both types build the app-image; msix additionally packs it below
Write-Host "==> jpackage --type app-image" -ForegroundColor Cyan
& $jpackage `
    --type app-image `
    --name web-agent `
    --app-version $AppVersion `
    --input $staging `
    --main-jar web-agent.jar `
    --main-class com.lsfusion.webagent.Main `
    --dest $dist `
    --add-modules $modules `
    --java-options '-Xms32m' `
    --java-options '-Xmx256m' `
    @iconArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

if ($Type -eq 'msix') {
    # MSIX requires a 4-part numeric version (1.0.0 -> 1.0.0.0).
    $vParts = $AppVersion.Split('.')
    while ($vParts.Count -lt 4) { $vParts += '0' }
    $msixVersion = ($vParts[0..3]) -join '.'

    Write-Host "==> staging MSIX layout" -ForegroundColor Cyan
    $msixStaging = Join-Path $root 'target\msix-staging'
    Remove-Item -Recurse -Force $msixStaging -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $msixStaging | Out-Null
    Copy-Item (Join-Path $dist 'web-agent\*') $msixStaging -Recurse
    Copy-Item (Join-Path $root 'packaging\msix\assets') (Join-Path $msixStaging 'Assets') -Recurse
    (Get-Content (Join-Path $root 'packaging\msix\AppxManifest.xml.template')) `
        -replace '\$\{VERSION\}', $msixVersion `
        | Out-File (Join-Path $msixStaging 'AppxManifest.xml') -Encoding utf8

    $makeappx = $env:MAKEAPPX
    if (-not $makeappx) {
        $kits = 'C:\Program Files (x86)\Windows Kits\10\bin'
        $found = Get-ChildItem -Path $kits -Filter makeappx.exe -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -like '*\x64\*' } |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($null -eq $found) { throw "MakeAppx.exe not found. Install the Windows 10/11 SDK or set `$env:MAKEAPPX." }
        $makeappx = $found.FullName
    }
    Write-Host "==> MakeAppx pack" -ForegroundColor Cyan
    $msixName = "web-agent-$msixVersion.msix"
    # quiet on success (it lists every packed file), full output on failure
    $packOut = & $makeappx pack /d $msixStaging /p (Join-Path $dist $msixName) /o 2>&1
    if ($LASTEXITCODE -ne 0) { $packOut | Write-Host; throw "MakeAppx failed" }
    $packOut | Where-Object { $_ } | Select-Object -Last 1 | Write-Host

    (Get-Content (Join-Path $root 'packaging\msix\web-agent.appinstaller.template')) `
        -replace '\$\{VERSION\}', $msixVersion `
        -replace '\$\{MSIX_NAME\}', $msixName `
        | Out-File (Join-Path $dist 'web-agent.appinstaller') -Encoding utf8
}

Write-Host "==> Done. Output in $dist" -ForegroundColor Green
Get-ChildItem $dist
