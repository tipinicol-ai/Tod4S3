package com.nikudaria.ai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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
        return request("/api/app/nikudaria/agent", payload);
    }

    private JSONObject request(String path, JSONObject payload) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API_BASE + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(65000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }
            int status = conn.getResponseCode();
            InputStream stream = status >= 200 && status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(stream);
            JSONObject result = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            if (status < 200 || status >= 300 || !result.optBoolean("ok", status < 300)) {
                throw new Exception(result.optString("error", result.optString("message", "Error HTTP " + status)));
            }
            return result;
        } finally {
            if (conn != null) conn.disconnect();
        }
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
