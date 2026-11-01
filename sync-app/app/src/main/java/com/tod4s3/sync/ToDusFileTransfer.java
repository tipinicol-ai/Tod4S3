package com.tod4s3.sync;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Native ToDus download flow: XMPP URL resolution followed by authenticated, resumable HTTP GET. */
final class ToDusFileTransfer {
    private static final String XMPP_HOST = "im.todus.cu";
    private static final int XMPP_PORT = 1756;
    private static final String VERSION_NAME = "2.1.2";
    private static final int SOCKET_TIMEOUT_MS = 15_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Pattern CONTENT_RANGE = Pattern.compile("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$");

    interface ProgressCallback {
        void onProgress(long bytesDownloaded, long totalBytes);
    }

    private ToDusFileTransfer() { }

    static void download(String token, String sharedUrl, File destination) throws Exception {
        download(token, sharedUrl, destination, 0L, null, null);
    }

    static void download(String token, String sharedUrl, File destination, ProgressCallback progressCallback) throws Exception {
        download(token, sharedUrl, destination, 0L, progressCallback, null);
    }

    static void download(String token, String sharedUrl, File destination, long previousBytes,
                         ProgressCallback progressCallback) throws Exception {
        download(token, sharedUrl, destination, previousBytes, progressCallback, null);
    }

    static void download(String token, String sharedUrl, File destination, long previousBytes,
                         ProgressCallback progressCallback,
                         ParallelRangeDownloader.DownloadControl control) throws Exception {
        String accessToken = token == null ? "" : token.trim();
        if (accessToken.isEmpty()) throw new IOException("Conecta tu cuenta ToDus antes de descargar.");
        if (control != null) control.check();
        String realUrl = resolveRealUrl(accessToken, sharedUrl);
        downloadHttp(accessToken, realUrl, destination, previousBytes, progressCallback, control);
    }

    private static String resolveRealUrl(String token, String sharedUrl) throws Exception {
        if (sharedUrl == null || !sharedUrl.startsWith("https://")) {
            throw new IOException("El enlace de ToDus no es válido.");
        }
        String id = randomToken(5);
        String phone = phoneFromToken(token);
        String authPayload = "\u0000" + phone + "\u0000" + token;
        String auth = Base64.encodeToString(authPayload.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

        try (SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(XMPP_HOST, XMPP_PORT)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            OutputStream output = socket.getOutputStream();
            send(output, "<stream:stream xmlns='jc' o='im.todus.cu' xmlns:stream='x1' v='1.0'>");
            String features = readUntilAny(socket, SOCKET_TIMEOUT_MS, "</stream:features>");
            if (features.contains("<not-authorized/>")) throw authError();

            send(output, "<ah xmlns='ah:ns' e='PLAIN'>" + auth + "</ah>");
            String authResult = readUntilAny(socket, "<ok xmlns='x2'/>", "<ok xmlns=\"x2\"/>", "<not-authorized/>");
            if (authResult.contains("<not-authorized/>")) throw authError();

            send(output, "<stream:stream xmlns='jc' o='im.todus.cu' xmlns:stream='x1' v='1.0'>");
            String reboundFeatures = readUntilAny(socket, SOCKET_TIMEOUT_MS, "</stream:features>");
            if (reboundFeatures.contains("<not-authorized/>")) throw authError();

            String bindId = id + "-1";
            send(output, "<iq i='" + bindId + "' t='set'><b1 xmlns='x4'></b1></iq>");
            String bindResult = readUntilTransaction(socket, bindId, "t='result'", "t=\"result\"");
            if (bindResult.contains("<not-authorized/>")) throw authError();
            if (!bindResult.contains("t='result'") && !bindResult.contains("t=\"result\"")) {
                throw new IOException("ToDus no confirmó el enlace de sesión XMPP.");
            }

            send(output, "<p xmlns='jc'/>");
            String queryId = id + "-2";
            send(output, "<iq i='" + queryId + "' t='get'><query xmlns='todus:gurl' url='"
                    + escapeXmlAttribute(sharedUrl) + "'></query></iq>");
            String response = readUntilTransaction(socket, queryId, "du='", "du=\"");
            if (response.contains("<not-authorized/>")) throw authError();
            String realUrl = xmlAttribute(response, "du");
            if (realUrl.isEmpty()) throw new IOException("ToDus no devolvió el enlace real de descarga.");
            return realUrl;
        } catch (SocketTimeoutException e) {
            throw new IOException("ToDus tardó demasiado en autorizar la descarga. Inténtalo de nuevo.", e);
        }
    }

    private static void downloadHttp(String token, String realUrl, File destination, long previousBytes,
                                     ProgressCallback progressCallback,
                                     ParallelRangeDownloader.DownloadControl control) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("No se pudo preparar el almacenamiento temporal.");
        }
        long localBytes = destination.isFile() ? destination.length() : 0L;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (control != null) control.check();
            boolean sendBearer = attempt == 0;
            HttpURLConnection connection = (HttpURLConnection) new URL(realUrl).openConnection();
            if (control != null) control.register(connection);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(60_000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "ToDus " + VERSION_NAME + " HTTP-Download");
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (sendBearer) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (localBytes > 0L) connection.setRequestProperty("Range", "bytes=" + localBytes + "-");
            try {
                int status = connection.getResponseCode();
                if (status == 416 && localBytes > 0L) {
                    Matcher unsatisfied = Pattern.compile("(?i)^bytes\\s+\\*/(\\d+)$")
                            .matcher(nullToEmpty(connection.getHeaderField("Content-Range")).trim());
                    if (unsatisfied.matches() && Long.parseLong(unsatisfied.group(1)) == localBytes) return;
                }
                if (sendBearer && status == HttpURLConnection.HTTP_BAD_REQUEST
                        && isMultipleAuthenticationError(readErrorBody(connection))) continue;
                if (status < 200 || status >= 300) {
                    String detail = readErrorBody(connection);
                    if (status == HttpURLConnection.HTTP_FORBIDDEN) {
                        throw new IOException("ToDus rechazó el acceso al archivo (HTTP 403)" + detail);
                    }
                    throw new IOException("El servidor ToDus respondió HTTP " + status + " al descargar" + detail);
                }
                long totalPartBytes;
                boolean append = localBytes > 0L;
                if (status == HttpURLConnection.HTTP_PARTIAL) {
                    Matcher range = CONTENT_RANGE.matcher(nullToEmpty(connection.getHeaderField("Content-Range")).trim());
                    if (!range.matches()) throw new IOException("ToDus devolvió un Content-Range inválido.");
                    long start = Long.parseLong(range.group(1));
                    if (start != localBytes) throw new IOException("ToDus no reanudó desde el byte temporal esperado.");
                    totalPartBytes = "*".equals(range.group(3)) ? 0L : Long.parseLong(range.group(3));
                    append = true;
                } else {
                    // Algunos servidores ignoran Range; conservar las partes completas y reiniciar
                    // solamente este fragmento evita dejar la tarea atascada al pulsar Reanudar.
                    localBytes = 0L;
                    totalPartBytes = contentLength(connection);
                    append = false;
                }
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(destination, append)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    if (progressCallback != null) {
                        long reportedTotal = totalPartBytes > 0 ? previousBytes + totalPartBytes : 0L;
                        progressCallback.onProgress(previousBytes + localBytes, reportedTotal);
                    }
                    while ((count = input.read(buffer)) != -1) {
                        if (control != null) control.check();
                        output.write(buffer, 0, count);
                        localBytes += count;
                        long reportedTotal = totalPartBytes > 0 ? previousBytes + totalPartBytes : 0L;
                        if (progressCallback != null) progressCallback.onProgress(previousBytes + localBytes, reportedTotal);
                    }
                    if (control != null) control.check();
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("No se pudo completar la descarga de ToDus.");
    }

    private static long contentLength(HttpURLConnection connection) {
        try { return Long.parseLong(connection.getHeaderField("Content-Length")); }
        catch (Exception ignored) { return -1L; }
    }

    private static boolean isMultipleAuthenticationError(String detail) {
        String lower = detail == null ? "" : detail.toLowerCase(java.util.Locale.US);
        return lower.contains("multiple authentication") || lower.contains("multiple auth types");
    }

    private static String readErrorBody(HttpURLConnection connection) {
        InputStream error = connection.getErrorStream();
        if (error == null) return "";
        try (InputStream input = error; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            while (output.size() < 1024) {
                int count = input.read(buffer, 0, Math.min(buffer.length, 1024 - output.size()));
                if (count < 0) break;
                output.write(buffer, 0, count);
            }
            String detail = new String(output.toByteArray(), StandardCharsets.UTF_8)
                    .replaceAll("<[^>]{0,500}>", " ")
                    .replaceAll("(?i)https?://[^\\s\\\"'<>]+", "[enlace oculto]")
                    .replaceAll("[\\p{Cntrl}&&[^\\t\\n]]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (detail.isEmpty()) return "";
            if (detail.length() > 180) detail = detail.substring(0, 180);
            return ": " + detail;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readUntilAny(SSLSocket socket, String... markers) throws IOException {
        return readUntilAny(socket, SOCKET_TIMEOUT_MS, markers);
    }

    private static String readUntilAny(SSLSocket socket, int timeoutMs, String... markers) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        InputStream input = socket.getInputStream();
        while (System.currentTimeMillis() < deadline) {
            int remaining = (int) Math.max(1, deadline - System.currentTimeMillis());
            socket.setSoTimeout(remaining);
            int count = input.read(buffer);
            if (count < 0) throw new IOException("ToDus cerró la conexión durante la descarga.");
            received.write(buffer, 0, count);
            String text = new String(received.toByteArray(), StandardCharsets.UTF_8);
            for (String marker : markers) if (text.contains(marker)) return text;
        }
        throw new SocketTimeoutException("ToDus no respondió a tiempo.");
    }

    private static String readUntilTransaction(SSLSocket socket, String transactionId, String... completionMarkers) throws IOException {
        long deadline = System.currentTimeMillis() + SOCKET_TIMEOUT_MS;
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        InputStream input = socket.getInputStream();
        while (System.currentTimeMillis() < deadline) {
            socket.setSoTimeout((int) Math.max(1, deadline - System.currentTimeMillis()));
            int count = input.read(buffer);
            if (count < 0) throw new IOException("ToDus cerró la conexión antes de responder.");
            received.write(buffer, 0, count);
            String text = new String(received.toByteArray(), StandardCharsets.UTF_8);
            if (text.contains("<not-authorized/>")) return text;
            if (text.contains(transactionId)) {
                for (String marker : completionMarkers) if (text.contains(marker)) return text;
            }
        }
        throw new SocketTimeoutException("ToDus no devolvió la respuesta de descarga.");
    }

    private static void send(OutputStream output, String stanza) throws IOException {
        output.write(stanza.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static String phoneFromToken(String token) throws IOException {
        String[] components = token.split("\\.");
        if (components.length > 1) {
            try {
                byte[] payload = Base64.decode(components[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                String username = new JSONObject(new String(payload, StandardCharsets.UTF_8)).optString("username", "");
                if (!username.isEmpty()) return username;
            } catch (Exception ignored) { }
        }
        Matcher matcher = Pattern.compile("(53[0-9]{8})").matcher(token);
        if (matcher.find()) return matcher.group(1);
        throw new IOException("No se pudo identificar la cuenta ToDus en la sesión.");
    }

    private static String xmlAttribute(String xml, String name) {
        Matcher matcher = Pattern.compile("(?:^|\\s)" + Pattern.quote(name) + "=['\"]([^'\"]+)['\"]").matcher(xml);
        if (!matcher.find()) return "";
        return matcher.group(1).replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'");
    }

    private static String escapeXmlAttribute(String value) {
        return value.replace("&", "&amp;").replace("'", "&apos;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private static IOException authError() {
        return new IOException("ToDus no autorizó la sesión. Vuelve a iniciar sesión y prueba de nuevo.");
    }

    private static String randomToken(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) value.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        return value.toString();
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}
