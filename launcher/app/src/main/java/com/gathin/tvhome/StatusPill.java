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
import android.view.ViewGroup;
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
    /** A badge label stays a small tag on the pill's corner: "22.5°" fits. */
    static final int MAX_BADGE = 5;
    private static final int FLAME = 0xFFFF8A3D;

    private final Activity activity;
    private final TextView pill;
    private final Uri status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ContentObserver observer;
    private final android.content.res.ColorStateList iconTint;
    private final int width, padStart, padEnd, iconPad, round;
    private final Badge badge;
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
        width = pill.getLayoutParams() == null ? ViewGroup.LayoutParams.WRAP_CONTENT : pill.getLayoutParams().width;
        padStart = pill.getPaddingStart();
        padEnd = pill.getPaddingEnd();
        iconPad = pill.getCompoundDrawablePadding();
        float density = activity.getResources().getDisplayMetrics().density;
        round = Math.round(42 * density);
        badge = new Badge(pill, density);
        pill.getOverlay().add(badge);
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
            String text = "", icon = "", description = "", target = "", count = "";
            try (Cursor row = resolver.query(status, null, null, null, null)) {
                if (row != null && row.moveToFirst()) {
                    text = column(row, "text");
                    icon = column(row, "icon");
                    description = column(row, "description");
                    target = column(row, "intent");
                    count = column(row, "badge");
                }
            } catch (RuntimeException unavailable) {
                text = "";
            }
            String shown = clean(text), spoken = description, opens = target, number = badge(count);
            int drawable = icon(icon);
            main.post(() -> {
                if (load != generation || !started) return;
                show(shown, drawable, spoken, opens, number);
            });
        });
    }

    private void show(String text, int icon, String description, String target, String number) {
        intent = target;
        if (text.isEmpty() && icon == 0) {
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
        // No text: a round icon pill like the icon-only Wi-Fi and Settings buttons.
        boolean iconOnly = text.isEmpty();
        pill.getLayoutParams().width = iconOnly ? round : width;
        // A label (a target) sits inside the pill's end as a small chip, so the text makes room for it.
        int chip = iconOnly || number.isEmpty() || isCount(number) ? 0 : badge.chipRoom(number);
        pill.setPaddingRelative(iconOnly ? (round - start.getIntrinsicWidth()) / 2 : padStart, 0,
                iconOnly ? (round - start.getIntrinsicWidth()) / 2 : padEnd + chip, 0);
        pill.setCompoundDrawablePadding(iconOnly ? 0 : iconPad);
        badge.set(number, pill.getLayoutParams().width == round ? round : pill.getWidth(), round);
        pill.setVisibility(View.VISIBLE);
        pill.requestLayout();
        pill.post(() -> badge.set(number, pill.getWidth(), pill.getHeight()));
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

    /** A count, "1" to "9" or "9+" for more, empty for none; any other value a short label, such as a target "22.5°". */
    static String badge(String value) {
        String line = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        int n;
        try {
            n = Integer.parseInt(line);
        } catch (NumberFormatException notACount) {
            return line.length() <= MAX_BADGE ? line : line.substring(0, MAX_BADGE).trim();
        }
        return n <= 0 ? "" : n > 9 ? "9+" : String.valueOf(n);
    }

    static boolean isCount(String badge) {
        return badge.equals("9+") || badge.length() == 1 && Character.isDigit(badge.charAt(0));
    }

    /** The app names one of the launcher's own icons; anything else shows none. */
    static int icon(String name) {
        switch (name == null ? "" : name) {
            case "thermostat": return R.drawable.ic_thermostat;
            case "flame": return R.drawable.ic_flame;
            case "door": return R.drawable.ic_door;
            default: return 0;
        }
    }

    /**
     * The small mark on the pill, drawn in its overlay: a count is an orange bubble on the top end corner that asks to
     * be seen, a label (a target temperature) a quiet light chip inside the pill's end.
     */
    private static final class Badge extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG),
                label = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float radius, pad;
        private final int inset;
        private final float density;
        private final View host;
        private String text = "";

        Badge(View host, float density) {
            this.host = host;
            radius = 8.5f * density;
            this.density = density;
            pad = 9 * density;
            inset = Math.round(8 * density);
            fill.setColor(FLAME);
            label.setColor(0xFFFFFFFF);
            label.setTextSize(11 * density);
            label.setFakeBoldText(true);
            label.setTextAlign(android.graphics.Paint.Align.CENTER);
        }

        void set(String value, int pillWidth, int pillHeight) {
            text = value;
            int size = Math.round(radius * 2);
            if (text.isEmpty() || isCount(text)) {
                setBounds(pillWidth - size, 0, pillWidth, size);
            } else {
                int width = chipWidth(text), height = Math.round(26 * density), top = (pillHeight - height) / 2;
                setBounds(pillWidth - inset - width, top, pillWidth - inset, top + height);
            }
            invalidateSelf();
        }

        private int chipWidth(String value) {
            size(value);
            return Math.round(label.measureText(value) + 2 * pad);
        }

        /** A count's digit is small and bold; a label reads like the pill's own text, a size down. */
        private void size(String value) {
            boolean count = isCount(value);
            label.setTextSize((count ? 11 : 15) * density);
            label.setFakeBoldText(count);
        }

        /** The end padding a label chip needs: its width, its inset from the pill's end, and a gap before it. */
        int chipRoom(String value) {
            return chipWidth(value) + inset;
        }

        @Override public void draw(android.graphics.Canvas canvas) {
            if (text.isEmpty()) return;
            android.graphics.Rect b = getBounds();
            // A single-line, centred TextView scrolls its canvas sideways; undo that so the bubble stays on the pill.
            canvas.save();
            canvas.translate(host.getScrollX(), host.getScrollY());
            boolean count = isCount(text);
            size(text);
            fill.setColor(count ? FLAME : 0x38FFFFFF);
            label.setColor(count ? 0xFFFFFFFF : 0xE6FFFFFF);
            float corner = b.height() / 2f;
            canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, corner, corner, fill);
            canvas.drawText(text, b.exactCenterX(), b.exactCenterY() - (label.descent() + label.ascent()) / 2, label);
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
