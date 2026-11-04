package com.tod4s3.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public final class DownloadKeepAliveService extends Service {
    public static final String ACTION_UPDATE = "com.tod4s3.sync.action.UPDATE_DOWNLOAD_STATUS";
    public static final String ACTION_STOP = "com.tod4s3.sync.action.STOP_DOWNLOAD_STATUS";
    public static final String EXTRA_COUNT = "download_count";
    public static final String EXTRA_PERCENT = "download_percent";
    private static final String CHANNEL_ID = "download_progress_v1";
    private static final int NOTIFICATION_ID = 8810;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopForegroundCompat();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        int count = intent == null ? 1 : Math.max(1, intent.getIntExtra(EXTRA_COUNT, 1));
        int percent = intent == null ? -1 : intent.getIntExtra(EXTRA_PERCENT, -1);
        Notification notification = buildNotification(count, percent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Descargas en curso", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Muestra el progreso de archivos que siguen descargándose en segundo plano.");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(int count, int percent) {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 8811, openApp, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_active_download)
                .setContentTitle(count == 1 ? "Descarga en curso" : count + " descargas en curso")
                .setContentText(percent >= 0 ? "Tod4S3 Sync · " + Math.min(100, percent) + "% completado"
                        : "Tod4S3 Sync · descargando en segundo plano")
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW);
        if (percent >= 0) builder.setProgress(100, Math.min(100, percent), false);
        else builder.setProgress(0, 0, true);
        return builder.build();
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Las descargas siguen activas aunque se quite la tarea visual de recientes.
        super.onTaskRemoved(rootIntent);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
