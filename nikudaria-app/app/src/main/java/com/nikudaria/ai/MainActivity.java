package com.nikudaria.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements TermuxResultReceiver.Listener {
    private static final int C_BG = Color.rgb(8, 10, 9);
    private static final int C_PANEL = Color.rgb(19, 22, 21);
    private static final int C_PANEL2 = Color.rgb(27, 30, 29);
    private static final int C_TEXT = Color.rgb(239, 240, 236);
    private static final int C_MUTED = Color.rgb(151, 157, 152);
    private static final int C_ACCENT = Color.rgb(209, 221, 204);
    private static final int C_LINE = Color.rgb(57, 62, 58);
    private static final int REQUEST_VOICE = 44;
    private ApiClient api;
    private LinearLayout page;
    private LinearLayout codeIntro;
    private LinearLayout teamPanel;
    private LinearLayout conversation;
    private LinearLayout composerBox;
    private ScrollView scroll;
    private EditText composer;
    private TextView sendButton;
    private final JSONArray history = new JSONArray();
    private JSONObject pendingAction;
    private boolean requestBusy;
    private String selectedTab = "Código";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(C_BG);
        getWindow().setNavigationBarColor(C_BG);
        api = new ApiClient(this);
        buildScreen();
        TermuxResultReceiver.setListener(this);
    }

    @Override protected void onResume() {
        super.onResume();
        TermuxResultReceiver.setListener(this);
        deliverPendingTermuxResult();
    }

    @Override protected void onPause() {
        TermuxResultReceiver.setListener(null);
        super.onPause();
    }

    private void deliverPendingTermuxResult() {
        android.content.SharedPreferences prefs = getSharedPreferences("nikudaria_private", MODE_PRIVATE);
        if (!prefs.getBoolean("termux_result_pending", false)) return;
        String transcript = prefs.getString("termux_last_transcript", "");
        int exitCode = prefs.getInt("termux_last_exit", -1);
        prefs.edit().putBoolean("termux_result_pending", false).apply();
        onTermuxResult(transcript, exitCode);
    }

    private void buildScreen() {
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(C_BG);
        setContentView(page);
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            page.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                android.graphics.Insets bars = windowInsets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return windowInsets;
            });
            page.requestApplyInsets();
        }

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(13), dp(7), dp(13), dp(7));
        header.setBackgroundColor(C_BG);
        TextView menu = label("☰", 20, C_TEXT);
        menu.setGravity(Gravity.CENTER);
        menu.setBackground(round(C_PANEL, C_LINE, 1, 14));
        header.addView(menu, new LinearLayout.LayoutParams(dp(38), dp(34)));
        menu.setOnClickListener(v -> showMenu());
        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER);
        String[] tabNames = {"Chat", "Código", "Equipo"};
        for (String tab : tabNames) {
            TextView t = label(tab, 12, tab.equals(selectedTab) ? C_ACCENT : C_MUTED);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(10), 0, dp(10), 0);
            tabs.addView(t, new LinearLayout.LayoutParams(-2, dp(34)));
            t.setOnClickListener(v -> { selectedTab = tab; refreshTab(tabs, tabNames); });
        }
        header.addView(tabs, new LinearLayout.LayoutParams(0, dp(36), 1));
        TextView terminal = label("▣", 17, C_TEXT);
        terminal.setGravity(Gravity.CENTER);
        terminal.setBackground(round(C_PANEL, C_LINE, 1, 14));
        header.addView(terminal, new LinearLayout.LayoutParams(dp(38), dp(34)));
        terminal.setOnClickListener(v -> showTermuxGuide());
        page.addView(header);
        View rule = new View(this); rule.setBackgroundColor(C_LINE); page.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(12));
        scroll.addView(content);
        codeIntro = new LinearLayout(this);
        codeIntro.setOrientation(LinearLayout.VERTICAL);
        content.addView(codeIntro);

        // Calm, typographic wordmark in place of the source video's unrelated logo.
        TextView brand = label("NikuDarIA", 31, C_TEXT);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        brand.setGravity(Gravity.CENTER);
        codeIntro.addView(brand, new LinearLayout.LayoutParams(-1, dp(145)));
        TextView badge = label("⌘   EN ESTE TELÉFONO", 10, C_ACCENT);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(12), dp(7), dp(12), dp(7));
        badge.setBackground(round(C_BG, C_LINE, 1, 16));
        LinearLayout badgeRow = new LinearLayout(this); badgeRow.setGravity(Gravity.CENTER);
        badgeRow.addView(badge); codeIntro.addView(badgeRow, margin(0, 0, 0, 14));
        TextView title = label("¿Qué armamos?", 22, C_TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        codeIntro.addView(title, margin(0, 0, 0, 8));
        TextView subtitle = label("Trabajo con las carpetas y la terminal de este teléfono.", 13, C_MUTED);
        subtitle.setGravity(Gravity.CENTER);
        codeIntro.addView(subtitle, margin(0, 0, 0, 17));

        TextView online = label("IA lista · conexión directa con el servidor", 12, C_MUTED);
        online.setGravity(Gravity.CENTER);
        codeIntro.addView(online, margin(0, 0, 0, 12));

        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this); chips.setGravity(Gravity.CENTER_VERTICAL);
        TextView website = pill("‹›  Crear una página web");
        TextView fix = pill("⚙  Arreglar un error");
        chips.addView(website, margin(0, 0, 8, 0)); chips.addView(fix, margin(0, 0, 0, 0));
        chipsScroll.addView(chips);
        codeIntro.addView(chipsScroll, margin(0, 17, 0, 12));
        website.setOnClickListener(v -> composer.setText("Crea una página web "));
        fix.setOnClickListener(v -> composer.setText("Ayúdame a arreglar este error: "));

        conversation = new LinearLayout(this);
        conversation.setOrientation(LinearLayout.VERTICAL);
        content.addView(conversation, margin(0, 3, 0, 8));
        addSystemLog("Listo cuando tú lo estés. Ninguna acción se ejecuta sin tu confirmación.");
        addText(codeIntro, "Protección activa", 11, C_MUTED, 0, 2, 0, 3);
        addText(codeIntro, "Antes de abrir una app o ejecutar un comando, verás el objetivo exacto, la razón y podrás cancelar.", 12, C_MUTED, 0, 0, 0, 12);
        TextView help = pill("Guía de Termux y permisos");
        codeIntro.addView(help, margin(0, 2, 0, 10)); help.setOnClickListener(v -> showTermuxGuide());

        teamPanel = new LinearLayout(this);
        teamPanel.setOrientation(LinearLayout.VERTICAL);
        teamPanel.setPadding(dp(15), dp(14), dp(15), dp(14));
        teamPanel.setBackground(round(C_PANEL, C_LINE, 1, 16));
        addText(teamPanel, "Equipo local", 18, C_TEXT, 0, 0, 0, 8);
        addText(teamPanel, "NikuDarIA funciona sin cuentas, Telegram ni Premium. Las acciones que apruebes se ejecutan únicamente en este teléfono.", 13, C_MUTED, 0, 0, 0, 0);
        content.addView(teamPanel, margin(0, 8, 0, 8));
        teamPanel.setVisibility(View.GONE);

        composerBox = new LinearLayout(this);
        composerBox.setOrientation(LinearLayout.VERTICAL);
        composerBox.setPadding(dp(11), dp(7), dp(11), dp(7));
        composerBox.setBackground(round(C_PANEL, C_LINE, 1, 18));
        LinearLayout.LayoutParams composerBoxLp = new LinearLayout.LayoutParams(-1, -2);
        composerBoxLp.setMargins(dp(10), dp(6), dp(10), dp(8));
        page.addView(composerBox, composerBoxLp);
        composer = new EditText(this);
        composer.setTextColor(C_TEXT); composer.setHintTextColor(C_MUTED);
        composer.setHint("Describe qué armar en el teléfono");
        composer.setTextSize(14); composer.setMinLines(1); composer.setMaxLines(4);
        composer.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        composer.setBackgroundColor(Color.TRANSPARENT);
        composer.setPadding(dp(2), dp(4), dp(2), dp(4));
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        composer.setImeOptions(EditorInfo.IME_ACTION_SEND);
        composerBox.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER_VERTICAL);
        TextView plus = control("+"); controls.addView(plus, new LinearLayout.LayoutParams(dp(37), dp(35)));
        plus.setOnClickListener(v -> showMenu());
        TextView model = pill("ϟ  NikuDarIA  ⌄");
        controls.addView(model, margin(8, 0, 0, 0));
        model.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Motor NikuDarIA")
                .setMessage("Este chat se procesa mediante el servicio oficial de NikuDarIA.")
                .setPositiveButton("Entendido", null).show());
        View spacer = new View(this); controls.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        TextView mic = control("♩"); controls.addView(mic, new LinearLayout.LayoutParams(dp(38), dp(35)));
        mic.setOnClickListener(v -> startVoiceInput());
        sendButton = control("↑"); sendButton.setTextColor(C_BG); sendButton.setBackground(round(C_ACCENT, C_ACCENT, 0, 12));
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(38), dp(35)); sendLp.setMargins(dp(5), 0, 0, 0);
        controls.addView(sendButton, sendLp); sendButton.setOnClickListener(v -> sendMessage());
        composer.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); return true; } return false;
        });
        composerBox.addView(controls);
    }

    private void refreshTab(LinearLayout tabs, String[] names) {
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView view = (TextView) tabs.getChildAt(i);
            view.setTextColor(names[i].equals(selectedTab) ? C_ACCENT : C_MUTED);
        }
        boolean team = "Equipo".equals(selectedTab);
        codeIntro.setVisibility("Código".equals(selectedTab) ? View.VISIBLE : View.GONE);
        teamPanel.setVisibility(team ? View.VISIBLE : View.GONE);
        conversation.setVisibility(team ? View.GONE : View.VISIBLE);
        composerBox.setVisibility(team ? View.GONE : View.VISIBLE);
        if ("Chat".equals(selectedTab)) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        else scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void showMenu() {
        String[] choices = {"Guía de Termux", "Limpiar conversación"};
        new AlertDialog.Builder(this).setTitle("NikuDarIA").setItems(choices, (dialog, which) -> {
            if (which == 0) showTermuxGuide();
            else { while (history.length() > 0) history.remove(0); conversation.removeAllViews(); addSystemLog("Conversación limpiada. Ninguna acción pendiente."); }
        }).show();
    }

    private void sendMessage() {
        String text = composer.getText().toString().trim();
        if (text.isEmpty() || requestBusy) return;
        composer.setText("");
        addUser(text);
        history.put(message("user", text));
        trimHistory();
        setBusy(true);
        long started = System.currentTimeMillis();
        addSystemLog("Enviando solicitud al agente…");
        JSONArray requestMessages = copyArray(history);
        new Thread(() -> {
            try {
                JSONObject response = api.agent(requestMessages);
                String reply = response.optString("reply", "");
                JSONObject action = response.optJSONObject("action");
                long elapsed = System.currentTimeMillis() - started;
                runOnUiThread(() -> {
                    setBusy(false);
                    if (!reply.isEmpty()) { addAssistant(reply); history.put(message("assistant", reply)); trimHistory(); }
                    addSystemLog("Respuesta recibida · " + elapsed + " ms · acción " + (action == null || action.isNull("type") ? "ninguna" : "requiere confirmación"));
                    if (action != null && !action.isNull("type")) offerAction(action);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { setBusy(false); addSystemLog("Solicitud fallida · " + safeError(e)); });
            }
        }, "niku-agent").start();
    }

    private void offerAction(JSONObject action) {
        String type = action.optString("type", "");
        String exact;
        if ("open_app".equals(type)) exact = "Aplicación: " + action.optString("app_name", "(sin nombre)");
        else if ("termux_command".equals(type)) exact = "Comando: " + action.optString("command", "");
        else { addSystemLog("El servidor propuso un tipo de acción no reconocido; no se ejecutó."); return; }
        pendingAction = action;
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(12), dp(10), dp(12), dp(10)); panel.setBackground(round(C_PANEL2, C_LINE, 1, 14));
        TextView heading = label("Acción pendiente · requiere tu permiso", 13, C_ACCENT); panel.addView(heading, margin(0, 0, 0, 7));
        TextView exactView = label(exact, 13, C_TEXT); panel.addView(exactView, margin(0, 0, 0, 6));
        TextView reason = label("Razón: " + action.optString("reason", "No se proporcionó razón."), 12, C_MUTED); panel.addView(reason, margin(0, 0, 0, 10));
        LinearLayout buttons = new LinearLayout(this);
        TextView cancel = pill("Cancelar"); TextView execute = pill("Ejecutar");
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1)); buttons.addView(execute, margin(8, 0, 0, 0));
        panel.addView(buttons); conversation.addView(panel, margin(0, 9, 0, 4));
        cancel.setOnClickListener(v -> {
            pendingAction = null;
            panel.removeAllViews();
            panel.setVisibility(View.GONE);
            addSystemLog("Acción cancelada por ti; no se ejecutó.");
            history.put(message("user", "Cancelé la acción propuesta: " + exact));
            trimHistory();
        });
        execute.setOnClickListener(v -> {
            if (pendingAction != action) return;
            pendingAction = null; cancel.setEnabled(false); execute.setEnabled(false);
            if ("termux_command".equals(type)) executeTermux(action, panel);
            else executeOpenApp(action, panel);
        });
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void executeTermux(JSONObject action, LinearLayout panel) {
        String command = action.optString("command", "");
        if (TermuxRunner.isBlocked(command)) { addPanelLine(panel, "Bloqueado por las salvaguardas del cliente; no se envió.", C_ACCENT); addSystemLog("Comando bloqueado por seguridad."); return; }
        TermuxRunner.Result result = TermuxRunner.run(this, command);
        addPanelLine(panel, result.message, result.started ? C_ACCENT : C_MUTED);
        addSystemLog(result.started ? "Termux inició la solicitud; no se afirma éxito hasta recibir su resultado." : "No ejecutado: " + result.message);
        if (!result.started && !isTermuxInstalled()) showTermuxGuide();
        else if (!result.started && result.message.contains("permiso")) showTermuxGuide();
    }

    private void executeOpenApp(JSONObject action, LinearLayout panel) {
        String requested = action.optString("app_name", "").trim();
        if (requested.isEmpty()) { addPanelLine(panel, "No se especificó ninguna app; no se abrió nada.", C_MUTED); return; }
        try {
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            PackageManager pm = getPackageManager();
            List<ResolveInfo> found = pm.queryIntentActivities(launcher, 0);
            ResolveInfo match = null;
            for (ResolveInfo info : found) {
                String pkg = info.activityInfo.packageName;
                String title = info.loadLabel(pm).toString();
                if (requested.equalsIgnoreCase(pkg) || requested.equalsIgnoreCase(title)) { match = info; break; }
            }
            if (match == null) { addPanelLine(panel, "No se encontró un launcher que coincida exactamente con «" + requested + "». No se abrió ninguna app.", C_MUTED); return; }
            Intent launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName);
            if (launch == null) { addPanelLine(panel, "La app no ofrece un launcher; no se abrió.", C_MUTED); return; }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            history.put(message("user", "Abrí la aplicación " + match.loadLabel(pm) + " tras confirmar la acción."));
            trimHistory();
            addSystemLog("Aplicación abierta tras confirmación: " + match.loadLabel(pm) + " (" + match.activityInfo.packageName + ").");
        } catch (Exception e) { addPanelLine(panel, "No se pudo abrir la app: " + e.getMessage(), C_MUTED); }
    }

    private void showTermuxGuide() {
        String text = "Para ejecutar una acción que tú apruebes:\n\n"
                + "1. Instala Termux desde una fuente compatible y ábrelo al menos una vez.\n"
                + "2. En Android, concede a NikuDarIA el permiso RUN_COMMAND desde los permisos de la app/Termux.\n"
                + "3. En Termux, edita ~/.termux/termux.properties y añade: allow-external-apps=true\n"
                + "4. Guarda el archivo y reinicia Termux.\n\n"
                + "Si falta Termux o el permiso, NikuDarIA no enviará el comando ni fingirá éxito. Se bloquean algunos patrones peligrosos conocidos, pero revisa el comando completo antes de aprobarlo.";
        new AlertDialog.Builder(this).setTitle("Guía de Termux").setMessage(text).setPositiveButton("Entendido", null).show();
    }

    private boolean isTermuxInstalled() { try { getPackageManager().getPackageInfo(TermuxRunner.TERMUX_PACKAGE, 0); return true; } catch (Exception e) { return false; } }

    @Override public void onTermuxResult(String transcript, int exitCode) {
        runOnUiThread(() -> {
            addSystemLog("Termux terminó · exit code " + exitCode + " · " + timeNow());
            String safeTranscript = transcript == null ? "" : transcript;
            if (safeTranscript.length() > 3000) safeTranscript = safeTranscript.substring(0, 3000) + "\n[transcript recortado]";
            String resultMessage = "Código de salida: " + exitCode + "\n" + safeTranscript;
            addBubble("Termux", resultMessage, false);
            history.put(message("user", "Resultado de Termux; úsalo para continuar la tarea.\n" + resultMessage));
            trimHistory();
        });
    }

    private void startVoiceInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es");
        try { startActivityForResult(intent, REQUEST_VOICE); }
        catch (ActivityNotFoundException e) { toast("No hay reconocimiento de voz disponible en este teléfono."); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VOICE && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) composer.setText(results.get(0));
        }
    }

    private void setBusy(boolean busy) { requestBusy = busy; if (sendButton != null) { sendButton.setEnabled(!busy); sendButton.setAlpha(busy ? .5f : 1f); } }
    private void addUser(String text) { addBubble("Tú", text, true); }
    private void addAssistant(String text) { addBubble("NikuDarIA", text, false); }
    private void addBubble(String who, String text, boolean user) {
        LinearLayout bubble = new LinearLayout(this); bubble.setOrientation(LinearLayout.VERTICAL); bubble.setPadding(dp(13), dp(10), dp(13), dp(10)); bubble.setBackground(round(user ? C_PANEL2 : C_PANEL, C_LINE, 1, 15));
        TextView label = label(who, 11, user ? C_ACCENT : C_MUTED); bubble.addView(label, margin(0, 0, 0, 5));
        bubble.addView(label(text, 14, C_TEXT));
        LinearLayout row = new LinearLayout(this); row.setGravity(user ? Gravity.RIGHT : Gravity.LEFT); row.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        conversation.addView(row, margin(user ? dp(30) : 0, 5, user ? 0 : dp(30), 5));
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void addSystemLog(String text) {
        TextView log = label("●  " + timeNow() + "   " + text, 11, C_MUTED);
        log.setPadding(dp(3), dp(4), dp(3), dp(4)); conversation.addView(log, margin(0, 2, 0, 2));
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void addPanelLine(LinearLayout panel, String text, int color) { panel.addView(label(text, 12, color), margin(0, 8, 0, 0)); }
    private String timeNow() { return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()); }
    private JSONObject message(String role, String content) { try { return new JSONObject().put("role", role).put("content", content); } catch (Exception e) { return new JSONObject(); } }
    private void trimHistory() {
        int totalChars = 0;
        for (int i = 0; i < history.length(); i++) totalChars += history.optJSONObject(i) == null ? 0 : history.optJSONObject(i).optString("content", "").length();
        while (history.length() > 12 || (totalChars > 15000 && history.length() > 1)) {
            JSONObject removed = history.optJSONObject(0);
            if (removed != null) totalChars -= removed.optString("content", "").length();
            history.remove(0);
        }
    }
    private JSONArray copyArray(JSONArray source) { JSONArray copy = new JSONArray(); for (int i = 0; i < source.length(); i++) copy.put(source.opt(i)); return copy; }
    private String safeError(Exception e) { String message = e.getMessage(); return message == null ? "error de conexión" : message; }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(13), dp(12), dp(13), dp(12)); box.setBackground(round(C_PANEL, C_LINE, 1, 15)); parent.addView(box, margin(0, 4, 0, 8)); return box;
    }
    private TextView label(String text, int size, int color) { TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); v.setIncludeFontPadding(true); return v; }
    private void addText(LinearLayout parent, String text, int size, int color, int left, int top, int right, int bottom) { parent.addView(label(text, size, color), margin(left, top, right, bottom)); }
    private TextView pill(String text) { TextView v = label(text, 12, C_TEXT); v.setGravity(Gravity.CENTER); v.setPadding(dp(12), dp(8), dp(12), dp(8)); v.setBackground(round(C_PANEL2, C_LINE, 1, 16)); return v; }
    private TextView control(String text) { TextView v = label(text, 18, C_TEXT); v.setGravity(Gravity.CENTER); v.setBackground(round(C_PANEL2, C_LINE, 1, 12)); return v; }
    private android.graphics.drawable.GradientDrawable round(int fill, int stroke, int strokeWidth, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); if (strokeWidth > 0) d.setStroke(dp(strokeWidth), stroke); return d;
    }
    private LinearLayout.LayoutParams margin(int left, int top, int right, int bottom) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return lp; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
