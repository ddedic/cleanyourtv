package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * status_widget: one pill in the top bar whose text another app supplies through its content provider
 * (docs/specs/status.md), for example the room temperature from a thermostat add-on. OK opens that app's screen.
 * The launcher only reads the row; it never goes online for it.
 */
final class StatusPill {
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    static final int MAX_TEXT = 24;
    private static final int FLAME = 0xFFFF8A3D;

    private final Activity activity;
    private final TextView pill;
    private final Uri status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ContentObserver observer;
    private final android.content.res.ColorStateList iconTint;
    private String intent = "";
    private boolean started;
    private int generation;

    StatusPill(Activity activity, TextView pill, String authority) {
        this.activity = activity;
        this.pill = pill;
        // One literal for the whole URI: a bare "status" string makes resource shrinking keep AndroidX's status_bar_*
        // strings in every language (+51 KB).
        this.status = Uri.parse("content://" + authority + "/status");
        iconTint = pill.getCompoundDrawableTintList();
        pill.setVisibility(View.GONE);
        pill.setOnClickListener(v -> open());
        observer = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                if (started) load(false);
            }
        };
    }

    /** Home came to the front: ask for a fresh row if the app's is stale, show what it has. */
    void start() {
        if (started) return;
        started = true;
        try {
            activity.getContentResolver().registerContentObserver(status, false, observer);
        } catch (RuntimeException unavailable) {
            // The app is gone or its provider is not exported: the pill stays hidden.
        }
        load(true);
    }

    void stop() {
        if (!started) return;
        started = false;
        generation++;
        try { activity.getContentResolver().unregisterContentObserver(observer); }
        catch (RuntimeException ignored) { }
    }

    private void load(boolean refresh) {
        int load = ++generation;
        ContentResolver resolver = activity.getContentResolver();
        LOADER.execute(() -> {
            if (refresh) {
                try { resolver.call(status, "refresh", null, null); }
                catch (RuntimeException unavailable) { /* show what it has */ }
            }
            String text = "", icon = "", description = "", target = "";
            try (Cursor row = resolver.query(status, null, null, null, null)) {
                if (row != null && row.moveToFirst()) {
                    text = column(row, "text");
                    icon = column(row, "icon");
                    description = column(row, "description");
                    target = column(row, "intent");
                }
            } catch (RuntimeException unavailable) {
                text = "";
            }
            String shown = clean(text), spoken = description, opens = target;
            int drawable = icon(icon);
            main.post(() -> {
                if (load != generation || !started) return;
                show(shown, drawable, spoken, opens);
            });
        });
    }

    private void show(String text, int icon, String description, String target) {
        intent = target;
        if (text.isEmpty()) {
            View next = pill.hasFocus() ? pill.focusSearch(View.FOCUS_RIGHT) : null;
            if (next != null) next.requestFocus();
            pill.setVisibility(View.GONE);
            return;
        }
        pill.setText(text);
        Drawable start = icon == 0 ? null : activity.getDrawable(icon);
        if (start != null) start.setBounds(0, 0, start.getIntrinsicWidth(), start.getIntrinsicHeight());
        // The flame is warm whatever the theme; every other icon takes the pills' own tint.
        if (start != null && icon == R.drawable.ic_flame) start.setTint(FLAME);
        pill.setCompoundDrawableTintList(icon == R.drawable.ic_flame ? null : iconTint);
        pill.setCompoundDrawablesRelative(start, null, null, null);
        pill.setContentDescription(description.isEmpty() ? text : description);
        pill.setVisibility(View.VISIBLE);
    }

    private void open() {
        Intent target = FeedRows.parseIntent(intent, activity.getPackageName());
        if (target == null) return;
        try {
            activity.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    private static String column(Cursor row, String name) {
        int index = row.getColumnIndex(name);
        String value = index < 0 ? null : row.getString(index);
        return value == null ? "" : value;
    }

    /** One line, at most MAX_TEXT characters: the pill sits beside Inputs and Wi-Fi and must not crowd them. */
    static String clean(String text) {
        String line = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return line.length() <= MAX_TEXT ? line : line.substring(0, MAX_TEXT - 1).trim() + "…";
    }

    /** The app names one of the launcher's own icons; anything else shows none. */
    static int icon(String name) {
        switch (name == null ? "" : name) {
            case "thermostat": return R.drawable.ic_thermostat;
            case "flame": return R.drawable.ic_flame;
            default: return 0;
        }
    }
}
