package com.nikudaria.ai;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/** Receives the nested result Bundle returned by Termux RUN_COMMAND. */
public final class TermuxResultReceiver extends BroadcastReceiver {
    public static final String ACTION_RESULT = "com.nikudaria.ai.TERMUX_RESULT";
    private static final String RESULT_BUNDLE = "result";
    private static final int MAX_TRANSCRIPT_CHARS = 8000;

    public interface Listener {
        void onTermuxResult(String transcript, int exitCode);
    }

    private static volatile Listener listener;

    public static void setListener(Listener value) {
        listener = value;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle result = intent == null ? null : intent.getBundleExtra(RESULT_BUNDLE);
        String transcript = result == null ? "" : safe(result.getString("stdout", ""));
        String stderr = result == null ? "" : safe(result.getString("stderr", ""));
        if (!stderr.isEmpty()) {
            transcript = transcript + (transcript.isEmpty() ? "" : "\n") + stderr;
        }

        int exitCode = result == null ? -1 : result.getInt("exitCode", -1);
        if (result != null && result.getInt("err", Activity.RESULT_OK) != Activity.RESULT_OK) {
            String error = safe(result.getString("errmsg", ""));
            if (!error.isEmpty()) transcript = transcript + (transcript.isEmpty() ? "" : "\n") + error;
        }
        if (transcript.isEmpty()) transcript = "Termux terminó sin devolver transcript.";
        if (transcript.length() > MAX_TRANSCRIPT_CHARS) {
            transcript = transcript.substring(0, MAX_TRANSCRIPT_CHARS) + "\n[transcript recortado]";
        }

        SharedPreferences prefs = context.getSharedPreferences("nikudaria_private", Context.MODE_PRIVATE);
        Listener current = listener;
        SharedPreferences.Editor editor = prefs.edit()
                .putString("termux_last_transcript", transcript)
                .putInt("termux_last_exit", exitCode)
                .putBoolean("termux_result_pending", current == null);
        editor.apply();
        if (current != null) current.onTermuxResult(transcript, exitCode);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
