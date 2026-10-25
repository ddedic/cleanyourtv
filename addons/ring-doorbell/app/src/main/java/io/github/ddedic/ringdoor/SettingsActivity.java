package io.github.ddedic.ringdoor;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The doorbell's settings on the TV: each row shows its value on the right, and OK steps to the next one. Changes
 * apply at once; the doorbell switch starts or stops the background connection.
 */
public final class SettingsActivity extends Activity {
    private static final int[] POPUP_SECONDS = {15, 25, 40, 60};
    /** Quiet hours as start and end hours; the first pair means off. */
    private static final int[][] QUIET = {{0, 0}, {22, 7}, {23, 7}, {21, 6}, {0, 8}};

    private Store store;
    private LinearLayout side, rows;
    private String focusKey = "enabled";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(Ui.backdrop(this));
        LinearLayout content = new LinearLayout(this);
        content.setPadding(Ui.dp(this, 56), Ui.dp(this, 40), Ui.dp(this, 56), Ui.dp(this, 32));
        side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        content.addView(side, new LinearLayout.LayoutParams(Ui.dp(this, 300), -1));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), Ui.dp(this, 16));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, -1, 1);
        at.setMarginStart(Ui.dp(this, 44));
        content.addView(scroll, at);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof String) focusKey = (String) focused.getTag();
        rows.removeAllViews();
        side.removeAllViews();
        side.addView(Ui.overline(this, getString(R.string.app_name)));
        side.addView(Ui.light(this, getString(R.string.settings), 34, Ui.WHITE));
        side.addView(Ui.statusCard(this, store), Ui.gapTop(this, -1, 22));
        TextView about = Ui.text(this, getString(R.string.settings_about), 14, Ui.DIM);
        about.setLineSpacing(0, 1.2f);
        side.addView(about, Ui.gapTop(this, -1, 18));
        boolean on = store.enabled();
        section(getString(R.string.section_alerts));
        row("enabled", getString(R.string.set_enabled), getString(R.string.set_enabled_sub), onOff(on), () -> {
            store.set("enabled", !store.enabled());
            if (store.enabled()) DoorbellService.start(this); else DoorbellService.stop(this);
            getContentResolver().notifyChange(StatusProvider.STATUS, null);
        });
        if (on) {
            row("popup", getString(R.string.set_popup), getString(R.string.set_popup_sub), onOff(store.popup()),
                    () -> store.set("popup", !store.popup()));
            row("pause", getString(R.string.set_pause), getString(R.string.set_pause_sub), onOff(store.pause()),
                    () -> store.set("pause", !store.pause()));
            row("motion", getString(R.string.set_motion), getString(R.string.set_motion_sub), motionLabel(store.motion()), () -> {
                String m = store.motion();
                store.set("motion", Store.MOTION_OFF.equals(m) ? Store.MOTION_PEOPLE
                        : Store.MOTION_PEOPLE.equals(m) ? Store.MOTION_ALL : Store.MOTION_OFF);
            });
            row("seconds", getString(R.string.set_seconds), getString(R.string.set_seconds_sub),
                    getString(R.string.seconds, store.popupSeconds()), () -> store.set("popup_seconds", next(POPUP_SECONDS, store.popupSeconds())));
            row("quiet", getString(R.string.set_quiet), getString(R.string.set_quiet_sub), quietLabel(), this::nextQuiet);
            List<RingApi.Device> cameras = new ArrayList<>();
            for (RingApi.Device d : store.devices()) if (!d.chime) cameras.add(d);
            if (cameras.size() > 1) {
                RingApi.Device current = store.camera();
                row("camera", getString(R.string.set_camera), getString(R.string.set_camera_sub),
                        current == null ? "" : current.name, () -> {
                            int i = 0;
                            for (int k = 0; k < cameras.size(); k++) if (current != null && cameras.get(k).id.equals(current.id)) i = k;
                            store.set("camera", cameras.get((i + 1) % cameras.size()).id);
                        });
            }
        }
        section(getString(R.string.section_ring));
        RingApi.Device chime = store.chime();
        if (chime != null) info(getString(R.string.set_chime), Ui.chimeLine(this, chime));
        if (store.linked()) {
            row("unlink", getString(R.string.set_unlink), getString(R.string.set_unlink_sub), "", () -> {
                store.unlink();
                getContentResolver().notifyChange(StatusProvider.STATUS, null);
            });
        }
        View again = rows.findViewWithTag(focusKey);
        if (again == null) again = rows.findViewWithTag("enabled");
        if (again != null) again.requestFocus();
    }

    private void section(String label) {
        TextView header = Ui.overline(this, label);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, -2);
        at.topMargin = Ui.dp(this, rows.getChildCount() == 0 ? 8 : 26);
        at.bottomMargin = Ui.dp(this, 2);
        rows.addView(header, at);
    }

    private void row(String key, String label, String sub, String value, Runnable change) {
        LinearLayout row = item(label, sub, value);
        row.setTag(key);
        LinearLayout lines = (LinearLayout) row.getChildAt(0);
        TextView chip = row.getChildCount() > 1 ? (TextView) row.getChildAt(1) : null;
        TextView[] texts = new TextView[lines.getChildCount()];
        for (int i = 0; i < texts.length; i++) texts[i] = (TextView) lines.getChildAt(i);
        if (chip != null) row.setOnFocusChangeListener((v, has) ->
                Ui.chipStyle(this, chip, getString(R.string.on), getString(R.string.off), has));
        Ui.focusCard(this, row, 18, 0x1AFFFFFF, texts);
        row.setOnClickListener(v -> { focusKey = key; change.run(); render(); });
    }

    private void info(String label, String value) {
        item(label, value, "");
    }

    private LinearLayout item(String label, String sub, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 22), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));
        row.setBackground(Ui.rounded(this, 0x1AFFFFFF, 18, 0));
        row.setMinimumHeight(Ui.dp(this, 60));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.addView(Ui.medium(this, label, 17, Ui.WHITE));
        if (!sub.isEmpty()) lines.addView(Ui.text(this, sub, 13, Ui.DIM));
        row.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        if (!value.isEmpty()) row.addView(Ui.chip(this, value, getString(R.string.on), getString(R.string.off)));
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, -2);
        at.topMargin = Ui.dp(this, 8);
        rows.addView(row, at);
        return row;
    }

    private String onOff(boolean on) { return getString(on ? R.string.on : R.string.off); }

    private String motionLabel(String m) {
        return getString(Store.MOTION_PEOPLE.equals(m) ? R.string.motion_people
                : Store.MOTION_ALL.equals(m) ? R.string.motion_all : R.string.off);
    }

    private String quietLabel() {
        return store.quietHours() ? getString(R.string.quiet_range, store.quietFrom(), store.quietTo()) : getString(R.string.off);
    }

    private void nextQuiet() {
        int current = 0;
        if (store.quietHours()) {
            for (int i = 1; i < QUIET.length; i++) {
                if (QUIET[i][0] == store.quietFrom() && QUIET[i][1] == store.quietTo()) current = i;
            }
        }
        int next = (current + 1) % QUIET.length;
        store.set("quiet", next != 0);
        if (next != 0) { store.set("quiet_from", QUIET[next][0]); store.set("quiet_to", QUIET[next][1]); }
    }

    private static int next(int[] values, int current) {
        for (int i = 0; i < values.length; i++) if (values[i] == current) return values[(i + 1) % values.length];
        return values[0];
    }
}
