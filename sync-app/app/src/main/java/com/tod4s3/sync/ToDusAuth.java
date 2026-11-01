package com.tod4s3.sync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal native HTTP authentication client for ToDus. */
final class ToDusAuth {
    private static final String AUTH_BASE = "https://auth.todus.cu/v2/auth/";
    private static final String AUTH_VERSION_NAME = "2.1.2";
    private static final String AUTH_VERSION_CODE = "30102";
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern JWT_PATTERN = Pattern.compile("eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("[a-fA-F0-9]{96}");

    private ToDusAuth() { }

    static final class Session {
        final String phone;
        final String password;
        final String token;

        Session(String phone, String password, String token) {
            this.phone = phone;
            this.password = password;
            this.token = token;
        }
    }

    static String requestPin(String phoneInput) throws Exception {
        String phone = normalizePhone(phoneInput);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeStringField(payload, 1, phone);
        writeStringField(payload, 2, randomToken(150));
        post("users.reserve", payload.toByteArray());
        return phone;
    }

    static Session validateAndLogin(String phoneInput, String pinInput) throws Exception {
        String phone = normalizePhone(phoneInput);
        String pin = pinInput == null ? "" : pinInput.trim();
        if (!pin.matches("[0-9]{4,8}")) {
            throw new IOException("El PIN debe contener entre 4 y 8 dígitos.");
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeStringField(payload, 1, phone);
        writeStringField(payload, 2, randomToken(150));
        writeStringField(payload, 3, pin);
        byte[] response = post("users.register", payload.toByteArray());
        String password = extractSessionPassword(response);
        String token = login(phone, password);
        return new Session(phone, password, token);
    }

    static String login(String phoneInput, String passwordInput) throws Exception {
        String phone = normalizePhone(phoneInput);
        String password = passwordInput == null ? "" : passwordInput.trim();
        if (password.isEmpty()) throw new IOException("No hay una clave de sesión ToDus guardada.");

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeStringField(payload, 1, phone);
        writeStringField(payload, 2, password);
        writeStringField(payload, 3, randomToken(150));
        writeStringField(payload, 4, AUTH_VERSION_CODE);
        byte[] response = post("token", payload.toByteArray());
        String raw = new String(response, StandardCharsets.UTF_8).trim();

        Matcher jwt = JWT_PATTERN.matcher(raw);
        if (jwt.find()) return jwt.group();

        String allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.-_~+/";
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (allowed.indexOf(ch) >= 0) token.append(ch);
        }
        if (token.length() == 0) throw new IOException("ToDus no devolvió un token de sesión válido.");
        return token.toString();
    }

    private static String normalizePhone(String input) throws IOException {
        String raw = input == null ? "" : input.trim();
        if (!raw.matches("\\+?[0-9\\s()\\-]+")) {
            throw new IOException("Escribe un número de teléfono válido.");
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("00")) digits = digits.substring(2);
        if (digits.length() == 8) return "53" + digits;
        if (digits.length() == 10 && digits.startsWith("53")) return digits;
        throw new IOException("Escribe el número ToDus con 8 dígitos o con el prefijo 53.");
    }

    private static String extractSessionPassword(byte[] response) throws IOException {
        if (response == null || response.length == 0) {
            throw new IOException("ToDus no devolvió la clave de sesión.");
        }

        for (int i = 0; i < response.length; i++) {
            if (response[i] == '`' && response.length - i - 1 >= 96) {
                return new String(response, i + 1, 96, StandardCharsets.UTF_8).trim();
            }
        }

        String latin = new String(response, StandardCharsets.ISO_8859_1);
        Matcher hexPassword = PASSWORD_PATTERN.matcher(latin);
        if (hexPassword.find()) return hexPassword.group();

        if (response.length >= 166) {
            String legacyField = new String(response, 5, 161, StandardCharsets.UTF_8).trim();
            if (!legacyField.isEmpty()) return legacyField;
        }
        throw new IOException("No se pudo interpretar la clave de sesión que devolvió ToDus.");
    }

    private static byte[] post(String endpoint, byte[] payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(AUTH_BASE + endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("User-Agent", "ToDus " + AUTH_VERSION_NAME + " Auth");
        connection.setRequestProperty("Content-Type", "application/x-protobuf");
        connection.setRequestProperty("Accept", "*/*");
        connection.setFixedLengthStreamingMode(payload.length);
        try {
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] body = stream == null ? new byte[0] : readAll(stream);
            if (status == 404 && "users.register".equals(endpoint)) {
                throw new IOException("ToDus devolvió HTTP 404 al validar el PIN. Pulsa «Reenviar código» y usa el PIN más reciente. Si el error continúa, contacta con el soporte de ToDus; no compartas tu PIN.");
            }
            if (status == 403) {
                throw new IOException("ToDus no aceptó la clave de sesión (HTTP 403). Solicita un PIN nuevo.");
            }
            if (status < 200 || status >= 300) {
                throw new IOException("auth.todus.cu/v2/auth/" + endpoint + " respondió HTTP " + status + ".");
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private static void writeStringField(ByteArrayOutputStream output, int fieldNumber, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarint(output, (fieldNumber << 3) | 2);
        writeVarint(output, bytes.length);
        output.write(bytes);
    }

    private static void writeVarint(ByteArrayOutputStream output, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write(value & 0x7F);
    }

    private static String randomToken(int length) {
        StringBuilder token = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            token.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return token.toString();
    }
}
