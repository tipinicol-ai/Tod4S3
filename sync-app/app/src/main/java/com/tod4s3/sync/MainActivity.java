package com.tod4s3.sync;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.View;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.InputFilter;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.MimeTypeMap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.RingtoneManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class MainActivity extends Activity {
    private static final String API_BASE = "https://tod4s3-production.up.railway.app";
    private static final String LOG_TAG = "Tod4S3Sync";
    private static final String KEY_ALIAS = "tod4s3.sync.todus.credentials.v1";
    private static final String PREF_TODUS_PHONE = "todus_phone_enc";
    private static final String PREF_TODUS_PASSWORD = "todus_password_enc";
    private static final String PREF_TODUS_TOKEN = "todus_token_enc";
    private static final String PREF_TODUS_PIN_REQUESTED = "todus_pin_requested";
    private static final String PREF_SELECTED_PLAN = "selected_plan";
    private static final String PREF_DOWNLOAD_HISTORY = "download_history_v1";
    private static final String PREF_DIRECT_LINK_TASKS = "direct_link_tasks_v1";
    private static final String PREF_TODUS_DOWNLOAD_TASKS = "todus_download_tasks_v1";
    private static final String PREF_ACTIVE_PLAN = "active_plan_v1";
    private static final String PREF_ACTIVE_PREMIUM = "active_premium_v1";
    private static final String PREF_PREMIUM_VERIFIED = "premium_verified_v1";
    private static final String PREF_HIDDEN_DELIVERIES = "hidden_delivery_ids_v1";
    private static final String PREF_UI_THEME = "ui_theme_v1";
    private static final String PREF_UI_ACCENT = "ui_accent_v1";
    private static final String PREF_DOWNLOAD_CONCURRENCY = "download_concurrency_v1";
    private static final String PREF_DOWNLOAD_FOLDER = "download_folder_uri_v1";
    private static final int REQUEST_WRITE_STORAGE = 702;
    private static final int REQUEST_PAYMENT_RECEIPT = 703;
    private static final int REQUEST_DOWNLOAD_FOLDER = 705;
    private static final int REQUEST_NOTIFICATIONS = 706;
    private static final String DOWNLOAD_NOTIFICATION_CHANNEL = "downloads_complete_v1";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (appBanned || unbannedScreenVisible) {
                refreshAccountProfile();
            } else {
                refreshDeliveries();
                refreshAppTasks();
                refreshAccountProfile();
            }
            handler.postDelayed(this, 15_000L);
        }
    };

    private android.content.SharedPreferences prefs;
    private FrameLayout appRoot;
    private ScrollView appScroll;
    private LinearLayout currentPage;
    private EditText codeInput;
    private EditText phoneInput;
    private EditText pinInput;
    private TextView syncStatus;
    private TextView todusStatus;
    private TextView todusSessionStatusText;
    private String todusSessionStatusMessage = "No hay una sesión ToDus guardada.";
    private TextView planStatus;
    private TextView dashboardPlanStatusText;
    private LinearLayout freeQuotaPanel;
    private ProgressBar freeQuotaPanelProgress;
    private TextView freeQuotaPanelUsage;
    private TextView freeQuotaPanelReset;
    private LinearLayout deliveriesBox;
    private LinearLayout dashboardItemsBox;
    private TextView dashboardStatusText;
    private LinearLayout quotaUsageBox;
    private ProgressBar quotaProgressBar;
    private TextView quotaUsageText;
    private TextView quotaResetText;
    private View dashboardFab;
    private String token;
    private String pendingPairingCode = "";
    private String todusPhone = "";
    private String todusPassword = "";
    private String todusToken = "";
    private String pendingPremiumShareText = "";
    private boolean savedCredentialsCouldNotBeDecrypted;
    private boolean viewingDownloadsTab;
    private boolean pairingInFlight;
    private boolean toDusPinRequestInFlight;
    private boolean toDusValidationInFlight;
    private volatile int toDusPinRequestGeneration;
    private volatile int toDusValidationGeneration;
    private final Object toDusAuthAttemptLock = new Object();
    private boolean deliveryRefreshInFlight;
    private boolean appTaskRefreshInFlight;
    private boolean viewingDashboard;
    private boolean showingPlansPage;
    private boolean relinkingBotAccount;
    private boolean profileRefreshInFlight;
    private boolean profileRefreshPending;
    private volatile boolean appBanned;
    private boolean bannedScreenVisible;
    private volatile boolean unbannedScreenVisible;
    private volatile int appAccessGeneration;
    private boolean showingAiChat;
    private boolean homeTabPageVisible;
    private boolean aiChatRequestInFlight;
    private ScrollView aiConversationScroll;
    private LinearLayout aiConversationBox;
    private EditText aiComposer;
    private Button aiSendButton;
    private TextView aiChatStatus;
    private boolean aiKeyboardWasVisible;
    private final ArrayList<AiChatMessage> aiChatMessages = new ArrayList<>();
    private TextView appLogOutput;
    private volatile int appLogGeneration;
    private boolean premiumActive;
    private boolean premiumVerified;
    private int freeQuotaPercent;
    private long freeQuotaUsedBytes;
    private long freeQuotaLimitBytes;
    private long freeQuotaRemainingBytes;
    private long freeQuotaResetInSeconds = 24 * 60 * 60;
    private String activePlan = "free";
    private String premiumExpiresAt = "";
    private final Object downloadConcurrencyLock = new Object();
    private int activeDownloadJobs;
    private int nextDownloadNotificationId = 2000;
    private final Object directLinkDownloadLock = new Object();
    private final java.util.Set<String> activeDirectLinkKeys = new java.util.HashSet<>();
    private final java.util.LinkedHashMap<String, DirectLinkTask> directLinkTasks = new java.util.LinkedHashMap<>();
    private final Object toDusDownloadLock = new Object();
    private final java.util.LinkedHashMap<String, ToDusDownloadTask> toDusDownloadTasks = new java.util.LinkedHashMap<>();
    private int activeDirectLinkJobs;
    private String dashboardFilter = "all";
    private String planReturnFilter = "all";
    private String dashboardSearch = "";
    private boolean sortOldestFirst;
    private JSONArray latestDeliveryRows = new JSONArray();
    private JSONArray latestAppTaskRows = new JSONArray();
    private android.graphics.Bitmap telegramProfileBitmap;
    private boolean telegramProfilePhotoLoading;
    private final ArrayList<ImageView> pendingProfileAvatarViews = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("tod4s3_sync", MODE_PRIVATE);
        token = prefs.getString("token", "");
        premiumActive = prefs.getBoolean(PREF_ACTIVE_PREMIUM, false);
        premiumVerified = prefs.getBoolean(PREF_PREMIUM_VERIFIED, false);
        activePlan = prefs.getString(PREF_ACTIVE_PLAN, "free");
        premiumExpiresAt = prefs.getString("active_plan_expires_v1", "");
        loadDirectLinkTasks();
        loadToDusDownloadTasks();
        loadToDusCredentials();
        android.util.Log.i(LOG_TAG, "Inicio de Tod4S3 Sync v" + appVersion() + " · Android API " + Build.VERSION.SDK_INT);
        buildUi();
        ensureDownloadNotificationChannel();
        handler.postDelayed(this::requestNotificationPermissionIfNeeded, 1_200L);

        if (!prefs.getString(PREF_SELECTED_PLAN, "").isEmpty()
                && savedCredentialsCouldNotBeDecrypted && todusStatus != null) {
            todusStatus.setText("La sesión guardada ya no se pudo descifrar. Solicita un código nuevo para conectar ToDus.");
        } else if (!prefs.getString(PREF_SELECTED_PLAN, "").isEmpty()
                && token != null && !token.isEmpty() && !todusPhone.isEmpty() && !todusPassword.isEmpty()) {
            handler.post(this::restoreToDusSession);
        }
        handler.post(poller);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DOWNLOAD_FOLDER) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri tree = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
                        && (flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } else if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                    getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                    getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } else {
                    throw new SecurityException("El selector no otorgó permiso persistente.");
                }
                prefs.edit().putString(PREF_DOWNLOAD_FOLDER, tree.toString()).apply();
                Toast.makeText(this, "Carpeta base de descargas guardada.", Toast.LENGTH_LONG).show();
                showSettings(false);
            } catch (Exception e) {
                Toast.makeText(this, "No se pudo guardar el acceso a esa carpeta.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode != REQUEST_PAYMENT_RECEIPT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingPremiumShareText = "";
            Toast.makeText(this, "No se seleccionó una captura; no se envió ningún mensaje.", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri receiptUri = data.getData();
        String message = pendingPremiumShareText;
        pendingPremiumShareText = "";
        sharePremiumReceipt(receiptUri, message);
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefs != null && token != null && !token.isEmpty()) refreshAccountProfile();
    }

    private void chooseReceiptAndShare(String message) {
        pendingPremiumShareText = message;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("image/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(picker, REQUEST_PAYMENT_RECEIPT);
        } catch (Exception e) {
            pendingPremiumShareText = "";
            Toast.makeText(this, "No se pudo abrir la galería para elegir el comprobante.", Toast.LENGTH_LONG).show();
        }
    }

    private void sharePremiumReceipt(Uri receiptUri, String message) {
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("image/*");
            share.putExtra(Intent.EXTRA_STREAM, receiptUri);
            share.putExtra(Intent.EXTRA_TEXT, message);
            share.setClipData(ClipData.newUri(getContentResolver(), "Comprobante de pago", receiptUri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setPackage("org.telegram.messenger");
            try {
                startActivity(share);
            } catch (Exception telegramUnavailable) {
                share.setPackage(null);
                startActivity(Intent.createChooser(share, "Compartir comprobante"));
            }
            Toast.makeText(this, "En Telegram selecciona el chat @Niku_Blade y revisa el texto y la captura antes de enviar.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo compartir el comprobante.", Toast.LENGTH_LONG).show();
        }
    }

    private void buildUi() {
        appRoot = new FrameLayout(this);
        appRoot.setBackgroundColor(backgroundColor());
        appScroll = new ScrollView(this);
        appScroll.setFillViewport(true);
        appScroll.setBackgroundColor(backgroundColor());
        appRoot.addView(appScroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(appRoot);
        appRoot.setFocusableInTouchMode(true);
        appRoot.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            android.graphics.Rect visibleFrame = new android.graphics.Rect();
            appRoot.getWindowVisibleDisplayFrame(visibleFrame);
            int rootHeight = appRoot.getRootView().getHeight();
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int obscuredHeight = Math.max(rootHeight - visibleFrame.bottom,
                    screenHeight - visibleFrame.bottom);
            boolean keyboardVisible = obscuredHeight > dp(160);
            if (aiKeyboardWasVisible && !keyboardVisible && showingAiChat) {
                handler.postDelayed(() -> {
                    if (showingAiChat && appScroll != null) {
                        appScroll.scrollTo(0, 0);
                        appScroll.requestLayout();
                        appRoot.requestLayout();
                    }
                }, 80L);
            }
            aiKeyboardWasVisible = keyboardVisible;
        });
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        applySystemBarTheme();
        if (prefs.getString(PREF_SELECTED_PLAN, "").isEmpty()) {
            showPlansStep(false);
        } else if (token == null || token.isEmpty()) {
            showBotStep(false);
        } else if (prefs.getBoolean(PREF_TODUS_PIN_REQUESTED, false)) {
            showPinStep(false);
        } else if (!todusPhone.isEmpty() && !todusPassword.isEmpty()) {
            showHomeStep(false);
        } else {
            showPhoneStep(false);
        }
    }

    private void ensureDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(DOWNLOAD_NOTIFICATION_CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(
                DOWNLOAD_NOTIFICATION_CHANNEL, "Descargas completadas", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Avisos sonoros al finalizar una descarga en Tod4S3 Sync.");
        channel.enableVibration(true);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attributes);
        manager.createNotificationChannel(channel);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void showPlansStep(boolean animated) {
        if (token != null && !token.isEmpty() && !showingPlansPage) {
            planReturnFilter = viewingDashboard ? dashboardFilter : "all";
        }
        showingPlansPage = true;
        viewingDashboard = false;
        dashboardItemsBox = null;
        deliveriesBox = null;
        quotaUsageBox = null;
        dashboardPlanStatusText = null;
        freeQuotaPanel = null;
        freeQuotaPanelProgress = null;
        freeQuotaPanelUsage = null;
        freeQuotaPanelReset = null;
        boolean verifiedPremium = hasAiPremiumAccess();
        LinearLayout page = createStepPage(0,
                verifiedPremium ? "Tu plan Premium está activo" : "Elige tu plan",
                verifiedPremium ? "Tu acceso Premium está verificado en este móvil."
                        : "Empieza gratis. Los planes Premium son opcionales.");
        if (token != null && !token.isEmpty()) {
            Button returnToApp = secondaryButton("← Volver a mis archivos");
            returnToApp.setOnClickListener(v -> {
                if (prefs.getBoolean(PREF_TODUS_PIN_REQUESTED, false)) showPinStep(true);
                else showDashboard(planReturnFilter, true);
                refreshAccountProfile();
            });
            page.addView(returnToApp, margin(0, 12, 0, 8));
        }
        planStatus = statusText(accountPlanStatusText());
        page.addView(planStatus, margin(0, 4, 0, 14));
        if (verifiedPremium) {
            LinearLayout active = card();
            active.setBackground(roundRect(Color.rgb(18, 42, 42), 22, Color.rgb(52, 174, 148)));
            TextView badge = text("✓  PREMIUM ACTIVO", 12, true);
            badge.setTextColor(Color.rgb(99, 230, 190));
            active.addView(badge);
            active.addView(text("AiTod4S3 y tus funciones Premium están disponibles", 17, true), margin(0, 7, 0, 0));
            if (!premiumExpiresAt.isEmpty()) {
                active.addView(bodyText("Vigencia: " + premiumExpiresAt), margin(0, 5, 0, 0));
            } else {
                active.addView(bodyText("Plan permanente de creador"), margin(0, 5, 0, 0));
            }
            Button continuePremium = primaryButton("Continuar con mi plan Premium");
            continuePremium.setOnClickListener(v -> continueWithActivePlan());
            active.addView(continuePremium, margin(0, 13, 0, 0));
            page.addView(active, margin(0, 8, 0, 8));
        }
        LinearLayout free = card();
        TextView freeTag = text(verifiedPremium ? "PLAN GRATUITO" : "RECOMENDADO PARA EMPEZAR", 11, true);
        freeTag.setTextColor(Color.rgb(99, 230, 216));
        free.addView(freeTag);
        free.addView(text("Gratis", 24, true), margin(0, 7, 0, 2));
        free.addView(text("1 GB cada 24 horas", 18, true));
        if (verifiedPremium) {
            free.addView(bodyText("Plan gratuito disponible para cuentas sin Premium. Tu plan Premium activo no cambia."), margin(0, 8, 0, 0));
        } else {
            free.addView(bodyText("Usa Tod4S3 sin pagar. Puedes volver a elegir un plan Premium más adelante."), margin(0, 8, 0, 14));
            freeQuotaPanel = new LinearLayout(this);
            freeQuotaPanel.setOrientation(LinearLayout.VERTICAL);
            free.addView(freeQuotaPanel, margin(0, 0, 0, 10));
            updateFreeQuotaPanel();
            Button chooseFree = primaryButton("Continuar con el plan gratis");
            chooseFree.setOnClickListener(v -> chooseFreePlan());
            free.addView(chooseFree);
        }
        page.addView(free, margin(0, 20, 0, 18));

        page.addView(text("Planes Premium", 20, true), margin(0, 4, 0, 4));
        page.addView(bodyText("Descargas sin límite y acceso a AiTod4S3, el chat de IA de texto, mientras el plan esté activo y verificado."), margin(0, 0, 0, 10));
        page.addView(planOffer("Premium 15 días", "250 CUP", "Descargas sin límite, entrega directa por ToDus y AiTod4S3.", "Premium 15 días · 250 CUP"), margin(0, 0, 0, 10));
        page.addView(planOffer("Premium 20 días", "300 CUP", "Descargas sin límite y chat de IA AiTod4S3 durante 20 días.", "Premium 20 días · 300 CUP"), margin(0, 0, 0, 10));
        page.addView(planOffer("Premium 30 días", "500 CUP", "Un mes de descargas sin límite y acceso al chat AiTod4S3.", "Premium 30 días · 500 CUP"), margin(0, 0, 0, 10));
        LinearLayout paymentMethods = new LinearLayout(this);
        paymentMethods.setOrientation(LinearLayout.HORIZONTAL);
        paymentMethods.addView(paymentMethodChip(R.drawable.transfermovil_payment, "Transfermóvil"), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams balanceChipLayout = new LinearLayout.LayoutParams(0, -2, 1f);
        balanceChipLayout.setMargins(dp(8), 0, 0, 0);
        paymentMethods.addView(paymentMethodChip(R.drawable.saldo_movil_payment, "Saldo móvil"), balanceChipLayout);
        page.addView(paymentMethods, margin(0, 5, 0, 10));
        page.addView(bodyText("Los pagos se coordinan por Transfermóvil o saldo móvil. La activación de Premium es manual. AiTod4S3 responde por texto mediante un servicio externo; su disponibilidad depende del saldo y los límites de la cuenta API."), margin(0, 6, 0, 0));
        showPage(page, animated);
        if (token != null && !token.isEmpty()) refreshAccountProfile();
    }

    private LinearLayout planOffer(String name, String price, String detail, String purchasePlan) {
        LinearLayout offer = card();
        offer.setBackground(roundRect(Color.rgb(23, 31, 44), 22, Color.rgb(143, 111, 48)));
        TextView nameLabel = text(name, 18, true);
        nameLabel.setTextColor(Color.rgb(255, 221, 141));
        offer.addView(nameLabel);
        TextView priceLabel = text(price, 16, true);
        priceLabel.setTextColor(Color.rgb(242, 198, 106));
        offer.addView(priceLabel, margin(0, 4, 0, 0));
        offer.addView(bodyText(detail), margin(0, 5, 0, 10));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button buyPlan = goldButton("Comprar premium");
        buyPlan.setTextSize(12);
        buyPlan.setOnClickListener(v -> openPremiumRequest(purchasePlan));
        LinearLayout.LayoutParams buyParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
        buyParams.setMargins(0, 0, dp(7), 0);
        actions.addView(buyPlan, buyParams);
        Button alreadyPremium = secondaryButton("Ya soy premium");
        alreadyPremium.setTextSize(12);
        alreadyPremium.setOnClickListener(v -> promptPremiumActivationToken());
        actions.addView(alreadyPremium, new LinearLayout.LayoutParams(0, dp(50), 1f));
        offer.addView(actions);
        return offer;
    }

    private void promptPremiumActivationToken() {
        if (token == null || token.isEmpty()) {
            Toast.makeText(this,
                    "Primero continúa con Gratis y vincula tu cuenta de Telegram; luego podrás validar el token.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(6), dp(8), dp(2));
        form.addView(bodyText("Pega el código que te entregó el administrador. Solo sirve para tu ID de Telegram y este móvil."));
        EditText codeField = input("Ejemplo: T4S3-…", android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeField.setSingleLine(true);
        form.addView(codeField, margin(0, 10, 0, 6));
        TextView status = bodyText("");
        status.setTextColor(Color.rgb(255, 205, 116));
        form.addView(status);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Ya soy Premium")
                .setView(form)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Verificar token", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String code = codeField.getText().toString().trim();
            if (code.isEmpty()) {
                status.setText("Pega el código que te entregó el administrador.");
                return;
            }
            verifyPremiumActivationToken(code, dialog, status);
        }));
        dialog.show();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    private void verifyPremiumActivationToken(String code, AlertDialog dialog, TextView status) {
        final String currentToken = token == null ? "" : token;
        status.setText("Verificando el ID, el plan y este móvil…");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("token", code.toUpperCase(java.util.Locale.ROOT));
                JSONObject result = request("POST", "/api/app/premium/claim", body.toString(), currentToken);
                if (!result.optBoolean("verified", false)) throw new IOException("premium_not_verified");
                runOnUiThread(() -> {
                    premiumActive = result.optBoolean("premium", true);
                    premiumVerified = true;
                    activePlan = result.optString("plan", "premium");
                    if (!activePlan.equals("premium") && !activePlan.equals("owner")) activePlan = "premium";
                    premiumExpiresAt = result.optString("expires", "");
                    prefs.edit().putBoolean(PREF_ACTIVE_PREMIUM, premiumActive)
                            .putBoolean(PREF_PREMIUM_VERIFIED, premiumVerified)
                            .putString(PREF_ACTIVE_PLAN, activePlan)
                            .putString("active_plan_expires_v1", premiumExpiresAt)
                            .putString(PREF_SELECTED_PLAN, "premium")
                            .apply();
                    dialog.dismiss();
                    Toast.makeText(this, "Premium verificado para este móvil.", Toast.LENGTH_LONG).show();
                    if (showingPlansPage) {
                        showPlansStep(false);
                    } else if (homeTabPageVisible) {
                        showHomeSection(viewingDownloadsTab, false);
                    } else if (planStatus != null) {
                        planStatus.setText(accountPlanStatusText());
                    }
                    refreshAccountProfile();
                });
            } catch (Exception e) {
                String raw = e.getMessage() == null ? "" : e.getMessage();
                String message = raw.contains("premium_not_active")
                        ? "El administrador todavía no ha activado Premium para esta cuenta."
                        : raw.contains("device_mismatch")
                        ? "Este código ya está vinculado a otro móvil. Contacta con el administrador."
                        : raw.contains("plan_changed") || raw.contains("activation_expired")
                        ? "El código venció o corresponde a un plan anterior. Pide uno nuevo al administrador."
                        : raw.contains("invalid_token")
                        ? "El código no es válido para esta cuenta. Comprueba que pertenece a tu ID."
                        : "No se pudo verificar el código. Revisa la conexión e inténtalo de nuevo.";
                runOnUiThread(() -> {
                    status.setText(message);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                });
            }
        }, "premium-token-verify").start();
    }

    private LinearLayout paymentMethodChip(int iconResource, String label) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(9), dp(8), dp(8), dp(8));
        chip.setBackground(roundRect(Color.rgb(15, 31, 49), 16, Color.rgb(50, 71, 88)));
        ImageView logo = new ImageView(this);
        logo.setImageResource(iconResource);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        logo.setContentDescription(label);
        logo.setBackground(roundRect(Color.WHITE, 8, 0));
        logo.setPadding(dp(2), dp(2), dp(2), dp(2));
        chip.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
        chip.addView(text(label, 12, true), margin(7, 0, 0, 0));
        return chip;
    }

    private void openPremiumRequest(String selectedPlan) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), dp(2));
        form.addView(bodyText("Plan seleccionado: " + selectedPlan), margin(0, 0, 0, 12));

        EditText nameInput = input("Tu nombre", android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        form.addView(nameInput, margin(0, 0, 0, 8));
        EditText usernameInput = input("Usuario de Telegram (opcional)", android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_NORMAL);
        form.addView(usernameInput, margin(0, 0, 0, 10));

        TextView paymentLabel = text("Método de pago", 14, true);
        paymentLabel.setTextColor(Color.rgb(176, 197, 211));
        form.addView(paymentLabel, margin(0, 0, 0, 3));
        RadioGroup paymentChoice = new RadioGroup(this);
        paymentChoice.setOrientation(RadioGroup.VERTICAL);
        RadioButton transfer = new RadioButton(this);
        transfer.setId(View.generateViewId());
        configurePaymentOption(transfer, R.drawable.transfermovil_payment, "Transfermóvil", "Paga desde la app y guarda el comprobante");
        RadioGroup.LayoutParams transferLayout = new RadioGroup.LayoutParams(-1, -2);
        transferLayout.setMargins(0, dp(5), 0, dp(5));
        paymentChoice.addView(transfer, transferLayout);
        RadioButton mobileBalance = new RadioButton(this);
        mobileBalance.setId(View.generateViewId());
        configurePaymentOption(mobileBalance, R.drawable.saldo_movil_payment, "Saldo móvil", "Envía el comprobante por Telegram");
        RadioGroup.LayoutParams balanceLayout = new RadioGroup.LayoutParams(-1, -2);
        balanceLayout.setMargins(0, dp(5), 0, dp(5));
        paymentChoice.addView(mobileBalance, balanceLayout);
        form.addView(paymentChoice, margin(0, 0, 0, 4));
        TextView paymentError = bodyText("Selecciona cómo vas a pagar.");
        paymentError.setTextColor(Color.rgb(242, 198, 106));
        paymentError.setVisibility(View.GONE);
        form.addView(paymentError);
        paymentChoice.setOnCheckedChangeListener((group, checkedId) -> paymentError.setVisibility(View.GONE));
        form.addView(bodyText("Para enviar texto y captura juntos, elige la primera opción y selecciona @Niku_Blade en Telegram. La opción de chat directo abre la conversación con el texto preparado; allí adjuntas la captura manualmente."), margin(0, 10, 0, 10));
        Button shareReceipt = goldButton("Adjuntar captura + texto");
        form.addView(shareReceipt, margin(0, 0, 0, 8));
        Button directChat = secondaryButton("Abrir chat directo");
        form.addView(directChat);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Solicitar plan Premium")
                .setView(form)
                .setNegativeButton("Cerrar", null)
                .create();
        shareReceipt.setOnClickListener(v -> {
            String message = premiumRequestMessage(selectedPlan, nameInput, usernameInput, paymentChoice, transfer, paymentError);
            if (message == null) return;
            dialog.dismiss();
            chooseReceiptAndShare(message);
        });
        directChat.setOnClickListener(v -> {
            String message = premiumRequestMessage(selectedPlan, nameInput, usernameInput, paymentChoice, transfer, paymentError);
            if (message == null) return;
            dialog.dismiss();
            openTelegramChat(message);
        });
        dialog.show();
    }

    private void configurePaymentOption(RadioButton option, int iconResource, String title, String subtitle) {
        String content = title + "\n" + subtitle;
        SpannableString styled = new SpannableString(content);
        styled.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new RelativeSizeSpan(0.82f), title.length() + 1, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new ForegroundColorSpan(Color.rgb(176, 197, 211)), title.length() + 1, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        option.setText(styled);
        option.setTextSize(14);
        option.setTextColor(Color.WHITE);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setMinHeight(dp(72));
        option.setPadding(dp(12), dp(9), dp(10), dp(9));
        option.setBackground(roundRect(Color.rgb(15, 31, 49), 16, Color.rgb(50, 71, 88)));
        option.setButtonTintList(ColorStateList.valueOf(Color.rgb(242, 198, 106)));
        option.setCompoundDrawablePadding(dp(12));
        Drawable icon = getDrawable(iconResource);
        if (icon != null) {
            icon.setBounds(0, 0, dp(44), dp(44));
            option.setCompoundDrawablesRelative(icon, null, null, null);
        }
        animateButton(option);
    }

    private String premiumRequestMessage(String selectedPlan, EditText nameInput, EditText usernameInput,
                                        RadioGroup paymentChoice, RadioButton transfer, TextView paymentError) {
        String customerName = nameInput.getText().toString().trim();
        if (customerName.isEmpty()) {
            nameInput.setError("Escribe tu nombre");
            nameInput.requestFocus();
            return null;
        }
        int selectedPayment = paymentChoice.getCheckedRadioButtonId();
        if (selectedPayment == View.NO_ID) {
            paymentError.setVisibility(View.VISIBLE);
            return null;
        }
        String payment = selectedPayment == transfer.getId() ? "Transfermóvil" : "Saldo móvil";
        String telegramUsername = usernameInput.getText().toString().trim();
        if (telegramUsername.isEmpty()) telegramUsername = "No indicado";
        return "Hola, deseo solicitar este plan Premium:\n\n"
                + "Plan: " + selectedPlan + "\n"
                + "Nombre: " + customerName + "\n"
                + "Usuario de Telegram: " + telegramUsername + "\n"
                + "Método de pago: " + payment + "\n\n"
                + "Realizaré el pago por el método indicado y enviaré la captura del comprobante por este chat para revisión y activación manual.";
    }

    private void openTelegramChat(String message) {
        Uri directUri = Uri.parse("tg://resolve")
                .buildUpon().appendQueryParameter("domain", "Niku_Blade")
                .appendQueryParameter("text", message).build();
        Uri webUri = Uri.parse("https://t.me/Niku_Blade")
                .buildUpon().appendQueryParameter("text", message).build();
        try {
            Intent telegram = new Intent(Intent.ACTION_VIEW, directUri);
            telegram.setPackage("org.telegram.messenger");
            startActivity(telegram);
        } catch (Exception directLinkUnavailable) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, webUri);
                fallback.setPackage("org.telegram.messenger");
                startActivity(fallback);
            } catch (Exception telegramUnavailable) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, webUri)); }
                catch (Exception e) {
                    Toast.makeText(this, "No se pudo abrir el chat @Niku_Blade en Telegram.", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void chooseFreePlan() {
        prefs.edit().putString(PREF_SELECTED_PLAN, "free").apply();
        continueToApp();
    }

    private void continueWithActivePlan() {
        if (!hasAiPremiumAccess()) {
            refreshAccountProfile();
            Toast.makeText(this, "Comprobando el estado de tu Premium…", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString(PREF_SELECTED_PLAN, "premium").apply();
        continueToApp();
    }

    private void continueToApp() {
        showingPlansPage = false;
        if (token == null || token.isEmpty()) {
            showBotStep(true);
        } else if (prefs.getBoolean(PREF_TODUS_PIN_REQUESTED, false)) {
            showPinStep(true);
        } else if (!todusPhone.isEmpty() && !todusPassword.isEmpty()) {
            showHomeStep(true);
            handler.postDelayed(this::restoreToDusSession, 300L);
        } else {
            showPhoneStep(true);
        }
    }

    private void showBotStep(boolean animated) {
        showingPlansPage = false;
        viewingDashboard = false;
        dashboardItemsBox = null;
        deliveriesBox = null;
        LinearLayout page = createStepPage(1,
                relinkingBotAccount ? "Renueva el vínculo con el bot" : "Vincula tu bot",
                relinkingBotAccount
                        ? "Genera un código nuevo en el bot. Se conservarán tu plan y la sesión de ToDus."
                        : "Un código de un solo uso conecta esta app con tus entregas de Tod4S3.");
        LinearLayout box = card();
        box.addView(text(relinkingBotAccount ? "Vuelve a conectar Tod4S3" : "Conecta tu cuenta", 18, true));
        box.addView(bodyText("En el chat privado del bot pulsa “Vincular aplicación” (o envía /app). Pega aquí el código que recibas."
                + (relinkingBotAccount ? " No se cerrará tu sesión ToDus." : "")), margin(0, 8, 0, 16));
        codeInput = input("Código del bot", android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        codeInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        box.addView(codeInput, margin(0, 0, 0, 14));
        Button pair = primaryButton(relinkingBotAccount ? "Volver a vincular" : "Conectar con el bot");
        pair.setOnClickListener(v -> pairDevice(v));
        box.addView(pair);
        syncStatus = statusText(relinkingBotAccount
                ? "El vínculo actual se reemplazará solo cuando el nuevo código se acepte."
                : "El código caduca a los 10 minutos.");
        box.addView(syncStatus, margin(0, 12, 0, 0));
        page.addView(box, margin(0, 20, 0, 0));
        page.addView(infoCard("Privacidad", "El código solo vincula tus entregas. La app no lee tus chats privados."), margin(0, 12, 0, 0));
        if (relinkingBotAccount) {
            Button cancel = secondaryButton("Cancelar y volver a la aplicación");
            cancel.setOnClickListener(v -> {
                relinkingBotAccount = false;
                showDashboard(dashboardFilter, true);
            });
            page.addView(cancel, margin(0, 12, 0, 0));
        }
        showPage(page, animated);
    }

    private void showPhoneStep(boolean animated) {
        showingPlansPage = false;
        viewingDashboard = false;
        dashboardItemsBox = null;
        deliveriesBox = null;
        LinearLayout page = createStepPage(2, "Tu número de ToDus", "Te enviaremos un código para confirmar que la cuenta es tuya.");
        LinearLayout box = card();
        box.addView(text("¿A qué número lo enviamos?", 18, true));
        box.addView(bodyText("Escribe el número asociado a tu cuenta ToDus."), margin(0, 8, 0, 16));
        phoneInput = input("Número de teléfono", android.text.InputType.TYPE_CLASS_PHONE);
        if (!todusPhone.isEmpty()) phoneInput.setText(todusPhone);
        box.addView(phoneInput, margin(0, 0, 0, 14));
        Button sendCode = primaryButton("Enviar código de ToDus");
        sendCode.setOnClickListener(v -> requestToDusPin(v));
        box.addView(sendCode);
        todusStatus = statusText(savedCredentialsCouldNotBeDecrypted
                ? "La sesión guardada no se pudo descifrar. Solicita un código nuevo para conectar ToDus."
                : "Usa el número de tu cuenta ToDus.");
        box.addView(todusStatus, margin(0, 12, 0, 0));
        page.addView(box, margin(0, 20, 0, 0));
        Button back = secondaryButton("Volver al paso del bot");
        back.setOnClickListener(v -> showBotStep(true));
        page.addView(back, margin(0, 12, 0, 0));
        showPage(page, animated);
    }

    private void showPinStep(boolean animated) {
        showingPlansPage = false;
        viewingDashboard = false;
        dashboardItemsBox = null;
        deliveriesBox = null;
        LinearLayout page = createStepPage(3, "Confirma tu número", "Escribe el código que ToDus envió a tu cuenta.");
        LinearLayout box = card();
        box.addView(text("Código de verificación", 18, true));
        box.addView(bodyText("Número: " + (todusPhone.isEmpty() ? "" : todusPhone)), margin(0, 8, 0, 14));
        pinInput = input("Código recibido", android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        box.addView(pinInput, margin(0, 0, 0, 14));
        Button confirm = primaryButton("Confirmar y continuar");
        confirm.setOnClickListener(v -> validateToDusPin(v));
        box.addView(confirm);
        Button resend = secondaryButton("Reenviar código");
        resend.setOnClickListener(v -> requestToDusPin(v));
        box.addView(resend, margin(0, 8, 0, 0));
        todusStatus = statusText("El código solo se usa para iniciar sesión en este dispositivo.");
        box.addView(todusStatus, margin(0, 12, 0, 0));
        page.addView(box, margin(0, 20, 0, 0));
        Button changeNumber = secondaryButton("Usar otro número");
        changeNumber.setOnClickListener(v -> {
            prefs.edit().putBoolean(PREF_TODUS_PIN_REQUESTED, false).apply();
            showPhoneStep(true);
        });
        page.addView(changeNumber, margin(0, 12, 0, 0));
        showPage(page, animated);
    }

    private void showHomeStep(boolean animated) {
        showDashboard("all", animated);
    }

    private void showDownloadsStep(boolean animated) {
        showDashboard("completed", animated);
    }

    private void showDashboard(String filter, boolean animated) {
        boolean alreadyOnDashboard = viewingDashboard && currentPage != null && dashboardItemsBox != null;
        showingPlansPage = false;
        viewingDashboard = true;
        dashboardFilter = filter;
        viewingDownloadsTab = "completed".equals(filter);
        if (alreadyOnDashboard) {
            deliveriesBox = null;
            todusSessionStatusText = null;
            if (currentPage.getChildCount() > 1) {
                currentPage.removeViewAt(1);
                currentPage.addView(dashboardTabs(), 1, margin(0, 12, 0, 0));
            }
            if (currentPage.getChildCount() > 0) {
                currentPage.removeViewAt(0);
                currentPage.addView(dashboardToolbar(), 0, margin(0, 0, 0, 0));
            }
            if (dashboardStatusText != null) {
                dashboardStatusText.setText("all".equals(filter) ? "Entregas recibidas por ToDus" : "Descargas de Tod4S3 Sync");
            }
            renderDashboardItems();
            if (token != null && !token.isEmpty()) {
                refreshDeliveries();
                refreshAppTasks();
            }
            return;
        }
        deliveriesBox = null;
        todusSessionStatusText = null;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(12), dp(14), dp(24));
        page.addView(dashboardToolbar(), margin(0, 0, 0, 0));
        page.addView(dashboardTabs(), margin(0, 12, 0, 0));
        View tabDivider = new View(this);
        tabDivider.setBackgroundColor(borderColor());
        page.addView(tabDivider, new LinearLayout.LayoutParams(-1, dp(1)));
        TextView status = bodyText("all".equals(filter) ? "Entregas recibidas por ToDus" : "Descargas de Tod4S3 Sync");
        status.setTextSize(12);
        dashboardStatusText = status;
        page.addView(status, margin(2, 12, 2, 0));
        dashboardPlanStatusText = bodyText(accountPlanStatusText() + "  ·  Toca para ver planes");
        dashboardPlanStatusText.setTextSize(11);
        dashboardPlanStatusText.setTextColor(accentColor());
        dashboardPlanStatusText.setContentDescription("Ver planes y ofertas");
        dashboardPlanStatusText.setOnClickListener(v -> showPlansStep(true));
        page.addView(dashboardPlanStatusText, margin(2, 5, 2, 0));
        quotaUsageBox = new LinearLayout(this);
        quotaUsageBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(quotaUsageBox, margin(0, 9, 0, 0));
        quotaProgressBar = null;
        quotaUsageText = null;
        quotaResetText = null;
        updateQuotaUsageCard();
        dashboardItemsBox = new LinearLayout(this);
        dashboardItemsBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(dashboardItemsBox, margin(0, 12, 0, 0));
        showPage(page, animated);
        showDashboardFab();
        renderDashboardItems();
        if (token != null && !token.isEmpty()) {
            handler.postDelayed(() -> {
                refreshDeliveries();
                refreshAppTasks();
                refreshAccountProfile();
            }, animated ? 260L : 0L);
        }
    }

    private LinearLayout dashboardToolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button menu = secondaryButton("☰");
        menu.setTextSize(20);
        menu.setMinWidth(dp(44));
        menu.setOnClickListener(this::showNavigationDrawer);
        bar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(46)));
        TextView title = text("Tod4S3 Sync", 18, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLayout.setMargins(dp(10), 0, dp(4), 0);
        bar.addView(title, titleLayout);
        Button search = secondaryButton("⌕");
        search.setTextSize(21);
        search.setMinWidth(dp(42));
        search.setOnClickListener(v -> showDashboardSearch());
        bar.addView(search, new LinearLayout.LayoutParams(dp(44), dp(44)));
        Button sort = secondaryButton(sortOldestFirst ? "↑" : "↓");
        sort.setTextSize(17);
        sort.setMinWidth(dp(42));
        sort.setOnClickListener(v -> {
            sortOldestFirst = !sortOldestFirst;
            showDashboard(dashboardFilter, true);
        });
        LinearLayout.LayoutParams sortLayout = new LinearLayout.LayoutParams(dp(44), dp(44));
        sortLayout.setMargins(dp(5), 0, 0, 0);
        bar.addView(sort, sortLayout);
        LinearLayout.LayoutParams accountLayout = new LinearLayout.LayoutParams(dp(52), dp(52));
        accountLayout.setMargins(dp(5), 0, 0, 0);
        bar.addView(accountMenuButton(), accountLayout);
        return bar;
    }

    private LinearLayout dashboardTabs() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        String[][] options = {{"all", "# ToDus"}, {"active", "↓ Activas"}, {"completed", "✓ Completadas"}};
        float itemWeight = 1f;
        for (String[] option : options) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            boolean selected = option[0].equals(dashboardFilter);
            item.setPadding(dp(4), dp(2), dp(4), dp(2));
            item.setBackground(roundRect(selected ? accentColor() : raisedSurfaceColor(), 24,
                    selected ? accentColor() : borderColor()));
            TextView label = text(option[1], 13, option[0].equals(dashboardFilter));
            label.setGravity(Gravity.CENTER);
            label.setTextColor(selected ? onAccentColor() : secondaryTextColor());
            item.addView(label, new LinearLayout.LayoutParams(-1, dp(44)));
            item.setOnClickListener(v -> showDashboard(option[0], true));
            LinearLayout.LayoutParams itemLayout = new LinearLayout.LayoutParams(0, dp(50), itemWeight);
            itemLayout.setMargins(dp(4), 0, dp(4), 0);
            tabs.addView(item, itemLayout);
        }
        if (hasAiPremiumAccess()) {
            LinearLayout aiItem = new LinearLayout(this);
            aiItem.setOrientation(LinearLayout.VERTICAL);
            aiItem.setGravity(Gravity.CENTER);
            aiItem.setPadding(dp(2), dp(2), dp(2), dp(2));
            aiItem.setBackground(roundRect(raisedSurfaceColor(), 24, borderColor()));
            TextView label = text("✦ AiTod4S3", 10, true);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setTextColor(Color.rgb(255, 221, 141));
            aiItem.addView(label, new LinearLayout.LayoutParams(-1, dp(44)));
            aiItem.setContentDescription("Abrir AiTod4S3, disponible con Premium");
            aiItem.setOnClickListener(v -> showAiChat(true));
            LinearLayout.LayoutParams aiLayout = new LinearLayout.LayoutParams(0, dp(50), 1f);
            aiLayout.setMargins(dp(4), 0, dp(4), 0);
            tabs.addView(aiItem, aiLayout);
        }
        return tabs;
    }

    private void showDashboardFab() {
        if (appRoot == null) return;
        if (dashboardFab != null) appRoot.removeView(dashboardFab);
        TextView fab = text("+", 32, false);
        fab.setGravity(Gravity.CENTER);
        fab.setTextColor(onAccentColor());
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(accentColor());
        fab.setBackground(circle);
        fab.setElevation(dp(8));
        fab.setContentDescription("Nueva descarga");
        fab.setOnClickListener(v -> openNewDownloadDialog());
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.BOTTOM | Gravity.END);
        layout.setMargins(0, 0, dp(20), dp(28));
        appRoot.addView(fab, layout);
        dashboardFab = fab;
    }

    private void showDashboardSearch() {
        EditText query = input("Buscar descargas", android.text.InputType.TYPE_CLASS_TEXT);
        query.setText(dashboardSearch);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Buscar")
                .setView(query)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Buscar", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(surfaceColor()));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                dashboardSearch = query.getText().toString().trim();
                dialog.dismiss();
                renderDashboardItems();
            });
        });
        dialog.show();
    }

    private void renderDashboardItems() {
        if (!viewingDashboard || dashboardItemsBox == null) return;
        dashboardItemsBox.removeAllViews();
        int shown = 0;
        boolean toDusTab = "all".equals(dashboardFilter);
        if (toDusTab) addToDusSessionStatus(dashboardItemsBox);
        shown += renderToDusDownloadTasks();
        if ("active".equals(dashboardFilter)) {
            shown += renderDirectLinkTasks();
        }
        if (!toDusTab) {
            ArrayList<JSONObject> tasks = new ArrayList<>();
            for (int i = 0; i < latestAppTaskRows.length(); i++) {
                JSONObject row = latestAppTaskRows.optJSONObject(i);
                if (row == null) continue;
                String state = row.optString("state", "queued");
                boolean active = state.equals("queued") || state.equals("downloading")
                        || state.equals("uploading") || state.equals("cancelling");
                boolean done = state.equals("done") || state.equals("error") || state.equals("paused");
                if ("active".equals(dashboardFilter) && !active) continue;
                if ("completed".equals(dashboardFilter) && !done) continue;
                String searchText = (row.optString("filename", "") + " " + row.optString("task_id", ""))
                        .toLowerCase(java.util.Locale.ROOT);
                if (!dashboardSearch.isEmpty() && !searchText.contains(dashboardSearch.toLowerCase(java.util.Locale.ROOT))) continue;
                tasks.add(row);
            }
            java.util.Collections.sort(tasks, (left, right) -> {
                long a = left.optLong("created_at", 0);
                long b = right.optLong("created_at", 0);
                return sortOldestFirst ? Long.compare(a, b) : Long.compare(b, a);
            });
            for (JSONObject row : tasks) {
                dashboardItemsBox.addView(appTaskCard(row), margin(0, 0, 0, 9));
                shown++;
            }
        }
        if (toDusTab && latestDeliveryRows.length() > 0) {
            TextView cloudTitle = text("Entregas de ToDus", 15, true);
            dashboardItemsBox.addView(cloudTitle, margin(2, 10, 0, 8));
            LinearLayout cloudBox = new LinearLayout(this);
            cloudBox.setOrientation(LinearLayout.VERTICAL);
            dashboardItemsBox.addView(cloudBox);
            deliveriesBox = cloudBox;
            renderDeliveries(latestDeliveryRows);
            shown += latestDeliveryRows.length();
        }
        if ("completed".equals(dashboardFilter) && savedDownloadRecords().length() > 0) {
            TextView downloadsTitle = text("Descargas guardadas", 15, true);
            dashboardItemsBox.addView(downloadsTitle, margin(2, 10, 0, 8));
            LinearLayout savedBox = new LinearLayout(this);
            savedBox.setOrientation(LinearLayout.VERTICAL);
            dashboardItemsBox.addView(savedBox);
            renderDownloadHistory(savedBox);
            shown += savedDownloadRecords().length();
        }
        if (shown == 0) addDashboardEmptyState(dashboardItemsBox);
    }

    private int renderToDusDownloadTasks() {
        ArrayList<ToDusDownloadTask> tasks;
        synchronized (toDusDownloadLock) {
            tasks = new ArrayList<>(toDusDownloadTasks.values());
        }
        java.util.Collections.sort(tasks, (left, right) -> sortOldestFirst
                ? Long.compare(left.updatedAt, right.updatedAt)
                : Long.compare(right.updatedAt, left.updatedAt));
        ArrayList<ToDusDownloadTask> visible = new ArrayList<>();
        for (ToDusDownloadTask task : tasks) {
            if (task.state.equals("done")) continue;
            if (!dashboardSearch.isEmpty() && !(task.filename + " " + task.taskId)
                    .toLowerCase(java.util.Locale.ROOT).contains(dashboardSearch.toLowerCase(java.util.Locale.ROOT))) continue;
            visible.add(task);
        }
        if (visible.isEmpty()) return 0;
        dashboardItemsBox.addView(text("Descargas ToDus en este teléfono", 15, true), margin(2, 8, 0, 8));
        for (ToDusDownloadTask task : visible) {
            dashboardItemsBox.addView(toDusDownloadTaskCard(task), margin(0, 0, 0, 9));
        }
        return visible.size();
    }

    private LinearLayout toDusDownloadTaskCard(ToDusDownloadTask task) {
        task.cardRefs = new DownloadCardRefs();
        LinearLayout item = buildActiveDownloadCard(task.filename, task.bytes, task.total,
                task.speedBytesPerSecond, task.state, task.cardRefs,
                () -> pauseToDusDownload(task.key), () -> resumeToDusDownload(task.key),
                () -> confirmCancelToDusDownload(task.key, task.filename));
        task.progressTextView = task.cardRefs.percent;
        task.progressBarView = task.cardRefs.progress;
        if (task.state.equals("error") && !task.error.isEmpty()) item.addView(bodyText(task.error), margin(0, 7, 0, 0));
        return item;
    }

    private static final class DownloadCardRefs {
        TextView size;
        TextView percent;
        TextView speed;
        TextView state;
        ProgressBar progress;
    }

    private LinearLayout buildActiveDownloadCard(String filename, long bytes, long total,
                                                  long speed, String state, DownloadCardRefs refs,
                                                  Runnable pause, Runnable resume, Runnable cancel) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(13), dp(12), dp(13), dp(12));
        item.setBackground(roundRect(Color.rgb(17, 21, 33), 20, Color.rgb(43, 49, 65)));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(iconBadge(R.drawable.ic_download_arrow, Color.rgb(30, 190, 206),
                Color.rgb(21, 27, 41)), new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(filename, 14, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(name);
        refs.size = text(downloadSizeLabel(bytes, total), 12, false);
        refs.size.setTextColor(Color.rgb(151, 160, 179));
        refs.size.setSingleLine(true);
        details.addView(refs.size, margin(0, 3, 0, 0));
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0, -2, 1f);
        detailsParams.setMargins(dp(9), 0, dp(5), 0);
        header.addView(details, detailsParams);

        refs.state = text("", 10, true);
        refs.state.setGravity(Gravity.CENTER);
        refs.state.setPadding(dp(7), dp(7), dp(7), dp(7));
        refs.state.setMinWidth(dp(72));
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-2, dp(34));
        stateParams.setMargins(0, 0, dp(5), 0);
        header.addView(refs.state, stateParams);

        boolean running = state.equals("downloading") || state.equals("saving") || state.equals("pausing");
        TextView action = downloadCircleButton(running ? "Ⅱ" : "▶",
                running ? Color.rgb(243, 177, 50) : Color.rgb(43, 203, 190),
                running ? "Pausar descarga" : "Reanudar descarga");
        action.setOnClickListener(v -> { if (running) pause.run(); else resume.run(); });
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        actionParams.setMargins(0, 0, dp(4), 0);
        header.addView(action, actionParams);

        TextView more = downloadCircleButton("⋯", Color.rgb(165, 174, 190), "Más opciones");
        more.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setItems(new String[]{"Cancelar descarga"}, (dialog, which) -> cancel.run())
                .show());
        header.addView(more, new LinearLayout.LayoutParams(dp(32), dp(36)));
        item.addView(header);

        refs.progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        refs.progress.setMax(100);
        refs.progress.setProgressTintList(ColorStateList.valueOf(Color.rgb(198, 32, 48)));
        refs.progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(35, 41, 55)));
        refs.progress.setIndeterminateTintList(ColorStateList.valueOf(Color.rgb(198, 32, 48)));
        int percent = total > 0 ? (int) Math.min(100L, Math.max(0L, bytes * 100L / total)) : 0;
        refs.progress.setProgress(percent);
        refs.progress.setIndeterminate(running && total <= 0);
        item.addView(refs.progress, margin(dp(2), 12, dp(2), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        refs.percent = text(percent + "%", 13, true);
        refs.percent.setTextColor(Color.rgb(222, 77, 83));
        refs.speed = text(downloadSpeedLabel(speed), 13, true);
        refs.speed.setTextColor(Color.rgb(222, 77, 83));
        bottom.addView(refs.percent, new LinearLayout.LayoutParams(0, -2, 1f));
        refs.speed.setGravity(Gravity.END);
        bottom.addView(refs.speed);
        item.addView(bottom, margin(dp(2), 7, dp(2), 0));
        updateDownloadCardViews(refs, bytes, total, speed, state);
        return item;
    }

    private TextView downloadCircleButton(String label, int tint, String description) {
        TextView button = text(label, 16, true);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(tint);
        button.setContentDescription(description);
        button.setBackground(roundRect(Color.rgb(20, 25, 38), 50, tint));
        return button;
    }

    private String downloadSizeLabel(long bytes, long total) {
        if (total > 0) return formatBytes(Math.max(0L, bytes)) + " de " + formatBytes(total);
        return formatBytes(Math.max(0L, bytes));
    }

    private String downloadSpeedLabel(long bytesPerSecond) {
        return bytesPerSecond > 0 ? formatBytes(bytesPerSecond) + "/s" : "— KB/s";
    }

    private void updateDownloadCardViews(DownloadCardRefs refs, long bytes, long total,
                                         long speed, String state) {
        if (refs == null) return;
        int percent = total > 0 ? (int) Math.min(100L, Math.max(0L, bytes * 100L / total)) : 0;
        if (refs.size != null) refs.size.setText(downloadSizeLabel(bytes, total));
        if (refs.percent != null) refs.percent.setText(percent + "%");
        if (refs.speed != null) refs.speed.setText(downloadSpeedLabel(speed));
        if (refs.progress != null) {
            boolean running = state.equals("downloading") || state.equals("saving") || state.equals("pausing");
            refs.progress.setIndeterminate(running && total <= 0);
            refs.progress.setProgress(percent);
        }
        if (refs.state != null) {
            String label = state.equals("downloading") ? "DESCARGANDO"
                    : state.equals("saving") ? "GUARDANDO"
                    : state.equals("pausing") ? "PAUSANDO"
                    : state.equals("deleting") ? "CANCELANDO"
                    : state.equals("error") ? "ERROR" : "PAUSADA";
            int foreground = state.equals("paused") ? Color.rgb(243, 177, 50) : Color.rgb(222, 77, 83);
            int background = state.equals("paused") ? Color.rgb(44, 37, 28) : Color.rgb(47, 25, 34);
            refs.state.setText(label);
            refs.state.setTextColor(foreground);
            refs.state.setBackground(roundRect(background, 9, foreground));
        }
    }

    private int renderDirectLinkTasks() {
        ArrayList<DirectLinkTask> tasks;
        synchronized (directLinkDownloadLock) {
            tasks = new ArrayList<>(directLinkTasks.values());
        }
        java.util.Collections.sort(tasks, (left, right) -> sortOldestFirst
                ? Long.compare(left.updatedAt, right.updatedAt)
                : Long.compare(right.updatedAt, left.updatedAt));
        ArrayList<DirectLinkTask> visible = new ArrayList<>();
        for (DirectLinkTask task : tasks) {
            if (task.state.equals("done")) continue;
            if (!dashboardSearch.isEmpty() && !task.filename.toLowerCase(java.util.Locale.ROOT)
                    .contains(dashboardSearch.toLowerCase(java.util.Locale.ROOT))) continue;
            visible.add(task);
        }
        if (visible.isEmpty()) return 0;
        dashboardItemsBox.addView(text("Descargas en este teléfono", 15, true), margin(2, 8, 0, 8));
        for (DirectLinkTask task : visible) {
            dashboardItemsBox.addView(directLinkTaskCard(task), margin(0, 0, 0, 9));
        }
        return visible.size();
    }

    private LinearLayout directLinkTaskCard(DirectLinkTask task) {
        task.cardRefs = new DownloadCardRefs();
        LinearLayout item = buildActiveDownloadCard(task.filename, task.bytes, task.total,
                task.speedBytesPerSecond, task.state, task.cardRefs,
                () -> pauseDirectLinkTask(task.key), () -> resumeDirectLinkTask(task.key),
                () -> confirmDeleteDirectLinkTask(task.key, task.filename));
        task.progressTextView = task.cardRefs.percent;
        task.progressBarView = task.cardRefs.progress;
        if (task.state.equals("error") && !task.error.isEmpty()) {
            item.addView(bodyText(task.error), margin(0, 7, 0, 0));
        }
        return item;
    }

    private LinearLayout appTaskCard(JSONObject row) {
        LinearLayout item = card();
        item.setPadding(dp(14), dp(12), dp(14), dp(12));
        String taskId = row.optString("task_id", "");
        String filename = row.optString("filename", "");
        if (filename.isEmpty()) filename = "Descarga " + (taskId.length() > 6 ? taskId.substring(0, 6) : taskId);
        item.addView(text(filename, 14, true));
        String state = row.optString("state", "queued");
        String stateLabel = state.equals("queued") ? "En cola"
                : state.equals("downloading") ? "Descargando"
                : state.equals("uploading") ? "Subiendo a ToDus"
                : state.equals("done") ? "Completada"
                : state.equals("paused") ? "Pausada"
                : state.equals("cancelling") ? "Cancelando"
                : "Error de descarga";
        TextView detail = bodyText(stateLabel + (row.optLong("size", 0) > 0 ? " · " + formatBytes(row.optLong("size", 0)) : ""));
        detail.setTextSize(12);
        item.addView(detail, margin(0, 4, 0, 0));
        if (state.equals("queued") || state.equals("downloading") || state.equals("uploading") || state.equals("cancelling")) {
            ProgressBar progress = new ProgressBar(this);
            progress.setIndeterminateTintList(ColorStateList.valueOf(accentColor()));
            item.addView(progress, margin(0, 8, 0, 0));
            Button cancel = secondaryButton("Cancelar");
            cancel.setOnClickListener(v -> cancelAppTask(taskId));
            item.addView(cancel, margin(0, 8, 0, 0));
        } else if (state.equals("error")) {
            item.addView(bodyText(row.optString("error", "La tarea falló; puedes volver a enviarla.")), margin(0, 5, 0, 0));
        }
        return item;
    }

    private void addDashboardEmptyState(LinearLayout container) {
        boolean toDusTab = "all".equals(dashboardFilter);
        LinearLayout empty = card();
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(dp(22), dp(34), dp(22), dp(30));
        TextView icon = text("⇩", 46, true);
        icon.setGravity(Gravity.CENTER);
        icon.setTextColor(accentColor());
        empty.addView(icon, new LinearLayout.LayoutParams(-1, dp(62)));
        TextView title = text(toDusTab ? "No hay entregas de ToDus" : "No hay descargas", 18, true);
        title.setGravity(Gravity.CENTER);
        empty.addView(title, margin(0, 8, 0, 0));
        TextView hint = bodyText(toDusTab
                ? "Cuando el bot te entregue archivos por ToDus, aparecerán aquí."
                : "Pega el enlace que recibiste del bot para descargarlo en el teléfono.");
        hint.setGravity(Gravity.CENTER);
        empty.addView(hint, margin(0, 7, 0, toDusTab ? 0 : 14));
        if (!toDusTab) {
            Button add = primaryButton("Nueva descarga");
            add.setOnClickListener(v -> openNewDownloadDialog());
            empty.addView(add, margin(0, 0, 0, 0));
        }
        container.addView(empty, margin(0, 24, 0, 0));
    }

    private void cancelAppTask(String taskId) {
        if (token == null || token.isEmpty() || taskId == null || taskId.isEmpty()) return;
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("task_id", taskId);
                request("POST", "/api/app/tasks/cancel", body.toString(), token);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Solicitud de cancelación enviada.", Toast.LENGTH_SHORT).show();
                    refreshAppTasks();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "No se pudo cancelar la tarea.", Toast.LENGTH_LONG).show());
            }
        }, "app-task-cancel").start();
    }

    private void openNewDownloadDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(10), dp(18), dp(12));
        content.addView(bodyText("Pega aquí el enlace de descarga que te entregó el bot para guardarlo en el teléfono."));
        EditText linkInput = input("Pega aquí el enlace del bot", android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        linkInput.setSingleLine(false);
        linkInput.setMinLines(2);
        linkInput.setMaxLines(5);
        linkInput.setGravity(Gravity.TOP | Gravity.START);
        content.addView(linkInput, margin(0, 12, 0, 0));
        TextView status = bodyText("El botón principal descarga aquí el archivo final que recibiste del bot; no lo vuelve a enviar a la cola.");
        status.setTextSize(12);
        content.addView(status, margin(0, 10, 0, 0));
        TextView localDownloadStatus = bodyText("Al iniciar, aparecerá en Activas con porcentaje y botones para pausar, reanudar o eliminar.");
        localDownloadStatus.setTextSize(12);
        content.addView(localDownloadStatus, margin(0, 12, 0, 0));
        Button localDownload = primaryButton("Descargar enlace recibido");
        final AlertDialog[] dialogRef = new AlertDialog[1];
        localDownload.setOnClickListener(v -> {
            ArrayList<String> links = extractLinks(linkInput.getText().toString());
            if (links.size() != 1) {
                localDownloadStatus.setText("Pega un solo enlace HTTPS recibido del bot para descargarlo aquí.");
                return;
            }
            if (!links.get(0).toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
                localDownloadStatus.setText("La descarga directa requiere un enlace HTTPS.");
                return;
            }
            String filename = filenameFromDirectLink(links.get(0));
            if (downloadLinkLocally(links.get(0), filename)) {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                showDashboard("active", true);
            }
        });
        content.addView(localDownload, margin(0, 8, 0, 0));
        Button submit = secondaryButton("Enviar otro enlace de origen al bot");
        content.addView(submit, margin(0, 10, 0, 0));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Nueva descarga")
                .setView(content)
                .setNegativeButton("Cerrar", null)
                .create();
        dialogRef[0] = dialog;
        submit.setOnClickListener(v -> {
            ArrayList<String> links = extractLinks(linkInput.getText().toString());
            if (links.isEmpty()) {
                status.setText("Pega un enlace HTTP/HTTPS válido.");
                return;
            }
            if (links.size() > 20) {
                status.setText("Puedes enviar como máximo 20 enlaces a la vez.");
                return;
            }
            if (token == null || token.isEmpty()) {
                dialog.dismiss();
                if (prefs.getString(PREF_SELECTED_PLAN, "").isEmpty()) showPlansStep(true);
                else showBotStep(true);
                return;
            }
            submitLinks(links, status, submit, dialog);
        });
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(surfaceColor()));
                dialog.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - dp(28), -2);
            }
        });
        dialog.show();
    }

    private boolean isTrustedS3Link(String link) {
        try {
            Uri uri = Uri.parse(link);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && "s3.todus.cu".equalsIgnoreCase(host)
                    && uri.getPath() != null && uri.getPath().startsWith("/stream/");
        } catch (Exception ignored) {
            return false;
        }
    }

    private String filenameFromDirectLink(String link) {
        String name = Uri.parse(link).getLastPathSegment();
        if (name == null || name.trim().isEmpty()) name = "archivo_descargado.bin";
        return safeName(name);
    }

    private boolean downloadLinkLocally(String url, String filename) {
        if (!hasPublicDownloadPermission()) return false;
        String key = downloadCacheKey(filename, new JSONArray().put(url));
        DirectLinkTask task;
        synchronized (directLinkDownloadLock) {
            task = directLinkTasks.get(key);
            if (task == null) task = new DirectLinkTask(key, url, filename);
            if (task.state.equals("downloading") || task.state.equals("saving") || task.state.equals("pausing")) {
                return true;
            }
        }
        return startDirectLinkTask(task);
    }

    private boolean hasPublicDownloadPermission() {
        if (!prefs.getString(PREF_DOWNLOAD_FOLDER, "").isEmpty()
                || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_STORAGE);
        Toast.makeText(this, "Permite guardar en Descargas y vuelve a iniciar la descarga.", Toast.LENGTH_LONG).show();
        return false;
    }

    private boolean startDirectLinkTask(DirectLinkTask task) {
        if (!hasPublicDownloadPermission()) return false;
        int maxConcurrent = Math.max(1, Math.min(5, prefs.getInt(PREF_DOWNLOAD_CONCURRENCY, 3)));
        int otherActive;
        synchronized (downloadConcurrencyLock) { otherActive = activeDownloadJobs; }
        Thread worker;
        synchronized (directLinkDownloadLock) {
            if (activeDirectLinkKeys.contains(task.key)) return true;
            if (activeDirectLinkJobs + otherActive >= maxConcurrent) {
                Toast.makeText(this, "Ya hay " + maxConcurrent + " descargas simultáneas. Espera o pausa una.", Toast.LENGTH_LONG).show();
                return false;
            }
            task.pauseRequested = false;
            task.deleteRequested = false;
            task.error = "";
            task.state = "downloading";
            task.updatedAt = System.currentTimeMillis();
            task.control = new ParallelRangeDownloader.DownloadControl();
            directLinkTasks.put(task.key, task);
            activeDirectLinkKeys.add(task.key);
            activeDirectLinkJobs++;
            worker = new Thread(() -> runDirectLinkTask(task), "direct-link-range-download");
            task.worker = worker;
            persistDirectLinkTasks();
        }
        worker.start();
        postDirectLinkTaskRefresh();
        return true;
    }

    private void runDirectLinkTask(DirectLinkTask task) {
        File workDir = new File(new File(getFilesDir(), "direct-link-downloads"), task.key);
        File downloaded = new File(workDir, "download.bin");
        DownloadTarget target = null;
        boolean saved = false;
        try {
            if (!workDir.exists() && !workDir.mkdirs()) {
                throw new IOException("No se pudo preparar el almacenamiento temporal.");
            }
            String transferToken = isTrustedS3Link(task.url) && todusToken != null ? todusToken : "";
            ParallelRangeDownloader.download(transferToken, task.url, task.url, downloaded, 0L,
                    (received, total) -> updateDirectLinkProgress(task, received, total), task.control);
            task.control.check();
            task.state = "saving";
            task.updatedAt = System.currentTimeMillis();
            persistDirectLinkTasks();
            postDirectLinkTaskRefresh();
            target = openPublicDownload(task.filename);
            long downloadedSize = downloaded.length();
            try (InputStream input = new FileInputStream(downloaded); OutputStream output = target.stream) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    task.control.check();
                    output.write(buffer, 0, count);
                }
            }
            task.control.check();
            finishPublicDownload(target);
            recordDownloadedFile(target, downloadedSize);
            saved = true;
            String savedName = target.name;
            notifyDownloadCompleted(savedName);
        } catch (Exception e) {
            if (target != null) deletePublicDownload(target);
            if (!task.pauseRequested && !task.deleteRequested) {
                task.state = "error";
                task.error = e.getMessage() == null ? "No se pudo completar la descarga." : e.getMessage();
                task.updatedAt = System.currentTimeMillis();
            }
        } finally {
            if (saved || task.deleteRequested) {
                deleteTree(workDir);
                synchronized (directLinkDownloadLock) { directLinkTasks.remove(task.key); }
            } else if (task.pauseRequested) {
                task.state = "paused";
                task.updatedAt = System.currentTimeMillis();
            }
            task.worker = null;
            task.control = null;
            releaseDirectLinkDownload(task.key);
            persistDirectLinkTasks();
            postDirectLinkTaskRefresh();
        }
    }

    private void updateDirectLinkProgress(DirectLinkTask task, long received, long total) {
        long now = System.currentTimeMillis();
        int percent = total > 0 ? (int) Math.min(100L, Math.max(0L, received * 100L / total)) : -1;
        boolean update;
        synchronized (directLinkDownloadLock) {
            task.bytes = Math.max(0L, received);
            task.total = Math.max(0L, total);
            if (task.speedSampleAt > 0L && now > task.speedSampleAt) {
                task.speedBytesPerSecond = Math.max(0L,
                        (task.bytes - task.speedSampleBytes) * 1000L / (now - task.speedSampleAt));
            }
            task.speedSampleAt = now;
            task.speedSampleBytes = task.bytes;
            update = percent != task.lastProgressPercent || now - task.lastPersistAt >= 800L;
            if (update) {
                task.lastProgressPercent = percent;
                task.lastPersistAt = now;
                task.updatedAt = now;
            }
        }
        if (update) {
            persistDirectLinkTasks();
            postDirectLinkProgressUpdate(task);
        }
    }

    private void postDirectLinkProgressUpdate(DirectLinkTask task) {
        runOnUiThread(() -> {
            DownloadCardRefs refs = task.cardRefs;
            if (!viewingDashboard || refs == null || refs.percent == null || refs.percent.getParent() == null) return;
            updateDownloadCardViews(refs, task.bytes, task.total, task.speedBytesPerSecond, task.state);
        });
    }

    private void pauseDirectLinkTask(String key) {
        DirectLinkTask task;
        synchronized (directLinkDownloadLock) {
            task = directLinkTasks.get(key);
            if (task == null || task.worker == null || task.control == null
                    || (!task.state.equals("downloading") && !task.state.equals("saving"))) return;
            task.pauseRequested = true;
            task.state = "pausing";
            task.updatedAt = System.currentTimeMillis();
            persistDirectLinkTasks();
        }
        task.control.cancel();
        task.worker.interrupt();
        postDirectLinkTaskRefresh();
    }

    private void resumeDirectLinkTask(String key) {
        DirectLinkTask task;
        synchronized (directLinkDownloadLock) { task = directLinkTasks.get(key); }
        if (task != null && (task.state.equals("paused") || task.state.equals("error"))) startDirectLinkTask(task);
    }

    private void confirmDeleteDirectLinkTask(String key, String filename) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar descarga")
                .setMessage("Se eliminarán la tarea y sus fragmentos temporales. El archivo solo se borra del teléfono si todavía no había terminado.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (dialog, which) -> deleteDirectLinkTask(key))
                .show();
    }

    private void deleteDirectLinkTask(String key) {
        DirectLinkTask task;
        synchronized (directLinkDownloadLock) {
            task = directLinkTasks.get(key);
            if (task == null) return;
            if (task.worker != null && task.control != null) {
                task.deleteRequested = true;
                task.pauseRequested = false;
                task.state = "deleting";
                task.updatedAt = System.currentTimeMillis();
                persistDirectLinkTasks();
            } else {
                directLinkTasks.remove(key);
                persistDirectLinkTasks();
            }
        }
        if (task.worker != null && task.control != null) {
            task.control.cancel();
            task.worker.interrupt();
        } else {
            deleteTree(new File(new File(getFilesDir(), "direct-link-downloads"), task.key));
            postDirectLinkTaskRefresh();
        }
    }

    private void postDirectLinkTaskRefresh() {
        runOnUiThread(() -> {
            if (viewingDashboard) renderDashboardItems();
        });
    }

    private void releaseDirectLinkDownload(String transferKey) {
        synchronized (directLinkDownloadLock) {
            activeDirectLinkKeys.remove(transferKey);
            activeDirectLinkJobs = Math.max(0, activeDirectLinkJobs - 1);
        }
    }

    private ArrayList<String> extractLinks(String raw) {
        ArrayList<String> links = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return links;
        Matcher matcher = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE).matcher(raw);
        while (matcher.find() && links.size() < 21) {
            String link = matcher.group().replaceAll("[.,;:!?)}\\]]+$", "");
            if (!link.isEmpty() && !links.contains(link)) links.add(link);
        }
        return links;
    }

    private void submitLinks(ArrayList<String> links, TextView status, Button submit, AlertDialog dialog) {
        submit.setEnabled(false);
        status.setText("Enviando " + links.size() + (links.size() == 1 ? " enlace a la cola…" : " enlaces a la cola…"));
        final String currentToken = token;
        new Thread(() -> {
            int accepted = 0;
            String error = null;
            for (String link : links) {
                try {
                    JSONObject body = new JSONObject().put("url", link);
                    request("POST", "/api/app/download", body.toString(), currentToken);
                    accepted++;
                } catch (Exception e) {
                    error = e.getMessage() == null ? "No se pudo enviar el enlace." : e.getMessage();
                    break;
                }
            }
            final int totalAccepted = accepted;
            final String failure = error;
            runOnUiThread(() -> {
                submit.setEnabled(true);
                if (totalAccepted > 0) {
                    dialog.dismiss();
                    Toast.makeText(this, totalAccepted + (totalAccepted == 1 ? " enlace añadido a la cola." : " enlaces añadidos a la cola."), Toast.LENGTH_LONG).show();
                    showDashboard("active", true);
                    refreshAppTasks();
                } else {
                    status.setText(failure == null ? "No se pudo enviar el enlace." : failure);
                }
            });
        }, "app-url-submit").start();
    }

    private void showNavigationDrawer(View anchor) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(22), dp(14), dp(18));
        content.setBackgroundColor(surfaceColor());
        LinearLayout account = new LinearLayout(this);
        account.setGravity(Gravity.CENTER_VERTICAL);
        account.setPadding(dp(10), dp(10), dp(10), dp(10));
        account.setBackground(roundRect(raisedSurfaceColor(), 20, borderColor()));
        FrameLayout badge = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(accentColor());
        badge.setBackground(circle);
        TextView menuGlyph = text("☰", 20, true);
        menuGlyph.setTextColor(onAccentColor());
        menuGlyph.setGravity(Gravity.CENTER);
        badge.addView(menuGlyph, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        account.addView(badge, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout accountText = new LinearLayout(this);
        accountText.setOrientation(LinearLayout.VERTICAL);
        accountText.addView(text(token == null || token.isEmpty() ? "Invitado" : "Tod4S3 Sync", 16, true));
        TextView linked = bodyText(token == null || token.isEmpty() ? "Toca para iniciar sesión" : "Cuenta vinculada");
        linked.setTextSize(12);
        accountText.addView(linked, margin(0, 3, 0, 0));
        LinearLayout.LayoutParams accountTextLayout = new LinearLayout.LayoutParams(0, -2, 1f);
        accountTextLayout.setMargins(dp(12), 0, 0, 0);
        account.addView(accountText, accountTextLayout);
        TextView accountAction = text(token == null || token.isEmpty() ? "+" : "↻", 18, true);
        accountAction.setTextColor(accentColor());
        account.addView(accountAction, new LinearLayout.LayoutParams(dp(34), dp(38)));
        content.addView(account, margin(0, 0, 0, 18));

        ScrollView drawerScroll = new ScrollView(this);
        drawerScroll.setFillViewport(true);
        drawerScroll.setSmoothScrollingEnabled(true);
        drawerScroll.setVerticalScrollBarEnabled(false);
        drawerScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        drawerScroll.setBackgroundColor(surfaceColor());
        drawerScroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        PopupWindow popup = new PopupWindow(drawerScroll, dp(296), -1, true);
        popup.setBackgroundDrawable(new ColorDrawable(surfaceColor()));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(18));
        if (token == null || token.isEmpty()) {
            account.setOnClickListener(v -> {
                popup.dismiss();
                if (prefs.getString(PREF_SELECTED_PLAN, "").isEmpty()) showPlansStep(true);
                else showBotStep(true);
            });
        }
        addDrawerRow(content, popup, "★", "Planes y ofertas", "Ver opciones y precios", () -> showPlansStep(true));
        addDrawerRow(content, popup, "▣", "Archivos en la Nube", "Entregas del bot", () -> showDashboard("all", true));
        if (token != null && !token.isEmpty()) {
            addDrawerRow(content, popup, "☁", "Vaciar mi nube", "Eliminar mis archivos de nube", this::confirmClearMyCloud);
        }
        addDrawerRow(content, popup, "↓", "Descargas", "Archivos guardados en el teléfono", () -> showDashboard("completed", true));
        addDrawerRow(content, popup, "▥", "Estadísticas", "Uso y actividad", () -> showStats(true));
        if (token != null && !token.isEmpty()) {
            addDrawerRow(content, popup, "▤", "Registros de la App", "Consultar actividad y errores", () -> showAppLogs(true));
        }
        addDrawerRow(content, popup, "◉", "Grupo de Telegram", "Dark Zone", () -> openExternalUrl("https://t.me/Dark_Zone_666"));
        addDrawerRow(content, popup, "↗", "Enlace no disponible", "Reportar pedido caído", () -> shareReport("Pedido caído"));
        addDrawerRow(content, popup, "⚠", "Reportar un error", "Enviar detalles al soporte", () -> shareReport("Error de Tod4S3 Sync"));
        addDrawerRow(content, popup, "i", "Acerca de", "Versión y privacidad", () -> showAbout(true));
        addDrawerRow(content, popup, "⚙", "Ajustes", "Tema, carpeta y almacenamiento", () -> showSettings(true));
        View divider = new View(this);
        divider.setBackgroundColor(borderColor());
        content.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        if (token != null && !token.isEmpty()) {
            addDrawerRow(content, popup, "↻", "Volver a vincular con el bot",
                    "Renovar acceso de Telegram sin cerrar ToDus", () -> {
                        relinkingBotAccount = true;
                        showBotStep(true);
                    });
        }
        addDrawerRow(content, popup, "↔", "Cambiar cuenta ToDus", "Conectar otro número", () ->
                new AlertDialog.Builder(this).setTitle("Cambiar cuenta ToDus")
                        .setMessage("Se quitará de este teléfono la sesión guardada de ToDus. Tus descargas no se borrarán.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Continuar", (dialog, which) -> changeToDusAccount()).show());
        if (token != null && !token.isEmpty()) {
            addDrawerRow(content, popup, "⇥", "Cerrar sesión", "Desvincular este teléfono", this::confirmLogout);
        }
        popup.showAtLocation(appRoot, Gravity.TOP | Gravity.START, 0, 0);
    }

    private void addDrawerRow(LinearLayout parent, PopupWindow popup, String icon, String title,
                              String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setBackground(roundRect(raisedSurfaceColor(), 14, borderColor()));
        TextView glyph = text(icon, 19, true);
        glyph.setGravity(Gravity.CENTER);
        glyph.setTextColor(accentColor());
        row.addView(glyph, new LinearLayout.LayoutParams(dp(34), dp(36)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 13, true));
        TextView detail = bodyText(subtitle);
        detail.setTextSize(10);
        labels.addView(detail, margin(0, 2, 0, 0));
        LinearLayout.LayoutParams labelsLayout = new LinearLayout.LayoutParams(0, -2, 1f);
        labelsLayout.setMargins(dp(8), 0, 0, 0);
        row.addView(labels, labelsLayout);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        parent.addView(row, margin(0, 0, 0, 7));
    }

    private void confirmClearMyCloud() {
        new AlertDialog.Builder(this)
                .setTitle("Vaciar mi nube")
                .setMessage("Se borrarán los archivos en la nube y sus enlaces registrados para tu cuenta. Las descargas que ya guardaste en el teléfono no se borrarán. Esta acción no se puede deshacer.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Vaciar mi nube", (dialog, which) -> clearMyCloud())
                .show();
    }

    private void clearMyCloud() {
        String currentToken = token;
        if (currentToken == null || currentToken.isEmpty()) {
            Toast.makeText(this, "Vincula primero la aplicación con el bot.", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Borrando tus archivos de nube…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                JSONObject result = request("POST", "/api/app/cloud/clear", "{}", currentToken);
                int deleted = result.optInt("deleted_files", 0);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Nube vaciada: " + deleted + " archivo(s) eliminado(s).", Toast.LENGTH_LONG).show();
                    refreshDeliveries();
                    if (viewingDashboard) renderDashboardItems();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "No se pudo vaciar la nube. Los registros se conservaron para reintentar.", Toast.LENGTH_LONG).show());
            }
        }, "clear-user-cloud").start();
    }

    private LinearLayout sectionHeader(String title) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = secondaryButton("←");
        back.setTextSize(20);
        back.setOnClickListener(v -> showDashboard("all", true));
        bar.addView(back, new LinearLayout.LayoutParams(dp(46), dp(44)));
        TextView heading = text(title, 20, true);
        LinearLayout.LayoutParams headingLayout = new LinearLayout.LayoutParams(0, -2, 1f);
        headingLayout.setMargins(dp(12), 0, 0, 0);
        bar.addView(heading, headingLayout);
        return bar;
    }

    private void addSettingsHeading(LinearLayout page, String value) {
        TextView heading = text(value, 13, true);
        heading.setTextColor(accentColor());
        page.addView(heading, margin(2, 20, 0, 8));
    }

    private void showSettings(boolean animated) {
        boolean preserveScroll = !viewingDashboard && !showingPlansPage && currentPage != null;
        int previousScrollY = appScroll.getScrollY();
        viewingDashboard = false;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(14), dp(16), dp(30));
        LinearLayout header = sectionHeader("Ajustes");
        if (token != null && !token.isEmpty()) {
            LinearLayout.LayoutParams avatarLayout = new LinearLayout.LayoutParams(dp(56), dp(56));
            avatarLayout.setMargins(dp(8), 0, 0, 0);
            header.addView(accountMenuButton(), avatarLayout);
        }
        page.addView(header);

        addSettingsHeading(page, "APARIENCIA");
        LinearLayout appearance = card();
        appearance.addView(text("Tema de la interfaz", 15, true));
        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        String currentTheme = prefs.getString(PREF_UI_THEME, "dark");
        String[][] themes = {{"system", "Sistema"}, {"light", "Claro"}, {"dark", "Oscuro"}};
        for (String[] theme : themes) {
            Button choice = theme[0].equals(currentTheme) ? primaryButton(theme[1]) : secondaryButton(theme[1]);
            choice.setTextSize(11);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            lp.setMargins(dp(3), dp(8), dp(3), 0);
            themeRow.addView(choice, lp);
            choice.setOnClickListener(v -> {
                prefs.edit().putString(PREF_UI_THEME, theme[0]).apply();
                appRoot.setBackgroundColor(backgroundColor());
                appScroll.setBackgroundColor(backgroundColor());
                applySystemBarTheme();
                showSettings(false);
            });
        }
        appearance.addView(themeRow);
        appearance.addView(text("Color de acento", 14, true), margin(0, 16, 0, 4));
        String[] accents = {
                "#C62828", "#D32F2F", "#E65100", "#EF6C00", "#F9A825", "#558B2F",
                "#2E7D32", "#1B5E20", "#00897B", "#009688", "#00838F", "#00ACC1",
                "#0277BD", "#1565C0", "#1976D2", "#0D47A1", "#3949AB", "#5E35B1",
                "#6A1B9A", "#8E24AA", "#AD1457", "#C2185B", "#455A64", "#607D8B"
        };
        String selectedAccent = prefs.getString(PREF_UI_ACCENT, "#C62828");
        for (int start = 0; start < accents.length; start += 6) {
            LinearLayout swatchRow = new LinearLayout(this);
            swatchRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = start; i < Math.min(start + 6, accents.length); i++) {
                final String colorHex = accents[i];
                View swatch = new View(this);
                GradientDrawable swatchShape = new GradientDrawable();
                swatchShape.setShape(GradientDrawable.OVAL);
                swatchShape.setColor(Color.parseColor(colorHex));
                swatchShape.setStroke(dp(colorHex.equalsIgnoreCase(selectedAccent) ? 3 : 1),
                        colorHex.equalsIgnoreCase(selectedAccent) ? primaryTextColor() : borderColor());
                swatch.setBackground(swatchShape);
                swatch.setContentDescription("Elegir acento " + colorHex);
                swatch.setOnClickListener(v -> {
                    prefs.edit().putString(PREF_UI_ACCENT, colorHex).apply();
                    showSettings(false);
                });
                LinearLayout.LayoutParams swatchLayout = new LinearLayout.LayoutParams(0, dp(38), 1f);
                swatchLayout.setMargins(dp(3), dp(6), dp(3), dp(4));
                swatchRow.addView(swatch, swatchLayout);
            }
            appearance.addView(swatchRow);
        }
        page.addView(appearance, margin(0, 0, 0, 0));

        addSettingsHeading(page, "DESCARGAS");
        LinearLayout downloads = card();
        int concurrency = Math.max(1, Math.min(5, prefs.getInt(PREF_DOWNLOAD_CONCURRENCY, 3)));
        TextView concurrencyLabel = text("Descargas simultáneas: " + concurrency, 15, true);
        downloads.addView(concurrencyLabel);
        LinearLayout concurrencyControls = new LinearLayout(this);
        concurrencyControls.setGravity(Gravity.CENTER_VERTICAL);
        Button decreaseConcurrency = secondaryButton("−");
        Button increaseConcurrency = secondaryButton("+");
        TextView concurrencyValue = text(String.valueOf(concurrency), 16, true);
        concurrencyValue.setGravity(Gravity.CENTER);
        concurrencyControls.addView(decreaseConcurrency, new LinearLayout.LayoutParams(dp(42), dp(40)));
        concurrencyControls.addView(concurrencyValue, new LinearLayout.LayoutParams(dp(44), dp(40)));
        concurrencyControls.addView(increaseConcurrency, new LinearLayout.LayoutParams(dp(42), dp(40)));
        LinearLayout.LayoutParams controlsLayout = new LinearLayout.LayoutParams(-2, dp(40));
        controlsLayout.gravity = Gravity.END;
        downloads.addView(concurrencyControls, controlsLayout);
        SeekBar concurrencyBar = new SeekBar(this);
        concurrencyBar.setMax(4);
        concurrencyBar.setProgress(concurrency - 1);
        concurrencyBar.setProgressTintList(ColorStateList.valueOf(accentColor()));
        concurrencyBar.setThumbTintList(ColorStateList.valueOf(accentColor()));
        concurrencyBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 1;
                concurrencyLabel.setText("Descargas simultáneas: " + value);
                concurrencyValue.setText(String.valueOf(value));
                prefs.edit().putInt(PREF_DOWNLOAD_CONCURRENCY, value).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        decreaseConcurrency.setOnClickListener(v -> concurrencyBar.setProgress(Math.max(0, concurrencyBar.getProgress() - 1)));
        increaseConcurrency.setOnClickListener(v -> concurrencyBar.setProgress(Math.min(concurrencyBar.getMax(), concurrencyBar.getProgress() + 1)));
        downloads.addView(concurrencyBar, margin(0, 4, 0, 0));
        String folder = prefs.getString(PREF_DOWNLOAD_FOLDER, "");
        TextView folderLabel = bodyText(folder.isEmpty() ? "Carpeta base: Descargas del teléfono" : "Carpeta base personalizada seleccionada");
        downloads.addView(folderLabel, margin(0, 12, 0, 0));
        Button chooseFolder = secondaryButton(folder.isEmpty() ? "Elegir carpeta base" : "Cambiar carpeta base");
        chooseFolder.setOnClickListener(v -> {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try { startActivityForResult(picker, REQUEST_DOWNLOAD_FOLDER); }
            catch (Exception e) { Toast.makeText(this, "No se pudo abrir el selector de carpetas.", Toast.LENGTH_LONG).show(); }
        });
        downloads.addView(chooseFolder, margin(0, 10, 0, 0));
        page.addView(downloads);

        addSettingsHeading(page, "ALMACENAMIENTO LOCAL");
        LinearLayout storage = card();
        storage.addView(text("Datos locales", 15, true));
        storage.addView(bodyText("Historial de descargas: " + formatBytes(savedHistorySize())), margin(0, 5, 0, 0));
        storage.addView(bodyText("Temporales de la app: " + formatBytes(directorySize(getCacheDir()))), margin(0, 3, 0, 0));
        Button cleanCache = secondaryButton("Limpiar temporales antiguos");
        cleanCache.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Limpiar caché antigua")
                .setMessage("Se borrarán solo archivos temporales con más de 7 días. Las descargas de Descargas no se tocarán.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Limpiar", (dialog, which) -> {
                    long removed = cleanOldCacheFiles(getCacheDir(), System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000);
                    Toast.makeText(this, "Caché antigua limpiada: " + formatBytes(removed), Toast.LENGTH_LONG).show();
                    showSettings(false);
                }).show());
        storage.addView(cleanCache, margin(0, 12, 0, 0));
        Button compactHistory = secondaryButton("Optimizar historial");
        compactHistory.setOnClickListener(v -> {
            int removed = removeMissingDownloadRecords();
            Toast.makeText(this, "Historial optimizado. Registros huérfanos quitados: " + removed, Toast.LENGTH_LONG).show();
            showSettings(false);
        });
        storage.addView(compactHistory, margin(0, 8, 0, 0));
        Button clearHistory = secondaryButton("Vaciar historial de descargas");
        clearHistory.setOnClickListener(v -> confirmClearDownloadHistory());
        storage.addView(clearHistory, margin(0, 8, 0, 0));
        page.addView(storage);

        addSettingsHeading(page, "APLICACIÓN");
        LinearLayout appInfo = card();
        appInfo.addView(text("Tod4S3 Sync", 15, true));
        appInfo.addView(bodyText("Versión " + appVersion() + " · Android"), margin(0, 5, 0, 0));
        page.addView(appInfo);
        page.addView(infoCard("Privacidad", "La app solo consulta las entregas del bot vinculado y las tareas que tú envías desde este formulario. No lee chats privados ni envía tu contraseña de ToDus."), margin(0, 12, 0, 0));
        showPage(page, animated);
        appScroll.post(() -> appScroll.scrollTo(0, preserveScroll ? previousScrollY : 0));
    }

    private long directorySize(File directory) {
        if (directory == null || !directory.exists()) return 0;
        if (directory.isFile()) return directory.length();
        long total = 0;
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) total += directorySize(child);
        return total;
    }

    private long savedHistorySize() {
        long total = 0;
        JSONArray records = savedDownloadRecords();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record != null) total += Math.max(0L, record.optLong("size", 0L));
        }
        return total;
    }

    private int removeMissingDownloadRecords() {
        JSONArray records = savedDownloadRecords();
        JSONArray kept = new JSONArray();
        int removed = 0;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) { removed++; continue; }
            boolean exists = true;
            String path = record.optString("path", "");
            String uri = record.optString("uri", "");
            try {
                if (!path.isEmpty()) exists = new File(path).exists();
                else if (uri.startsWith("content://")) {
                    try (android.os.ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(Uri.parse(uri), "r")) {
                        exists = descriptor != null;
                    }
                }
            } catch (Exception ignored) { exists = false; }
            if (exists) kept.put(record); else removed++;
        }
        prefs.edit().putString(PREF_DOWNLOAD_HISTORY, kept.toString()).apply();
        return removed;
    }

    private long cleanOldCacheFiles(File directory, long cutoffMillis) {
        if (directory == null || !directory.exists()) return 0;
        long removed = 0;
        File[] children = directory.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            if (child.isDirectory()) {
                removed += cleanOldCacheFiles(child, cutoffMillis);
                File[] remaining = child.listFiles();
                if (remaining != null && remaining.length == 0) child.delete();
            } else if (child.lastModified() > 0 && child.lastModified() < cutoffMillis) {
                long size = child.length();
                if (child.delete()) removed += size;
            }
        }
        return removed;
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { return "1.1.3"; }
    }

    private void showStats(boolean animated) {
        viewingDashboard = false;
        JSONArray records = savedDownloadRecords();
        long totalBytes = 0;
        for (int i = 0; i < records.length(); i++) totalBytes += records.optJSONObject(i) == null ? 0 : records.optJSONObject(i).optLong("size", 0);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(14), dp(16), dp(28));
        page.addView(sectionHeader("Estadísticas"));
        page.addView(infoCard("Descargas guardadas", records.length() + " archivos · " + formatBytes(totalBytes)), margin(0, 20, 0, 0));
        page.addView(infoCard("Entregas del bot", latestDeliveryRows.length() + " archivos disponibles en la nube"), margin(0, 10, 0, 0));
        page.addView(infoCard("Tareas Android", latestAppTaskRows.length() + " solicitudes en el historial del proceso"), margin(0, 10, 0, 0));
        Button refresh = secondaryButton("Actualizar estadísticas");
        refresh.setOnClickListener(v -> { refreshDeliveries(); refreshAppTasks(); });
        page.addView(refresh, margin(0, 16, 0, 0));
        showPage(page, animated);
    }

    private void showAbout(boolean animated) {
        viewingDashboard = false;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(14), dp(16), dp(28));
        page.addView(sectionHeader("Acerca de"));
        page.addView(infoCard("Tod4S3 Sync", "Versión " + appVersion() + "\nDescarga las entregas y enlaces que recibes del bot."), margin(0, 20, 0, 0));
        page.addView(infoCard("Privacidad", "La app no accede a tus chats privados ni a mensajes de otras personas. Solo muestra los archivos que entrega el bot y los enlaces que tú envías explícitamente. La cuota gratis se guarda en el servidor por tu cuenta y borrar datos locales no la reinicia."), margin(0, 10, 0, 0));
        page.addView(infoCard("Dispositivo y cuota", "Se conserva un identificador seudónimo estable del móvil para vincular esta instalación. La cuota gratuita se comparte por cuenta de Telegram y se renueva al cumplirse 24 horas."), margin(0, 10, 0, 0));
        page.addView(infoCard("ToDus", "La contraseña se guarda cifrada en este dispositivo y no se envía al bot."), margin(0, 10, 0, 0));
        Button group = secondaryButton("Abrir grupo de Telegram");
        group.setOnClickListener(v -> openExternalUrl("https://t.me/Dark_Zone_666"));
        page.addView(group, margin(0, 16, 0, 0));
        showPage(page, animated);
    }

    private void showAppLogs(boolean animated) {
        viewingDashboard = false;
        final ArrayList<String> logRows = new ArrayList<>();
        final String[] selectedLevel = {"TODOS"};
        final int[] requestGeneration = {0};
        LinearLayout page = createStepPage(0, "Registros de la App", "Consulta y filtra la actividad reciente de Tod4S3 Sync.");
        page.addView(infoCard("Diagnóstico local", "Solo se muestran registros del proceso de Tod4S3 Sync. No se leen chats ni registros de otras aplicaciones."), margin(0, 18, 0, 12));

        EditText search = input("Buscar en logs…", android.text.InputType.TYPE_CLASS_TEXT);
        page.addView(search, margin(0, 0, 0, 10));

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        String[] levels = {"TODOS", "INFO", "WARN", "ERROR"};
        ArrayList<Button> filterButtons = new ArrayList<>();
        for (String level : levels) {
            Button chip = secondaryButton(level);
            chip.setTextSize(11);
            chip.setMinHeight(dp(40));
            boolean active = level.equals(selectedLevel[0]);
            chip.setBackground(roundRect(active ? accentColor() : raisedSurfaceColor(), 12, borderColor()));
            chip.setTextColor(active ? onAccentColor() : secondaryTextColor());
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
            chipParams.setMargins(dp(3), 0, dp(3), 0);
            filters.addView(chip, chipParams);
            filterButtons.add(chip);
        }
        page.addView(filters, margin(-3, 0, -3, 12));

        TextView output = new TextView(this);
        output.setTextSize(11);
        output.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        output.setTextColor(isDarkTheme() ? Color.rgb(222, 229, 239) : Color.rgb(30, 39, 52));
        output.setPadding(dp(12), dp(12), dp(12), dp(12));
        output.setTextIsSelectable(true);
        output.setGravity(Gravity.TOP | Gravity.START);
        ScrollView consoleScroll = new ScrollView(this);
        consoleScroll.setFillViewport(true);
        consoleScroll.setVerticalScrollBarEnabled(true);
        consoleScroll.setBackground(roundRect(isDarkTheme() ? Color.rgb(8, 12, 22) : Color.rgb(247, 248, 251), 14, borderColor()));
        consoleScroll.addView(output, new ScrollView.LayoutParams(-1, -2));
        LinearLayout console = card();
        console.addView(consoleScroll, new LinearLayout.LayoutParams(-1, dp(350)));
        page.addView(console, margin(0, 0, 0, 10));

        TextView status = bodyText("Leyendo registros de Tod4S3 Sync…");
        page.addView(status, margin(0, 0, 0, 10));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button clear = secondaryButton("Limpiar vista");
        clear.setContentDescription("Limpiar vista local");
        clear.setOnClickListener(v -> {
            logRows.clear();
            renderAppLogRows(logRows, selectedLevel[0], search.getText().toString(), output, consoleScroll);
            status.setText("Vista limpiada localmente. Pulsa actualizar para volver a leer los registros.");
        });
        Button refresh = secondaryButton("Actualizar registros");
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        actionParams.setMargins(0, 0, dp(8), 0);
        actions.addView(clear, actionParams);
        actions.addView(refresh, new LinearLayout.LayoutParams(0, dp(48), 1f));
        page.addView(actions, margin(0, 0, 0, 8));
        showPage(page, animated);
        appLogOutput = output;
        requestGeneration[0] = ++appLogGeneration;

        Runnable render = () -> renderAppLogRows(logRows, selectedLevel[0], search.getText().toString(), output, consoleScroll);
        for (int i = 0; i < filterButtons.size(); i++) {
            final String level = levels[i];
            filterButtons.get(i).setOnClickListener(v -> {
                selectedLevel[0] = level;
                for (int j = 0; j < filterButtons.size(); j++) {
                    boolean chosen = levels[j].equals(level);
                    filterButtons.get(j).setBackground(roundRect(chosen ? accentColor() : raisedSurfaceColor(), 12, borderColor()));
                    filterButtons.get(j).setTextColor(chosen ? onAccentColor() : secondaryTextColor());
                }
                render.run();
            });
        }
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { render.run(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        refresh.setOnClickListener(v -> {
            requestGeneration[0] = ++appLogGeneration;
            loadAppLogRows(logRows, output, consoleScroll, status, selectedLevel, search, requestGeneration[0]);
        });
        loadAppLogRows(logRows, output, consoleScroll, status, selectedLevel, search, requestGeneration[0]);
    }

    private void loadAppLogRows(ArrayList<String> rows, TextView output, ScrollView scroll,
                                TextView status, String[] selectedLevel, EditText search, int generation) {
        status.setText("Leyendo registros de Tod4S3 Sync…");
        new Thread(() -> {
            ArrayList<String> captured = captureOwnProcessLogcat();
            runOnUiThread(() -> {
                if (generation != appLogGeneration || appLogOutput != output) return;
                rows.clear();
                rows.addAll(captured);
                renderAppLogRows(rows, selectedLevel[0], search.getText().toString(), output, scroll);
                status.setText(captured.size() + " registro(s) del proceso de Tod4S3 Sync.");
            });
        }, "tod4s3-app-logcat").start();
    }

    private ArrayList<String> captureOwnProcessLogcat() {
        ArrayList<String> rows = new ArrayList<>();
        String ownPid = Integer.toString(android.os.Process.myPid());
        try {
            Process process = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "600")
                    .redirectErrorStream(true).start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.trim().split("\\s+", 6);
                    if (fields.length < 6 || !ownPid.equals(fields[2])) continue;
                    String priority = fields[4];
                    String level;
                    if ("E".equals(priority) || "F".equals(priority) || "A".equals(priority)) level = "ERROR";
                    else if ("W".equals(priority)) level = "WARN";
                    else level = "INFO";
                    rows.add("[" + level + "] " + fields[0] + " " + fields[1] + " " + fields[5]);
                }
            }
            process.waitFor();
        } catch (Exception e) {
            rows.add("[WARN] Captura local no disponible (" + e.getClass().getSimpleName() + ").");
        }
        if (rows.isEmpty()) rows.add("[INFO] Tod4S3 Sync está activa. No hay registros recientes del proceso.");
        if (rows.size() > 400) return new ArrayList<>(rows.subList(rows.size() - 400, rows.size()));
        return rows;
    }

    private void renderAppLogRows(ArrayList<String> rows, String levelFilter, String query,
                                  TextView output, ScrollView scroll) {
        android.text.SpannableStringBuilder rendered = new android.text.SpannableStringBuilder();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        int count = 0;
        for (String row : rows) {
            String level = row.contains("[ERROR]") ? "ERROR" : row.contains("[WARN]") ? "WARN" : "INFO";
            if (!"TODOS".equals(levelFilter) && !levelFilter.equals(level)) continue;
            if (!normalizedQuery.isEmpty() && !row.toLowerCase(java.util.Locale.ROOT).contains(normalizedQuery)) continue;
            int start = rendered.length();
            rendered.append(row).append('\n');
            int color = "ERROR".equals(level) ? Color.rgb(255, 126, 137)
                    : "WARN".equals(level) ? Color.rgb(255, 204, 112) : Color.rgb(99, 230, 216);
            rendered.setSpan(new android.text.style.ForegroundColorSpan(color), start, rendered.length(),
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (++count >= 300) break;
        }
        if (count == 0) rendered.append("No hay registros que coincidan con la búsqueda.");
        output.setText(rendered);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void openExternalUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { Toast.makeText(this, "No se pudo abrir el enlace.", Toast.LENGTH_LONG).show(); }
    }

    private void shareReport(String topic) {
        String message = "Hola, necesito ayuda con Tod4S3 Sync.\n\nMotivo: " + topic
                + "\n\nDescribe aquí el problema y revisa el mensaje antes de enviarlo.";
        openTelegramChat(message);
    }

    private void showHomeSection(boolean downloadsTab, boolean animated) {
        viewingDashboard = false;
        showingAiChat = false;
        viewingDownloadsTab = downloadsTab;
        String title = downloadsTab ? "Descargas" : "Tus entregas";
        String subtitle = downloadsTab
                ? "Archivos guardados en la carpeta Descargas del teléfono."
                : "Las entregas del bot aparecen aquí. Descárgalas para guardarlas en Descargas.";
        LinearLayout page = createStepPage(0, title, subtitle);
        addHomeTabs(page, downloadsTab, false);

        if (downloadsTab) {
            LinearLayout historyHeading = new LinearLayout(this);
            historyHeading.setGravity(Gravity.CENTER_VERTICAL);
            historyHeading.addView(text("Historial de descargas", 16, true),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            if (savedDownloadRecords().length() > 0) {
                Button clearHistory = secondaryButton("Vaciar lista");
                clearHistory.setTextSize(12);
                clearHistory.setMinHeight(dp(40));
                clearHistory.setOnClickListener(v -> confirmClearDownloadHistory());
                historyHeading.addView(clearHistory, new LinearLayout.LayoutParams(-2, dp(40)));
            }
            page.addView(historyHeading, margin(0, 18, 0, 0));
            LinearLayout savedBox = new LinearLayout(this);
            savedBox.setOrientation(LinearLayout.VERTICAL);
            page.addView(savedBox, margin(0, 10, 0, 0));
            renderDownloadHistory(savedBox);
        } else {
            LinearLayout account = card();
            account.addView(text("ToDus conectado", 17, true));
            account.addView(bodyText(todusPhone.isEmpty() ? "Sesión segura" : "Cuenta " + todusPhone), margin(0, 5, 0, 0));
            page.addView(account, margin(0, 16, 0, 0));

            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView recentTitle = text("Archivos recientes", 20, true);
            heading.addView(recentTitle, new LinearLayout.LayoutParams(0, -2, 1f));
            Button refresh = secondaryButton("Actualizar");
            refresh.setOnClickListener(v -> refreshDeliveries(v));
            heading.addView(refresh, new LinearLayout.LayoutParams(-2, dp(42)));
            page.addView(heading, margin(0, 22, 0, 4));

            syncStatus = statusText("Preparando tus entregas…");
            page.addView(syncStatus, margin(0, 4, 0, 10));
            deliveriesBox = new LinearLayout(this);
            deliveriesBox.setOrientation(LinearLayout.VERTICAL);
            page.addView(deliveriesBox);
        }

        showPage(page, animated);
        homeTabPageVisible = true;
        if (!downloadsTab && token != null && !token.isEmpty()) {
            handler.postDelayed(this::refreshDeliveries, animated ? 300L : 0L);
        }
        if (token != null && !token.isEmpty()) refreshAccountProfile();
    }

    private boolean hasAiPremiumAccess() {
        return premiumVerified && (premiumActive || "premium".equals(activePlan) || "owner".equals(activePlan));
    }

    private void addHomeTabs(LinearLayout page, boolean downloadsTab, boolean aiTabSelected) {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        boolean showAi = hasAiPremiumAccess();
        Button deliveries = !downloadsTab && !aiTabSelected ? primaryButton("ToDus") : secondaryButton("ToDus");
        Button downloads = downloadsTab ? primaryButton("Descargas") : secondaryButton("Descargas");
        setButtonIcon(deliveries, R.drawable.ic_inbox_delivery,
                !downloadsTab && !aiTabSelected ? Color.rgb(5, 26, 35) : Color.rgb(172, 224, 226));
        setButtonIcon(downloads, R.drawable.ic_download_arrow,
                downloadsTab ? Color.rgb(5, 26, 35) : Color.rgb(172, 224, 226));
        deliveries.setOnClickListener(v -> showHomeStep(true));
        downloads.setOnClickListener(v -> showDownloadsStep(true));
        if (!showAi) {
            LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, dp(54), 1f);
            first.setMargins(0, 0, dp(7), 0);
            LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, dp(54), 1f);
            second.setMargins(dp(7), 0, 0, 0);
            tabs.addView(deliveries, first);
            tabs.addView(downloads, second);
        } else {
            Button ai = aiTabSelected ? primaryButton("✦ AiTod4S3") : secondaryButton("✦ AiTod4S3");
            ai.setTextSize(12);
            ai.setMinWidth(0);
            ai.setPadding(dp(3), dp(7), dp(3), dp(7));
            deliveries.setTextSize(12);
            deliveries.setMinWidth(0);
            deliveries.setPadding(dp(3), dp(7), dp(3), dp(7));
            downloads.setTextSize(12);
            downloads.setMinWidth(0);
            downloads.setPadding(dp(3), dp(7), dp(3), dp(7));
            ai.setOnClickListener(v -> showAiChat(true));
            Button[] buttons = {deliveries, downloads, ai};
            for (int i = 0; i < buttons.length; i++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1f);
                params.setMargins(i == 0 ? 0 : dp(3), 0, i == buttons.length - 1 ? 0 : dp(3), 0);
                tabs.addView(buttons[i], params);
            }
        }
        page.addView(tabs, margin(0, 16, 0, 0));
    }

    private void showAiChat(boolean animated) {
        if (!hasAiPremiumAccess()) {
            Toast.makeText(this, "AiTod4S3 está disponible solo con Premium activo y verificado.", Toast.LENGTH_LONG).show();
            showPlansStep(true);
            return;
        }
        viewingDashboard = false;
        showingAiChat = true;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(8), dp(12), dp(12));
        page.setBackgroundColor(backgroundColor());

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = secondaryButton("← Volver");
        back.setTextSize(12);
        back.setMinHeight(dp(38));
        back.setOnClickListener(v -> {
            showingAiChat = false;
            homeTabPageVisible = false;
            showDashboard(dashboardFilter, true);
        });
        header.addView(back, new LinearLayout.LayoutParams(dp(84), dp(38)));
        TextView title = text("✦  AiTod4S3", 20, true);
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        title.setTextColor(primaryTextColor());
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView premiumBadge = text("PREMIUM", 10, true);
        premiumBadge.setGravity(Gravity.CENTER);
        premiumBadge.setTextColor(Color.rgb(255, 221, 141));
        premiumBadge.setPadding(dp(7), dp(4), dp(7), dp(4));
        premiumBadge.setBackground(roundRect(Color.rgb(60, 48, 28), 18, Color.rgb(143, 111, 48)));
        header.addView(premiumBadge, new LinearLayout.LayoutParams(-2, dp(26)));
        page.addView(header);

        aiChatStatus = bodyText("●  Listo para responder");
        aiChatStatus.setTextSize(11);
        page.addView(aiChatStatus, margin(2, 6, 2, 6));

        aiConversationScroll = new ScrollView(this);
        aiConversationScroll.setFillViewport(true);
        aiConversationScroll.setVerticalScrollBarEnabled(true);
        aiConversationScroll.setBackground(roundRect(isDarkTheme() ? Color.rgb(8, 12, 22) : Color.rgb(247, 248, 251), 18, borderColor()));
        aiConversationBox = new LinearLayout(this);
        aiConversationBox.setOrientation(LinearLayout.VERTICAL);
        aiConversationBox.setPadding(dp(11), dp(12), dp(11), dp(12));
        aiConversationScroll.addView(aiConversationBox, new ScrollView.LayoutParams(-1, -2));
        LinearLayout chatPanel = card();
        chatPanel.setPadding(dp(6), dp(6), dp(6), dp(6));
        chatPanel.setMinimumHeight(dp(190));
        chatPanel.addView(aiConversationScroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams chatPanelParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        chatPanelParams.setMargins(0, 0, 0, dp(8));
        page.addView(chatPanel, chatPanelParams);

        LinearLayout composerRow = new LinearLayout(this);
        composerRow.setGravity(Gravity.CENTER_VERTICAL);
        composerRow.setOrientation(LinearLayout.HORIZONTAL);
        aiComposer = new EditText(this);
        aiComposer.setHint("Escribe tu pregunta…");
        aiComposer.setSingleLine(false);
        aiComposer.setMinLines(1);
        aiComposer.setMaxLines(3);
        aiComposer.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        aiComposer.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        aiComposer.setImeOptions(EditorInfo.IME_ACTION_SEND);
        aiComposer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2500)});
        aiComposer.setTextColor(primaryTextColor());
        aiComposer.setHintTextColor(secondaryTextColor());
        aiComposer.setTextSize(15);
        aiComposer.setPadding(dp(12), dp(7), dp(12), dp(7));
        aiComposer.setBackground(roundRect(raisedSurfaceColor(), 24, borderColor()));
        composerRow.addView(aiComposer, new LinearLayout.LayoutParams(0, dp(48), 1f));
        aiSendButton = primaryButton("➤");
        aiSendButton.setTextSize(18);
        aiSendButton.setPadding(0, 0, 0, 0);
        aiSendButton.setContentDescription("Enviar pregunta");
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        sendParams.setMargins(dp(7), 0, 0, 0);
        composerRow.addView(aiSendButton, sendParams);
        page.addView(composerRow, margin(0, 0, 0, 12));

        aiSendButton.setOnClickListener(v -> sendAiMessage());
        aiComposer.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendAiMessage();
                return true;
            }
            return false;
        });
        renderAiConversation();
        showPage(page, animated);
        showingAiChat = true;
        homeTabPageVisible = true;
    }

    private void renderAiConversation() {
        if (aiConversationBox == null || aiConversationScroll == null) return;
        aiConversationBox.removeAllViews();
        if (aiChatMessages.isEmpty()) {
            TextView welcome = bodyText("✦\n\nHola, soy AiTod4S3.\n¿Qué te gustaría saber hoy?");
            welcome.setGravity(Gravity.CENTER);
            welcome.setTextSize(16);
            welcome.setLineSpacing(dp(3), 1f);
            welcome.setTextColor(secondaryTextColor());
            welcome.setPadding(dp(18), dp(24), dp(18), dp(24));
            aiConversationBox.addView(welcome, new LinearLayout.LayoutParams(-1, -2));
        }
        for (AiChatMessage message : aiChatMessages) {
            boolean userMessage = "user".equals(message.role);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(userMessage ? Gravity.END : Gravity.START);
            LinearLayout bubble = new LinearLayout(this);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(dp(14), dp(10), dp(14), dp(11));
            bubble.setBackground(roundRect(userMessage ? Color.rgb(29, 91, 108)
                            : (isDarkTheme() ? Color.rgb(29, 36, 49) : Color.rgb(239, 242, 247)),
                    18, userMessage ? Color.rgb(49, 143, 158) : borderColor()));
            TextView author = text(userMessage ? "TÚ" : "✦  AiTod4S3", 10, true);
            author.setTextColor(userMessage ? Color.rgb(151, 239, 228) : Color.rgb(241, 197, 111));
            bubble.addView(author, margin(0, 0, 0, 5));
            TextView body = text(message.content, 14, false);
            body.setTextColor(primaryTextColor());
            body.setLineSpacing(dp(3), 1.02f);
            bubble.addView(body);
            LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(-2, -2);
            bubbleParams.width = Math.min(dp(310), getResources().getDisplayMetrics().widthPixels - dp(96));
            bubbleParams.setMargins(dp(2), 0, dp(2), dp(8));
            row.addView(bubble, bubbleParams);
            aiConversationBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        aiConversationScroll.post(() -> aiConversationScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void sendAiMessage() {
        if (aiChatRequestInFlight || aiComposer == null || aiSendButton == null) return;
        if (!hasAiPremiumAccess()) {
            Toast.makeText(this, "AiTod4S3 requiere Premium activo y verificado.", Toast.LENGTH_LONG).show();
            showPlansStep(true);
            return;
        }
        final String userText = aiComposer.getText().toString().trim();
        if (userText.isEmpty()) {
            aiComposer.setError("Escribe una pregunta");
            return;
        }
        android.view.inputmethod.InputMethodManager inputMethod =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (inputMethod != null) inputMethod.hideSoftInputFromWindow(aiComposer.getWindowToken(), 0);
        aiComposer.clearFocus();
        appRoot.requestFocus();
        final String currentToken = token == null ? "" : token;
        if (currentToken.isEmpty()) {
            aiChatStatus.setText("Vincula tu cuenta de Telegram para usar AiTod4S3.");
            return;
        }
        aiChatMessages.add(new AiChatMessage("user", userText));
        aiComposer.setText("");
        renderAiConversation();
        aiChatRequestInFlight = true;
        aiSendButton.setEnabled(false);
        aiSendButton.setText("…");
        aiChatStatus.setText("AiTod4S3 está preparando una respuesta…");
        final int requestGeneration = appAccessGeneration;
        JSONArray messages = new JSONArray();
        int from = Math.max(0, aiChatMessages.size() - 12);
        int totalChars = 0;
        int safeFrom = aiChatMessages.size();
        for (int i = aiChatMessages.size() - 1; i >= from; i--) {
            int size = aiChatMessages.get(i).content.length();
            if (totalChars + size > 7800) break;
            totalChars += size;
            safeFrom = i;
        }
        for (int i = safeFrom; i < aiChatMessages.size(); i++) {
            AiChatMessage item = aiChatMessages.get(i);
            try {
                JSONObject row = new JSONObject();
                row.put("role", item.role);
                row.put("content", item.content);
                messages.put(row);
            } catch (org.json.JSONException ignored) {
                // Los dos campos son strings, por lo que normalmente no pueden fallar.
            }
        }
        new Thread(() -> {
            String reply = null;
            String failure = null;
            try {
                JSONObject response = request("POST", "/api/app/ai/chat",
                        new JSONObject().put("messages", messages).toString(), currentToken);
                reply = response.optString("reply", "").trim();
                if (reply.isEmpty()) failure = "La IA no devolvió texto. Inténtalo de nuevo.";
            } catch (Exception e) {
                String raw = e.getMessage() == null ? "" : e.getMessage();
                String safeStatus = raw.startsWith("HTTP ")
                        ? raw.replaceAll("[^A-Za-z0-9 _:-]", "").substring(0, Math.min(100, raw.replaceAll("[^A-Za-z0-9 _:-]", "").length()))
                        : "";
                android.util.Log.e(LOG_TAG, "AiTod4S3 request failed: " + e.getClass().getSimpleName()
                        + (safeStatus.isEmpty() ? "" : " " + safeStatus));
                if (raw.contains("premium_required")) failure = "AiTod4S3 está disponible solo con Premium activo y verificado.";
                else if (raw.contains("ai_not_configured")) failure = "El servicio AiTod4S3 todavía no está configurado en el servidor.";
                else if (raw.contains("ai_credits_exhausted")) failure = "Se agotó el saldo de la clave de IA. Recárgalo en NoTrack → API Keys y vuelve a intentarlo.";
                else if (raw.contains("ai_provider_daily_cap")) failure = "La clave llegó a su tope diario de gasto. Súbelo en NoTrack → API Keys o espera al reinicio UTC.";
                else if (raw.contains("ai_payment_required")) failure = "La clave de IA requiere saldo o tiene un límite de gasto activo. Revísala en NoTrack → API Keys.";
                else if (raw.contains("ai_daily_limit")) failure = "Alcanzaste el límite diario de consultas. Vuelve a intentarlo mañana.";
                else if (raw.contains("ai_service_daily_limit")) failure = "AiTod4S3 alcanzó su límite diario. Inténtalo más tarde.";
                else if (raw.contains("ai_rate_limited") || raw.contains("ai_busy")) failure = "Hay muchas consultas en este momento. Espera un poco y vuelve a intentarlo.";
                else if (raw.contains("ai_provider_auth")) failure = "El servicio de IA requiere renovar su configuración. Contacta al administrador.";
                else if (raw.contains("ai_provider_endpoint")) failure = "El servidor no encontró la dirección de la IA. Se corrigió la ruta del servicio; vuelve a probar cuando termine el despliegue.";
                else if (raw.contains("ai_provider_request")) failure = "El proveedor rechazó la solicitud. Revisa que el servidor tenga la clave IASINCENSURA completa y actualizada.";
                else if (raw.contains("ai_provider_access")) failure = "La clave de IA no tiene acceso al servicio. El administrador debe revisar o renovar la clave.";
                else if (raw.contains("ai_provider_error") || raw.contains("ai_invalid_response")) failure = "El proveedor de IA devolvió una respuesta inesperada. Inténtalo de nuevo más tarde.";
                else if (raw.contains("HTTP 404")) failure = "Railway todavía no tiene activa la ruta nueva de la IA. Espera a que termine el despliegue del backend y vuelve a probar.";
                else if (raw.contains("HTTP 401")) failure = "La sesión de la aplicación venció. Vuelve a vincular la app desde el bot.";
                else if (raw.contains("HTTP 500")) failure = "El backend de Railway devolvió un error interno. Revisa el despliegue y los logs del servicio.";
                else if (raw.contains("ai_unavailable") || raw.contains("HTTP 502") || raw.contains("HTTP 503")) failure = "El servicio de IA no responde ahora. Espera un momento y vuelve a intentarlo.";
                else if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException) failure = "No se pudo alcanzar el servidor de Railway. Comprueba Internet y que el servicio esté activo.";
                else if (e instanceof java.net.SocketTimeoutException) failure = "La conexión tardó demasiado. Comprueba Internet e inténtalo otra vez.";
                else failure = "No se pudo conectar con AiTod4S3 (" + e.getClass().getSimpleName() + "). Comprueba la conexión y el despliegue del servidor.";
            }
            final String finalReply = reply;
            final String finalFailure = failure;
            runOnUiThread(() -> {
                aiChatRequestInFlight = false;
                if (currentToken.equals(token) && requestGeneration == appAccessGeneration && !appBanned
                        && aiConversationBox != null) {
                    if (finalReply != null) {
                        aiChatMessages.add(new AiChatMessage("assistant", finalReply));
                        if (aiChatMessages.size() > 24) aiChatMessages.subList(0, aiChatMessages.size() - 24).clear();
                        aiChatStatus.setText("Respuesta recibida.");
                    } else {
                        String visibleFailure = finalFailure == null ? "No se pudo obtener una respuesta." : finalFailure;
                        aiChatStatus.setText("No se pudo responder.");
                        aiChatMessages.add(new AiChatMessage("assistant", visibleFailure));
                    }
                    renderAiConversation();
                }
                if (aiSendButton != null) {
                    aiSendButton.setEnabled(true);
                    aiSendButton.setText("➤");
                }
            });
        }, "aitod4s3-chat").start();
    }

    private static final class AiChatMessage {
        final String role;
        final String content;
        AiChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private void setButtonIcon(Button button, int iconResource, int tint) {
        Drawable icon = getDrawable(iconResource);
        if (icon == null) return;
        icon.setTint(tint);
        icon.setBounds(0, 0, dp(21), dp(21));
        button.setCompoundDrawablesRelative(icon, null, null, null);
        button.setCompoundDrawablePadding(dp(9));
        button.setGravity(Gravity.CENTER);
    }

    private FrameLayout iconBadge(int iconResource, int iconTint, int backgroundColor) {
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(roundRect(backgroundColor, 16, borderColor()));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setColorFilter(iconTint);
        icon.setContentDescription("Icono de archivo");
        badge.addView(icon, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));
        return badge;
    }

    private JSONArray savedDownloadRecords() {
        try {
            return new JSONArray(prefs.getString(PREF_DOWNLOAD_HISTORY, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void renderDownloadHistory(LinearLayout box) {
        box.removeAllViews();
        JSONArray records = savedDownloadRecords();
        if (records.length() == 0) {
            LinearLayout empty = card();
            empty.setGravity(Gravity.CENTER);
            TextView title = text("Todavía no hay descargas guardadas", 15, true);
            title.setGravity(Gravity.CENTER);
            empty.addView(title);
            TextView hint = bodyText("Cuando guardes una entrega del bot, aparecerá aquí.");
            hint.setGravity(Gravity.CENTER);
            empty.addView(hint, margin(0, 5, 0, 0));
            box.addView(empty, margin(0, 0, 0, 8));
            return;
        }
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            String name = record.optString("name", "archivo");
            long size = record.optLong("size", 0);
            LinearLayout item = card();
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            item.addView(iconBadge(R.drawable.ic_download_arrow, accentColor(),
                    raisedSurfaceColor()), new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setGravity(Gravity.CENTER_VERTICAL);
            TextView fileName = text(name, 14, true);
            fileName.setSingleLine(true);
            fileName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            details.addView(fileName);
            TextView fileMeta = bodyText(formatBytes(size) + " · Descargas");
            fileMeta.setTextSize(12);
            fileMeta.setSingleLine(true);
            details.addView(fileMeta, margin(0, 3, 0, 0));
            LinearLayout.LayoutParams detailsLayout = new LinearLayout.LayoutParams(0, -2, 1f);
            detailsLayout.setMargins(dp(10), 0, 0, 0);
            item.addView(details, detailsLayout);
            Button remove = secondaryButton("Eliminar");
            remove.setTextSize(11);
            remove.setTextColor(Color.rgb(255, 190, 190));
            remove.setMinHeight(dp(38));
            remove.setPadding(dp(8), dp(3), dp(8), dp(3));
            remove.setBackground(roundRect(Color.rgb(54, 31, 41), 12, Color.rgb(112, 63, 76)));
            remove.setOnClickListener(v -> confirmDeleteDownloadedFile(record));
            LinearLayout.LayoutParams removeLayout = new LinearLayout.LayoutParams(-2, dp(38));
            removeLayout.setMargins(dp(8), 0, 0, 0);
            item.addView(remove, removeLayout);
            box.addView(item, margin(0, 0, 0, 8));
            item.setAlpha(0f);
            item.setTranslationY(dp(4));
            item.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(i * 25L, 150L))
                    .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void confirmDeleteDownloadedFile(JSONObject record) {
        String name = record.optString("name", "archivo");
        new AlertDialog.Builder(this)
                .setTitle("Eliminar del teléfono")
                .setMessage("Se borrará «" + name + "» de la carpeta Descargas de este teléfono. La entrega original de ToDus no se borrará.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (dialog, which) -> deleteDownloadedFile(record))
                .show();
    }

    private void deleteDownloadedFile(JSONObject record) {
        final String uriString = record.optString("uri", "");
        final String path = record.optString("path", "");
        final String name = record.optString("name", "archivo");
        new Thread(() -> {
            try {
                boolean deleted = false;
                if (uriString.startsWith("content://")) {
                    deleted = getContentResolver().delete(Uri.parse(uriString), null, null) > 0;
                } else {
                    String filePath = path;
                    if (filePath.isEmpty() && uriString.startsWith("file://")) {
                        filePath = Uri.parse(uriString).getPath();
                    }
                    if (!filePath.isEmpty()) {
                        File file = new File(filePath);
                        deleted = !file.exists() || file.delete();
                    }
                }
                if (!deleted) throw new IOException("Android no pudo eliminar el archivo.");
                removeDownloadedRecordFromHistory(record);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Archivo eliminado de Descargas: " + name, Toast.LENGTH_LONG).show();
                    showDownloadsStep(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "No se pudo eliminar el archivo del teléfono. Sigue en Descargas.", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void removeDownloadedRecordFromHistory(JSONObject target) {
        String targetUri = target.optString("uri", "");
        String targetPath = target.optString("path", "");
        long targetSavedAt = target.optLong("saved_at", -1L);
        JSONArray records = savedDownloadRecords();
        JSONArray remaining = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            boolean matches = !targetUri.isEmpty()
                    ? targetUri.equals(record.optString("uri", ""))
                    : !targetPath.isEmpty()
                    ? targetPath.equals(record.optString("path", ""))
                    : targetSavedAt >= 0L && targetSavedAt == record.optLong("saved_at", -2L);
            if (!removed && matches) {
                removed = true;
            } else {
                remaining.put(record);
            }
        }
        prefs.edit().putString(PREF_DOWNLOAD_HISTORY, remaining.toString()).apply();
    }

    private void confirmClearDownloadHistory() {
        new AlertDialog.Builder(this)
                .setTitle("Vaciar historial")
                .setMessage("Se vaciará solo esta lista. Los archivos seguirán en Descargas; usa «Eliminar» en cada fila si quieres borrarlos del teléfono.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Vaciar lista", (dialog, which) -> {
                    prefs.edit().remove(PREF_DOWNLOAD_HISTORY).apply();
                    showDownloadsStep(true);
                })
                .show();
    }

    private void changeToDusAccount() {
        clearToDusCredentials();
        prefs.edit().putBoolean(PREF_TODUS_PIN_REQUESTED, false).apply();
        savedCredentialsCouldNotBeDecrypted = false;
        showPhoneStep(true);
    }

    private LinearLayout createStepPage(int activeStep, String title, String subtitle) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(30), dp(24), dp(32));
        LinearLayout brandHeader = new LinearLayout(this);
        brandHeader.setOrientation(LinearLayout.HORIZONTAL);
        brandHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("Tod4S3", 30, true);
        identity.addView(brand);
        TextView product = text("SYNC  ·  ENTREGA PRIVADA", 11, true);
        product.setTextColor(accentColor());
        identity.addView(product, margin(0, 2, 0, 0));
        brandHeader.addView(identity, new LinearLayout.LayoutParams(0, -2, 1f));
        if (token != null && !token.isEmpty()) brandHeader.addView(accountMenuButton());
        page.addView(brandHeader, margin(0, 0, 0, 20));
        if (activeStep > 0) addProgress(page, activeStep);
        page.addView(text(title, 26, true), margin(0, 24, 0, 7));
        page.addView(bodyText(subtitle));
        return page;
    }

    private FrameLayout accountMenuButton() {
        FrameLayout avatar = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.rgb(190, 30, 38));
        circle.setStroke(dp(2), Color.rgb(99, 230, 216));
        avatar.setBackground(circle);
        avatar.setElevation(dp(3));
        avatar.setContentDescription("Abrir menú ☰");
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_foreground);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(9), dp(9), dp(9), dp(9));
        avatar.addView(logo, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        loadTelegramProfilePhoto(logo);
        TextView bars = text("☰", 11, true);
        bars.setGravity(Gravity.CENTER);
        bars.setTextColor(Color.rgb(40, 27, 6));
        bars.setBackground(roundRect(Color.rgb(242, 198, 106), 10, Color.rgb(7, 17, 31)));
        FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM | Gravity.END);
        avatar.addView(bars, badge);
        avatar.setOnClickListener(this::showNavigationDrawer);
        avatar.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start();
            else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL)
                view.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            return false;
        });
        return avatar;
    }

    private void loadTelegramProfilePhoto(ImageView target) {
        if (token == null || token.isEmpty()) return;
        if (telegramProfileBitmap != null) {
            applyTelegramProfilePhoto(target, telegramProfileBitmap);
            return;
        }
        if (!pendingProfileAvatarViews.contains(target)) pendingProfileAvatarViews.add(target);
        if (telegramProfilePhotoLoading) return;
        telegramProfilePhotoLoading = true;
        String requestToken = token;
        new Thread(() -> {
            HttpURLConnection connection = null;
            android.graphics.Bitmap decoded = null;
            try {
                connection = (HttpURLConnection) new URL(API_BASE + "/api/app/profile/photo").openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(12_000);
                connection.setRequestProperty("Accept", "image/*");
                connection.setRequestProperty("Authorization", "Bearer " + requestToken);
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    try (InputStream input = connection.getInputStream();
                         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int count;
                        int total = 0;
                        while ((count = input.read(buffer)) != -1) {
                            total += count;
                            if (total > 1024 * 1024) throw new IOException("La foto de perfil supera el límite.");
                            output.write(buffer, 0, count);
                        }
                        byte[] image = output.toByteArray();
                        decoded = android.graphics.BitmapFactory.decodeByteArray(image, 0, image.length);
                    }
                }
            } catch (Exception ignored) {
                // Mantener el icono predeterminado si el usuario no tiene foto o no hay conexión.
            } finally {
                if (connection != null) connection.disconnect();
            }
            android.graphics.Bitmap result = decoded;
            runOnUiThread(() -> {
                telegramProfilePhotoLoading = false;
                if (requestToken.equals(token) && result != null) {
                    telegramProfileBitmap = result;
                    for (ImageView image : pendingProfileAvatarViews) applyTelegramProfilePhoto(image, result);
                }
                pendingProfileAvatarViews.clear();
            });
        }, "telegram-profile-photo").start();
    }

    private void applyTelegramProfilePhoto(ImageView target, android.graphics.Bitmap photo) {
        target.setScaleType(ImageView.ScaleType.CENTER_CROP);
        target.setPadding(0, 0, 0, 0);
        GradientDrawable clip = new GradientDrawable();
        clip.setShape(GradientDrawable.OVAL);
        clip.setColor(Color.TRANSPARENT);
        target.setBackground(clip);
        target.setClipToOutline(true);
        target.setImageBitmap(photo);
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("Cerrar sesión")
                .setMessage("Se desvincularán el bot y la cuenta ToDus en este teléfono. Los archivos guardados en Descargas no se borrarán.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Cerrar sesión", (dialog, which) -> logout())
                .show();
    }

    private void showBannedScreen() {
        if (!appBanned || bannedScreenVisible) return;
        appBanned = true;
        bannedScreenVisible = true;
        unbannedScreenVisible = false;
        showAccessStatusScreen(false);
    }

    private void showUnbannedScreen() {
        appBanned = false;
        bannedScreenVisible = false;
        unbannedScreenVisible = true;
        showAccessStatusScreen(true);
    }

    private void resetDashboardReferencesForAccessTransition() {
        viewingDashboard = false;
        showingPlansPage = false;
        viewingDownloadsTab = false;
        deliveriesBox = null;
        dashboardItemsBox = null;
        dashboardStatusText = null;
        dashboardPlanStatusText = null;
        planStatus = null;
        syncStatus = null;
        todusStatus = null;
        todusSessionStatusText = null;
        quotaUsageBox = null;
        quotaProgressBar = null;
        quotaUsageText = null;
        quotaResetText = null;
        freeQuotaPanel = null;
        freeQuotaPanelProgress = null;
        freeQuotaPanelUsage = null;
        freeQuotaPanelReset = null;
    }

    private void showAccessStatusScreen(boolean accessRestored) {
        resetDashboardReferencesForAccessTransition();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(26), dp(52), dp(26), dp(36));
        page.setBackgroundColor(backgroundColor());

        TextView brand = text("Tod4S3 Sync", 24, true);
        brand.setGravity(Gravity.CENTER);
        page.addView(brand, margin(0, 0, 0, 34));

        ImageView accessIllustration = new ImageView(this);
        accessIllustration.setImageResource(accessRestored ? R.drawable.access_restored : R.drawable.access_banned);
        accessIllustration.setScaleType(ImageView.ScaleType.FIT_CENTER);
        accessIllustration.setContentDescription(accessRestored ? "Acceso restaurado" : "Acceso bloqueado");
        LinearLayout.LayoutParams illustrationParams = new LinearLayout.LayoutParams(dp(148), dp(148));
        illustrationParams.gravity = Gravity.CENTER_HORIZONTAL;
        illustrationParams.bottomMargin = dp(26);
        page.addView(accessIllustration, illustrationParams);

        TextView title = text(accessRestored ? "Usted ha sido desbaneado" : "Usted ha sido baneado", 23, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title, margin(0, 0, 0, 12));
        String detailCopy = accessRestored
                ? "El administrador restauró tu acceso. Pulsa el botón para continuar con la aplicación."
                : "El administrador bloqueó el acceso a esta aplicación por un posible incumplimiento de las reglas de uso, incluida la prioridad de voz. El bloqueo se vincula a tu ID de Telegram y a este móvil. Si crees que fue un error, escribe al soporte desde aquí.";
        TextView detail = bodyText(detailCopy);
        detail.setGravity(Gravity.CENTER);
        page.addView(detail, margin(0, 0, 0, 18));

        if (!accessRestored) {
            LinearLayout help = card();
            help.addView(text("Mensaje al bot de soporte", 16, true));
            help.addView(bodyText("Escribe aquí. El mensaje aparecerá en la bandeja de soporte APK del bot, junto con tu ID de Telegram y el ID de este móvil; no se enviará al privado del administrador."),
                    margin(0, 6, 0, 0));
            EditText supportInput = input("Describe brevemente el problema", android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            supportInput.setSingleLine(false);
            supportInput.setMinLines(3);
            supportInput.setMaxLines(6);
            supportInput.setGravity(Gravity.TOP | Gravity.START);
            help.addView(supportInput, margin(0, 10, 0, 0));
            TextView supportStatus = bodyText("Máximo 2000 caracteres.");
            help.addView(supportStatus, margin(0, 8, 0, 0));
            page.addView(help, margin(0, 0, 0, 14));

            Button support = primaryButton("✉  Enviar mensaje al bot");
            support.setOnClickListener(v -> sendAppSupportMessage(supportInput, supportStatus, support));
            page.addView(support, margin(0, 4, 0, 10));
        }

        if (accessRestored) {
            Button continueButton = primaryButton("Continuar con la aplicación");
            continueButton.setOnClickListener(v -> continueAfterUnban());
            page.addView(continueButton, margin(0, 2, 0, 0));
        } else {
            Button signOut = secondaryButton("Cerrar sesión");
            signOut.setOnClickListener(v -> confirmLogout());
            page.addView(signOut, margin(0, 2, 0, 0));
        }

        TextView footer = bodyText("Tod4S3 Sync · Revisión de acceso APK");
        footer.setGravity(Gravity.CENTER);
        page.addView(footer, margin(0, 24, 0, 0));
        showPage(page, true);
    }

    private void sendAppSupportMessage(EditText supportInput, TextView supportStatus, Button sendButton) {
        final String currentToken = token == null ? "" : token;
        final String currentPairingCode = pendingPairingCode == null ? "" : pendingPairingCode;
        final String currentDeviceId = deviceId();
        final String message = supportInput.getText().toString().trim();
        if (currentToken.isEmpty() && currentPairingCode.isEmpty()) {
            supportStatus.setText("No hay un token ni un código temporal vigente. Solicita un código nuevo con /app en el bot y vuelve a probar.");
            return;
        }
        if (message.isEmpty() || message.length() > 2000) {
            supportStatus.setText("Escribe un mensaje de 1 a 2000 caracteres.");
            return;
        }
        sendButton.setEnabled(false);
        supportStatus.setText("Enviando al inbox de soporte del bot…");
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject().put("message", message);
                if (currentToken.isEmpty()) {
                    payload.put("code", currentPairingCode).put("device_id", currentDeviceId);
                }
                JSONObject result = request("POST", "/api/app/support", payload.toString(), currentToken);
                String ticketId = result.optString("ticket_id", "");
                runOnUiThread(() -> {
                    sendButton.setEnabled(true);
                    supportInput.setText("");
                    supportStatus.setText("Mensaje recibido por el bot. Ticket: " + ticketId
                            + ". El administrador lo verá en Usuarios APK → Mensajes de soporte.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    sendButton.setEnabled(true);
                    String reason = e.getMessage() == null ? "error de conexión" : e.getMessage();
                    supportStatus.setText("No se pudo enviar el mensaje: " + reason);
                });
            }
        }, "app-support-send").start();
    }

    private void continueAfterUnban() {
        if (appBanned) {
            showBannedScreen();
            return;
        }
        unbannedScreenVisible = false;
        bannedScreenVisible = false;
        resetDashboardReferencesForAccessTransition();
        latestDeliveryRows = new JSONArray();
        latestAppTaskRows = new JSONArray();
        showHomeStep(true);
        refreshDeliveries();
        refreshAppTasks();
        refreshAccountProfile();
    }

    private void logout() {
        final String oldToken = token == null ? "" : token;
        invalidateAccessRequests();
        bannedScreenVisible = false;
        unbannedScreenVisible = false;
        token = "";
        prefs.edit().remove("token").remove(PREF_SELECTED_PLAN)
                .remove(PREF_ACTIVE_PREMIUM).remove(PREF_PREMIUM_VERIFIED)
                .remove(PREF_ACTIVE_PLAN).remove("active_plan_expires_v1")
                .putBoolean(PREF_TODUS_PIN_REQUESTED, false).apply();
        premiumActive = false;
        premiumVerified = false;
        activePlan = "free";
        premiumExpiresAt = "";
        clearToDusCredentials();
        savedCredentialsCouldNotBeDecrypted = false;
        deliveriesBox = null;
        syncStatus = null;
        todusStatus = null;
        handler.post(() -> showPlansStep(true));
        if (!oldToken.isEmpty()) {
            new Thread(() -> {
                try { request("POST", "/api/app/revoke", "", oldToken); }
                catch (Exception ignored) { }
            }).start();
        }
    }

    private void addProgress(LinearLayout page, int activeStep) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"1  Bot", "2  Teléfono", "3  Código"};
        for (int i = 0; i < labels.length; i++) {
            int step = i + 1;
            TextView item = text(labels[i], 11, true);
            item.setGravity(Gravity.CENTER);
            item.setTextColor(step <= activeStep ? Color.rgb(5, 26, 35) : Color.rgb(172, 192, 207));
            item.setBackground(roundRect(step <= activeStep ? Color.rgb(99, 230, 216) : Color.rgb(22, 39, 57), 18, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            if (i > 0) lp.setMargins(dp(6), 0, 0, 0);
            row.addView(item, lp);
        }
        page.addView(row);
    }

    private boolean isDarkTheme() {
        String selected = prefs.getString(PREF_UI_THEME, "dark");
        if ("light".equals(selected)) return false;
        if ("system".equals(selected)) {
            int mode = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }
        return true;
    }

    private void applySystemBarTheme() {
        getWindow().setStatusBarColor(backgroundColor());
        getWindow().setNavigationBarColor(backgroundColor());
        int systemFlags = 0;
        if (!isDarkTheme()) {
            systemFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                systemFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(systemFlags);
    }

    private int backgroundColor() {
        return isDarkTheme() ? Color.rgb(8, 10, 16) : Color.rgb(246, 247, 250);
    }

    private int surfaceColor() {
        return isDarkTheme() ? Color.rgb(16, 19, 28) : Color.WHITE;
    }

    private int raisedSurfaceColor() {
        return isDarkTheme() ? Color.rgb(22, 26, 37) : Color.rgb(239, 240, 244);
    }

    private int borderColor() {
        return isDarkTheme() ? Color.rgb(35, 40, 54) : Color.rgb(218, 220, 227);
    }

    private int primaryTextColor() {
        return isDarkTheme() ? Color.WHITE : Color.rgb(27, 28, 34);
    }

    private int secondaryTextColor() {
        return isDarkTheme() ? Color.rgb(173, 175, 184) : Color.rgb(93, 96, 106);
    }

    private int accentColor() {
        try { return Color.parseColor(prefs.getString(PREF_UI_ACCENT, "#C62828")); }
        catch (Exception ignored) { return Color.rgb(198, 40, 40); }
    }

    private int onAccentColor() {
        int color = accentColor();
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255.0;
        return luminance > 0.62 ? Color.rgb(25, 26, 30) : Color.WHITE;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));
        box.setBackground(roundRect(surfaceColor(), 20, borderColor()));
        return box;
    }

    private LinearLayout infoCard(String title, String description) {
        LinearLayout box = card();
        TextView heading = text(title, 14, true);
        heading.setTextColor(accentColor());
        box.addView(heading);
        box.addView(bodyText(description), margin(0, 5, 0, 0));
        return box;
    }

    private EditText input(String hint, int inputType) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setSingleLine(true);
        edit.setSaveEnabled(false);
        edit.setInputType(inputType);
        edit.setTextColor(primaryTextColor());
        edit.setHintTextColor(secondaryTextColor());
        edit.setTextSize(16);
        edit.setPadding(dp(14), dp(11), dp(14), dp(11));
        edit.setBackground(roundRect(raisedSurfaceColor(), 14, borderColor()));
        return edit;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(onAccentColor());
        button.setMinHeight(dp(54));
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        button.setBackground(roundRect(accentColor(), 16, 0));
        animateButton(button);
        return button;
    }

    private Button goldButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.rgb(40, 27, 6));
        button.setMinHeight(dp(50));
        button.setPadding(dp(16), dp(9), dp(16), dp(9));
        button.setBackground(roundRect(Color.rgb(242, 198, 106), 16, 0));
        animateButton(button);
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(accentColor());
        button.setMinHeight(dp(42));
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setBackground(roundRect(raisedSurfaceColor(), 14, borderColor()));
        animateButton(button);
        return button;
    }

    private void animateButton(Button button) {
        button.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
            return false;
        });
    }

    private TextView statusText(String value) {
        TextView status = bodyText(value);
        status.setTextColor(accentColor());
        return status;
    }

    private TextView bodyText(String value) {
        TextView v = text(value, 14, false);
        v.setTextColor(secondaryTextColor());
        v.setLineSpacing(dp(2), 1f);
        return v;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(primaryTextColor());
        v.setTextSize(size);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private GradientDrawable roundRect(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (strokeColor != 0) drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private void showPage(LinearLayout page, boolean animated) {
        homeTabPageVisible = false;
        showingAiChat = false;
        if (dashboardFab != null && appRoot != null) {
            appRoot.removeView(dashboardFab);
            dashboardFab = null;
        }
        if (currentPage != null) currentPage.animate().cancel();
        page.animate().cancel();
        appScroll.removeAllViews();
        currentPage = page;
        page.setVisibility(View.VISIBLE);
        page.setAlpha(animated ? 0f : 1f);
        page.setTranslationY(animated ? dp(12) : 0f);
        appScroll.addView(page, new FrameLayout.LayoutParams(-1, -2));
        if (animated) {
            page.animate().alpha(1f).translationY(0f).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private String deviceId() {
        String id = prefs.getString("device_id", "");
        if (id.matches("[0-9a-f]{64}")) return id;
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId != null && !androidId.isEmpty()) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(("Tod4S3Sync:" + androidId).getBytes(StandardCharsets.UTF_8));
                StringBuilder fingerprint = new StringBuilder(64);
                final char[] hex = "0123456789abcdef".toCharArray();
                for (byte value : digest) {
                    fingerprint.append(hex[(value >> 4) & 0x0f]);
                    fingerprint.append(hex[value & 0x0f]);
                }
                id = fingerprint.toString();
            } catch (Exception ignored) {
                id = androidId;
            }
        } else if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
        }
        prefs.edit().putString("device_id", id).apply();
        return id;
    }

    private void pairDevice(View trigger) {
        if (pairingInFlight) return;
        final boolean renewExistingLink = relinkingBotAccount;
        final String code = codeInput.getText().toString().trim().toUpperCase(java.util.Locale.ROOT);
        if (code.isEmpty()) {
            if (syncStatus != null) syncStatus.setText("Escribe el código recibido con /app.");
            return;
        }
        pendingPairingCode = code;
        pairingInFlight = true;
        trigger.setEnabled(false);
        syncStatus.setText("Vinculando con el bot…");
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("code", code).put("device_id", deviceId());
                JSONObject result = request("POST", "/api/app/pair", body.toString(), "");
                String newToken = result.optString("token", "");
                if (newToken.isEmpty()) throw new Exception("Código inválido o vencido");
                prefs.edit().putString("token", newToken).apply();
                token = newToken;
                pendingPairingCode = "";
                runOnUiThread(() -> {
                    pairingInFlight = false;
                    if (renewExistingLink) {
                        relinkingBotAccount = false;
                        showDashboard(dashboardFilter, true);
                        refreshAccountProfile();
                        Toast.makeText(this, "Vínculo con el bot renovado. Tu sesión ToDus se conservó.", Toast.LENGTH_LONG).show();
                    } else {
                        showPhoneStep(true);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pairingInFlight = false;
                    if (appBanned) return;
                    trigger.setEnabled(true);
                    if (syncStatus != null) syncStatus.setText("No se pudo vincular: " + e.getMessage());
                });
            }
        }).start();
    }

    private void requestToDusPin(View trigger) {
        if (toDusPinRequestInFlight) {
            if (todusStatus != null) todusStatus.setText("Ya se está solicitando un código. Espera a que termine.");
            return;
        }
        final String phone = phoneInput == null ? todusPhone : phoneInput.getText().toString().trim();
        if (phone.isEmpty()) {
            if (todusStatus != null) todusStatus.setText("Escribe tu número ToDus.");
            return;
        }
        final LinearLayout requestPage = currentPage;
        final int generation;
        synchronized (toDusAuthAttemptLock) {
            toDusPinRequestInFlight = true;
            generation = ++toDusPinRequestGeneration;
        }
        trigger.setEnabled(false);
        if (todusStatus != null) todusStatus.setText("Solicitando código a ToDus…");
        final Runnable timeout = () -> {
            synchronized (toDusAuthAttemptLock) {
                if (generation != toDusPinRequestGeneration || !toDusPinRequestInFlight) return;
                toDusPinRequestGeneration++;
                toDusPinRequestInFlight = false;
            }
            trigger.setEnabled(true);
            if (currentPage == requestPage && todusStatus != null) {
                todusStatus.setText("ToDus tardó demasiado en responder. La app sigue funcionando; revisa la conexión y vuelve a intentarlo.");
            }
        };
        handler.postDelayed(timeout, 32_000L);
        new Thread(() -> {
            try {
                String normalizedPhone = ToDusAuth.requestPin(phone);
                synchronized (toDusAuthAttemptLock) {
                    if (generation != toDusPinRequestGeneration) return;
                    putEncrypted(PREF_TODUS_PHONE, normalizedPhone);
                    todusPhone = normalizedPhone;
                    prefs.edit().putBoolean(PREF_TODUS_PIN_REQUESTED, true).apply();
                }
                runOnUiThread(() -> {
                    handler.removeCallbacks(timeout);
                    synchronized (toDusAuthAttemptLock) {
                        if (generation != toDusPinRequestGeneration) return;
                        toDusPinRequestInFlight = false;
                    }
                    trigger.setEnabled(true);
                    if (currentPage == requestPage) {
                        showPinStep(true);
                        if (todusStatus != null) todusStatus.setText("Código solicitado. Usa el PIN más reciente que recibas.");
                    } else {
                        Toast.makeText(this, "PIN solicitado. Vuelve al paso de autenticación para introducirlo.", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    handler.removeCallbacks(timeout);
                    synchronized (toDusAuthAttemptLock) {
                        if (generation != toDusPinRequestGeneration) return;
                        toDusPinRequestInFlight = false;
                    }
                    trigger.setEnabled(true);
                    if (currentPage == requestPage && todusStatus != null) {
                        todusStatus.setText("No se pudo solicitar el código: " + e.getMessage());
                    } else {
                        Toast.makeText(this, "No se pudo solicitar el PIN. Puedes volver a intentarlo desde ToDus.", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "todus-pin-request").start();
    }

    private void validateToDusPin(View trigger) {
        if (toDusValidationInFlight) {
            if (todusStatus != null) todusStatus.setText("La validación ya está en curso. Espera un momento.");
            return;
        }
        final String phone = todusPhone.isEmpty() && phoneInput != null
                ? phoneInput.getText().toString().trim() : todusPhone;
        final String pin = pinInput == null ? "" : pinInput.getText().toString().trim();
        if (phone.isEmpty() || pin.isEmpty()) {
            if (todusStatus != null) todusStatus.setText("Escribe el código recibido por ToDus.");
            return;
        }
        final LinearLayout validationPage = currentPage;
        final int generation;
        synchronized (toDusAuthAttemptLock) {
            toDusValidationInFlight = true;
            generation = ++toDusValidationGeneration;
        }
        trigger.setEnabled(false);
        if (todusStatus != null) todusStatus.setText("Validando el código e iniciando sesión…");
        final Runnable timeout = () -> {
            synchronized (toDusAuthAttemptLock) {
                if (generation != toDusValidationGeneration || !toDusValidationInFlight) return;
                toDusValidationGeneration++;
                toDusValidationInFlight = false;
            }
            trigger.setEnabled(true);
            if (currentPage == validationPage && todusStatus != null) {
                todusStatus.setText("La autenticación tardó demasiado y se detuvo en esta pantalla. Comprueba tu conexión e inténtalo otra vez.");
            }
        };
        handler.postDelayed(timeout, 65_000L);
        new Thread(() -> {
            try {
                ToDusAuth.Session session = ToDusAuth.validateAndLogin(phone, pin);
                synchronized (toDusAuthAttemptLock) {
                    if (generation != toDusValidationGeneration) return;
                    putEncrypted(PREF_TODUS_PHONE, session.phone);
                    putEncrypted(PREF_TODUS_PASSWORD, session.password);
                    putEncrypted(PREF_TODUS_TOKEN, session.token);
                    todusPhone = session.phone;
                    todusPassword = session.password;
                    todusToken = session.token;
                    prefs.edit().putBoolean(PREF_TODUS_PIN_REQUESTED, false).apply();
                }
                runOnUiThread(() -> {
                    handler.removeCallbacks(timeout);
                    synchronized (toDusAuthAttemptLock) {
                        if (generation != toDusValidationGeneration) return;
                        toDusValidationInFlight = false;
                    }
                    trigger.setEnabled(true);
                    if (currentPage == validationPage) {
                        showHomeStep(true);
                        setToDusSessionStatus("Sesión ToDus conectada correctamente.");
                        if (syncStatus != null) syncStatus.setText("Sesión iniciada. Tus credenciales quedaron cifradas.");
                    } else {
                        Toast.makeText(this, "Sesión ToDus conectada. Vuelve a tus archivos para continuar.", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    handler.removeCallbacks(timeout);
                    synchronized (toDusAuthAttemptLock) {
                        if (generation != toDusValidationGeneration) return;
                        toDusValidationInFlight = false;
                    }
                    trigger.setEnabled(true);
                    if (currentPage == validationPage && todusStatus != null) {
                        todusStatus.setText("No se pudo iniciar sesión en ToDus: " + e.getMessage());
                    } else {
                        Toast.makeText(this, "No se pudo iniciar sesión en ToDus. Vuelve al paso de autenticación e inténtalo de nuevo.", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "todus-pin-validation").start();
    }

    private void restoreToDusSession() {
        final String phone = todusPhone;
        final String password = todusPassword;
        if (phone.isEmpty() || password.isEmpty()) {
            setToDusSessionStatus("No hay una sesión ToDus guardada.");
            if (todusStatus != null) todusStatus.setText("No hay una sesión ToDus guardada para restaurar.");
            return;
        }
        setToDusSessionStatus("Restaurando sesión ToDus…");
        if (syncStatus != null) syncStatus.setText("Restaurando sesión ToDus…");
        new Thread(() -> {
            try {
                String newToken = ToDusAuth.login(phone, password);
                putEncrypted(PREF_TODUS_TOKEN, newToken);
                todusToken = newToken;
                runOnUiThread(() -> {
                    setToDusSessionStatus("Sesión ToDus restaurada correctamente.");
                    if (syncStatus != null) syncStatus.setText("Sesión ToDus restaurada.");
                    if (deliveriesBox == null && !showingPlansPage) showHomeStep(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setToDusSessionStatus("No se pudo restaurar; vuelve a conectar ToDus.");
                    if (!showingPlansPage) showPhoneStep(true);
                    if (todusStatus != null) todusStatus.setText("No se pudo restaurar la sesión: " + e.getMessage());
                });
            }
        }).start();
    }

    private SecretKey getOrCreateKeystoreKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }

    private String encryptSecret(String plainText) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey());
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        String data = Base64.encodeToString(encrypted, Base64.NO_WRAP);
        return iv + "." + data;
    }

    private String decryptSecret(String stored) throws Exception {
        if (stored == null || stored.isEmpty()) return "";
        String[] pieces = stored.split("\\.", 2);
        if (pieces.length != 2) throw new Exception("Formato de credencial inválido");
        byte[] iv = Base64.decode(pieces[0], Base64.NO_WRAP);
        byte[] encrypted = Base64.decode(pieces[1], Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private void putEncrypted(String key, String value) throws Exception {
        String encrypted = encryptSecret(value);
        prefs.edit().putString(key, encrypted).apply();
    }

    private String readEncrypted(String key) throws Exception {
        return decryptSecret(prefs.getString(key, ""));
    }

    private void loadToDusCredentials() {
        try {
            todusPhone = readEncrypted(PREF_TODUS_PHONE);
            todusPassword = readEncrypted(PREF_TODUS_PASSWORD);
            todusToken = readEncrypted(PREF_TODUS_TOKEN);
            setToDusSessionStatus(!todusPhone.isEmpty() && !todusPassword.isEmpty()
                    ? "Sesión guardada; comprobando conexión con ToDus…"
                    : "No hay una sesión ToDus guardada.");
        } catch (Exception e) {
            savedCredentialsCouldNotBeDecrypted = true;
            clearToDusCredentials();
        }
    }

    private void clearToDusCredentials() {
        prefs.edit()
                .remove(PREF_TODUS_PHONE)
                .remove(PREF_TODUS_PASSWORD)
                .remove(PREF_TODUS_TOKEN)
                .apply();
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) { }
        todusPhone = "";
        todusPassword = "";
        todusToken = "";
        setToDusSessionStatus("Sesión ToDus desconectada.");
    }

    private void refreshDeliveries() {
        refreshDeliveries(null);
    }

    private void refreshDeliveries(View trigger) {
        final String current = token;
        if (appBanned || current == null || current.isEmpty() || (deliveriesBox == null && !viewingDashboard)) return;
        if (deliveryRefreshInFlight) {
            if (syncStatus != null) syncStatus.setText("Las entregas ya se están actualizando…");
            return;
        }
        deliveryRefreshInFlight = true;
        if (trigger != null) trigger.setEnabled(false);
        new Thread(() -> {
            try {
                JSONObject result = request("GET", "/api/app/deliveries", "", current);
                JSONArray rows = result.optJSONArray("deliveries");
                runOnUiThread(() -> {
                    deliveryRefreshInFlight = false;
                    if (trigger != null) trigger.setEnabled(true);
                    JSONArray nextRows = rows == null ? new JSONArray() : rows;
                    boolean changed = !jsonArraysEqual(latestDeliveryRows, nextRows);
                    latestDeliveryRows = nextRows;
                    if (viewingDashboard && changed) renderDashboardItems();
                    else if (!viewingDashboard) renderDeliveries(latestDeliveryRows);
                    if (syncStatus != null) syncStatus.setText("Entregas actualizadas.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    deliveryRefreshInFlight = false;
                    if (trigger != null) trigger.setEnabled(true);
                    if (syncStatus != null) syncStatus.setText("Servidor no disponible; se reintentará.");
                });
            }
        }).start();
    }

    private void refreshAppTasks() {
        final String current = token;
        if (appBanned || current == null || current.isEmpty() || appTaskRefreshInFlight) return;
        appTaskRefreshInFlight = true;
        new Thread(() -> {
            try {
                JSONObject result = request("GET", "/api/app/tasks", "", current);
                JSONArray rows = result.optJSONArray("tasks");
                runOnUiThread(() -> {
                    appTaskRefreshInFlight = false;
                    JSONArray nextRows = rows == null ? new JSONArray() : rows;
                    boolean changed = !appTaskRowsVisiblyEqual(latestAppTaskRows, nextRows);
                    latestAppTaskRows = nextRows;
                    if (viewingDashboard && changed) renderDashboardItems();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> appTaskRefreshInFlight = false);
            }
        }, "app-task-refresh").start();
    }

    private boolean jsonArraysEqual(JSONArray first, JSONArray second) {
        return (first == null ? "[]" : first.toString())
                .equals(second == null ? "[]" : second.toString());
    }

    private boolean appTaskRowsVisiblyEqual(JSONArray first, JSONArray second) {
        int firstLength = first == null ? 0 : first.length();
        int secondLength = second == null ? 0 : second.length();
        if (firstLength != secondLength) return false;
        String[] visibleFields = {"task_id", "filename", "state", "error"};
        for (int i = 0; i < firstLength; i++) {
            JSONObject left = first.optJSONObject(i);
            JSONObject right = second.optJSONObject(i);
            if (left == null || right == null) {
                if (left != right) return false;
                continue;
            }
            for (String field : visibleFields) {
                if (!left.optString(field, "").equals(right.optString(field, ""))) return false;
            }
            if (left.optLong("size", 0) != right.optLong("size", 0)) return false;
        }
        return true;
    }

    private void refreshAccountProfile() {
        final String current = token;
        if (current == null || current.isEmpty()) return;
        if (profileRefreshInFlight) {
            profileRefreshPending = true;
            return;
        }
        profileRefreshInFlight = true;
        final int profileAccessGeneration = appAccessGeneration;
        final boolean previousPremium = premiumActive;
        final boolean previousVerified = premiumVerified;
        new Thread(() -> {
            try {
                JSONObject profile = request("GET", "/api/app/profile", "", current);
                boolean newPremium = profile.optBoolean("premium", false);
                boolean newPremiumVerified = profile.optBoolean("premium_verified", false);
                String newPlan = profile.optString("plan", "free");
                String newExpiry = profile.optString("expires", "");
                JSONObject quota = profile.optJSONObject("quota");
                long newQuotaLimit = quota == null ? 0 : Math.max(0, quota.optLong("limit_bytes", 0));
                long newQuotaUsed = quota == null ? 0 : Math.max(0, quota.optLong("used_bytes", 0));
                long newQuotaRemaining = quota == null ? 0 : Math.max(0, quota.optLong("remaining_bytes", 0));
                int newQuotaPercent = quota == null ? 0 : Math.max(0, Math.min(100, quota.optInt("percent_used", 0)));
                long newQuotaResetIn = quota == null ? 0 : Math.max(0, quota.optLong("reset_in_seconds", 0));
                runOnUiThread(() -> {
                    if (!current.equals(token)) {
                        profileRefreshInFlight = false;
                        profileRefreshPending = false;
                        return;
                    }
                    int profileAccess = acceptProfileAccess(profileAccessGeneration);
                    if (profileAccess < 0) {
                        profileRefreshInFlight = false;
                        profileRefreshPending = false;
                        return;
                    }
                    boolean refreshAgain = profileRefreshPending;
                    profileRefreshPending = false;
                    boolean accessRestored = profileAccess == 1;
                    boolean entitlementChanged = previousPremium != newPremium || previousVerified != newPremiumVerified;
                    premiumActive = newPremium;
                    premiumVerified = newPremiumVerified;
                    activePlan = newPlan;
                    premiumExpiresAt = newExpiry;
                    freeQuotaLimitBytes = newQuotaLimit;
                    freeQuotaUsedBytes = newQuotaUsed;
                    freeQuotaRemainingBytes = newQuotaRemaining;
                    freeQuotaPercent = newQuotaPercent;
                    freeQuotaResetInSeconds = newQuotaResetIn;
                    profileRefreshInFlight = false;
                    prefs.edit().putBoolean(PREF_ACTIVE_PREMIUM, premiumActive)
                            .putBoolean(PREF_PREMIUM_VERIFIED, premiumVerified)
                            .putString(PREF_ACTIVE_PLAN, activePlan)
                            .putString("active_plan_expires_v1", premiumExpiresAt).apply();
                    if (planStatus != null) planStatus.setText(accountPlanStatusText());
                    if (dashboardPlanStatusText != null) {
                        dashboardPlanStatusText.setText(accountPlanStatusText() + "  ·  Toca para ver planes");
                    }
                    updateQuotaUsageCard();
                    updateFreeQuotaPanel();
                    if (accessRestored) {
                        showUnbannedScreen();
                    } else if (entitlementChanged && showingPlansPage) {
                        showPlansStep(false);
                    } else if (entitlementChanged && homeTabPageVisible) {
                        if (showingAiChat && !hasAiPremiumAccess()) {
                            showHomeSection(false, false);
                            Toast.makeText(this, "El acceso Premium a AiTod4S3 ya no está activo.", Toast.LENGTH_LONG).show();
                        } else if (showingAiChat) {
                            showAiChat(false);
                        } else {
                            showHomeSection(viewingDownloadsTab, false);
                        }
                    }
                    if (refreshAgain) handler.postDelayed(this::refreshAccountProfile, 120L);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    profileRefreshInFlight = false;
                    boolean refreshAgain = profileRefreshPending;
                    profileRefreshPending = false;
                    if (refreshAgain) handler.postDelayed(this::refreshAccountProfile, 250L);
                });
            }
        }, "account-profile-refresh").start();
    }

    private synchronized int acceptProfileAccess(int requestGeneration) {
        if (requestGeneration != appAccessGeneration) return -1;
        if (!appBanned) return 0;
        appBanned = false;
        appAccessGeneration++;
        return 1;
    }

    private synchronized boolean markAppBanned(int requestGeneration) {
        if (requestGeneration != appAccessGeneration || appBanned) return false;
        appAccessGeneration++;
        appBanned = true;
        return true;
    }

    private synchronized void invalidateAccessRequests() {
        appAccessGeneration++;
        appBanned = false;
    }

    private String accountPlanStatusText() {
        if (premiumActive) {
            String label = premiumVerified ? "Plan Premium activo y verificado en este móvil"
                    : "Premium activo para tu ID · falta pegar el token del administrador";
            if (!premiumExpiresAt.isEmpty()) label += " · hasta " + premiumExpiresAt;
            else label += " · permanente";
            return label;
        }
        if ("free".equals(activePlan) && freeQuotaLimitBytes > 0) {
            return "Plan Gratis · " + freeQuotaPercent + "% de la cuota diaria usada · reinicio en "
                    + quotaCountdownLabel(freeQuotaResetInSeconds);
        }
        return token == null || token.isEmpty()
                ? "Vincula tu cuenta para sincronizar el plan activado por el bot."
                : "Plan actual: Gratis · se actualizará automáticamente si activan Premium.";
    }

    private void updateQuotaUsageCard() {
        if (quotaUsageBox == null) return;
        if (token == null || token.isEmpty() || !"free".equals(activePlan) || freeQuotaLimitBytes <= 0) {
            quotaUsageBox.removeAllViews();
            quotaUsageBox.setVisibility(View.GONE);
            quotaProgressBar = null;
            quotaUsageText = null;
            quotaResetText = null;
            return;
        }
        quotaUsageBox.setVisibility(View.VISIBLE);
        if (quotaProgressBar == null || quotaUsageText == null || quotaResetText == null) {
            LinearLayout quotaCard = card();
            quotaCard.setPadding(dp(14), dp(11), dp(14), dp(11));
            TextView title = text("CUOTA GRATUITA · 1 GB CADA 24 H", 11, true);
            title.setTextColor(accentColor());
            quotaCard.addView(title);
            quotaProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            quotaProgressBar.setMax(100);
            quotaProgressBar.setProgressTintList(ColorStateList.valueOf(accentColor()));
            quotaCard.addView(quotaProgressBar, margin(0, 8, 0, 0));
            quotaUsageText = bodyText("");
            quotaUsageText.setTextSize(12);
            quotaCard.addView(quotaUsageText, margin(0, 5, 0, 0));
            quotaResetText = bodyText("");
            quotaResetText.setTextSize(11);
            quotaCard.addView(quotaResetText, margin(0, 3, 0, 0));
            quotaUsageBox.removeAllViews();
            quotaUsageBox.addView(quotaCard);
        }
        int percent = Math.max(0, Math.min(100, freeQuotaPercent));
        quotaProgressBar.setProgress(percent);
        quotaUsageText.setText(percent + "% usado · " + formatBytes(freeQuotaUsedBytes)
                + " de " + formatBytes(freeQuotaLimitBytes) + " · quedan "
                + formatBytes(freeQuotaRemainingBytes));
        quotaResetText.setText("La cuota gratis se renueva en " + quotaCountdownLabel(freeQuotaResetInSeconds));
    }

    private void updateFreeQuotaPanel() {
        if (freeQuotaPanel == null) return;
        if (token == null || token.isEmpty() || !"free".equals(activePlan) || freeQuotaLimitBytes <= 0) {
            freeQuotaPanel.setVisibility(View.GONE);
            freeQuotaPanel.removeAllViews();
            freeQuotaPanelProgress = null;
            freeQuotaPanelUsage = null;
            freeQuotaPanelReset = null;
            return;
        }
        freeQuotaPanel.setVisibility(View.VISIBLE);
        if (freeQuotaPanelProgress == null || freeQuotaPanelUsage == null || freeQuotaPanelReset == null) {
            freeQuotaPanelProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            freeQuotaPanelProgress.setMax(100);
            freeQuotaPanelProgress.setProgressTintList(ColorStateList.valueOf(accentColor()));
            freeQuotaPanel.addView(freeQuotaPanelProgress, margin(0, 0, 0, 0));
            freeQuotaPanelUsage = bodyText("");
            freeQuotaPanelUsage.setTextSize(12);
            freeQuotaPanel.addView(freeQuotaPanelUsage, margin(0, 5, 0, 0));
            freeQuotaPanelReset = bodyText("");
            freeQuotaPanelReset.setTextSize(11);
            freeQuotaPanel.addView(freeQuotaPanelReset, margin(0, 3, 0, 0));
            freeQuotaPanel.setAlpha(0f);
            freeQuotaPanel.setTranslationY(dp(4));
            freeQuotaPanel.animate().alpha(1f).translationY(0f).setDuration(190)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        int percent = Math.max(0, Math.min(100, freeQuotaPercent));
        freeQuotaPanelProgress.setProgress(percent);
        freeQuotaPanelUsage.setText(percent + "% usado · " + formatBytes(freeQuotaUsedBytes)
                + " de " + formatBytes(freeQuotaLimitBytes) + " · quedan "
                + formatBytes(freeQuotaRemainingBytes));
        freeQuotaPanelReset.setText("Reinicio de cuota en " + quotaCountdownLabel(freeQuotaResetInSeconds));
    }

    private String quotaCountdownLabel(long seconds) {
        long safe = Math.max(0, seconds);
        long hours = safe / 3600;
        long minutes = (safe % 3600 + 59) / 60;
        if (minutes >= 60) { hours++; minutes = 0; }
        if (hours > 0) return hours + " h " + minutes + " min";
        return Math.max(1, minutes) + " min";
    }

    private void addToDusSessionStatus(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(10), dp(13), dp(10));
        card.setBackground(roundRect(Color.rgb(11, 31, 48), 15, borderColor()));
        TextView title = text("SESIÓN TODUS", 10, true);
        title.setTextColor(accentColor());
        card.addView(title);
        TextView detail = bodyText(todusSessionStatusMessage);
        detail.setTextSize(13);
        detail.setSingleLine(false);
        card.addView(detail, margin(0, 4, 0, 0));
        parent.addView(card, margin(0, 0, 0, 9));
        todusSessionStatusText = detail;
        colorToDusSessionStatus(detail, todusSessionStatusMessage);
    }

    private void setToDusSessionStatus(String message) {
        todusSessionStatusMessage = message;
        if (todusSessionStatusText != null) {
            todusSessionStatusText.setText(message);
            colorToDusSessionStatus(todusSessionStatusText, message);
        }
    }

    private void colorToDusSessionStatus(TextView view, String message) {
        String lower = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("restaurada") || lower.contains("conectada")) {
            view.setTextColor(Color.rgb(99, 230, 216));
        } else if (lower.contains("no se pudo") || lower.contains("desconectada")
                || lower.contains("no hay una sesión")) {
            view.setTextColor(Color.rgb(255, 174, 160));
        } else {
            view.setTextColor(Color.rgb(242, 198, 106));
        }
    }

    private void renderDeliveries(JSONArray rows) {
        if (deliveriesBox == null) return;
        deliveriesBox.removeAllViews();
        int shown = 0;
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                try {
                    JSONObject row = rows.getJSONObject(i);
                    if (!"tod4s3_bot".equals(row.optString("source", "tod4s3_bot"))) continue;
                    String taskId = row.optString("task_id", "");
                    if (isDeliveryHidden(taskId)) continue;
                    String name = row.optString("filename", "archivo");
                    long size = row.optLong("size", 0);
                    boolean cloudDeletable = row.optBoolean("cloud_deletable", false);
                    boolean todusDeletable = row.optBoolean("todus_deletable", false);
                    JSONArray parts = deliveryParts(row);
                    int partCount = parts.length();
                    if (partCount == 0) continue;
                    LinearLayout card = new LinearLayout(this);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(dp(14), dp(12), dp(14), dp(12));
                    GradientDrawable cardBackground = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                            new int[]{surfaceColor(), raisedSurfaceColor()});
                    cardBackground.setCornerRadius(dp(18));
                    cardBackground.setStroke(dp(1), borderColor());
                    card.setBackground(cardBackground);
                    card.setElevation(dp(1));
                    String partLabel = partCount == 1 ? "1 parte" : partCount + " partes";
                    LinearLayout fileHeader = new LinearLayout(this);
                    fileHeader.setOrientation(LinearLayout.HORIZONTAL);
                    fileHeader.setGravity(Gravity.CENTER_VERTICAL);
                    fileHeader.addView(iconBadge(R.drawable.ic_file_card, accentColor(),
                            raisedSurfaceColor()), new LinearLayout.LayoutParams(dp(42), dp(42)));
                    LinearLayout fileInfo = new LinearLayout(this);
                    fileInfo.setOrientation(LinearLayout.VERTICAL);
                    fileInfo.setGravity(Gravity.CENTER_VERTICAL);
                    TextView source = text("ENTREGA DEL BOT", 9, true);
                    source.setTextColor(accentColor());
                    fileInfo.addView(source);
                    TextView fileName = text(name, 14, true);
                    fileName.setSingleLine(true);
                    fileName.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    fileInfo.addView(fileName, margin(0, 2, 0, 0));
                    TextView fileMeta = bodyText(formatBytes(size) + " · " + partLabel);
                    fileMeta.setTextSize(12);
                    fileMeta.setSingleLine(true);
                    fileInfo.addView(fileMeta, margin(0, 2, 0, 0));
                    LinearLayout.LayoutParams fileInfoLayout = new LinearLayout.LayoutParams(0, -2, 1f);
                    fileInfoLayout.setMargins(dp(10), 0, 0, 0);
                    fileHeader.addView(fileInfo, fileInfoLayout);
                    if (!taskId.isEmpty()) {
                        Button hide = secondaryButton("Ocultar");
                        hide.setTextSize(11);
                        hide.setMinHeight(dp(36));
                        hide.setPadding(dp(8), dp(2), dp(8), dp(2));
                        hide.setOnClickListener(v -> confirmHideDelivery(taskId, name));
                        LinearLayout.LayoutParams hideLayout = new LinearLayout.LayoutParams(-2, dp(36));
                        hideLayout.setMargins(dp(7), 0, 0, 0);
                        fileHeader.addView(hide, hideLayout);
                    }
                    card.addView(fileHeader);
                    View divider = new View(this);
                    divider.setBackgroundColor(borderColor());
                    LinearLayout.LayoutParams dividerLayout = new LinearLayout.LayoutParams(-1, dp(1));
                    dividerLayout.setMargins(0, dp(9), 0, 0);
                    card.addView(divider, dividerLayout);
                    TextView progressLabel = bodyText("Preparada para descargar");
                    progressLabel.setVisibility(View.GONE);
                    card.addView(progressLabel, margin(0, 8, 0, 4));
                    ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                    progressBar.setMax(100);
                    progressBar.setProgressTintList(ColorStateList.valueOf(accentColor()));
                    progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(borderColor()));
                    progressBar.setVisibility(View.GONE);
                    LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(-1, dp(8));
                    progressLayout.setMargins(0, 0, 0, dp(8));
                    card.addView(progressBar, progressLayout);
                    LinearLayout cloudActions = new LinearLayout(this);
                    cloudActions.setOrientation(LinearLayout.HORIZONTAL);
                    Button download = primaryButton(partCount > 1
                            ? "Descargar y unir · " + partCount : "Descargar");
                    download.setTextSize(13);
                    download.setMinHeight(dp(46));
                    download.setPadding(dp(10), dp(5), dp(10), dp(5));
                    setButtonIcon(download, R.drawable.ic_download_arrow, Color.rgb(5, 26, 35));
                    final JSONArray copy = parts;
                    final String copyName = name;
                    final long copySize = size;
                    download.setOnClickListener(v -> startToDusDelivery(taskId, copyName, copy, copySize));
                    cloudActions.addView(download, new LinearLayout.LayoutParams(0, -2, 1f));
                    if (!taskId.isEmpty() && (cloudDeletable || todusDeletable)) {
                        Button deleteCloud = secondaryButton(todusDeletable ? "Eliminar entrega" : "Eliminar nube");
                        deleteCloud.setTextSize(12);
                        deleteCloud.setMinHeight(dp(46));
                        LinearLayout.LayoutParams deleteLayout = new LinearLayout.LayoutParams(0, -2, 0.85f);
                        deleteLayout.setMargins(dp(8), 0, 0, 0);
                        cloudActions.addView(deleteCloud, deleteLayout);
                        deleteCloud.setOnClickListener(v -> confirmDeleteCloudDelivery(taskId, copyName, todusDeletable));
                    } else if (!taskId.isEmpty()) {
                        TextView storageNote = bodyText("Entrega alojada en ToDus; no se puede borrar desde Nube S3.");
                        storageNote.setTextSize(11);
                        storageNote.setTextColor(Color.rgb(180, 190, 205));
                        cloudActions.addView(storageNote, new LinearLayout.LayoutParams(0, -2, 1f));
                    }
                    card.addView(cloudActions, margin(0, 8, 0, 0));
                    deliveriesBox.addView(card, margin(0, 0, 0, 8));
                    shown++;
                } catch (Exception ignored) { }
            }
        }
        if (shown == 0) {
            TextView empty = bodyText("No tienes entregas nuevas de ToDus.");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(20), dp(12), dp(20));
            deliveriesBox.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private JSONArray deliveryParts(JSONObject row) {
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        String[] listKeys = {"parts", "urls", "part_urls", "links"};
        for (String key : listKeys) {
            Object value = row.opt(key);
            if (value instanceof JSONArray) {
                JSONArray candidates = (JSONArray) value;
                for (int i = 0; i < candidates.length(); i++) {
                    Object candidate = candidates.opt(i);
                    String url = "";
                    if (candidate instanceof JSONObject) {
                        JSONObject part = (JSONObject) candidate;
                        url = part.optString("url", part.optString("download_url",
                                part.optString("link", "")));
                    } else if (candidate instanceof String) {
                        url = (String) candidate;
                    }
                    if (url.startsWith("https://")) urls.add(url);
                }
            } else if (value instanceof String) {
                String raw = ((String) value).trim();
                if (raw.startsWith("https://")) {
                    urls.add(raw);
                } else if (raw.startsWith("[")) {
                    try {
                        JSONArray candidates = new JSONArray(raw);
                        for (int i = 0; i < candidates.length(); i++) {
                            String url = candidates.optString(i, "");
                            if (url.startsWith("https://")) urls.add(url);
                        }
                    } catch (Exception ignored) { }
                }
            }
        }
        for (String key : new String[]{"url", "download_url"}) {
            String url = row.optString(key, "");
            if (url.startsWith("https://")) urls.add(url);
        }
        return new JSONArray(urls);
    }

    private String hiddenDeliveryPreferenceKey() {
        String currentToken = token == null ? "" : token;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(currentToken.getBytes(StandardCharsets.UTF_8));
            return PREF_HIDDEN_DELIVERIES + "_" + Base64.encodeToString(
                    digest, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception ignored) {
            return PREF_HIDDEN_DELIVERIES + "_" + Integer.toHexString(currentToken.hashCode());
        }
    }

    private boolean isDeliveryHidden(String taskId) {
        if (taskId == null || taskId.isEmpty()) return false;
        try {
            JSONArray hidden = new JSONArray(prefs.getString(hiddenDeliveryPreferenceKey(), "[]"));
            for (int i = 0; i < hidden.length(); i++) {
                if (taskId.equals(hidden.optString(i, ""))) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void rememberHiddenDelivery(String taskId) {
        if (taskId == null || taskId.isEmpty() || isDeliveryHidden(taskId)) return;
        JSONArray hidden;
        try {
            hidden = new JSONArray(prefs.getString(hiddenDeliveryPreferenceKey(), "[]"));
        } catch (Exception ignored) {
            hidden = new JSONArray();
        }
        hidden.put(taskId);
        prefs.edit().putString(hiddenDeliveryPreferenceKey(), hidden.toString()).apply();
    }

    private void confirmHideDelivery(String taskId, String filename) {
        new AlertDialog.Builder(this)
                .setTitle("Quitar de recientes")
                .setMessage("«" + filename + "» se ocultará solo en esta app y no volverá a aparecer al actualizar. La entrega original seguirá en ToDus.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Ocultar", (dialog, which) -> {
                    rememberHiddenDelivery(taskId);
                    renderDeliveries(latestDeliveryRows);
                    Toast.makeText(this, "Entrega ocultada de esta lista; sigue en ToDus.", Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private String formatBytes(long n) {
        if (n < 1024 * 1024) return (n / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.2f MB", n / 1048576.0);
    }

    private void notifyDownloadCompleted(String filename) {
        handler.post(() -> {
            String safeName = filename == null || filename.trim().isEmpty() ? "Archivo" : filename.trim();
            android.util.Log.i(LOG_TAG, "Descarga completada y guardada en Descargas.");
            Toast.makeText(this, "Descarga completada en Descargas: " + safeName, Toast.LENGTH_LONG).show();
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;
            Intent openApp = new Intent(this, MainActivity.class);
            openApp.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pending = PendingIntent.getActivity(
                    this, nextDownloadNotificationId,
                    openApp, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, DOWNLOAD_NOTIFICATION_CHANNEL)
                    : new Notification.Builder(this).setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                            .setVibrate(new long[]{0, 180, 100, 180});
            Notification notification = builder
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Descarga completada")
                    .setContentText(safeName + " está disponible en Descargas")
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setAutoCancel(true)
                    .setContentIntent(pending)
                    .build();
            try {
                manager.notify(nextDownloadNotificationId++, notification);
            } catch (SecurityException ignored) {
                // El permiso puede haberse retirado entre la comprobación y el envío.
            }
        });
    }

    private void startToDusDelivery(String taskId, String name, JSONArray parts, long expectedSize) {
        if (parts == null || parts.length() == 0) return;
        String key = downloadCacheKey(name, parts);
        ToDusDownloadTask task;
        synchronized (toDusDownloadLock) {
            task = toDusDownloadTasks.get(key);
            if (task == null) {
                task = new ToDusDownloadTask(key, taskId == null ? "" : taskId, name, parts, Math.max(0L, expectedSize));
                toDusDownloadTasks.put(key, task);
                persistToDusDownloadTasksLocked();
            }
        }
        if (task.state.equals("downloading") || task.state.equals("saving") || task.state.equals("pausing")) return;
        startToDusDownload(task);
    }

    private boolean startToDusDownload(ToDusDownloadTask task) {
        if (!hasPublicDownloadPermission()) return false;
        final String sessionToken = todusToken;
        if (sessionToken == null || sessionToken.isEmpty()) {
            Toast.makeText(this, "Conecta tu cuenta ToDus antes de descargar.", Toast.LENGTH_LONG).show();
            return false;
        }
        int maxConcurrent = Math.max(1, Math.min(5, prefs.getInt(PREF_DOWNLOAD_CONCURRENCY, 3)));
        synchronized (downloadConcurrencyLock) {
            if (activeDownloadJobs >= maxConcurrent) {
                Toast.makeText(this, "Ya hay " + maxConcurrent + " descargas simultáneas. Espera o pausa una.", Toast.LENGTH_LONG).show();
                return false;
            }
            activeDownloadJobs++;
        }
        synchronized (toDusDownloadLock) {
            if (task.worker != null) {
                synchronized (downloadConcurrencyLock) { activeDownloadJobs = Math.max(0, activeDownloadJobs - 1); }
                return true;
            }
            task.pauseRequested = false;
            task.deleteRequested = false;
            task.error = "";
            task.state = "downloading";
            task.updatedAt = System.currentTimeMillis();
            task.control = new ParallelRangeDownloader.DownloadControl();
            task.worker = new Thread(() -> runToDusDownload(task, sessionToken), "todus-file-download");
            persistToDusDownloadTasksLocked();
            task.worker.start();
        }
        postToDusDownloadRefresh();
        return true;
    }

    private void runToDusDownload(ToDusDownloadTask task, String sessionToken) {
        File workDir = new File(new File(getFilesDir(), "todus-downloads"), task.key);
        DownloadTarget target = null;
        boolean saved = false;
        try {
            if (!workDir.exists() && !workDir.mkdirs()) throw new IOException("No se pudo preparar el almacenamiento temporal.");
            ParallelRangeDownloader.DownloadControl control = task.control;
            long completedBytes = 0L;
            int firstPart;
            synchronized (toDusDownloadLock) { firstPart = Math.max(0, Math.min(task.currentPart, task.parts.length())); }
            for (int i = 0; i < firstPart; i++) {
                File completedPart = new File(workDir, "part" + i);
                if (!completedPart.isFile()) throw new IOException("Falta un fragmento temporal; cancela esta tarea e inicia una nueva descarga.");
                completedBytes += completedPart.length();
            }
            for (int i = firstPart; i < task.parts.length(); i++) {
                control.check();
                final int partIndex = i;
                File part = new File(workDir, "part" + i);
                long partBase = completedBytes;
                ToDusFileTransfer.download(sessionToken, task.parts.getString(i), part, partBase,
                        (received, reportedTotal) -> updateToDusDownloadProgress(task, received,
                                task.expectedSize > 0 ? task.expectedSize : reportedTotal), control);
                control.check();
                completedBytes += part.length();
                task.currentPart = i + 1;
                task.bytes = completedBytes;
                task.updatedAt = System.currentTimeMillis();
                persistToDusDownloadTasks();
                postToDusDownloadRefresh();
            }
            if (task.expectedSize > 0L && completedBytes != task.expectedSize) {
                throw new IOException("El archivo unido no coincide con el tamaño original ("
                        + formatBytes(completedBytes) + " de " + formatBytes(task.expectedSize) + ").");
            }
            control.check();
            task.state = "saving";
            task.total = task.expectedSize > 0 ? task.expectedSize : completedBytes;
            task.bytes = completedBytes;
            persistToDusDownloadTasks();
            postToDusDownloadRefresh();
            target = openPublicDownload(task.filename);
            task.target = target;
            try (OutputStream output = target.stream) {
                byte[] buffer = new byte[64 * 1024];
                for (int i = 0; i < task.parts.length(); i++) {
                    control.check();
                    try (InputStream input = new FileInputStream(new File(workDir, "part" + i))) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            control.check();
                            output.write(buffer, 0, count);
                        }
                    }
                }
            }
            synchronized (toDusDownloadLock) {
                control.check();
                if (task.deleteRequested) throw new IOException("La descarga fue cancelada.");
                finishPublicDownload(target);
                recordDownloadedFile(target, completedBytes);
                saved = true;
                toDusDownloadTasks.remove(task.key);
            }
            final String savedName = target.name;
            notifyDownloadCompleted(savedName);
        } catch (Exception e) {
            if (target != null) deletePublicDownload(target);
            if (!task.pauseRequested && !task.deleteRequested) {
                task.state = "error";
                task.error = e.getMessage() == null ? "No se pudo completar la descarga." : e.getMessage();
                task.updatedAt = System.currentTimeMillis();
            }
        } finally {
            if (saved || task.deleteRequested) {
                deleteTree(workDir);
                synchronized (toDusDownloadLock) { toDusDownloadTasks.remove(task.key); }
            } else if (task.pauseRequested) {
                task.state = "paused";
                task.bytes = measureToDusDownloadBytes(task, workDir);
                task.updatedAt = System.currentTimeMillis();
            }
            task.target = null;
            task.worker = null;
            task.control = null;
            synchronized (downloadConcurrencyLock) { activeDownloadJobs = Math.max(0, activeDownloadJobs - 1); }
            persistToDusDownloadTasks();
            postToDusDownloadRefresh();
        }
    }

    private long measureToDusDownloadBytes(ToDusDownloadTask task, File workDir) {
        long bytes = 0L;
        int complete = Math.max(0, Math.min(task.currentPart, task.parts.length()));
        for (int i = 0; i < complete; i++) {
            File part = new File(workDir, "part" + i);
            if (part.isFile()) bytes += part.length();
        }
        if (complete < task.parts.length()) {
            File current = new File(workDir, "part" + complete);
            if (current.isFile()) bytes += current.length();
        }
        return bytes;
    }

    private void updateToDusDownloadProgress(ToDusDownloadTask task, long received, long total) {
        long now = System.currentTimeMillis();
        boolean update;
        synchronized (toDusDownloadLock) {
            task.bytes = Math.max(0L, received);
            if (total > 0L) task.total = Math.max(task.total, total);
            if (task.speedSampleAt > 0L && now > task.speedSampleAt) {
                task.speedBytesPerSecond = Math.max(0L,
                        (task.bytes - task.speedSampleBytes) * 1000L / (now - task.speedSampleAt));
            }
            task.speedSampleAt = now;
            task.speedSampleBytes = task.bytes;
            update = now - task.lastPersistAt >= 800L;
            if (update) {
                task.lastPersistAt = now;
                task.updatedAt = now;
            }
        }
        if (update) {
            persistToDusDownloadTasks();
            postToDusDownloadProgressUpdate(task);
        }
    }

    private void postToDusDownloadProgressUpdate(ToDusDownloadTask task) {
        runOnUiThread(() -> {
            DownloadCardRefs refs = task.cardRefs;
            if (!viewingDashboard || refs == null || refs.percent == null || refs.percent.getParent() == null) return;
            updateDownloadCardViews(refs, task.bytes, task.total, task.speedBytesPerSecond, task.state);
        });
    }

    private void pauseToDusDownload(String key) {
        ToDusDownloadTask task;
        synchronized (toDusDownloadLock) {
            task = toDusDownloadTasks.get(key);
            if (task == null || task.worker == null || task.control == null
                    || (!task.state.equals("downloading") && !task.state.equals("saving"))) return;
            task.pauseRequested = true;
            task.state = "pausing";
            task.updatedAt = System.currentTimeMillis();
            persistToDusDownloadTasksLocked();
        }
        task.control.cancel();
        if (task.worker != null) task.worker.interrupt();
        postToDusDownloadRefresh();
    }

    private void resumeToDusDownload(String key) {
        ToDusDownloadTask task;
        synchronized (toDusDownloadLock) { task = toDusDownloadTasks.get(key); }
        if (task != null && (task.state.equals("paused") || task.state.equals("error"))) startToDusDownload(task);
    }

    private void confirmCancelToDusDownload(String key, String filename) {
        new AlertDialog.Builder(this).setTitle("Cancelar descarga")
                .setMessage("Se cancelará esta descarga y se borrarán solo sus temporales. La entrega original de ToDus no se borrará.")
                .setNegativeButton("Volver", null)
                .setPositiveButton("Cancelar descarga", (dialog, which) -> cancelToDusDownload(key))
                .show();
    }

    private void cancelToDusDownload(String key) {
        ToDusDownloadTask task;
        synchronized (toDusDownloadLock) {
            task = toDusDownloadTasks.get(key);
            if (task == null) return;
            if (task.worker != null && task.control != null) {
                task.deleteRequested = true;
                task.pauseRequested = false;
                task.state = "deleting";
                task.updatedAt = System.currentTimeMillis();
                persistToDusDownloadTasksLocked();
            } else {
                toDusDownloadTasks.remove(key);
                persistToDusDownloadTasksLocked();
            }
        }
        if (task.worker != null && task.control != null) {
            task.control.cancel();
            task.worker.interrupt();
        } else {
            deleteTree(new File(new File(getFilesDir(), "todus-downloads"), task.key));
            postToDusDownloadRefresh();
        }
    }

    private void confirmDeleteCloudDelivery(String taskId, String filename, boolean nativeToDus) {
        String title = nativeToDus ? "Eliminar entrega de ToDus" : "Eliminar de Nube S3";
        String message = nativeToDus
                ? "Se eliminarán del chat ToDus las partes enviadas por el bot. Los archivos ya guardados en el teléfono no se borrarán."
                : "Se eliminarán de Nube S3 las partes de esta entrega. Los archivos ya guardados en el teléfono no se borrarán.";
        new AlertDialog.Builder(this).setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (dialog, which) -> deleteCloudDelivery(taskId, filename, nativeToDus))
                .show();
    }

    private void deleteCloudDelivery(String taskId, String filename, boolean nativeToDus) {
        if (taskId == null || taskId.trim().isEmpty()) {
            Toast.makeText(this, "Esta entrega no tiene task_id; no se puede solicitar el borrado remoto.", Toast.LENGTH_LONG).show();
            return;
        }
        ArrayList<String> matching = new ArrayList<>();
        synchronized (toDusDownloadLock) {
            for (ToDusDownloadTask task : toDusDownloadTasks.values()) {
                if (taskId.equals(task.taskId)) matching.add(task.key);
            }
        }
        for (String key : matching) cancelToDusDownload(key);
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("task_id", taskId);
                JSONObject result = request("POST", "/api/app/deliveries/delete", body.toString(), token == null ? "" : token);
                if (!result.optBoolean("ok", false)) {
                    throw new IOException(result.optString("message", "El servidor no confirmó el borrado remoto."));
                }
                rememberHiddenDelivery(taskId);
                runOnUiThread(() -> {
                    Toast.makeText(this, nativeToDus ? "Entrega eliminada de ToDus." : "Entrega eliminada de Nube S3.", Toast.LENGTH_LONG).show();
                    refreshDeliveries();
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "No se pudo eliminar la entrega remota." : e.getMessage();
                if (message.contains("delivery_not_deletable")) {
                    message = "Esta entrega es de ToDus, no de Nube S3. No se borró.";
                }
                final String userMessage = message;
                runOnUiThread(() -> Toast.makeText(this, "No se eliminó la entrega: " + userMessage, Toast.LENGTH_LONG).show());
            }
        }, "todus-cloud-delete").start();
    }

    private void postToDusDownloadRefresh() {
        runOnUiThread(() -> { if (viewingDashboard) renderDashboardItems(); });
    }

    private void persistToDusDownloadTasks() {
        synchronized (toDusDownloadLock) { persistToDusDownloadTasksLocked(); }
    }

    private void persistToDusDownloadTasksLocked() {
        JSONArray rows = new JSONArray();
        try {
            for (ToDusDownloadTask task : toDusDownloadTasks.values()) {
                if (task.state.equals("done") || task.state.equals("deleting")) continue;
                JSONObject row = new JSONObject().put("key", task.key).put("task_id", task.taskId)
                        .put("filename", task.filename).put("parts", task.parts).put("expected_size", task.expectedSize)
                        .put("bytes", task.bytes).put("total", task.total).put("current_part", task.currentPart)
                        .put("state", task.state).put("error", task.error).put("updated_at", task.updatedAt);
                rows.put(row);
            }
            prefs.edit().putString(PREF_TODUS_DOWNLOAD_TASKS, rows.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void loadToDusDownloadTasks() {
        try {
            JSONArray rows = new JSONArray(prefs.getString(PREF_TODUS_DOWNLOAD_TASKS, "[]"));
            synchronized (toDusDownloadLock) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    String key = row.optString("key", "");
                    JSONArray parts = row.optJSONArray("parts");
                    if (key.isEmpty() || parts == null || parts.length() == 0) continue;
                    ToDusDownloadTask task = new ToDusDownloadTask(key, row.optString("task_id", ""),
                            row.optString("filename", "archivo.bin"), parts, row.optLong("expected_size", 0L));
                    task.bytes = row.optLong("bytes", 0L);
                    task.total = row.optLong("total", task.expectedSize);
                    task.currentPart = Math.max(0, Math.min(row.optInt("current_part", 0), parts.length()));
                    task.state = row.optString("state", "paused");
                    if (task.state.equals("downloading") || task.state.equals("saving") || task.state.equals("pausing")) task.state = "paused";
                    task.error = row.optString("error", "");
                    task.updatedAt = row.optLong("updated_at", System.currentTimeMillis());
                    File workDir = new File(new File(getFilesDir(), "todus-downloads"), key);
                    task.bytes = measureToDusDownloadBytes(task, workDir);
                    if (!task.state.equals("done")) toDusDownloadTasks.put(key, task);
                }
            }
            persistToDusDownloadTasks();
        } catch (Exception ignored) { }
    }

    private String downloadCacheKey(String name, JSONArray parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((name + "\n" + parts.toString()).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : bytes) result.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
            return result.toString();
        } catch (Exception e) {
            return Integer.toHexString((name + parts.toString()).hashCode());
        }
    }

    private void loadDirectLinkTasks() {
        try {
            JSONArray saved = new JSONArray(prefs.getString(PREF_DIRECT_LINK_TASKS, "[]"));
            synchronized (directLinkDownloadLock) {
                for (int i = 0; i < saved.length(); i++) {
                    JSONObject row = saved.optJSONObject(i);
                    if (row == null) continue;
                    String key = row.optString("key", "");
                    String url = row.optString("url", "");
                    String filename = row.optString("filename", "");
                    if (key.isEmpty() || url.isEmpty() || filename.isEmpty()) continue;
                    if (!key.equals(downloadCacheKey(filename, new JSONArray().put(url)))) continue;
                    DirectLinkTask task = new DirectLinkTask(key, url, filename);
                    task.state = row.optString("state", "paused");
                    if (task.state.equals("downloading") || task.state.equals("saving")
                            || task.state.equals("pausing") || task.state.equals("deleting")) {
                        task.state = "paused";
                    }
                    task.bytes = Math.max(0L, row.optLong("bytes", 0L));
                    task.total = Math.max(0L, row.optLong("total", 0L));
                    task.error = row.optString("error", "");
                    task.updatedAt = row.optLong("updated_at", System.currentTimeMillis());
                    directLinkTasks.put(key, task);
                }
            }
            persistDirectLinkTasks();
        } catch (Exception ignored) { }
    }

    private void persistDirectLinkTasks() {
        if (prefs == null) return;
        JSONArray rows = new JSONArray();
        synchronized (directLinkDownloadLock) {
            for (DirectLinkTask task : directLinkTasks.values()) {
                try {
                    rows.put(new JSONObject()
                            .put("key", task.key)
                            .put("url", task.url)
                            .put("filename", task.filename)
                            .put("state", task.state)
                            .put("bytes", task.bytes)
                            .put("total", task.total)
                            .put("error", task.error)
                            .put("updated_at", task.updatedAt));
                } catch (Exception ignored) { }
            }
        }
        prefs.edit().putString(PREF_DIRECT_LINK_TASKS, rows.toString()).apply();
    }

    private void recordDownloadedFile(DownloadTarget target, long size) {
        try {
            JSONArray oldRecords = savedDownloadRecords();
            JSONArray records = new JSONArray();
            JSONObject current = new JSONObject()
                    .put("uri", target.uri.toString())
                    .put("path", target.file == null ? "" : target.file.getAbsolutePath())
                    .put("name", target.name)
                    .put("size", size)
                    .put("saved_at", System.currentTimeMillis());
            records.put(current);
            for (int i = 0; i < oldRecords.length() && records.length() < 100; i++) {
                JSONObject old = oldRecords.optJSONObject(i);
                if (old == null || target.uri.toString().equals(old.optString("uri", ""))) continue;
                records.put(old);
            }
            prefs.edit().putString(PREF_DOWNLOAD_HISTORY, records.toString()).apply();
        } catch (Exception ignored) { }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WRITE_STORAGE && syncStatus != null) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            syncStatus.setText(granted
                    ? "Permiso concedido. Vuelve a pegar el enlace y pulsa Descargar enlace recibido."
                    : "Sin permiso no se puede guardar el archivo en la carpeta Descargas del teléfono.");
        }
    }

    private DownloadTarget openPublicDownload(String originalName) throws Exception {
        String displayName = safeName(originalName);
        String customFolder = prefs.getString(PREF_DOWNLOAD_FOLDER, "");
        if (!customFolder.isEmpty()) {
            Uri tree = Uri.parse(customFolder);
            String documentId = DocumentsContract.getTreeDocumentId(tree);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
            Uri created = DocumentsContract.createDocument(getContentResolver(), parent,
                    mimeTypeFor(displayName), displayName);
            if (created == null) throw new IOException("No se pudo crear el archivo en la carpeta seleccionada.");
            try {
                OutputStream stream = getContentResolver().openOutputStream(created, "w");
                if (stream == null) throw new IOException("No se pudo abrir el archivo en la carpeta seleccionada.");
                return new DownloadTarget(created, null, stream, displayName, false);
            } catch (Exception e) {
                getContentResolver().delete(created, null, null);
                throw e;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(displayName));
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("No se pudo crear el archivo en Descargas.");
            try {
                OutputStream stream = getContentResolver().openOutputStream(uri, "w");
                if (stream == null) throw new IOException("No se pudo abrir el archivo en Descargas.");
                return new DownloadTarget(uri, null, stream, displayName, true);
            } catch (Exception e) {
                getContentResolver().delete(uri, null, null);
                throw e;
            }
        }

        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!downloads.exists() && !downloads.mkdirs()) throw new IOException("No se pudo crear la carpeta Descargas.");
        File file = new File(downloads, displayName);
        return new DownloadTarget(Uri.fromFile(file), file, new FileOutputStream(file), displayName, false);
    }

    private void finishPublicDownload(DownloadTarget target) throws IOException {
        if (target.mediaStore) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (getContentResolver().update(target.uri, values, null, null) == 0) {
                throw new IOException("El archivo se descargó, pero Android no pudo publicarlo en Descargas.");
            }
        }
    }

    private void deletePublicDownload(DownloadTarget target) {
        try {
            if (target.mediaStore) getContentResolver().delete(target.uri, null, null);
            else if ("content".equals(target.uri.getScheme())) getContentResolver().delete(target.uri, null, null);
            else if (target.file != null) target.file.delete();
        } catch (Exception ignored) { }
    }

    private String mimeTypeFor(String name) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(name);
        String type = extension.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(java.util.Locale.US));
        return type == null ? "application/octet-stream" : type;
    }

    private static final class DownloadTarget {
        final Uri uri;
        final File file;
        final OutputStream stream;
        final String name;
        final boolean mediaStore;

        DownloadTarget(Uri uri, File file, OutputStream stream, String name, boolean mediaStore) {
            this.uri = uri;
            this.file = file;
            this.stream = stream;
            this.name = name;
            this.mediaStore = mediaStore;
        }
    }

    private static final class ToDusDownloadTask {
        final String key;
        final String taskId;
        final String filename;
        final JSONArray parts;
        final long expectedSize;
        volatile String state = "paused";
        volatile long bytes;
        volatile long total;
        volatile int currentPart;
        volatile long updatedAt = System.currentTimeMillis();
        volatile long lastPersistAt;
        volatile long speedBytesPerSecond;
        volatile long speedSampleBytes;
        volatile long speedSampleAt;
        volatile String error = "";
        volatile boolean pauseRequested;
        volatile boolean deleteRequested;
        volatile Thread worker;
        volatile ParallelRangeDownloader.DownloadControl control;
        volatile DownloadTarget target;
        volatile TextView progressTextView;
        volatile ProgressBar progressBarView;
        volatile DownloadCardRefs cardRefs;

        ToDusDownloadTask(String key, String taskId, String filename, JSONArray parts, long expectedSize) {
            this.key = key;
            this.taskId = taskId == null ? "" : taskId;
            this.filename = filename;
            this.parts = parts;
            this.expectedSize = expectedSize;
            this.total = expectedSize;
        }
    }

    private static final class DirectLinkTask {
        final String key;
        final String url;
        final String filename;
        volatile String state = "paused";
        volatile long bytes;
        volatile long total;
        volatile long updatedAt = System.currentTimeMillis();
        volatile long speedBytesPerSecond;
        volatile long speedSampleBytes;
        volatile long speedSampleAt;
        volatile String error = "";
        volatile boolean pauseRequested;
        volatile boolean deleteRequested;
        volatile int lastProgressPercent = -1;
        volatile long lastPersistAt;
        volatile Thread worker;
        volatile ParallelRangeDownloader.DownloadControl control;
        volatile TextView progressTextView;
        volatile ProgressBar progressBarView;
        volatile DownloadCardRefs cardRefs;

        DirectLinkTask(String key, String url, String filename) {
            this.key = key;
            this.url = url;
            this.filename = filename;
        }
    }

    private String safeName(String name) {
        String clean = name == null ? "" : name.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
        while (clean.startsWith(".")) clean = clean.substring(1);
        if (clean.length() > 180) {
            int dot = clean.lastIndexOf('.');
            String extension = dot > 0 && clean.length() - dot <= 16 ? clean.substring(dot) : "";
            String base = extension.isEmpty() ? clean : clean.substring(0, dot);
            clean = base.substring(0, Math.min(base.length(), 180 - extension.length())) + extension;
        }
        return clean.isEmpty() ? "archivo.bin" : clean;
    }

    private void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteTree(c);
        }
        f.delete();
    }

    private JSONObject request(String method, String path, String body, String bearer) throws Exception {
        final int requestAccessGeneration = appAccessGeneration;
        HttpURLConnection c = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15_000);
        c.setReadTimeout("/api/app/ai/chat".equals(path) ? 90_000 : 30_000);
        c.setRequestProperty("Accept", "application/json");
        if (!bearer.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (!body.isEmpty()) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (stream != null) {
            try (InputStream in = stream) {
                byte[] chunk = new byte[8192];
                int count;
                while ((count = in.read(chunk)) != -1) buffer.write(chunk, 0, count);
            }
        }
        byte[] bytes = buffer.toByteArray();
        c.disconnect();
        String responseText = new String(bytes, StandardCharsets.UTF_8);
        if (code / 100 != 2) {
            String detail = "";
            try {
                JSONObject error = new JSONObject(responseText);
                detail = error.optString("error", "");
                if (detail.isEmpty()) detail = error.optString("message", "");
            } catch (Exception ignored) { }
            detail = detail.replaceAll("[\\p{Cntrl}]+", " ").trim();
            if (detail.length() > 160) detail = detail.substring(0, 160) + "…";
            android.util.Log.w(LOG_TAG, method + " " + path + " devolvió HTTP " + code
                    + (detail.isEmpty() ? "" : ": " + detail));
            if (code == HttpURLConnection.HTTP_FORBIDDEN && "app_banned".equals(detail)) {
                if (markAppBanned(requestAccessGeneration)) {
                    handler.post(() -> {
                        if (appBanned) showBannedScreen();
                    });
                }
            }
            throw new IOException("HTTP " + code + (detail.isEmpty() ? "" : ": " + detail));
        }
        return new JSONObject(responseText);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(poller);
        super.onDestroy();
    }
}
