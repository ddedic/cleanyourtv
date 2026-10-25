package io.github.ddedic.netatmo;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * The heating screen the launcher's pill opens, full screen: a big dial for one room (▲ ▼ warmer or colder), its
 * rooms, and the home's mode (Schedule, Away, Frost guard). Changes go to Netatmo once the keys rest.
 */
public final class PanelActivity extends Activity {
    private static final int DIM = 0x99FFFFFF, FAINT = 0x14FFFFFF, FOCUS = 0x33FFFFFF;
    /** A change is sent once the keys rest this long, so five presses make one call to Netatmo. */
    private static final long SEND_AFTER_MS = 1200;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Double> pending = new HashMap<>();
    private final Map<String, Runnable> sends = new HashMap<>();
    private Store store;
    private ImageView background;
    private LinearLayout left, right;
    private DialView dial;
    private String dialRoom = "", focusKey = "dial";
    private float density;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        store = new Store(this);
        dialRoom = store.room();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080B);
        background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(background, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00000000, 0x66000000, 0xB3000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setPadding(dp(64), dp(36), dp(72), dp(36));
        left = column(Gravity.CENTER);
        right = column(Gravity.CENTER_VERTICAL);
        content.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
        content.addView(right, new LinearLayout.LayoutParams(dp(400), -1));
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store.importCredentials(this)) store.fetched(null, "");
        render();
        Fetcher.refresh(this, true, () -> main.post(this::renderIfOpen));
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving with a change still waiting sends it now rather than dropping it.
        for (Runnable send : sends.values()) { main.removeCallbacks(send); send.run(); }
        sends.clear();
    }

    private void renderIfOpen() {
        if (!isFinishing() && !isDestroyed()) render();
    }

    private void render() {
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof String) focusKey = (String) focused.getTag();
        left.removeAllViews();
        right.removeAllViews();
        Status status = store.signedIn() ? store.status() : null;
        Status.Room room = status == null ? null : status.main(dialRoom.isEmpty() ? store.room() : dialRoom);
        boolean heating = status != null && status.heating();
        background.setImageResource(heating ? R.drawable.bg_warm : R.drawable.bg_cool);

        TextView heading = overline(getString(R.string.title));
        heading.setGravity(Gravity.CENTER);
        left.addView(heading);
        if (!store.signedIn() || room == null) {
            TextView message = text(store.signedIn() ? statusLine(status) : getString(R.string.setup), 20, Color.WHITE);
            message.setGravity(Gravity.CENTER);
            left.addView(spaced(message, 24));
            return;
        }
        TextView name = text(room.name, 34, Color.WHITE);
        name.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        name.setGravity(Gravity.CENTER);
        left.addView(spaced(name, 6));
        dial = new DialView(this);
        dial.setTag("dial");
        bindDial(dial, room);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(320), dp(320));
        size.topMargin = dp(8);
        left.addView(dial, size);
        TextView hint = text(getString(status.rooms.size() > 1 ? R.string.hint_rooms : R.string.hint), 13, DIM);
        hint.setGravity(Gravity.CENTER);
        left.addView(spaced(hint, 8));

        right.addView(statusCard(status, heating));
        if (status.rooms.size() > 1) {
            right.addView(spaced(overline(getString(R.string.rooms)), 28));
            for (Status.Room r : status.rooms) right.addView(spaced(roomRow(status, r, r.id.equals(room.id)), 8));
        }
        right.addView(spaced(overline(getString(R.string.mode)), 28));
        String mode = status.homeMode();
        right.addView(spaced(modeRow(R.drawable.ic_calendar, R.string.mode_schedule, R.string.mode_schedule_sub, "schedule", mode), 8));
        right.addView(spaced(modeRow(R.drawable.ic_away, R.string.mode_away, R.string.mode_away_sub, "away", mode), 8));
        right.addView(spaced(modeRow(R.drawable.ic_frost, R.string.mode_frost, R.string.mode_frost_sub, "hg", mode), 8));
        if ("manual".equals(room.mode) || pending.containsKey(room.id)) {
            right.addView(spaced(button(R.string.back_to_schedule, "back", () -> backToSchedule(room)), 20));
        }

        View again = findViewByTag(focusKey);
        (again != null ? again : dial).requestFocus();
    }

    private View findViewByTag(String tag) {
        View found = left.findViewWithTag(tag);
        return found != null ? found : right.findViewWithTag(tag);
    }

    private void bindDial(DialView view, Status.Room room) {
        double target = pending.containsKey(room.id) ? pending.get(room.id) : room.target;
        view.set(room.measured, target, room.heating, pending.containsKey(room.id), modeLabel(room.mode).toUpperCase());
        view.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            double base = pending.containsKey(room.id) ? pending.get(room.id) : room.target;
            double next = Status.step(base, code == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1);
            pending.put(room.id, next);
            view.set(room.measured, next, room.heating, true, getString(R.string.room_manual).toUpperCase());
            schedule(room.id, () -> sendRoom(room.id, next));
            if (right.findViewWithTag("back") == null) render();   // offer Back to schedule at once
            return true;
        });
    }

    private View statusCard(Status status, boolean heating) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(rounded(heating ? 0x33FF8A3D : 0x2B5CC8FF, 20, 0));
        card.addView(icon(heating ? R.drawable.ic_flame : R.drawable.ic_frost, heating ? DialView.WARM : DialView.COOL, 30));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(dp(16), 0, 0, 0);
        TextView title = text(getString(heating ? R.string.heating_now : R.string.not_heating), 22, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        lines.addView(title);
        lines.addView(text(statusLine(status), 14, DIM));
        card.addView(lines);
        return card;
    }

    private String statusLine(Status status) {
        if (!store.lastError().isEmpty()) return getString(R.string.failed, store.lastError());
        if (status == null) return getString(R.string.updating);
        long now = System.currentTimeMillis();
        CharSequence when = now - status.fetchedAt < DateUtils.MINUTE_IN_MILLIS ? getString(R.string.just_now)
                : DateUtils.getRelativeTimeSpanString(status.fetchedAt, now, DateUtils.MINUTE_IN_MILLIS);
        return getString(R.string.updated, when);
    }

    private View roomRow(Status status, Status.Room room, boolean shown) {
        LinearLayout row = focusRow("room:" + room.id, shown);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(text(room.name, 18, Color.WHITE));
        Status.Room onHome = status.main(store.room());
        String sub = modeLabel(room.mode) + (onHome != null && onHome.id.equals(room.id) ? " · " + getString(R.string.on_home) : "");
        names.addView(text(sub, 13, room.heating ? DialView.WARM : DIM));
        row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        TextView temp = text(Status.degrees(room.measured), 24, Color.WHITE);
        temp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        row.addView(temp);
        row.setOnClickListener(v -> { dialRoom = room.id; focusKey = "dial"; render(); });
        row.setOnLongClickListener(v -> {
            store.setRoom(room.id);
            getContentResolver().notifyChange(StatusProvider.STATUS, null);
            render();
            return true;
        });
        return row;
    }

    private View modeRow(int iconRes, int label, int sub, String mode, String current) {
        boolean on = mode.equals(current);
        LinearLayout row = focusRow("mode:" + mode, on);
        row.addView(icon(iconRes, on ? Color.BLACK : Color.WHITE, 24));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(dp(16), 0, 0, 0);
        lines.addView(text(getString(label), 18, on ? Color.BLACK : Color.WHITE));
        lines.addView(text(getString(sub), 13, on ? 0x99000000 : DIM));
        row.addView(lines);
        if (on) row.setBackground(rounded(0xF2FFFFFF, 18, 0));
        row.setOnFocusChangeListener((v, has) -> v.setBackground(
                rounded(on ? 0xF2FFFFFF : has ? FOCUS : FAINT, 18, has ? 0xFFFFFFFF : 0)));
        row.setOnClickListener(v -> {
            if (on) return;
            Fetcher.send(this, (api, token, home) -> api.setMode(token, home, mode), () -> main.post(this::renderIfOpen));
        });
        return row;
    }

    private View button(int label, String tag, Runnable action) {
        TextView button = text(getString(label), 17, Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(20), dp(14), dp(20), dp(14));
        button.setFocusable(true);
        button.setTag(tag);
        button.setBackground(rounded(FAINT, 24, 0));
        button.setOnFocusChangeListener((v, has) -> v.setBackground(rounded(has ? FOCUS : FAINT, 24, has ? 0xFFFFFFFF : 0)));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private LinearLayout focusRow(String tag, boolean selected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));
        row.setFocusable(true);
        row.setTag(tag);
        row.setBackground(rounded(selected ? 0x2EFFFFFF : FAINT, 18, 0));
        row.setOnFocusChangeListener((v, has) -> v.setBackground(
                rounded(has ? FOCUS : selected ? 0x2EFFFFFF : FAINT, 18, has ? 0xFFFFFFFF : 0)));
        return row;
    }

    private void backToSchedule(Status.Room room) {
        pending.remove(room.id);
        Runnable waiting = sends.remove(room.id);
        if (waiting != null) main.removeCallbacks(waiting);
        focusKey = "dial";
        sendRoom(room.id, Double.NaN);
    }

    private String modeLabel(String mode) {
        switch (mode) {
            case "manual": return getString(R.string.room_manual);
            case "away": return getString(R.string.mode_away);
            case "hg": return getString(R.string.mode_frost);
            case "off": return getString(R.string.room_off);
            default: return getString(R.string.mode_schedule);
        }
    }

    private void schedule(String room, Runnable send) {
        Runnable old = sends.put(room, send);
        if (old != null) main.removeCallbacks(old);
        main.postDelayed(send, SEND_AFTER_MS);
    }

    private void sendRoom(String room, double temp) {
        sends.remove(room);
        Fetcher.send(this, (api, token, home) -> api.setRoom(token, home, room, temp), () -> main.post(() -> {
            pending.remove(room);
            renderIfOpen();
        }));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) { finish(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    private LinearLayout column(int gravity) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(gravity);
        return column;
    }

    private TextView overline(String value) {
        TextView view = text(value.toUpperCase(), 13, DIM);
        view.setLetterSpacing(0.25f);
        return view;
    }

    private ImageView icon(int res, int tint, int sizeDp) {
        ImageView view = new ImageView(this);
        Drawable drawable = getDrawable(res);
        if (drawable != null) drawable.setTint(tint);
        view.setImageDrawable(drawable);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return view;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private View spaced(View view, int topDp) {
        ViewGroup.LayoutParams old = view.getLayoutParams();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                old != null ? old.width : ViewGroup.LayoutParams.MATCH_PARENT,
                old != null ? old.height : ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topDp);
        view.setLayoutParams(params);
        return view;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (stroke != 0) shape.setStroke(dp(2), stroke);
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
