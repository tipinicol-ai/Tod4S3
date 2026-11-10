# NikuDarIA para Android

Aplicación Android nativa en Java para conversar con el agente de Tod4S3 y, solo tras confirmación explícita, solicitar la apertura de una app o ejecutar un comando no destructivo mediante la integración oficial RUN_COMMAND de Termux. Este proyecto es independiente de `sync-app`.

## Requisitos y build

- JDK 17 o posterior, compatible con Gradle 8.7.
- Android SDK con plataforma y Build Tools 35.
- Gradle 8.7 instalado y `ANDROID_HOME` configurado.

Compilar debug APK:

```sh
cd nikudaria-app
gradle --no-daemon assembleDebug
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk` y se firma con la clave debug del entorno de compilación. Para actualizarla sin desinstalar, las compilaciones futuras deben conservar esa misma clave; no se incluye el keystore en el repositorio.

## Instalar

Copia el APK al teléfono e instálalo desde el gestor de archivos. Android puede solicitar habilitar temporalmente «Instalar apps desconocidas» para la aplicación que abra el APK. NikuDarIA requiere Android 6.0 (API 23) o posterior y conexión HTTPS para el API.

## Uso del agente

La app abre directamente el chat: no requiere Telegram, login, vinculación ni acceso Premium. Cada instalación genera un UUID aleatorio `client_id`, que conserva en SharedPreferences privadas. El cliente envía `POST /api/app/nikudaria/agent` con JSON `{"client_id":"<UUID local>","messages":[{"role":"user","content":"..."}]}` y no añade cabecera Authorization. El `client_id` identifica esta instalación, no es una credencial ni un secreto. La IA permanece en el backend; la app no incorpora claves.

## Termux (opcional)

Termux debe estar instalado desde una fuente compatible y abierto al menos una vez. Para permitir que NikuDarIA use el servicio oficial `com.termux.app.RunCommandService`:

1. Concede a NikuDarIA el permiso `com.termux.permission.RUN_COMMAND` desde los permisos de la app/Termux cuando Android/Termux lo ofrezca.
2. En Termux abre `~/.termux/termux.properties` y añade `allow-external-apps=true`.
3. Guarda el archivo y reinicia Termux.
4. Cuando el agente proponga un comando, revisa el comando exacto y su razón. Pulsa **Ejecutar** solo si deseas proceder; **Cancelar** no envía nada.

Si Termux, su servicio o el permiso no están disponibles, la app informa el problema y no declara éxito. Termux debe permanecer accesible; el cliente solicita la ejecución foreground. Al volver, muestra el transcript y añade el resultado al contexto de la conversación.

## Seguridad

- No hay ejecución automática: cada acción del agente presenta objetivo/comando literal y razón con botones **Cancelar**/**Ejecutar**.
- Los launchers se resuelven con `PackageManager` y coincidencia exacta del nombre visible o package id. El manifiesto consulta solo `com.termux` y apps con launcher; no solicita `QUERY_ALL_PACKAGES`.
- El cliente y el servidor bloquean algunos patrones evidentes como `su`, `sudo`, `rm -rf`, `mkfs*`, `dd`, `wipefs` y `reboot`; esto no sustituye revisar el comando completo antes de aprobarlo.
- No incluye AccessibilityService, root, overlay, ejecución en background, actualizador ni código de publicación.

## Estructura

- `MainActivity.java`: interfaz, chat y confirmaciones.
- `ApiClient.java`: UUID local y llamadas HTTPS al agente.
- `TermuxRunner.java`: validación, permiso e invocación RUN_COMMAND.
- `TermuxResultReceiver.java`: callback del transcript/exit code.
