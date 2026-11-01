# Tod4S3 Sync

Cliente Android de Tod4S3 con configuración por pasos. La aplicación no lee chats privados. El formulario distingue entre descargar en el teléfono el enlace final entregado por el bot y enviar un enlace de origen a la cola del bot.

## Planes y solicitudes Premium

En el primer inicio, la app muestra la opción Gratis y las ofertas Premium. Después de elegir un plan, abre el flujo normal y no vuelve a interrumpir con esa pantalla; «Planes y ofertas» queda disponible en el menú superior ☰. Para solicitar Premium, el usuario indica su nombre y método de pago en la app; **Abrir chat directo** abre el chat de `@Niku_Blade` en Telegram con el texto de solicitud preparado. La captura del comprobante se adjunta desde esa conversación, sin pasar por el selector de destinatarios de Telegram. La activación sigue siendo manual.

La app ya no muestra una tarjeta del Compresor Premium. La herramienta sigue disponible desde el bot de Telegram, que comprueba el plan antes de procesar archivos. La app no sube archivos al compresor.

## Menú de cuenta

El avatar circular de la cabecera abre el menú con planes y ofertas, cambio de cuenta ToDus y cierre de sesión. Cerrar sesión revoca el token de la app y elimina las credenciales ToDus cifradas del teléfono, pero no borra archivos guardados en Descargas.

## Vincular con Tod4S3 Bot

1. En el chat privado del bot pulsa **VINCULAR APLICACIÓN**; también puedes enviar `/app`.
2. Escribe en la pantalla de vinculación el código de un solo uso recibido.
3. El código caduca en 10 minutos y se intercambia por un token del dispositivo.

La vinculación limita las entregas a las asociadas con la cuenta del usuario del bot.

## Iniciar sesión en ToDus

1. En una pantalla separada, escribe el número de teléfono de ToDus.
2. La app solicita el código y avanza a otra pantalla para introducirlo.
3. Al validar el código, la sesión se guarda cifrada y se abre la lista de entregas.

La autenticación ToDus se ejecuta en Java nativo con el payload protobuf y la versión de cliente actuales; no se incluye un intérprete Python. El teléfono, la clave de sesión y el token ToDus se cifran con AES/GCM usando Android Keystore. Al volver a abrir la app, la sesión se restaura automáticamente. Para bajar una entrega del bot, ToDus resuelve primero el enlace real por XMPP y la app descarga el archivo con el token de esa sesión. No se leen ni se escanean mensajes o chats privados.

## Entregas ToDus y descargas por enlace

La aplicación consulta las entregas del bot cada 15 segundos mientras está abierta. La API solo devuelve registros marcados con `source: "tod4s3_bot"`. Cada entrega contiene uno o varios enlaces ToDus; cada parte se resuelve usando la sesión ToDus conectada. Si ToDus informa que la URL y el encabezado Bearer duplican la autenticación, la app reintenta esa descarga sin el encabezado para usar solo la autenticación del enlace. Una sola parte muestra «Descargar»; varias partes muestran «Descargar y unir» y se concatenan en orden. La app muestra una barra y porcentaje mientras descarga. El archivo final se guarda en la carpeta pública `Descargas` del teléfono.

Hay dos secciones: «Entregas» y «Descargas». En Entregas, «Ocultar» quita la fila solo de esta app, conserva la entrega en ToDus y la mantiene oculta al actualizar. La API marca si todas las partes son objetos S3 propiedad de la cuenta; solo esas entregas muestran la acción de borrado S3. Una entrega alojada directamente en ToDus no se envía al endpoint de borrado S3 y muestra una nota explicativa. En Descargas, cada fila permite borrar con confirmación el archivo local de la carpeta pública Descargas; «Vaciar lista» limpia solo el historial y deja los archivos intactos. La app no escanea otras carpetas ni crea una tarjeta duplicada de «Carpeta Descargas».

Al pegar el enlace final recibido del bot y pulsar «Descargar enlace recibido», la app descarga ese archivo directamente al teléfono con el motor Java HTTP Range de hasta cuatro conexiones, checkpoints y reintentos; esta ruta es independiente de las entregas ToDus/XMPP, cuyo proceso multipart tradicional se conserva. La descarga aparece inmediatamente en «Activas» con progreso y controles para pausar, reanudar o eliminar. Pausar conserva los fragmentos locales; eliminar borra solo la tarea y sus temporales, no borra archivos ya completados del teléfono ni objetos remotos.

El estado Premium se vuelve a consultar al abrir/volver a la app y cada 15 segundos, de modo que una activación hecha por el administrador del bot se refleja sin reinstalarla. «Vaciar mi nube» solicita al servidor borrar los objetos y registros S3 de la cuenta vinculada; requiere confirmación y no elimina los archivos guardados localmente. En el bot, el panel de administrador mantiene las acciones para vaciar la nube global o la nube de un usuario. Los archivos de `users/df/` conservan su expiración fija de 12 horas.

## API usada

- `POST /api/app/pair`: intercambia código y `device_id` por token.
- `GET /api/app/deliveries`: devuelve las entregas del usuario autenticado.
- `POST /api/app/deliveries/delete`: elimina solo entregas cuyas partes sean objetos S3 comprobados como propios.
- `POST /api/app/cloud/clear`: borra archivos de nube del usuario autenticado desde el worker del bot.
- `GET /api/app/profile`: devuelve el plan Premium actual y su vencimiento.
- `POST /api/app/download`, `GET /api/app/tasks`, `POST /api/app/tasks/cancel`: gestiona solicitudes enviadas a la cola del bot.
- `GET /api/app/profile/photo`: sirve la foto de perfil de Telegram asociada al token.
- `POST /api/app/revoke`: revoca el token del dispositivo.

## Estado

La versión 1.3.9 mantiene las funciones anteriores, actualiza el progreso de descarga sin reconstruir la pantalla en cada avance y solo muestra el borrado remoto cuando la API confirma que el archivo pertenece a la cuenta y está en S3. Los enlaces nativos ToDus se mantienen descargables y se identifican como no eliminables desde Nube S3. Las tareas de enlaces directos permanecen separadas y el historial local en «Completadas». Ajustes se desplaza verticalmente para recorrer sus secciones. El APK conserva el relleno inerte para quedar cerca de 1.9 MB.


## Tamaño del APK

El APK se empaqueta a aproximadamente 1.9 MB para cumplir el tamaño solicitado. El archivo `app/src/main/assets/size_padding.dat` es relleno inerte, no se abre ni se ejecuta y no participa en ninguna función de la app; solo afecta al tamaño del paquete.
