# DukeDownloader 3.1.2 — avance de correcciones

**El cierre al entrar en plataformas sigue pendiente del logcat del dispositivo. Este paquete no se presenta como solución completa de las tres incidencias.** Las correcciones de portadas y carpeta predeterminada están implementadas; los resultados de compilación y pruebas se encuentran en `validacion`.

La Release usa `com.dukedownloader.ps2emu`, versión `3.1.2`, código `12`. No se ha utilizado ni incluido una clave privada. La carpeta `fuente` contiene el proyecto completo; el parche adicional parte de la entrega 3.1.1, antes de los cambios manuales del Windows. Evita aplicarlo a ciegas sobre archivos modificados: el proyecto completo puede compilarse en otra carpeta sin tocar `C:\Tottodrillo-PS2EMU`.

## Firma local de la Release

El APK adjunto es **sin firmar**. Para poder actualizar la instalación actual, fírmalo en tu PowerShell con la clave original:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Firmar-Release.ps1 -KeystorePath 'C:\RUTA_REAL\DukeDownloader-release.jks'
```

Sustituye la ruta por la ubicación real de tu archivo; no lo copies dentro del paquete ni lo compartas. La herramienta oficial solicitará su contraseña localmente. Si el almacén tiene varias claves, añade `-KeyAlias 'TU_ALIAS'`. Puedes indicar otra instalación del SDK mediante `-SdkPath 'C:\Android\Sdk'`.

El script verifica el paquete, la versión, la alineación, las firmas v2/v3 y que el certificado sea exactamente:

`32eb072fcd3419ef7bdf979a73086ad990978321775809c963bff29e334c4586`

Solo entonces produce `DukeDownloader-3.1.2-signed.apk`. No desinstales la aplicación actual para probar una actualización. Si no coincide el certificado, el script se detiene y no genera una actualización instalable con otra clave.

## Compilación local opcional

Con JDK 17, Python 3.11, Android SDK Platform 34 y Build-Tools 34.0.0:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Compilar-Release.ps1
```

Admite `-PythonPath 'C:\Python311\python.exe'` y `-SdkPath 'C:\Android\Sdk'`. Ejecuta una compilación limpia, las pruebas Release y Lint, con R8. Genera otra Release sin firmar, que después se firma con el script anterior. Registro: `C:\DukeDownloader-build.txt`.

## Comportamiento corregido y límites

- Las portadas se resuelven por consola, título y región sin depender de LoLROMs/EmuParadise ni de claves IGDB. Las imágenes anteriores siguen en la galería de la ficha. Una portada importada explícitamente con IGDB conserva su prioridad.
- Se conservan las variantes de edición, prototipo, demo y secuela al normalizar títulos. No se utiliza una coincidencia parcial o una portada PS2 para Arcade. Se permite una coincidencia regional o una portada marcada World; ante otra región o una coincidencia dudosa se muestra el marcador.
- La carpeta predeterminada nueva es `Download/DukeDownloader`. Una ruta ya guardada, incluso `Download/Tottodrillo`, se conserva porque puede ser una elección deliberada. No se mueven ni borran archivos. Para cambiarla en la instalación existente usa Configuración → Ruta de Descarga.
- Búsqueda, fuentes, favoritos y descargador segmentado se conservan. No se ha incorporado Internet Archive ni autenticación nueva.

Para terminar el diagnóstico del cierre:

```powershell
adb logcat -b crash -c
Read-Host 'Reproduce el cierre en DukeDownloader y pulsa Enter'
adb logcat -b crash -d -v threadtime > C:\DukeDownloader-crash.txt
```

Si la traza está ofuscada, hace falta también el `mapping.txt` de la Release **3.1.1 instalada**, normalmente en `C:\Tottodrillo-PS2EMU\app\build\outputs\mapping\release\mapping.txt`. El adjunto en este paquete corresponde únicamente al nuevo APK 3.1.2. Comparte solo el fragmento de la excepción, revisando que no incluya datos privados.

La navegación y la interfaz todavía requieren comprobación en el Android físico. Los scripts PowerShell requieren ejecución en tu Windows para su validación local; no se han ejecutado allí durante esta preparación.
