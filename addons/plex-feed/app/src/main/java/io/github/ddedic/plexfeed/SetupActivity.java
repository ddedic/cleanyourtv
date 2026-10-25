package io.github.ddedic.plexfeed;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sign in with a plex.tv/link code, pick a server, choose what the shelf shows. Everything here is remote-friendly. */
public final class SetupActivity extends Activity {
    private static final long POLL_MS = 2000;
    /** plex.tv keeps a code for 15 minutes. */
    private static final int MAX_POLLS = 450;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private Plex plex;
    private LinearLayout content;
    private Plex.Pin pin;
    private int polls;
    private boolean resumed;
    private String message = "";
    private List<Plex.Server> servers;
    private final Runnable poll = this::pollPin;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        plex = new Plex(store.clientId());
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101418);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(96), dp(48), dp(96), dp(48));
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (pin != null) main.postDelayed(poll, POLL_MS);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        main.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void render() {
        // A switch rebuilds the screen: keep focus on the row that was pressed, not back at the top.
        View focused = content.getFocusedChild();
        int focusIndex = focused == null ? -1 : content.indexOfChild(focused);
        content.removeAllViews();
        content.addView(text(getString(R.string.app_name), 34, Color.WHITE, Typeface.DEFAULT));
        if (!message.isEmpty()) content.addView(spaced(text(message, 16, 0xFFFFB74D, Typeface.DEFAULT), 12));
        if (!store.signedIn()) renderSignIn();
        else if (!store.hasServer()) renderServers();
        else renderFeed();
        View again = focusIndex >= 0 && focusIndex < content.getChildCount() ? content.getChildAt(focusIndex) : null;
        if (again == null || !again.isFocusable()) again = firstButton();
        if (again != null) again.requestFocus();
    }

    // ---- sign in ---------------------------------------------------------------------------------

    private void renderSignIn() {
        if (pin == null) {
            content.addView(spaced(text(getString(R.string.intro), 18, 0xCCFFFFFF, Typeface.DEFAULT), 20));
            content.addView(button(getString(R.string.sign_in), this::startPin), buttonParams(32));
            return;
        }
        content.addView(spaced(text(getString(R.string.enter_code), 18, 0xCCFFFFFF, Typeface.DEFAULT), 20));
        TextView code = text(pin.code, 72, Color.WHITE, Typeface.DEFAULT_BOLD);
        code.setLetterSpacing(0.3f);
        content.addView(spaced(code, 24));
        content.addView(button(getString(R.string.cancel), () -> {
            pin = null;
            main.removeCallbacks(poll);
            render();
        }), buttonParams(32));
    }

    private void startPin() {
        message = "";
        io.execute(() -> {
            try {
                Plex.Pin created = plex.createPin();
                main.post(() -> {
                    pin = created;
                    polls = 0;
                    render();
                    if (resumed) main.postDelayed(poll, POLL_MS);
                });
            } catch (Exception failed) {
                showMessage(getString(R.string.no_plex_tv, failed.getMessage()));
            }
        });
    }

    private void pollPin() {
        Plex.Pin current = pin;
        if (current == null || !resumed) return;
        if (++polls > MAX_POLLS) {
            pin = null;
            showMessage(getString(R.string.code_expired));
            return;
        }
        io.execute(() -> {
            String token = null;
            try {
                token = plex.pollPin(current.id);
            } catch (Exception retry) {
                // A dropped request: try again on the next tick.
            }
            String found = token;
            main.post(() -> {
                if (pin != current) return;
                if (found == null) {
                    if (resumed) main.postDelayed(poll, POLL_MS);
                    return;
                }
                store.setAccountToken(found);
                pin = null;
                servers = null;
                render();
            });
        });
    }

    // ---- server ----------------------------------------------------------------------------------

    private void renderServers() {
        content.addView(spaced(text(getString(R.string.choose_server), 22, Color.WHITE, Typeface.DEFAULT), 24));
        if (servers == null) {
            content.addView(spaced(text(getString(R.string.loading), 16, 0x99FFFFFF, Typeface.DEFAULT), 12));
            io.execute(() -> {
                try {
                    List<Plex.Server> found = plex.servers(store.accountToken());
                    main.post(() -> { servers = found; render(); });
                } catch (Exception failed) {
                    showMessage(getString(R.string.no_plex_tv, failed.getMessage()));
                }
            });
        } else if (servers.isEmpty()) {
            content.addView(spaced(text(getString(R.string.no_servers), 16, 0x99FFFFFF, Typeface.DEFAULT), 12));
        } else {
            for (Plex.Server server : servers) {
                String label = server.owned ? server.name : getString(R.string.shared_server, server.name);
                content.addView(button(label, () -> connect(server)), buttonParams(14));
            }
        }
        content.addView(button(getString(R.string.sign_out), this::signOut), buttonParams(32));
    }

    private void connect(Plex.Server server) {
        showMessage(getString(R.string.connecting, server.name));
        io.execute(() -> {
            String uri = plex.reachable(server);
            main.post(() -> {
                if (uri == null) {
                    showMessage(getString(R.string.unreachable, server.name));
                    return;
                }
                store.setServer(server, uri);
                message = "";
                refresh();
            });
        });
    }

    // ---- feed ------------------------------------------------------------------------------------

    private void renderFeed() {
        content.addView(spaced(text(getString(R.string.server, store.serverName()), 20, Color.WHITE, Typeface.DEFAULT), 20));
        content.addView(spaced(text(status(), 15, 0x99FFFFFF, Typeface.DEFAULT), 8));
        toggle(Store.CONTINUE, R.string.show_continue);
        toggle(Store.MOVIES, R.string.show_movies);
        toggle(Store.EPISODES, R.string.show_episodes);
        content.addView(button(getString(R.string.per_section, store.perSection()), () -> {
            int next = Store.PER_SECTION[0];
            for (int i = 0; i < Store.PER_SECTION.length; i++) {
                if (Store.PER_SECTION[i] == store.perSection()) next = Store.PER_SECTION[(i + 1) % Store.PER_SECTION.length];
            }
            store.setPerSection(next);
            refresh();
        }), buttonParams(14));
        renderScreensaver();
        content.addView(button(getString(R.string.refresh), this::refresh), buttonParams(32));
        content.addView(button(getString(R.string.change_server), () -> {
            store.setServer(new Plex.Server("", "", "", false, java.util.Collections.emptyList()), "");
            servers = null;
            render();
        }), buttonParams(14));
        content.addView(button(getString(R.string.sign_out), this::signOut), buttonParams(14));
        content.addView(spaced(text(getString(R.string.launcher_hint), 14, 0x80FFFFFF, Typeface.DEFAULT), 32));
    }

    /** The TV's screensaver: this add-on's artwork on or off, and how long the TV waits. */
    private void renderScreensaver() {
        Screensaver saver = new Screensaver(this);
        if (!saver.canChange()) {
            content.addView(spaced(text(getString(R.string.screensaver_needs_grant), 15, 0x99FFFFFF, Typeface.DEFAULT), 32));
            return;
        }
        boolean on = saver.isOn();
        content.addView(button(getString(R.string.screensaver_state, getString(on ? R.string.on : R.string.off)), () -> {
            if (saver.isOn()) saver.turnOff();
            else saver.turnOn(saver.minutes());
            render();
        }), buttonParams(32));
        if (!on) return;
        content.addView(button(getString(R.string.screensaver_wait, saver.minutes()), () -> {
            int next = Screensaver.MINUTES[0];
            for (int i = 0; i < Screensaver.MINUTES.length; i++) {
                if (Screensaver.MINUTES[i] == saver.minutes()) next = Screensaver.MINUTES[(i + 1) % Screensaver.MINUTES.length];
            }
            saver.turnOn(next);
            render();
        }), buttonParams(14));
    }

    private void toggle(String key, int label) {
        String state = getString(store.section(key) ? R.string.on : R.string.off);
        content.addView(button(getString(label, state), () -> {
            store.setSection(key, !store.section(key));
            refresh();
        }), buttonParams(14));
    }

    private String status() {
        if (Fetcher.isRunning()) return getString(R.string.updating);
        if (!store.lastError().isEmpty()) return getString(R.string.failed, store.lastError());
        if (store.itemCount() == 0 && store.lastFetch() == 0) return getString(R.string.updating);
        CharSequence when = DateUtils.getRelativeTimeSpanString(store.lastFetch(), System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS);
        return getString(R.string.updated, when, store.itemCount());
    }

    private void refresh() {
        Fetcher.refresh(this, true, () -> main.post(() -> { if (!isFinishing()) render(); }));
        render();
    }

    private void signOut() {
        pin = null;
        servers = null;
        message = "";
        store.signOut();
        getContentResolver().notifyChange(FeedProvider.ITEMS, null);
        render();
    }

    // ---- views -----------------------------------------------------------------------------------

    private void showMessage(String text) {
        main.post(() -> {
            message = text;
            if (!isFinishing()) render();
        });
    }

    private View firstButton() {
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child.isFocusable()) return child;
        }
        return null;
    }

    private TextView text(String value, int sp, int color, Typeface face) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(face);
        return view;
    }

    private View spaced(View view, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topDp);
        view.setLayoutParams(params);
        return view;
    }

    private TextView button(String label, Runnable action) {
        TextView button = text(label, 18, Color.WHITE, Typeface.DEFAULT);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(24), 0, dp(24), 0);
        button.setFocusable(true);
        button.setClickable(true);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, rounded(0x40FFFFFF, 0xCCFFFFFF));
        background.addState(new int[0], rounded(0x14FFFFFF, 0x00000000));
        button.setBackground(background);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private GradientDrawable rounded(int fill, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(14));
        shape.setStroke(dp(2), stroke);
        return shape;
    }

    private LinearLayout.LayoutParams buttonParams(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(56));
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
