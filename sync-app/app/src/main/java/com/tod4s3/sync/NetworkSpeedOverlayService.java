package com.tod4s3.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public final class NetworkSpeedOverlayService extends Service {
    public static final String ACTION_START = "com.tod4s3.sync.action.START_NETWORK_SPEED";
    public static final String ACTION_STOP = "com.tod4s3.sync.action.STOP_NETWORK_SPEED";

    private static final String CHANNEL_ID = "network_speed_overlay_v1";
    private static final String PREFS_NAME = "tod4s3_sync";
    private static final String PREF_ENABLED = "speed_overlay_enabled_v1";
    private static final String PREF_X = "speed_overlay_x_v1";
    private static final String PREF_Y = "speed_overlay_y_v1";
    private static final int NOTIFICATION_ID = 8820;
    private static final long SAMPLE_INTERVAL_MS = 1_000L;
    private static final int TRANSLUCENT_NAVY = Color.argb(64, 20, 28, 43);
    private static final int DOWNLOAD_BLUE = Color.rgb(110, 190, 255);
    private static final int SOFT_WHITE = Color.rgb(242, 245, 250);
    private static final int MUTED = Color.rgb(177, 190, 207);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sampleRunnable = this::sampleAndRender;
    private WindowManager windowManager;
    private WindowManager.LayoutParams windowParams;
    private View overlay;
    private TextView networkLabel;
    private TextView downloadRate;
    private TextView uploadRate;
    private String previousTransport = "";
    private long previousRx = -1L;
    private long previousTx = -1L;
    private long previousSampleAt;
    private float dragStartX;
    private float dragStartY;
    private int dragStartWindowX;
    private int dragStartWindowY;
    private int touchSlopPx;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        touchSlopPx = Math.max(1, Math.round(8 * getResources().getDisplayMetrics().density));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopOverlayAndService(startId);
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        try {
            ensureOverlay();
            startVisibleForegroundService();
            resetSampling();
            handler.removeCallbacks(sampleRunnable);
            handler.post(sampleRunnable);
            return START_NOT_STICKY;
        } catch (RuntimeException e) {
            android.util.Log.e("Tod4S3Speed", "No se pudo mostrar la burbuja de velocidad", e);
            stopOverlayAndService(startId);
            return START_NOT_STICKY;
        }
    }

    private void ensureOverlay() {
        if (overlay != null) return;
        if (windowManager == null) throw new IllegalStateException("WindowManager no disponible");

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.HORIZONTAL);
        bubble.setGravity(Gravity.CENTER_VERTICAL);
        bubble.setPadding(dp(10), dp(6), dp(5), dp(6));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(dp(5));
        background.setColor(TRANSLUCENT_NAVY);
        bubble.setBackground(background);
        bubble.setElevation(dp(4));
        bubble.setContentDescription("Panel rectangular de velocidad de red. Arrastra para moverlo.");

        LinearLayout rates = new LinearLayout(this);
        rates.setOrientation(LinearLayout.VERTICAL);
        rates.setGravity(Gravity.CENTER_VERTICAL);
        networkLabel = new TextView(this);
        networkLabel.setText("SIN CONEXIÓN");
        networkLabel.setTextColor(MUTED);
        networkLabel.setTextSize(9);
        networkLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        networkLabel.setShadowLayer(dp(3), 0f, dp(1), Color.BLACK);
        rates.addView(networkLabel, new LinearLayout.LayoutParams(-2, -2));

        downloadRate = rateText(DOWNLOAD_BLUE);
        uploadRate = rateText(SOFT_WHITE);
        rates.addView(downloadRate, new LinearLayout.LayoutParams(-2, -2));
        rates.addView(uploadRate, new LinearLayout.LayoutParams(-2, -2));
        bubble.addView(rates, new LinearLayout.LayoutParams(-2, -2));
        rates.setOnTouchListener(this::handleDrag);

        TextView close = new TextView(this);
        close.setText("×");
        close.setTextColor(MUTED);
        close.setTextSize(20);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Cerrar indicador de velocidad");
        LinearLayout.LayoutParams closeLayout = new LinearLayout.LayoutParams(dp(30), dp(42));
        closeLayout.leftMargin = dp(5);
        bubble.addView(close, closeLayout);
        close.setOnClickListener(v -> stopOverlayAndService(0));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        windowParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = Math.max(0, getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_X, dp(14)));
        windowParams.y = Math.max(0, getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_Y, dp(150)));
        windowManager.addView(bubble, windowParams);
        overlay = bubble;
    }

    private TextView rateText(int color) {
        TextView text = new TextView(this);
        text.setTextColor(color);
        text.setTextSize(12);
        text.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        text.setIncludeFontPadding(false);
        text.setShadowLayer(dp(2), 0f, dp(1), Color.BLACK);
        text.setText("↓ 0 B/s");
        return text;
    }

    private boolean handleDrag(View view, MotionEvent event) {
        if (windowParams == null || overlay == null) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragStartX = event.getRawX();
                dragStartY = event.getRawY();
                dragStartWindowX = windowParams.x;
                dragStartWindowY = windowParams.y;
                return true;
            case MotionEvent.ACTION_MOVE:
                int dx = Math.round(event.getRawX() - dragStartX);
                int dy = Math.round(event.getRawY() - dragStartY);
                if (Math.abs(dx) > touchSlopPx || Math.abs(dy) > touchSlopPx) {
                    windowParams.x = Math.max(0, dragStartWindowX + dx);
                    windowParams.y = Math.max(0, dragStartWindowY + dy);
                    int screenWidth = getResources().getDisplayMetrics().widthPixels;
                    int screenHeight = getResources().getDisplayMetrics().heightPixels;
                    windowParams.x = Math.min(windowParams.x, Math.max(0, screenWidth - dp(70)));
                    windowParams.y = Math.min(windowParams.y, Math.max(0, screenHeight - dp(80)));
                    try { windowManager.updateViewLayout(overlay, windowParams); }
                    catch (RuntimeException ignored) { }
                }
                return true;
            case MotionEvent.ACTION_UP:
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putInt(PREF_X, windowParams.x).putInt(PREF_Y, windowParams.y).apply();
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default:
                return false;
        }
    }

    private void resetSampling() {
        previousTransport = "";
        previousRx = -1L;
        previousTx = -1L;
        previousSampleAt = 0L;
    }

    private void sampleAndRender() {
        if (overlay == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopOverlayAndService(0);
            return;
        }
        String transport = activeTransport();
        long[] counters = countersFor(transport);
        long now = SystemClock.elapsedRealtime();
        long rxPerSecond = 0L;
        long txPerSecond = 0L;
        if (transport.equals(previousTransport) && previousSampleAt > 0L
                && counters[0] >= previousRx && counters[1] >= previousTx) {
            long elapsed = Math.max(1L, now - previousSampleAt);
            rxPerSecond = (counters[0] - previousRx) * 1_000L / elapsed;
            txPerSecond = (counters[1] - previousTx) * 1_000L / elapsed;
        }
        previousTransport = transport;
        previousRx = counters[0];
        previousTx = counters[1];
        previousSampleAt = now;

        if (networkLabel != null) networkLabel.setText(transport);
        if (networkLabel != null) {
            networkLabel.setTextColor("WI-FI".equals(transport) ? DOWNLOAD_BLUE
                    : ("DATOS MÓVILES".equals(transport) ? Color.rgb(255, 164, 171) : SOFT_WHITE));
        }
        if (downloadRate != null) downloadRate.setText("↓ " + formatRate(rxPerSecond));
        if (uploadRate != null) uploadRate.setText("↑ " + formatRate(txPerSecond));
        handler.postDelayed(sampleRunnable, SAMPLE_INTERVAL_MS);
    }

    private String activeTransport() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return "SIN CONEXIÓN";
        try {
            Network network = manager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WI-FI";
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "DATOS MÓVILES";
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ETHERNET";
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
                return "RED ACTIVA";
            }
        } catch (SecurityException e) {
            android.util.Log.e("Tod4S3Speed", "Falta permiso ACCESS_NETWORK_STATE", e);
        } catch (RuntimeException e) {
            android.util.Log.w("Tod4S3Speed", "No se pudieron leer capacidades de red", e);
        }
        try {
            android.net.NetworkInfo info = manager.getActiveNetworkInfo();
            if (info != null && info.isConnected()) {
                if (info.getType() == ConnectivityManager.TYPE_WIFI) return "WI-FI";
                if (info.getType() == ConnectivityManager.TYPE_MOBILE) return "DATOS MÓVILES";
                if (info.getType() == ConnectivityManager.TYPE_ETHERNET) return "ETHERNET";
                if (info.getType() == ConnectivityManager.TYPE_VPN) return "VPN";
                return "RED ACTIVA";
            }
        } catch (RuntimeException e) {
            android.util.Log.w("Tod4S3Speed", "Fallback de estado de red no disponible", e);
        }
        return "SIN CONEXIÓN";
    }

    private long[] countersFor(String transport) {
        long totalRx = TrafficStats.getTotalRxBytes();
        long totalTx = TrafficStats.getTotalTxBytes();
        long mobileRx = TrafficStats.getMobileRxBytes();
        long mobileTx = TrafficStats.getMobileTxBytes();
        if ("DATOS MÓVILES".equals(transport) && mobileRx >= 0L && mobileTx >= 0L) {
            return new long[]{mobileRx, mobileTx};
        }
        if ("WI-FI".equals(transport) && totalRx >= 0L && totalTx >= 0L
                && mobileRx >= 0L && mobileTx >= 0L) {
            return new long[]{Math.max(0L, totalRx - mobileRx), Math.max(0L, totalTx - mobileTx)};
        }
        return new long[]{Math.max(0L, totalRx), Math.max(0L, totalTx)};
    }

    private String formatRate(long bytesPerSecond) {
        if (bytesPerSecond < 1_024L) return bytesPerSecond + " B/s";
        if (bytesPerSecond < 1_048_576L) {
            return String.format(Locale.getDefault(), "%.1f KB/s", bytesPerSecond / 1_024.0d);
        }
        return String.format(Locale.getDefault(), "%.2f MB/s", bytesPerSecond / 1_048_576.0d);
    }

    private void startVisibleForegroundService() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Velocidad de red", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Indica cuándo está activo el panel flotante de velocidad de red.");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 8821, openApp, flags);

        Intent stop = new Intent(this, NetworkSpeedOverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 8822, stop, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_active_download)
                .setContentTitle("Velocidad de red activada")
                .setContentText("El panel flotante muestra bajada y subida en vivo.")
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Desactivar", stopIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW);
        return builder.build();
    }

    private void stopOverlayAndService(int startId) {
        handler.removeCallbacks(sampleRunnable);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_ENABLED, false).apply();
        if (overlay != null && windowManager != null) {
            try { windowManager.removeView(overlay); } catch (RuntimeException ignored) { }
        }
        overlay = null;
        networkLabel = null;
        downloadRate = null;
        uploadRate = null;
        windowParams = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        if (startId > 0) stopSelfResult(startId);
        else stopSelf();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(sampleRunnable);
        if (overlay != null && windowManager != null) {
            try { windowManager.removeView(overlay); } catch (RuntimeException ignored) { }
        }
        overlay = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
