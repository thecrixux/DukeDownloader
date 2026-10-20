[CmdletBinding()]
param(
    [string]$SdkPath = '',
    [string]$PythonPath = '',
    [string]$LogPath = 'C:\DukeDownloader-build.txt'
)
$ErrorActionPreference = 'Stop'
$repo = Join-Path $PSScriptRoot 'fuente'
if (-not (Test-Path (Join-Path $repo 'gradlew.bat'))) { throw 'Extrae todo el paquete antes de compilar.' }
if (-not $PythonPath) {
    if (-not (Get-Command py -ErrorAction SilentlyContinue)) { throw 'Indica -PythonPath C:\ruta\Python311\python.exe.' }
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $PythonPath = & py -3.11 -c 'import sys; print(sys.executable)'
    $pythonExit = $LASTEXITCODE
    $ErrorActionPreference = $oldPreference
    if ($pythonExit -ne 0 -or -not $PythonPath) { throw 'Se requiere Python 3.11.' }
}
if (-not $SdkPath) {
    if ($env:ANDROID_HOME) { $SdkPath = $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $SdkPath = $env:ANDROID_SDK_ROOT }
    else { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
}
if (-not (Test-Path (Join-Path $SdkPath 'platforms\android-34\android.jar'))) { throw 'Falta Android SDK Platform 34.' }
$utf8 = New-Object System.Text.UTF8Encoding($false)
$sdkValue = -join ([IO.Path]::GetFullPath($SdkPath).Replace('\', '/').ToCharArray() | ForEach-Object {
    if ([int][char]$_ -gt 127) { '\u' + ([int][char]$_).ToString('x4') } else { [string]$_ }
})
[IO.File]::WriteAllText((Join-Path $repo 'local.properties'), "sdk.dir=$sdkValue`n", $utf8)
[IO.File]::WriteAllText($LogPath, '', $utf8)
Write-Host 'Usa JDK 17 en JAVA_HOME y PATH. Esta compilacion genera una Release SIN FIRMAR.'
Push-Location $repo
$oldPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat clean :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease "-PbuildPython=$PythonPath" --console=plain --no-daemon 2>&1 | Tee-Object -FilePath $LogPath
    $result = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $oldPreference
    Pop-Location
}
if ($result -ne 0) { throw "Compilacion fallida. Registro: $LogPath" }
$apk = Join-Path $repo 'app\build\outputs\apk\release\app-release-unsigned.apk'
if (-not (Test-Path $apk)) { throw 'No se encuentra el APK Release sin firmar.' }
Copy-Item -LiteralPath $apk -Destination (Join-Path $PSScriptRoot 'DukeDownloader-3.1.2-unsigned.apk') -Force
Write-Host 'Release lista. Firma con Firmar-Release.ps1 usando tu clave original.' -ForegroundColor Green
