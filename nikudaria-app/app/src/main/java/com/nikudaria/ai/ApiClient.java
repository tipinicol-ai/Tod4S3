package com.nikudaria.ai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Direct, unauthenticated HTTPS client. Network methods run off the UI thread. */
public final class ApiClient {
    public static final String API_BASE = "https://tod4s3-production-9207.up.railway.app";
    private static final String PREFS = "nikudaria_private";
    private static final String KEY_CLIENT_ID = "client_id";
    private final SharedPreferences prefs;

    public ApiClient(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Random per-install identifier; it is not an authentication credential. */
    public synchronized String clientId() {
        String id = prefs.getString(KEY_CLIENT_ID, "");
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_CLIENT_ID, id).apply();
        }
        return id;
    }

    public JSONObject agent(JSONArray messages) throws Exception {
        JSONObject payload = new JSONObject().put("client_id", clientId()).put("messages", messages);
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return request("/api/app/nikudaria/agent", payload);
            } catch (Exception error) {
                last = error;
                if (attempt == 0 && isTransient(error)) {
                    try {
                        Thread.sleep(900);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw error;
                    }
                } else {
                    throw error;
                }
            }
        }
        throw last == null ? new IOException("No se pudo contactar con el servidor.") : last;
    }

    private JSONObject request(String path, JSONObject payload) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API_BASE + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(90000);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(true);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
            int status = conn.getResponseCode();
            InputStream stream = status >= 200 && status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(stream);
            JSONObject result;
            try {
                result = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            } catch (Exception malformed) {
                throw new IOException("El servidor devolvió una respuesta inválida (HTTP " + status + ").");
            }
            if (status < 200 || status >= 300 || !result.optBoolean("ok", status < 300)) {
                String message = result.optString("error", result.optString("message", ""));
                if (message.isEmpty()) message = "Error HTTP " + status;
                throw new IOException(message + " (HTTP " + status + ")");
            }
            return result;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean isTransient(Exception error) {
        if (error instanceof SocketTimeoutException || error instanceof UnknownHostException || error instanceof IOException) return true;
        String message = error.getMessage();
        return message != null && (message.contains("HTTP 429") || message.matches(".*HTTP 5\\d\\d.*"));
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = stream.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
