param([switch]$Install)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

if ([Environment]::OSVersion.Version.Build -lt 22000) { throw 'Windows 11 is required.' }
foreach ($program in @('java', 'go', 'dotnet')) {
    if (-not (Get-Command $program -ErrorAction SilentlyContinue)) { throw "Install $program and add it to PATH before running this script." }
}
$output = Join-Path $root 'build\desktop-windows'
New-Item -ItemType Directory -Path $output -Force | Out-Null
Start-Transcript -Path (Join-Path $output 'build.log') -Force
try {
    Push-Location (Join-Path $root 'desktop-helper')
    try { Invoke-Checked 'go' @('test', './...') } finally { Pop-Location }
    # Remove stale native SDK files from earlier publications before staging the new package.
    Invoke-Checked (Join-Path $root 'gradlew.bat') @('-PdesktopOnly=true', ':desktopApp:clean', '--console=plain')
    $nativeOutput = Join-Path $root 'desktopApp\build\generated\resources\common\VerveWidgets'
    Invoke-Checked 'dotnet' @('publish', 'native/windows/VerveWidgets/VerveWidgets.csproj', '-c', 'Release', '-r', 'win-x64', '-o', $nativeOutput)
    Invoke-Checked (Join-Path $root 'gradlew.bat') @('-PdesktopOnly=true', ':desktopIntegration:test', ':desktopApp:createDistributable', '--console=plain')
    $app = Join-Path $root 'desktopApp\build\compose\binaries\main\app\VerveTwoDo'
    $package = Join-Path $output ('package-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $package -Force | Out-Null
    Copy-Item $app (Join-Path $package 'Desktop') -Recurse
    Copy-Item 'native/windows/Package.appxmanifest' (Join-Path $package 'AppxManifest.xml')
    Set-Content -Path (Join-Path $output 'latest-package.txt') -Value $package -Encoding UTF8
    New-Item -ItemType Directory -Path (Join-Path $package 'Public') -Force | Out-Null
    $assets = Join-Path $package 'Assets'
    New-Item -ItemType Directory -Path $assets -Force | Out-Null
    Add-Type -AssemblyName System.Drawing
    foreach ($entry in @(@('Logo.png', 150), @('SmallLogo.png', 44))) {
        $bitmap = New-Object System.Drawing.Bitmap($entry[1], $entry[1])
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        $graphics.Clear([System.Drawing.Color]::FromArgb(69, 107, 77))
        $font = New-Object System.Drawing.Font('Segoe UI', ($entry[1] * 0.55))
        $graphics.DrawString('V', $font, [System.Drawing.Brushes]::White, ($entry[1] * 0.15), 0)
        $bitmap.Save((Join-Path $assets $entry[0]), [System.Drawing.Imaging.ImageFormat]::Png)
        $font.Dispose(); $graphics.Dispose(); $bitmap.Dispose()
    }
    $helper = Join-Path $package 'Desktop\app\resources\vervetwodo-helper.exe'
    $launcher = Join-Path $package 'Desktop\app\VerveTwoDo.cfg'
    if ((Get-Content $launcher -Raw).Contains('-Dvervetwodo.helpers=')) {
        throw 'The packaged launcher contains a development-only helper path.'
    }
    if (-not (Test-Path $helper)) { throw "Missing packaged helper: $helper" }
    $probeDirectory = Join-Path $output ('probe-' + [Guid]::NewGuid())
    $response = '{"version":1,"id":"windows-probe","method":"hello"}' | & $helper '-data-dir' $probeDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Packaged helper failed.' }
    $decoded = $response | ConvertFrom-Json
    if ($decoded.version -ne 1 -or $decoded.id -ne 'windows-probe' -or $decoded.error) { throw 'Packaged helper protocol mismatch.' }
    if ($Install) {
        $developerMode = Get-ItemPropertyValue 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\AppModelUnlock' 'AllowDevelopmentWithoutDevLicense' -ErrorAction SilentlyContinue
        if ($developerMode -ne 1) { throw 'Enable Developer Mode in Windows Settings, then rerun with -Install. This script does not change that security setting.' }
        Add-AppxPackage -Register (Join-Path $package 'AppxManifest.xml') -ForceApplicationShutdown
        $installed = Get-AppxPackage -Name 'studio.camille.vervetwodo.preview'
        if (-not $installed) { throw 'The app package was not registered.' }
        Start-Process 'explorer.exe' ('shell:AppsFolder\' + $installed.PackageFamilyName + '!App')
    }
    Write-Host "Build and protocol checks passed. Package: $package"
    Write-Host 'In the app, add a task, test its notification, and switch to French. Close and reopen the window from the tray.'
    Write-Host 'In Windows Widgets, add VerveTwoDo and complete the task. Repeat after explicitly quitting the app.'
    Write-Host ('Return the build log and the results of those checks: ' + (Join-Path $output 'build.log'))
} finally { Stop-Transcript }
