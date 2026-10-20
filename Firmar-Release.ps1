[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$KeystorePath,
    [string]$KeyAlias = '',
    [string]$SdkPath = '',
    [string]$ApkPath = (Join-Path $PSScriptRoot 'DukeDownloader-3.1.2-unsigned.apk'),
    [string]$OutputPath = (Join-Path $PSScriptRoot 'DukeDownloader-3.1.2-signed.apk')
)
$ErrorActionPreference = 'Stop'
$expectedCertificate = '32eb072fcd3419ef7bdf979a73086ad990978321775809c963bff29e334c4586'
if (-not $SdkPath) {
    if ($env:ANDROID_HOME) { $SdkPath = $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $SdkPath = $env:ANDROID_SDK_ROOT }
    else { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
}
$tools = Get-ChildItem (Join-Path $SdkPath 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $tools) { throw 'No se encuentran Android SDK Build-Tools.' }
$align = Join-Path $tools.FullName 'zipalign.exe'
$signer = Join-Path $tools.FullName 'apksigner.bat'
$aapt = Join-Path $tools.FullName 'aapt.exe'
foreach ($path in @($align, $signer, $aapt, $KeystorePath, $ApkPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "No se encuentra: $path" }
}
$KeystorePath = [IO.Path]::GetFullPath($KeystorePath)
$ApkPath = [IO.Path]::GetFullPath($ApkPath)
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
if ($OutputPath -eq $KeystorePath -or $OutputPath -eq $ApkPath -or [IO.Path]::GetExtension($OutputPath) -ne '.apk') {
    throw 'La salida debe ser un APK distinto del original y del almacen de claves.'
}
$identity = & $aapt dump badging $ApkPath
if ($LASTEXITCODE -ne 0 -or ($identity -join "`n") -notmatch "package: name='com\.dukedownloader\.ps2emu' versionCode='12' versionName='3\.1\.2'") {
    throw 'El APK no tiene el paquete/version esperados para esta actualizacion.'
}
$folder = Split-Path $OutputPath -Parent
if (-not (Test-Path $folder -PathType Container)) { throw 'La carpeta de salida no existe.' }
$token = [guid]::NewGuid().ToString('N')
$aligned = Join-Path $folder "$token-aligned.apk"
$pending = Join-Path $folder "$token-signed.apk"
try {
    & $align -f -p 4 $ApkPath $aligned
    if ($LASTEXITCODE -ne 0) { throw 'zipalign ha fallado.' }
    Write-Host 'La herramienta oficial pedira la contrasena localmente. No se guarda ni se envia a ningun servidor.'
    $signArgs = @('sign', '--ks', $KeystorePath, '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true', '--out', $pending)
    if ($KeyAlias) { $signArgs += @('--ks-key-alias', $KeyAlias) }
    $signArgs += $aligned
    & $signer @signArgs
    if ($LASTEXITCODE -ne 0) { throw 'La firma ha fallado. Comprueba la clave y, si contiene varias, indica KeyAlias.' }
    $verification = & $signer verify --verbose --print-certs $pending
    if ($LASTEXITCODE -ne 0) { throw 'La verificacion de la firma ha fallado.' }
    $certificate = $verification | Select-String '^Signer #1 certificate SHA-256 digest:\s*(\S+)'
    if (-not $certificate -or $certificate.Matches[0].Groups[1].Value.ToLowerInvariant() -ne $expectedCertificate) {
        throw 'El certificado no coincide con el de DukeDownloader instalado. No instales este APK como actualizacion.'
    }
    $details = $verification -join "`n"
    if ($details -notmatch 'Verified using v2 scheme[^\r\n]*true' -or $details -notmatch 'Verified using v3 scheme[^\r\n]*true') {
        throw 'El APK no verifica ambos esquemas v2 y v3.'
    }
    & $align -c -p 4 $pending
    if ($LASTEXITCODE -ne 0) { throw 'El APK firmado no supera la comprobacion de alineacion.' }
    Move-Item -LiteralPath $pending -Destination $OutputPath -Force
    Write-Host "Firma, certificado, paquete y alineacion correctos: $OutputPath" -ForegroundColor Green
    Write-Host 'Instala este APK sobre la app actual sin desinstalarla.'
} finally {
    Remove-Item -LiteralPath $aligned, $pending -Force -ErrorAction SilentlyContinue
}
