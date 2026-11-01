package com.tod4s3.sync;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
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
    private static final String KEY_ALIAS = "tod4s3.sync.todus.credentials.v1";
    private static final String PREF_TODUS_PHONE = "todus_phone_enc";
    private static final String PREF_TODUS_PASSWORD = "todus_password_enc";
    private static final String PREF_TODUS_TOKEN = "todus_token_enc";
    private static final String PREF_TODUS_PIN_REQUESTED = "todus_pin_requested";
    private static final String PREF_SELECTED_PLAN = "selected_plan";
    private static final String PREF_DOWNLOAD_HISTORY = "download_history_v1";
    private static final String PREF_HIDDEN_DELIVERIES = "hidden_delivery_ids_v1";
    private static final String PREF_UI_THEME = "ui_theme_v1";
    private static final String PREF_UI_ACCENT = "ui_accent_v1";
    private static final String PREF_DOWNLOAD_CONCURRENCY = "download_concurrency_v1";
    private static final String PREF_DOWNLOAD_FOLDER = "download_folder_uri_v1";
    private static final int REQUEST_WRITE_STORAGE = 702;
    private static final int REQUEST_PAYMENT_RECEIPT = 703;
    private static final int REQUEST_IMPORT_JK = 704;
    private static final int REQUEST_DOWNLOAD_FOLDER = 705;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            refreshDeliveries();
            refreshAppTasks();
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
    private TextView planStatus;
    private LinearLayout deliveriesBox;
    private LinearLayout dashboardItemsBox;
    private View dashboardFab;
    private EditText jkImportTarget;
    private TextView jkImportStatus;
    private String token;
    private String todusPhone = "";
    private String todusPassword = "";
    private String todusToken = "";
    private String pendingPremiumShareText = "";
    private boolean savedCredentialsCouldNotBeDecrypted;
    private boolean viewingDownloadsTab;
    private boolean pairingInFlight;
    private boolean toDusPinRequestInFlight;
    private boolean toDusValidationInFlight;
    private boolean deliveryRefreshInFlight;
    private boolean appTaskRefreshInFlight;
    private boolean viewingDashboard;
    private final Object downloadConcurrencyLock = new Object();
    private final java.util.Set<String> activeDownloadKeys = new java.util.HashSet<>();
    private int activeDownloadJobs;
    private String dashboardFilter = "all";
    private String dashboardSearch = "";
    private boolean sortOldestFirst;
    private JSONArray latestDeliveryRows = new JSONArray();
    private JSONArray latestAppTaskRows = new JSONArray();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("tod4s3_sync", MODE_PRIVATE);
        token = prefs.getString("token", "");
        loadToDusCredentials();
        buildUi();

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
        if (requestCode == REQUEST_IMPORT_JK) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            try {
                ArrayList<String> links = extractLinks(readImportFile(data.getData()));
                if (links.isEmpty()) {
                    if (jkImportStatus != null) jkImportStatus.setText("El archivo no contiene enlaces HTTP/HTTPS.");
                } else if (jkImportTarget != null) {
                    jkImportTarget.setText(android.text.TextUtils.join("\n", links));
                    if (jkImportStatus != null) jkImportStatus.setText("Se importaron " + links.size() + " enlace(s). Revisa la lista y pulsa Iniciar descarga.");
                }
            } catch (Exception e) {
                if (jkImportStatus != null) jkImportStatus.setText(e.getMessage() == null ? "No se pudo leer el archivo .JK." : e.getMessage());
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
        applySystemBarTheme();
        showDashboard("all", false);
    }

    private void showPlansStep(boolean animated) {
        LinearLayout page = createStepPage(0, "Elige tu plan", "Empieza gratis. Los planes Premium son opcionales.");
        LinearLayout free = card();
        TextView freeTag = text("RECOMENDADO PARA EMPEZAR", 11, true);
        freeTag.setTextColor(Color.rgb(99, 230, 216));
        free.addView(freeTag);
        free.addView(text("Gratis", 24, true), margin(0, 7, 0, 2));
        free.addView(text("1 GB cada 24 horas", 18, true));
        free.addView(bodyText("Usa Tod4S3 sin pagar. Puedes volver a elegir un plan Premium más adelante."), margin(0, 8, 0, 14));
        Button chooseFree = primaryButton("Continuar con el plan gratis");
        chooseFree.setOnClickListener(v -> chooseFreePlan());
        free.addView(chooseFree);
        page.addView(free, margin(0, 20, 0, 18));

        page.addView(text("Planes Premium", 20, true), margin(0, 4, 0, 4));
        page.addView(bodyText("Descargas sin límite mientras el plan esté activo."), margin(0, 0, 0, 10));
        page.addView(planOffer("Premium 15", "15 días · 250 CUP", "Compresor Premium y entrega directa por ToDus.", "Premium 15 días · 250 CUP"), margin(0, 0, 0, 10));
        page.addView(planOffer("Premium 20", "20 días · 300 CUP", "Descargas sin límite durante 20 días.", "Premium 20 días · 300 CUP"), margin(0, 0, 0, 10));
        page.addView(planOffer("Premium 30", "30 días · 500 CUP", "Un mes completo de descargas sin límite.", "Premium 30 días · 500 CUP"), margin(0, 0, 0, 10));
        LinearLayout paymentMethods = new LinearLayout(this);
        paymentMethods.setOrientation(LinearLayout.HORIZONTAL);
        paymentMethods.addView(paymentMethodChip(R.drawable.transfermovil_payment, "Transfermóvil"), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams balanceChipLayout = new LinearLayout.LayoutParams(0, -2, 1f);
        balanceChipLayout.setMargins(dp(8), 0, 0, 0);
        paymentMethods.addView(paymentMethodChip(R.drawable.saldo_movil_payment, "Saldo móvil"), balanceChipLayout);
        page.addView(paymentMethods, margin(0, 5, 0, 10));
        page.addView(bodyText("Los pagos se coordinan por Transfermóvil o saldo móvil. La activación de Premium es manual."), margin(0, 6, 0, 0));
        planStatus = statusText("");
        page.addView(planStatus, margin(0, 8, 0, 0));
        if (!prefs.getString(PREF_SELECTED_PLAN, "").isEmpty()) {
            Button returnToApp = secondaryButton("Volver a mis entregas");
            returnToApp.setOnClickListener(v -> chooseFreePlan());
            page.addView(returnToApp, margin(0, 12, 0, 0));
        }
        showPage(page, animated);
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
        Button requestPlan = goldButton("Solicitar por Telegram");
        requestPlan.setOnClickListener(v -> openPremiumRequest(purchasePlan));
        offer.addView(requestPlan);
        return offer;
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
        LinearLayout page = createStepPage(1, "Vincula tu bot", "Un código de un solo uso conecta esta app con tus entregas de Tod4S3.");
        LinearLayout box = card();
        box.addView(text("Conecta tu cuenta", 18, true));
        box.addView(bodyText("En el chat privado del bot pulsa “Vincular aplicación” (o envía /app). Pega aquí el código que recibas."), margin(0, 8, 0, 16));
        codeInput = input("Código del bot", android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        codeInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        box.addView(codeInput, margin(0, 0, 0, 14));
        Button pair = primaryButton("Conectar con el bot");
        pair.setOnClickListener(v -> pairDevice(v));
        box.addView(pair);
        syncStatus = statusText("El código caduca a los 10 minutos.");
        box.addView(syncStatus, margin(0, 12, 0, 0));
        page.addView(box, margin(0, 20, 0, 0));
        page.addView(infoCard("Privacidad", "El código solo vincula tus entregas. La app no lee tus chats privados."), margin(0, 12, 0, 0));
        showPage(page, animated);
    }

    private void showPhoneStep(boolean animated) {
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
        viewingDashboard = true;
        dashboardFilter = filter;
        viewingDownloadsTab = "completed".equals(filter);
        deliveriesBox = null;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(12), dp(14), dp(24));
        page.addView(dashboardToolbar(), margin(0, 0, 0, 0));
        page.addView(dashboardTabs(), margin(0, 12, 0, 0));
        View tabDivider = new View(this);
        tabDivider.setBackgroundColor(borderColor());
        page.addView(tabDivider, new LinearLayout.LayoutParams(-1, dp(1)));
        TextView status = bodyText("Descargas de Tod4S3 Sync");
        status.setTextSize(12);
        page.addView(status, margin(2, 12, 2, 0));
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
        return bar;
    }

    private LinearLayout dashboardTabs() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        String[][] options = {{"all", "# Todos"}, {"active", "↓ Activas"}, {"completed", "✓ Completadas"}};
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
            LinearLayout.LayoutParams itemLayout = new LinearLayout.LayoutParams(0, dp(50), 1f);
            itemLayout.setMargins(dp(4), 0, dp(4), 0);
            tabs.addView(item, itemLayout);
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
        if ("all".equals(dashboardFilter) && latestDeliveryRows.length() > 0) {
            TextView cloudTitle = text("Archivos en la nube", 15, true);
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
        LinearLayout empty = card();
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(dp(22), dp(34), dp(22), dp(30));
        TextView icon = text("⇩", 46, true);
        icon.setGravity(Gravity.CENTER);
        icon.setTextColor(accentColor());
        empty.addView(icon, new LinearLayout.LayoutParams(-1, dp(62)));
        TextView title = text("No hay descargas", 18, true);
        title.setGravity(Gravity.CENTER);
        empty.addView(title, margin(0, 8, 0, 0));
        TextView hint = bodyText("Pega un enlace o importa un archivo .JK para empezar.");
        hint.setGravity(Gravity.CENTER);
        empty.addView(hint, margin(0, 7, 0, 14));
        Button add = primaryButton("Nueva descarga");
        add.setOnClickListener(v -> openNewDownloadDialog());
        empty.addView(add, margin(0, 0, 0, 0));
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
        content.addView(bodyText("Pega enlaces de YouTube o enlaces directos. El bot los procesará y entregará el archivo a tu ToDus."));
        EditText linkInput = input("Pega aquí el enlace", android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        linkInput.setSingleLine(false);
        linkInput.setMinLines(2);
        linkInput.setMaxLines(5);
        linkInput.setGravity(Gravity.TOP | Gravity.START);
        content.addView(linkInput, margin(0, 12, 0, 0));
        TextView status = bodyText("También puedes importar un archivo .JK. Para entregar por ToDus, registra tu número en el bot con /todus.");
        status.setTextSize(12);
        content.addView(status, margin(0, 10, 0, 0));
        Button importButton = secondaryButton("Importar archivo .JK");
        importButton.setOnClickListener(v -> {
            jkImportTarget = linkInput;
            jkImportStatus = status;
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("*/*");
            try {
                startActivityForResult(picker, REQUEST_IMPORT_JK);
            } catch (Exception e) {
                status.setText("No se pudo abrir el selector de archivos.");
            }
        });
        content.addView(importButton, margin(0, 12, 0, 0));
        TextView localDownloadStatus = bodyText("Los enlaces ToDus del bot se pueden descargar directamente aquí con conexiones simultáneas.");
        localDownloadStatus.setTextSize(12);
        content.addView(localDownloadStatus, margin(0, 12, 0, 0));
        ProgressBar localDownloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        localDownloadProgress.setMax(100);
        localDownloadProgress.setVisibility(View.GONE);
        content.addView(localDownloadProgress, margin(0, 6, 0, 0));
        Button localDownload = secondaryButton("Descargar enlace ToDus en este teléfono");
        localDownload.setOnClickListener(v -> {
            ArrayList<String> links = extractLinks(linkInput.getText().toString());
            if (links.size() != 1) {
                localDownloadStatus.setText("Pega un solo enlace ToDus del bot para descargarlo aquí.");
                return;
            }
            if (!isTodusShareLink(links.get(0))) {
                localDownloadStatus.setText("Esta opción es para enlaces HTTPS de ToDus generados por el bot.");
                return;
            }
            if (todusToken == null || todusToken.isEmpty()) {
                localDownloadStatus.setText("Conecta primero tu cuenta ToDus en Ajustes para descargar este enlace.");
                return;
            }
            String filename = filenameFromTodusLink(links.get(0));
            downloadParts(filename, new JSONArray().put(links.get(0)), 0L,
                    localDownload, localDownloadStatus, localDownloadProgress);
        });
        content.addView(localDownload, margin(0, 8, 0, 0));
        Button submit = primaryButton("Enviar enlace al bot");
        content.addView(submit, margin(0, 10, 0, 0));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Nueva descarga")
                .setView(content)
                .setNegativeButton("Cerrar", null)
                .create();
        submit.setOnClickListener(v -> {
            ArrayList<String> links = extractLinks(linkInput.getText().toString());
            if (links.isEmpty()) {
                status.setText("Pega un enlace HTTP/HTTPS válido o importa un archivo .JK.");
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

    private boolean isTodusShareLink(String link) {
        try {
            Uri uri = Uri.parse(link);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && (host.equalsIgnoreCase("todus.cu") || host.toLowerCase(java.util.Locale.ROOT).endsWith(".todus.cu"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private String filenameFromTodusLink(String link) {
        String name = Uri.parse(link).getLastPathSegment();
        if (name == null || name.trim().isEmpty()) name = "archivo_todus.bin";
        return safeName(name);
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

    private String readImportFile(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("No se pudo leer el archivo.");
            byte[] buffer = new byte[4096];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 256 * 1024) throw new IOException("El archivo .JK supera el límite de 256 KB.");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
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
        TextView initials = text(token == null || token.isEmpty() ? "➤" : "T", 20, true);
        initials.setTextColor(onAccentColor());
        initials.setGravity(Gravity.CENTER);
        badge.addView(initials, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
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

        PopupWindow popup = new PopupWindow(content, dp(296), -1, true);
        popup.setBackgroundDrawable(new ColorDrawable(surfaceColor()));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(18));
        account.setOnClickListener(v -> {
            popup.dismiss();
            if (token == null || token.isEmpty()) {
                if (prefs.getString(PREF_SELECTED_PLAN, "").isEmpty()) showPlansStep(true);
                else showBotStep(true);
            }
            else showAccountMenu(v);
        });
        addDrawerRow(content, popup, "▣", "Archivos en la Nube", "Entregas del bot", () -> showHomeSection(false, true));
        addDrawerRow(content, popup, "↓", "Descargas", "Archivos guardados en el teléfono", () -> showDashboard("completed", true));
        addDrawerRow(content, popup, "▥", "Estadísticas", "Uso y actividad", () -> showStats(true));
        addDrawerRow(content, popup, "◉", "Grupo de Telegram", "Dark Zone", () -> openExternalUrl("https://t.me/Dark_Zone_666"));
        addDrawerRow(content, popup, "↗", "Enlace no disponible", "Reportar pedido caído", () -> shareReport("Pedido caído"));
        addDrawerRow(content, popup, "⚠", "Reportar un error", "Enviar detalles al soporte", () -> shareReport("Error de Tod4S3 Sync"));
        addDrawerRow(content, popup, "i", "Acerca de", "Versión y privacidad", () -> showAbout(true));
        addDrawerRow(content, popup, "⚙", "Ajustes", "Tema, carpeta y almacenamiento", () -> showSettings(true));
        View divider = new View(this);
        divider.setBackgroundColor(borderColor());
        content.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
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
        viewingDashboard = false;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(14), dp(16), dp(30));
        page.addView(sectionHeader("Ajustes"));

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
        appInfo.addView(text("JK Downloader · Tod4S3 Sync", 15, true));
        appInfo.addView(bodyText("Versión " + appVersion() + " · Android"), margin(0, 5, 0, 0));
        Button repository = secondaryButton("Ver repositorio y actualizaciones");
        repository.setOnClickListener(v -> openExternalUrl("https://github.com/yunierki1234-coder/Tod4S3"));
        appInfo.addView(repository, margin(0, 12, 0, 0));
        page.addView(appInfo);
        page.addView(infoCard("Privacidad", "La app solo consulta las entregas del bot vinculado y las tareas que tú envías desde este formulario. No lee chats privados ni envía tu contraseña de ToDus."), margin(0, 12, 0, 0));
        showPage(page, animated);
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
        page.addView(infoCard("Tod4S3 Sync", "Versión " + appVersion() + "\nDescargas gestionadas por el bot vinculado."), margin(0, 20, 0, 0));
        page.addView(infoCard("Privacidad", "La app no accede a tus chats privados ni a mensajes de otras personas. Solo muestra los archivos que entrega el bot y los enlaces que tú envías explícitamente."), margin(0, 10, 0, 0));
        page.addView(infoCard("ToDus", "La contraseña se guarda cifrada en este dispositivo y no se envía al bot."), margin(0, 10, 0, 0));
        Button group = secondaryButton("Abrir grupo de Telegram");
        group.setOnClickListener(v -> openExternalUrl("https://t.me/Dark_Zone_666"));
        page.addView(group, margin(0, 16, 0, 0));
        showPage(page, animated);
    }

    private void openExternalUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { Toast.makeText(this, "No se pudo abrir el enlace.", Toast.LENGTH_LONG).show(); }
    }

    private void shareReport(String topic) {
        String message = "Reporte desde Tod4S3 Sync: " + topic + ". Describe aquí lo ocurrido.";
        openExternalUrl("https://t.me/share/url?url=&text=" + Uri.encode(message));
    }

    private void showHomeSection(boolean downloadsTab, boolean animated) {
        viewingDashboard = false;
        viewingDownloadsTab = downloadsTab;
        String title = downloadsTab ? "Descargas" : "Tus entregas";
        String subtitle = downloadsTab
                ? "Archivos guardados en la carpeta Descargas del teléfono."
                : "Las entregas del bot aparecen aquí. Descárgalas para guardarlas en Descargas.";
        LinearLayout page = createStepPage(0, title, subtitle);
        addHomeTabs(page, downloadsTab);

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
        if (!downloadsTab && token != null && !token.isEmpty()) {
            handler.postDelayed(this::refreshDeliveries, animated ? 300L : 0L);
        }
    }

    private void addHomeTabs(LinearLayout page, boolean downloadsTab) {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        Button deliveries = downloadsTab ? secondaryButton("Entregas") : primaryButton("Entregas");
        Button downloads = downloadsTab ? primaryButton("Descargas") : secondaryButton("Descargas");
        setButtonIcon(deliveries, R.drawable.ic_inbox_delivery,
                downloadsTab ? Color.rgb(172, 224, 226) : Color.rgb(5, 26, 35));
        setButtonIcon(downloads, R.drawable.ic_download_arrow,
                downloadsTab ? Color.rgb(5, 26, 35) : Color.rgb(172, 224, 226));
        deliveries.setOnClickListener(v -> showHomeStep(true));
        downloads.setOnClickListener(v -> showDownloadsStep(true));
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, dp(54), 1f);
        first.setMargins(0, 0, dp(7), 0);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, dp(54), 1f);
        second.setMargins(dp(7), 0, 0, 0);
        tabs.addView(deliveries, first);
        tabs.addView(downloads, second);
        page.addView(tabs, margin(0, 16, 0, 0));
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
        avatar.setContentDescription("Abrir menú de cuenta");
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_foreground);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(9), dp(9), dp(9), dp(9));
        avatar.addView(logo, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        TextView bars = text("☰", 11, true);
        bars.setGravity(Gravity.CENTER);
        bars.setTextColor(Color.rgb(40, 27, 6));
        bars.setBackground(roundRect(Color.rgb(242, 198, 106), 10, Color.rgb(7, 17, 31)));
        FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM | Gravity.END);
        avatar.addView(bars, badge);
        avatar.setOnClickListener(this::showAccountMenu);
        avatar.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start();
            else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL)
                view.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            return false;
        });
        return avatar;
    }

    private void showAccountMenu(View anchor) {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(10), dp(10), dp(10), dp(10));
        menu.setBackground(roundRect(Color.rgb(10, 29, 48), 20, Color.rgb(49, 94, 122)));
        TextView heading = text("MENÚ DE CUENTA", 11, true);
        heading.setTextColor(Color.rgb(99, 230, 216));
        menu.addView(heading, margin(dp(5), dp(2), 0, 2));
        TextView account = bodyText(todusPhone.isEmpty() ? "Sesión conectada" : "ToDus · " + todusPhone);
        account.setTextSize(12);
        menu.addView(account, margin(dp(5), 0, 0, 8));

        int screenWidthDp = (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int menuWidth = dp(Math.min(284, Math.max(240, screenWidthDp - 28)));
        PopupWindow popup = new PopupWindow(menu, menuWidth, -2, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(18));
        menu.addView(accountMenuItem("★", "Planes y ofertas", "Ver opciones y precios", Color.rgb(242, 198, 106), popup,
                () -> showPlansStep(true)), margin(0, 0, 0, 7));
        menu.addView(accountMenuItem("↻", "Cambiar cuenta ToDus", "Conectar otro número", Color.rgb(99, 230, 216), popup,
                () -> new AlertDialog.Builder(this)
                        .setTitle("Cambiar cuenta ToDus")
                        .setMessage("Se borrarán de este teléfono las credenciales ToDus guardadas. Tus archivos de Descargas no se tocarán.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Continuar", (dialog, which) -> changeToDusAccount())
                        .show()), margin(0, 0, 0, 7));
        menu.addView(accountMenuItem("⇥", "Cerrar sesión", "Desvincular este teléfono", Color.rgb(255, 157, 164), popup,
                this::confirmLogout));
        menu.setAlpha(0f);
        menu.setTranslationY(-dp(9));
        popup.showAsDropDown(anchor, 0, dp(8), Gravity.END);
        menu.animate().alpha(1f).translationY(0f).setDuration(190)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private View accountMenuItem(String glyph, String title, String subtitle, int accent,
                                 PopupWindow popup, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(9), dp(8));
        row.setBackground(roundRect(Color.rgb(17, 42, 66), 14, Color.rgb(37, 73, 98)));
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(roundRect(Color.rgb(7, 23, 39), 12, 0));
        TextView icon = text(glyph, 20, true);
        icon.setGravity(Gravity.CENTER);
        icon.setTextColor(accent);
        badge.addView(icon, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
        row.addView(badge, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 14, true));
        TextView detail = bodyText(subtitle);
        detail.setTextSize(11);
        labels.addView(detail, margin(0, 2, 0, 0));
        LinearLayout.LayoutParams labelsLayout = new LinearLayout.LayoutParams(0, -2, 1f);
        labelsLayout.setMargins(dp(10), 0, 0, 0);
        row.addView(labels, labelsLayout);
        row.setContentDescription(title + ". " + subtitle);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        row.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(75).start();
            else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL)
                view.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            return false;
        });
        return row;
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("Cerrar sesión")
                .setMessage("Se desvincularán el bot y la cuenta ToDus en este teléfono. Los archivos guardados en Descargas no se borrarán.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Cerrar sesión", (dialog, which) -> logout())
                .show();
    }

    private void logout() {
        final String oldToken = token == null ? "" : token;
        token = "";
        prefs.edit().remove("token").remove(PREF_SELECTED_PLAN)
                .putBoolean(PREF_TODUS_PIN_REQUESTED, false).apply();
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
        if (dashboardFab != null && appRoot != null) {
            appRoot.removeView(dashboardFab);
            dashboardFab = null;
        }
        Runnable install = () -> {
            currentPage = page;
            appScroll.removeAllViews();
            appScroll.addView(page);
            page.setAlpha(0f);
            page.setTranslationY(dp(12));
            page.animate().alpha(1f).translationY(0f).setDuration(240)
                    .setInterpolator(new DecelerateInterpolator()).start();
        };
        if (animated && currentPage != null) {
            currentPage.animate().alpha(0f).translationY(-dp(8)).setDuration(120)
                    .withEndAction(install).start();
        } else {
            install.run();
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
        if (id.isEmpty()) {
            id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (id == null || id.isEmpty()) id = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private void pairDevice(View trigger) {
        if (pairingInFlight) return;
        final String code = codeInput.getText().toString().trim().toUpperCase(java.util.Locale.ROOT);
        if (code.isEmpty()) { syncStatus.setText("Escribe el código recibido con /app."); return; }
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
                runOnUiThread(() -> {
                    pairingInFlight = false;
                    showPhoneStep(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pairingInFlight = false;
                    trigger.setEnabled(true);
                    syncStatus.setText("No se pudo vincular: " + e.getMessage());
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
        toDusPinRequestInFlight = true;
        trigger.setEnabled(false);
        if (todusStatus != null) todusStatus.setText("Solicitando código a ToDus…");
        new Thread(() -> {
            try {
                String normalizedPhone = ToDusAuth.requestPin(phone);
                putEncrypted(PREF_TODUS_PHONE, normalizedPhone);
                todusPhone = normalizedPhone;
                prefs.edit().putBoolean(PREF_TODUS_PIN_REQUESTED, true).apply();
                runOnUiThread(() -> {
                    toDusPinRequestInFlight = false;
                    trigger.setEnabled(true);
                    showPinStep(true);
                    if (todusStatus != null) todusStatus.setText("Código solicitado. Usa el PIN más reciente que recibas.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    toDusPinRequestInFlight = false;
                    trigger.setEnabled(true);
                    if (todusStatus != null) todusStatus.setText("No se pudo solicitar el código: " + e.getMessage());
                });
            }
        }).start();
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
        toDusValidationInFlight = true;
        trigger.setEnabled(false);
        if (todusStatus != null) todusStatus.setText("Validando el código e iniciando sesión…");
        new Thread(() -> {
            try {
                ToDusAuth.Session session = ToDusAuth.validateAndLogin(phone, pin);
                putEncrypted(PREF_TODUS_PHONE, session.phone);
                putEncrypted(PREF_TODUS_PASSWORD, session.password);
                putEncrypted(PREF_TODUS_TOKEN, session.token);
                todusPhone = session.phone;
                todusPassword = session.password;
                todusToken = session.token;
                prefs.edit().putBoolean(PREF_TODUS_PIN_REQUESTED, false).apply();
                runOnUiThread(() -> {
                    toDusValidationInFlight = false;
                    showHomeStep(true);
                    if (syncStatus != null) syncStatus.setText("Sesión iniciada. Tus credenciales quedaron cifradas.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    toDusValidationInFlight = false;
                    trigger.setEnabled(true);
                    if (todusStatus != null) todusStatus.setText("No se pudo iniciar sesión en ToDus: " + e.getMessage());
                });
            }
        }).start();
    }

    private void restoreToDusSession() {
        final String phone = todusPhone;
        final String password = todusPassword;
        if (phone.isEmpty() || password.isEmpty()) {
            if (todusStatus != null) todusStatus.setText("No hay una sesión ToDus guardada para restaurar.");
            return;
        }
        if (syncStatus != null) syncStatus.setText("Restaurando sesión ToDus…");
        new Thread(() -> {
            try {
                String newToken = ToDusAuth.login(phone, password);
                putEncrypted(PREF_TODUS_TOKEN, newToken);
                todusToken = newToken;
                runOnUiThread(() -> {
                    if (syncStatus != null) syncStatus.setText("Sesión ToDus restaurada.");
                    if (deliveriesBox == null) showHomeStep(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    showPhoneStep(true);
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
    }

    private void refreshDeliveries() {
        refreshDeliveries(null);
    }

    private void refreshDeliveries(View trigger) {
        final String current = token;
        if (current == null || current.isEmpty() || (deliveriesBox == null && !viewingDashboard)) return;
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
                    latestDeliveryRows = rows == null ? new JSONArray() : rows;
                    if (viewingDashboard) renderDashboardItems();
                    else renderDeliveries(latestDeliveryRows);
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
        if (current == null || current.isEmpty() || appTaskRefreshInFlight) return;
        appTaskRefreshInFlight = true;
        new Thread(() -> {
            try {
                JSONObject result = request("GET", "/api/app/tasks", "", current);
                JSONArray rows = result.optJSONArray("tasks");
                runOnUiThread(() -> {
                    appTaskRefreshInFlight = false;
                    latestAppTaskRows = rows == null ? new JSONArray() : rows;
                    if (viewingDashboard) renderDashboardItems();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> appTaskRefreshInFlight = false);
            }
        }, "app-task-refresh").start();
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
                    JSONArray parts = row.optJSONArray("parts");
                    int partCount = parts == null ? 0 : parts.length();
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
                    Button download = primaryButton(partCount > 1 ? "Descargar y unir" : "Descargar");
                    download.setTextSize(13);
                    download.setMinHeight(dp(46));
                    download.setPadding(dp(10), dp(5), dp(10), dp(5));
                    setButtonIcon(download, R.drawable.ic_download_arrow, Color.rgb(5, 26, 35));
                    final JSONArray copy = parts;
                    final String copyName = name;
                    final long copySize = size;
                    download.setOnClickListener(v -> downloadParts(copyName, copy, copySize, download, progressLabel, progressBar));
                    card.addView(download, margin(0, 8, 0, 0));
                    deliveriesBox.addView(card, margin(0, 0, 0, 8));
                    card.setAlpha(0f);
                    card.setTranslationY(dp(4));
                    card.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(shown * 25L, 150L))
                            .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
                    shown++;
                } catch (Exception ignored) { }
            }
        }
        if (shown == 0) {
            TextView empty = bodyText("No tienes entregas nuevas del bot.");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(20), dp(12), dp(20));
            deliveriesBox.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
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

    private void downloadParts(String name, JSONArray parts, long expectedSize, Button downloadButton,
                               TextView progressLabel, ProgressBar progressBar) {
        if (parts == null || parts.length() == 0) return;
        final String transferKey = downloadCacheKey(name, parts);
        final int maxConcurrent = Math.max(1, Math.min(5, prefs.getInt(PREF_DOWNLOAD_CONCURRENCY, 3)));
        synchronized (downloadConcurrencyLock) {
            if (activeDownloadKeys.contains(transferKey)) {
                progressLabel.setText("Esta descarga ya está activa.");
                return;
            }
            if (activeDownloadJobs >= maxConcurrent) {
                if (syncStatus != null) syncStatus.setText("Límite alcanzado: hay " + maxConcurrent + " descargas simultáneas. Espera a que termine una.");
                return;
            }
            activeDownloadKeys.add(transferKey);
            activeDownloadJobs++;
        }
        if (prefs.getString(PREF_DOWNLOAD_FOLDER, "").isEmpty()
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_STORAGE);
            if (syncStatus != null) syncStatus.setText("Permite guardar archivos en Descargas y vuelve a pulsar Descargar.");
            releaseActiveDownload(transferKey);
            return;
        }
        final String sessionToken = todusToken;
        if (sessionToken == null || sessionToken.isEmpty()) {
            if (syncStatus != null) syncStatus.setText("Conecta tu cuenta ToDus antes de descargar.");
            releaseActiveDownload(transferKey);
            return;
        }
        if (syncStatus != null) syncStatus.setText("Descargando " + name + "…");
        final long totalSize = Math.max(0L, expectedSize);
        downloadButton.setEnabled(false);
        progressLabel.setVisibility(View.VISIBLE);
        progressLabel.setTextColor(Color.rgb(99, 230, 216));
        progressLabel.setText(totalSize > 0 ? "Descargando… 0%" : "Descargando…");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        progressBar.setIndeterminate(totalSize <= 0);
        new Thread(() -> {
            File tempDir = new File(getCacheDir(), "todus-range-" + transferKey);
            DownloadTarget target = null;
            long completedBytes = 0L;
            try {
                if (!tempDir.exists() && !tempDir.mkdirs()) throw new IOException("No se pudo preparar el almacenamiento temporal.");
                target = openPublicDownload(name);
                try (OutputStream out = target.stream) {
                    byte[] buffer = new byte[65536];
                    for (int i = 0; i < parts.length(); i++) {
                        File part = new File(tempDir, "part" + i);
                        final int[] lastShownPercent = {-1};
                        ToDusFileTransfer.download(sessionToken, parts.getString(i), part, completedBytes, (received, reportedTotal) -> {
                            long effectiveTotal = totalSize > 0 ? totalSize : reportedTotal;
                            if (effectiveTotal > 0) {
                                int percent = (int) Math.min(99L, Math.max(0L, received * 100L / effectiveTotal));
                                boolean shouldUpdate;
                                synchronized (lastShownPercent) {
                                    shouldUpdate = percent > lastShownPercent[0];
                                    if (shouldUpdate) lastShownPercent[0] = percent;
                                }
                                if (shouldUpdate) {
                                    runOnUiThread(() -> {
                                        progressBar.setIndeterminate(false);
                                        int current = progressBar.getProgress();
                                        ObjectAnimator.ofInt(progressBar, "progress", current, percent).setDuration(140).start();
                                        progressLabel.setText("Descargando… " + percent + "%");
                                    });
                                }
                            } else {
                                runOnUiThread(() -> progressLabel.setText("Descargando… " + formatBytes(received)));
                            }
                        });
                        completedBytes += part.length();
                        runOnUiThread(() -> progressLabel.setText(parts.length() > 1 ? "Uniendo partes…" : "Guardando en Descargas…"));
                        try (FileInputStream in = new FileInputStream(part)) {
                            int count;
                            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                        }
                    }
                }
                finishPublicDownload(target);
                recordDownloadedFile(target, completedBytes);
                deleteTree(tempDir);
                final String savedName = target.name;
                runOnUiThread(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(100);
                    progressLabel.setTextColor(Color.rgb(99, 230, 216));
                    progressLabel.setText("Completada · 100%");
                    downloadButton.setEnabled(true);
                    if (syncStatus != null) syncStatus.setText("Descarga guardada en Descargas: " + savedName);
                    if (viewingDownloadsTab) showDownloadsStep(false);
                });
            } catch (Exception e) {
                if (target != null) deletePublicDownload(target);
                runOnUiThread(() -> {
                    progressBar.setIndeterminate(false);
                    progressLabel.setTextColor(Color.rgb(255, 156, 156));
                    progressLabel.setText("Error de descarga. Se conservaron los fragmentos para reanudar al reintentar.");
                    downloadButton.setEnabled(true);
                    if (syncStatus != null) syncStatus.setText("Error de descarga: " + e.getMessage());
                });
            } finally {
                releaseActiveDownload(transferKey);
            }
        }).start();
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

    private void releaseActiveDownload(String transferKey) {
        synchronized (downloadConcurrencyLock) {
            activeDownloadKeys.remove(transferKey);
            activeDownloadJobs = Math.max(0, activeDownloadJobs - 1);
        }
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
                    ? "Permiso concedido. Pulsa Descargar para guardar el archivo en Descargas."
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
        HttpURLConnection c = (HttpURLConnection) new URL(API_BASE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
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
            throw new IOException("HTTP " + code + (detail.isEmpty() ? "" : ": " + detail));
        }
        return new JSONObject(responseText);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(poller);
        super.onDestroy();
    }
}
