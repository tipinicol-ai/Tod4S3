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

Hay dos secciones: «Entregas» y «Descargas». En Entregas, «Ocultar» quita la fila solo de esta app, conserva la entrega en ToDus y la mantiene oculta al actualizar. Las entregas S3 muestran «Eliminar nube» solo cuando el servidor confirma que todas las partes pertenecen a la cuenta. Las entregas nativas ToDus muestran «Eliminar entrega» cuando el servidor confirma que sus mensajes están registrados de forma segura. En Descargas, cada fila permite borrar con confirmación el archivo local de la carpeta pública Descargas; «Vaciar lista» limpia solo el historial y deja los archivos intactos. La app no escanea otras carpetas ni crea una tarjeta duplicada de «Carpeta Descargas».

Al pegar el enlace final recibido del bot y pulsar «Descargar enlace recibido», la app descarga ese archivo directamente al teléfono con el motor Java HTTP Range de hasta cuatro conexiones, checkpoints y reintentos; esta ruta es independiente de las entregas ToDus/XMPP, cuyo proceso multipart tradicional se conserva. La descarga aparece inmediatamente en «Activas» con progreso y controles para pausar, reanudar o eliminar. Pausar conserva los fragmentos locales; eliminar borra solo la tarea y sus temporales, no borra archivos ya completados del teléfono ni objetos remotos.

El estado Premium y la cuota se vuelven a consultar al abrir/volver a la app y cada 15 segundos. En el plan Gratis, la tarjeta «Gratis» muestra una barra con el porcentaje del 1 GB gastado, los datos restantes y la cuenta atrás del reinicio móvil de 24 horas; el valor de la barra y su etiqueta se limitan a 100 %. En cada tarjeta de oferta Premium aparecen juntos «Comprar premium» y «Ya soy premium», con una entrada animada discreta. Tras la activación del administrador, este recibe un código secreto y se lo entrega al comprador; el usuario lo pega en «Ya soy premium». El servidor verifica el plan activo, el ID de Telegram asociado a la sesión y el móvil emparejado. El primer canje liga el código a ese dispositivo; no puede reutilizarse en otro móvil. El código vence junto con el plan o se invalida si el plan se cambia; el panel permite consultar por ID si Premium está activo y si el código ya fue validado, y reemitirlo cuando corresponda. Los archivos de `users/df/` conservan su expiración fija de 12 horas.

## API usada

- `POST /api/app/pair`: intercambia código y `device_id` por token.
- `GET /api/app/deliveries`: devuelve las entregas del usuario autenticado.
- `POST /api/app/deliveries/delete`: elimina objetos S3 comprobados como propios o, si existe el vínculo de mensajes, una entrega ToDus nativa del usuario autenticado.
- `GET /api/app/profile`: devuelve el plan Premium actual y su vencimiento.
- `POST /api/app/premium/claim`: valida el token admin de Premium y lo liga al ID y móvil autenticados.
- `POST /api/app/download`, `GET /api/app/tasks`, `POST /api/app/tasks/cancel`: gestiona solicitudes enviadas a la cola del bot.
- `GET /api/app/profile/photo`: sirve la foto de perfil de Telegram asociada al token.
- `POST /api/app/revoke`: revoca el token del dispositivo.

## Estado

La versión 1.5.34 conserva las funciones anteriores, mantiene el splash animado visible unos 3,4 segundos y coloca a @Niku_Blade y @fefefelix como vendedores enlazados dentro de cada oferta Premium. El diálogo de actualización no muestra el origen de descarga y usa un indicador animado; AiTod4S3 incluye preguntas frecuentes para ayudar con actualización, instalación, descargas, registro y errores comunes, tanto al iniciar el chat como en su menú. La IA ofrece pasos seguros sin recomendar borrar los datos ni compartir códigos o credenciales.


## Tamaño del APK

El APK se empaqueta a aproximadamente 1.9 MB para cumplir el tamaño solicitado. El archivo `app/src/main/assets/size_padding.dat` es relleno inerte, no se abre ni se ejecuta y no participa en ninguna función de la app; solo afecta al tamaño del paquete.
