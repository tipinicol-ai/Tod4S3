package com.tod4s3.sync;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parallel HTTP range downloader used after ToDus has resolved a shared URL. */
final class ParallelRangeDownloader {
    private static final int MAX_CONNECTIONS = 4;
    private static final int MAX_RETRIES = 3;
    private static final long MIN_PARALLEL_BYTES = 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final Pattern CONTENT_RANGE = Pattern.compile("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$");

    interface ProgressCallback {
        void onProgress(long bytesDownloaded, long totalBytes);
    }

    private ParallelRangeDownloader() { }

    static void download(String token, String realUrl, String resourceKey, File destination,
                         long previousBytes, ProgressCallback progressCallback) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("No se pudo preparar el almacenamiento temporal.");
        }

        Probe probe = probe(token, realUrl);
        if (probe.status == HttpURLConnection.HTTP_OK) {
            try {
                writeSequential(probe.connection, destination, previousBytes, progressCallback);
            } finally {
                probe.connection.disconnect();
            }
            return;
        }
        probe.connection.disconnect();

        if (probe.totalBytes < 0) {
            downloadSequential(token, realUrl, probe.sendBearer, destination, previousBytes, progressCallback);
            return;
        }
        if (probe.totalBytes == 0) {
            try (FileOutputStream ignored = new FileOutputStream(destination, false)) { }
            if (progressCallback != null) progressCallback.onProgress(previousBytes, previousBytes);
            return;
        }
        if (probe.totalBytes < MIN_PARALLEL_BYTES) {
            downloadSequential(token, realUrl, probe.sendBearer, destination, previousBytes, progressCallback);
            return;
        }

        downloadRanges(token, realUrl, resourceKey, destination, previousBytes,
                probe.totalBytes, probe.sendBearer, probe.etag, probe.lastModified, progressCallback);
    }

    private static Probe probe(String token, String realUrl) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean sendBearer = attempt == 0;
            HttpURLConnection connection = open(realUrl, token, sendBearer, 0L, 0L);
            int status;
            try {
                status = connection.getResponseCode();
                if (sendBearer && status == HttpURLConnection.HTTP_BAD_REQUEST
                        && isMultipleAuthenticationError(readErrorBody(connection))) {
                    connection.disconnect();
                    continue;
                }
                if (status == HttpURLConnection.HTTP_OK) {
                    return new Probe(connection, status, contentLength(connection), sendBearer,
                            connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"));
                }
                if (status == HttpURLConnection.HTTP_PARTIAL) {
                    String range = connection.getHeaderField("Content-Range");
                    long total = totalFromContentRange(range);
                    if (!rangeStartsAtZero(range)) {
                        connection.disconnect();
                        throw new IOException("El servidor ToDus respondió con un rango inválido.");
                    }
                    return new Probe(connection, status, total, sendBearer,
                            connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"));
                }
                String detail = readErrorBody(connection);
                if (status == HttpURLConnection.HTTP_FORBIDDEN) {
                    throw new IOException("ToDus rechazó el acceso al archivo (HTTP 403)" + detail);
                }
                throw new IOException("El servidor ToDus respondió HTTP " + status + " al iniciar la descarga" + detail);
            } catch (IOException e) {
                connection.disconnect();
                throw e;
            }
        }
        throw new IOException("No se pudo iniciar la descarga de ToDus.");
    }

    private static void downloadSequential(String token, String realUrl, boolean preferredBearer,
                                           File destination, long previousBytes,
                                           ProgressCallback progressCallback) throws Exception {
        IOException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean sendBearer = attempt == 0 ? preferredBearer : !preferredBearer;
            HttpURLConnection connection = open(realUrl, token, sendBearer, -1L, -1L);
            try {
                int status = connection.getResponseCode();
                if (sendBearer && status == HttpURLConnection.HTTP_BAD_REQUEST
                        && isMultipleAuthenticationError(readErrorBody(connection))) {
                    lastError = new IOException("ToDus requiere una sola forma de autenticación.");
                    continue;
                }
                if (status < 200 || status >= 300) {
                    String detail = readErrorBody(connection);
                    if (status == HttpURLConnection.HTTP_FORBIDDEN) {
                        throw new IOException("ToDus rechazó el acceso al archivo (HTTP 403)" + detail);
                    }
                    throw new IOException("El servidor ToDus respondió HTTP " + status + " al descargar" + detail);
                }
                writeSequential(connection, destination, previousBytes, progressCallback);
                return;
            } catch (IOException e) {
                lastError = e;
                if (attempt > 0 || !isMultipleAuthenticationError(e.getMessage())) throw e;
            } finally {
                connection.disconnect();
            }
        }
        throw lastError == null ? new IOException("No se pudo completar la descarga de ToDus.") : lastError;
    }

    private static void writeSequential(HttpURLConnection connection, File destination,
                                        long previousBytes, ProgressCallback progressCallback) throws IOException {
        InputStream response = connection.getInputStream();
        boolean gzip = "gzip".equalsIgnoreCase(connection.getContentEncoding());
        long responseLength = contentLength(connection);
        long totalBytes = !gzip && responseLength >= 0 ? previousBytes + responseLength : 0L;
        if (gzip) response = new java.util.zip.GZIPInputStream(response);
        try (InputStream input = response; FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            long transferred = 0L;
            if (progressCallback != null) progressCallback.onProgress(previousBytes, totalBytes);
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                transferred += count;
                if (progressCallback != null) progressCallback.onProgress(previousBytes + transferred, totalBytes);
            }
        }
    }

    private static void downloadRanges(String token, String realUrl, String resourceKey, File destination,
                                       long previousBytes, long totalBytes, boolean sendBearer,
                                       String etag, String lastModified,
                                       ProgressCallback progressCallback) throws Exception {
        int connectionCount = (int) Math.min(MAX_CONNECTIONS,
                Math.max(2L, (totalBytes + MIN_PARALLEL_BYTES - 1L) / MIN_PARALLEL_BYTES));
        long chunkSize = (totalBytes + connectionCount - 1L) / connectionCount;
        File parent = destination.getParentFile();
        String signature = resourceKey + "\n" + totalBytes + "\n" + nullToEmpty(etag)
                + "\n" + nullToEmpty(lastModified);
        File metadata = new File(destination.getAbsolutePath() + ".range.meta");
        if (!signature.equals(readMetadata(metadata))) {
            deleteRangeFiles(destination, MAX_CONNECTIONS);
            if (destination.exists() && !destination.delete()) {
                throw new IOException("No se pudo reiniciar el archivo temporal de descarga.");
            }
            writeMetadata(metadata, signature);
        }
        if (destination.isFile() && destination.length() == totalBytes) {
            if (progressCallback != null) progressCallback.onProgress(previousBytes + totalBytes, previousBytes + totalBytes);
            return;
        }

        List<File> chunks = new ArrayList<>();
        List<Long> starts = new ArrayList<>();
        List<Long> ends = new ArrayList<>();
        AtomicLong completed = new AtomicLong(0L);
        for (int index = 0; index < connectionCount; index++) {
            long start = index * chunkSize;
            long end = Math.min(totalBytes - 1L, start + chunkSize - 1L);
            if (start > end) break;
            File chunk = new File(parent, destination.getName() + ".range-" + index + ".part");
            long expected = end - start + 1L;
            if (chunk.exists() && chunk.length() > expected && !chunk.delete()) {
                throw new IOException("No se pudo reiniciar un fragmento de descarga.");
            }
            long present = chunk.isFile() ? chunk.length() : 0L;
            completed.addAndGet(present);
            chunks.add(chunk);
            starts.add(start);
            ends.add(end);
        }
        if (progressCallback != null) progressCallback.onProgress(previousBytes + completed.get(), previousBytes + totalBytes);

        ExecutorService pool = Executors.newFixedThreadPool(chunks.size());
        List<Future<?>> futures = new ArrayList<>();
        AtomicBoolean useBearer = new AtomicBoolean(sendBearer);
        try {
            for (int index = 0; index < chunks.size(); index++) {
                final File chunk = chunks.get(index);
                final long start = starts.get(index);
                final long end = ends.get(index);
                futures.add(pool.submit(() -> {
                    downloadOneRange(token, realUrl, chunk, start, end, totalBytes, useBearer,
                            completed, previousBytes, progressCallback, etag);
                    return null;
                }));
            }
            for (Future<?> future : futures) future.get();
        } catch (ExecutionException e) {
            for (Future<?> future : futures) future.cancel(true);
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("Falló una conexión de descarga por fragmentos.", cause);
        } catch (InterruptedException e) {
            for (Future<?> future : futures) future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("La descarga fue interrumpida.", e);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }

        try (FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[64 * 1024];
            long merged = 0L;
            for (File chunk : chunks) {
                try (InputStream input = new FileInputStream(chunk)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        merged += count;
                    }
                }
            }
            if (merged != totalBytes) {
                throw new IOException("La descarga fragmentada quedó incompleta.");
            }
        }
        for (File chunk : chunks) chunk.delete();
        if (progressCallback != null) progressCallback.onProgress(previousBytes + totalBytes, previousBytes + totalBytes);
    }

    private static void downloadOneRange(String token, String realUrl, File chunk, long rangeStart,
                                         long rangeEnd, long totalBytes, AtomicBoolean useBearer,
                                         AtomicLong completed, long previousBytes,
                                         ProgressCallback progressCallback, String expectedEtag) throws IOException {
        long expectedLength = rangeEnd - rangeStart + 1L;
        long currentLength = chunk.isFile() ? chunk.length() : 0L;
        for (int attempt = 0; attempt < MAX_RETRIES && currentLength < expectedLength; attempt++) {
            long requestStart = rangeStart + currentLength;
            HttpURLConnection connection = open(realUrl, token, useBearer.get(), requestStart, rangeEnd);
            try {
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_BAD_REQUEST && useBearer.get()
                        && isMultipleAuthenticationError(readErrorBody(connection))) {
                    useBearer.set(false);
                    continue;
                }
                if (status != HttpURLConnection.HTTP_PARTIAL) {
                    String detail = readErrorBody(connection);
                    throw new IOException("ToDus no aceptó el fragmento HTTP Range (" + status + ")" + detail);
                }
                String contentRange = connection.getHeaderField("Content-Range");
                long returnedStart = rangeStartFromContentRange(contentRange);
                long returnedEnd = rangeEndFromContentRange(contentRange);
                long returnedTotal = totalFromContentRange(contentRange);
                if (returnedStart != requestStart || returnedEnd < returnedStart
                        || returnedEnd > rangeEnd || returnedTotal != totalBytes) {
                    throw new IOException("ToDus devolvió un fragmento distinto al solicitado.");
                }
                String actualEtag = connection.getHeaderField("ETag");
                if (expectedEtag != null && !expectedEtag.isEmpty()
                        && actualEtag != null && !actualEtag.isEmpty() && !expectedEtag.equals(actualEtag)) {
                    throw new IOException("El archivo cambió mientras se descargaban sus fragmentos.");
                }
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(chunk, true)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("La descarga fue interrumpida.");
                        if (currentLength + count > expectedLength) {
                            throw new IOException("ToDus envió más datos que el tamaño del fragmento.");
                        }
                        output.write(buffer, 0, count);
                        currentLength += count;
                        completed.addAndGet(count);
                        if (progressCallback != null) {
                            progressCallback.onProgress(previousBytes + completed.get(), previousBytes + totalBytes);
                        }
                    }
                }
                if (currentLength < expectedLength && attempt + 1 == MAX_RETRIES) {
                    throw new IOException("Una conexión terminó antes de completar su fragmento.");
                }
            } catch (IOException e) {
                if (attempt + 1 == MAX_RETRIES) throw e;
                try { Thread.sleep(250L * (attempt + 1)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("La descarga fue interrumpida.", interrupted);
                }
            } finally {
                connection.disconnect();
            }
            currentLength = chunk.isFile() ? chunk.length() : 0L;
        }
        if (currentLength != expectedLength) throw new IOException("No se pudo completar un fragmento de descarga.");
    }

    private static HttpURLConnection open(String realUrl, String token, boolean sendBearer,
                                          long rangeStart, long rangeEnd) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(realUrl).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "ToDus 2.1.2 HTTP-Download");
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (sendBearer && token != null && !token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (rangeStart >= 0L && rangeEnd >= rangeStart) {
            connection.setRequestProperty("Range", "bytes=" + rangeStart + "-" + rangeEnd);
        }
        return connection;
    }

    private static long contentLength(HttpURLConnection connection) {
        try { return Long.parseLong(connection.getHeaderField("Content-Length")); }
        catch (Exception ignored) { return -1L; }
    }

    private static boolean rangeStartsAtZero(String value) {
        return rangeStartFromContentRange(value) == 0L;
    }

    private static long rangeStartFromContentRange(String value) { return contentRangeGroup(value, 1); }
    private static long rangeEndFromContentRange(String value) { return contentRangeGroup(value, 2); }

    private static long totalFromContentRange(String value) {
        Matcher matcher = CONTENT_RANGE.matcher(value == null ? "" : value.trim());
        if (!matcher.matches() || "*".equals(matcher.group(3))) return -1L;
        try { return Long.parseLong(matcher.group(3)); }
        catch (NumberFormatException ignored) { return -1L; }
    }

    private static long contentRangeGroup(String value, int group) {
        Matcher matcher = CONTENT_RANGE.matcher(value == null ? "" : value.trim());
        if (!matcher.matches()) return -1L;
        try { return Long.parseLong(matcher.group(group)); }
        catch (NumberFormatException ignored) { return -1L; }
    }

    private static String readMetadata(File metadata) {
        if (!metadata.isFile()) return "";
        try (InputStream input = new FileInputStream(metadata);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
        catch (Exception ignored) { return ""; }
    }

    private static void writeMetadata(File metadata, String value) throws IOException {
        File temporary = new File(metadata.getAbsolutePath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (metadata.exists() && !metadata.delete()) {
            temporary.delete();
            throw new IOException("No se pudo actualizar el punto de reanudación.");
        }
        if (!temporary.renameTo(metadata)) {
            temporary.delete();
            throw new IOException("No se pudo guardar el punto de reanudación.");
        }
    }

    private static void deleteRangeFiles(File destination, int count) {
        File parent = destination.getParentFile();
        if (parent == null) return;
        for (int index = 0; index < count; index++) {
            new File(parent, destination.getName() + ".range-" + index + ".part").delete();
        }
        new File(destination.getAbsolutePath() + ".range.meta").delete();
    }

    private static boolean isMultipleAuthenticationError(String detail) {
        String lower = detail == null ? "" : detail.toLowerCase(Locale.US);
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

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private static final class Probe {
        final HttpURLConnection connection;
        final int status;
        final long totalBytes;
        final boolean sendBearer;
        final String etag;
        final String lastModified;

        Probe(HttpURLConnection connection, int status, long totalBytes, boolean sendBearer,
              String etag, String lastModified) {
            this.connection = connection;
            this.status = status;
            this.totalBytes = totalBytes;
            this.sendBearer = sendBearer;
            this.etag = etag;
            this.lastModified = lastModified;
        }
    }
}
