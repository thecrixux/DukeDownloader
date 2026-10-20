#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Repo = 'C:\DukeDownloader',
    [ValidateSet('Apply', 'Build', 'Undo')][string]$Action = 'Apply',
    [string]$PythonPath = '',
    [string]$LogPath = 'C:\DukeDownloader-hotfix-build.txt'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Patch: permanent Chaquopy JNI/reflection rules. No APK compilation unless -Action Build.
# Upstream 16.1.0: product/gradle-plugin/src/main/resources/com/chaquo/python/proguard-rules.pro
# https://github.com/chaquo/chaquopy/tree/16.1.0
# Device verification remains necessary. No category-crash fix is claimed here.

$container = (Resolve-Path -LiteralPath $Repo).ProviderPath
$project = $container
if (-not (Test-Path -LiteralPath (Join-Path $project 'app\build.gradle.kts'))) {
    $project = Join-Path $container 'fuente'
}
$gradle = Join-Path $project 'app\build.gradle.kts'
$rules = Join-Path $project 'app\proguard-rules.pro'
if (-not (Test-Path -LiteralPath $gradle -PathType Leaf) -or
    -not (Test-Path -LiteralPath $rules -PathType Leaf)) {
    throw 'No encuentro app\build.gradle.kts y app\proguard-rules.pro. Indica -Repo con tu proyecto o su carpeta contenedora.'
}
$config = [IO.File]::ReadAllText($gradle)
if ($config -notmatch 'applicationId\s*=\s*"com\.dukedownloader\.ps2emu"' -or
    $config -notmatch 'id\("com\.chaquo\.python"\)' -or
    $config -notmatch '"proguard-rules\.pro"') {
    throw 'La configuracion no coincide con DukeDownloader/Chaquopy. No se ha modificado nada.'
}
$backupDir = Join-Path $project '.duke-chaquopy-hotfix-backup'
$backup = Join-Path $backupDir 'proguard-rules.pro.original'
$record = Join-Path $backupDir 'state.json'
$begin = '# BEGIN DUKE_CHAQUOPY_STARTUP_HOTFIX_V1'
$end = '# END DUKE_CHAQUOPY_STARTUP_HOTFIX_V1'
$utf8 = New-Object System.Text.UTF8Encoding($false)
function Get-Hash([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

if ($Action -eq 'Undo') {
    if (-not (Test-Path -LiteralPath $record -PathType Leaf) -or
        -not (Test-Path -LiteralPath $backup -PathType Leaf)) {
        throw 'No existe una copia completa creada por este parche.'
    }
    $state = [IO.File]::ReadAllText($record) | ConvertFrom-Json
    if ((Get-Hash $backup) -ne $state.originalHash) { throw 'La copia de seguridad ha cambiado; no se restaura.' }
    $currentHash = Get-Hash $rules
    if ($currentHash -eq $state.originalHash) {
        Write-Host 'El archivo original ya esta restaurado.'
        return
    }
    if ($currentHash -ne $state.patchedHash) {
        throw "Hay cambios posteriores en proguard-rules.pro. No los sobrescribo. Copia original: $backup"
    }
    [IO.File]::WriteAllBytes($rules, [IO.File]::ReadAllBytes($backup))
    Write-Host 'Reglas originales restauradas. La APK instalada no cambia hasta que vuelvas a compilar, firmar e instalar.'
    return
}

$block = @'
# BEGIN DUKE_CHAQUOPY_STARTUP_HOTFIX_V1
# Keep the native/Python bridge even if Gradle clean removes generated rules.
# Rules from Chaquopy 16.1.0; keep R8 enabled for the rest of the app.
-keep class com.chaquo.python.** { *; }
-keep class kotlin.jvm.functions.** { *; }
-keep class kotlin.jvm.internal.FunctionBase { *; }
-keep class kotlin.reflect.KAnnotatedElement { *; }
-dontwarn org.jetbrains.annotations.NotNull
# END DUKE_CHAQUOPY_STARTUP_HOTFIX_V1
'@
$text = [IO.File]::ReadAllText($rules)
$newline = "`n"
if ($text.Contains("`r`n")) { $newline = "`r`n" }
$block = $block.Replace("`r`n", "`n").Replace("`n", $newline)
if ($text.Contains($begin) -or $text.Contains($end)) {
    if (-not $text.Contains($block)) { throw 'El bloque del parche esta incompleto o modificado. No se ha cambiado nada.' }
    Write-Host 'El parche ya esta aplicado.'
} else {
    $original = [IO.File]::ReadAllBytes($rules)
    if (Test-Path -LiteralPath $backupDir) {
        if (-not (Test-Path -LiteralPath $record) -or -not (Test-Path -LiteralPath $backup)) {
            throw "Copia anterior incompleta: $backupDir. No se sobrescribe."
        }
        $previous = [IO.File]::ReadAllText($record) | ConvertFrom-Json
        if ((Get-Hash $rules) -ne $previous.originalHash -or (Get-Hash $backup) -ne $previous.originalHash) {
            throw 'Ya existe una copia de otra revision. No se sobrescribe.'
        }
    } else {
        [void][IO.Directory]::CreateDirectory($backupDir)
        [IO.File]::WriteAllBytes($backup, $original)
    }
    $updated = $text + $newline + $block + $newline
    $digest = [Security.Cryptography.SHA256]::Create()
    try { $patchedHash = [BitConverter]::ToString($digest.ComputeHash($utf8.GetBytes($updated))).Replace('-', '') }
    finally { $digest.Dispose() }
    $state = @{ originalHash = (Get-Hash $backup); patchedHash = $patchedHash; patch = 'DUKE_CHAQUOPY_STARTUP_HOTFIX_V1' }
    [IO.File]::WriteAllText($record, ($state | ConvertTo-Json), $utf8)
    try {
        [IO.File]::WriteAllText($rules, $updated, $utf8)
        if ((Get-Hash $rules) -ne $patchedHash) { throw 'No coincide la verificacion de escritura.' }
    } catch {
        [IO.File]::WriteAllBytes($rules, $original)
        throw
    }
    Write-Host "Parche aplicado. Copia de seguridad: $backup" -ForegroundColor Green
}
if ($Action -ne 'Build') { return }

if (-not $PythonPath) {
    if (-not (Get-Command py -ErrorAction SilentlyContinue)) { throw 'Indica -PythonPath con tu Python 3.11.' }
    $PythonPath = (& py -3.11 -c 'import sys; print(sys.executable)')
    if ($LASTEXITCODE -ne 0 -or -not $PythonPath) { throw 'No se encuentra Python 3.11.' }
}
$pythonVersion = & $PythonPath -c 'import sys; print(sys.version_info.major, sys.version_info.minor)'
if ($LASTEXITCODE -ne 0 -or $pythonVersion -ne '3 11') { throw 'Esta version de DukeDownloader requiere Python 3.11 para compilar.' }
if (-not (Test-Path -LiteralPath (Join-Path $project 'gradlew.bat'))) { throw 'Falta gradlew.bat.' }
[IO.File]::WriteAllText($LogPath, '', $utf8)
Push-Location $project
$savedPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    # IMPORTANT: separate Gradle invocations. The second recreates Chaquopy resources AFTER clean.
    & .\gradlew.bat clean --console=plain --no-daemon 2>&1 | Tee-Object -FilePath $LogPath -Append
    if ($LASTEXITCODE -ne 0) { throw "Fallo la limpieza. Registro: $LogPath" }
    & .\gradlew.bat :app:assembleRelease "-PbuildPython=$PythonPath" --console=plain --no-daemon 2>&1 | Tee-Object -FilePath $LogPath -Append
    if ($LASTEXITCODE -ne 0) { throw "Fallo la compilacion. Registro: $LogPath" }
} finally {
    $ErrorActionPreference = $savedPreference
    Pop-Location
}
$mapping = Join-Path $project 'app\build\outputs\mapping\release\mapping.txt'
if (-not (Test-Path -LiteralPath $mapping)) { throw 'Falta mapping.txt de Release; no puedo verificar la proteccion de Chaquopy.' }
$rows = @(Select-String -LiteralPath $mapping -Pattern '^com\.chaquo\.python\.[^ ]+ -> [^ ]+:$')
if ($rows.Count -eq 0) { throw 'No se encuentran clases de Chaquopy en mapping.txt.' }
foreach ($row in $rows) {
    $parts = $row.Line -split ' -> '
    if ($parts[0] -ne $parts[1].TrimEnd(':')) { throw "Chaquopy sigue ofuscado: $($row.Line)" }
}
foreach ($required in @('Python', 'PyObject', 'PyException')) {
    if (-not ($rows.Line -contains "com.chaquo.python.$required -> com.chaquo.python.${required}:")) {
        throw "No se conserva la clase requerida: $required"
    }
}
$apk = Join-Path $project 'app\build\outputs\apk\release\app-release-unsigned.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'No encuentro la Release sin firmar. Revisa tu configuracion local de firma.' }
$versionMatch = [regex]::Match($config, 'versionName\s*=\s*"([^"]+)"')
if (-not $versionMatch.Success) { throw 'No se puede leer versionName.' }
$output = Join-Path $container ("DukeDownloader-" + $versionMatch.Groups[1].Value + '-unsigned.apk')
Copy-Item -LiteralPath $apk -Destination $output -Force
Write-Host "Release compilada; clases Chaquopy protegidas. APK sin firmar: $output" -ForegroundColor Green
Write-Host 'Firma con tu clave original, copia la APK al movil e instalala sobre la actual. Prueba arranque y categorias.'
