package com.nikudaria.ai;

import android.Manifest;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Locale;
import java.util.regex.Pattern;

/** Explicit, user-confirmed bridge to Termux's documented RUN_COMMAND service. */
public final class TermuxRunner {
    public static final String TERMUX_PACKAGE = "com.termux";
    private static final Pattern BLOCKED = Pattern.compile(
            "(^|[^a-z0-9_])(su|sudo|mkfs[a-z0-9._-]*|dd|wipefs|reboot|shutdown|poweroff|halt)([^a-z0-9_]|$)"
                    + "|(^|[^a-z0-9_])rm\\s+-(?:[^\\s]*r[^\\s]*f|[^\\s]*f[^\\s]*r)(?:\\s|$)"
                    + "|(^|[^a-z0-9_])init\\s+0([^0-9]|$)", Pattern.CASE_INSENSITIVE);

    public static final class Result {
        public final boolean started;
        public final String message;
        Result(boolean started, String message) { this.started = started; this.message = message; }
    }

    public static boolean isBlocked(String command) {
        String normalized = command == null ? "" : command.replaceAll("\\s+", " ").trim();
        return normalized.isEmpty() || BLOCKED.matcher(normalized).find();
    }

    public static Result run(Context context, String command) {
        if (isBlocked(command)) return new Result(false, "Comando bloqueado por seguridad. No se envió a Termux.");
        PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(TERMUX_PACKAGE, 0);
        } catch (Exception e) {
            return new Result(false, "Termux no está instalado. Instálalo, ábrelo al menos una vez y vuelve a intentarlo.");
        }
        if (Build.VERSION.SDK_INT >= 23 && context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) {
            return new Result(false, "Falta el permiso RUN_COMMAND de Termux. Concede el permiso a NikuDarIA desde los permisos de Termux y vuelve a intentarlo.");
        }
        Intent serviceIntent = new Intent("com.termux.RUN_COMMAND");
        serviceIntent.setComponent(new ComponentName(TERMUX_PACKAGE, "com.termux.app.RunCommandService"));
        if (pm.resolveService(serviceIntent, 0) == null) {
            return new Result(false, "No se encontró el servicio RUN_COMMAND. Actualiza Termux desde una fuente compatible y habilita la integración.");
        }
        serviceIntent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        serviceIntent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", command});
        serviceIntent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        serviceIntent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
        serviceIntent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        Intent callback = new Intent(context, TermuxResultReceiver.class)
                .setAction(TermuxResultReceiver.ACTION_RESULT)
                .setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        int requestCode = (int) (System.nanoTime() & 0x7fffffff);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, requestCode, callback, flags);
        serviceIntent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent);
        try {
            context.startService(serviceIntent);
            return new Result(true, "Solicitud enviada a Termux en primer plano; esperando transcript y código de salida.");
        } catch (SecurityException e) {
            return new Result(false, "Termux rechazó RUN_COMMAND. Confirma el permiso y allow-external-apps=true en ~/.termux/termux.properties.");
        } catch (Exception e) {
            return new Result(false, "No se pudo iniciar Termux: " + e.getMessage());
        }
    }
}
