# Tod4S3 Sync

Cliente Android de Tod4S3 con configuración por pasos. La aplicación no lee chats privados ni permite enviar archivos o enlaces al bot desde la app.

## Planes y solicitudes Premium

Al abrir por primera vez, la app muestra la opción Gratis y las ofertas Premium publicadas en la web. Para solicitar Premium, el usuario indica su nombre y método de pago en la app; **Abrir chat directo** abre el chat de `@Niku_Blade` en Telegram con el texto de solicitud preparado. La captura del comprobante se adjunta desde esa conversación, sin pasar por el selector de destinatarios de Telegram. La activación sigue siendo manual.

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

## Entregas y Descargas

La aplicación consulta las entregas del bot cada 15 segundos mientras está abierta. La API solo devuelve registros marcados con `source: "tod4s3_bot"`. Cada entrega contiene uno o varios enlaces ToDus; cada parte se resuelve usando la sesión ToDus conectada. Si ToDus informa que la URL y el encabezado Bearer duplican la autenticación, la app reintenta esa descarga sin el encabezado para usar solo la autenticación del enlace. Una sola parte muestra «Descargar»; varias partes muestran «Descargar y unir» y se concatenan en orden. La app muestra una barra y porcentaje mientras descarga. El archivo final se guarda en la carpeta pública `Descargas` del teléfono.

Hay dos secciones: «Entregas» y «Descargas». En Entregas, «Ocultar» quita la fila solo de esta app, conserva la entrega en ToDus y la mantiene oculta al actualizar. En Descargas, cada fila permite borrar con confirmación el archivo local de la carpeta pública Descargas; «Vaciar lista» limpia solo el historial y deja los archivos intactos. La app no escanea otras carpetas ni crea una tarjeta duplicada de «Carpeta Descargas».

## API usada

- `POST /api/app/pair`: intercambia código y `device_id` por token.
- `GET /api/app/deliveries`: devuelve las entregas del usuario autenticado.
- `POST /api/app/revoke`: revoca el token del dispositivo.

## Estado

La interfaz incluye planes Premium dorados, iconos de Transfermóvil y saldo móvil tomados de la web del proyecto, un icono de lanzador basado en la imagen de bienvenida del bot con márgenes ampliados, transiciones entre pantallas, menú circular de cuenta, progreso de descarga y listas compactas de entregas e historial local. Tras iniciar sesión desaparece el indicador 1-2-3.


## Tamaño del APK

El APK se empaqueta a aproximadamente 1.9 MB para cumplir el tamaño solicitado. El archivo `app/src/main/assets/size_padding.dat` es relleno inerte, no se abre ni se ejecuta y no participa en ninguna función de la app; solo afecta al tamaño del paquete.
