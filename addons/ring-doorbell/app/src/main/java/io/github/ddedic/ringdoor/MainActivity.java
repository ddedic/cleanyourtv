package io.github.ddedic.ringdoor;

import android.app.Activity;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The doorbell, full screen: the chosen picture large on the left, where things stand, Check now and every recent
 * ring and motion on the right, grouped by day. Focusing an entry shows its picture; one Ring has not given us yet is
 * fetched for that moment.
 */
public final class MainActivity extends Activity {
    /** The selection that means "the newest picture", from Check now, rather than one event's. */
    private static final String LATEST = "latest";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService ring = Executors.newSingleThreadExecutor();
    private final Set<String> asked = new HashSet<>(), failed = new HashSet<>();
    private Store store;
    private FrameLayout frame;
    private ImageView picture;
    private TextView title, subtitle;
    private LinearLayout status, list;
    private View check;
    private String selected = "";
    private boolean checking;
    private final ContentObserver changes = new ContentObserver(main) {
        @Override public void onChange(boolean self) { render(); }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(Ui.backdrop(this));
        LinearLayout content = new LinearLayout(this);
        content.setPadding(Ui.dp(this, 56), Ui.dp(this, 40), Ui.dp(this, 56), Ui.dp(this, 32));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(Ui.overline(this, getString(R.string.app_name)));
        title = Ui.light(this, "", 34, Ui.WHITE);
        left.addView(title);
        frame = Ui.pictureFrame(this, getString(R.string.no_picture_yet), 26);
        picture = (ImageView) frame.getChildAt(1);
        left.addView(frame, Ui.gapTop(this, -1, 18));
        subtitle = Ui.text(this, "", 14, Ui.DIM);
        left.addView(subtitle, Ui.gapTop(this, -1, 14));
        content.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        status = new LinearLayout(this);
        right.addView(status, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = new LinearLayout(this);
        check = Ui.button(this, R.drawable.ic_refresh, getString(R.string.check_now));
        check.setOnClickListener(v -> checkNow());
        actions.addView(check, new LinearLayout.LayoutParams(0, -2, 1));
        View settings = Ui.button(this, R.drawable.ic_settings, null);
        settings.setContentDescription(getString(R.string.settings));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams settingsAt = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48));
        settingsAt.setMarginStart(Ui.dp(this, 10));
        actions.addView(settings, settingsAt);
        right.addView(actions, Ui.gapTop(this, -1, 12));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 8));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams scrollAt = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollAt.topMargin = Ui.dp(this, 8);
        right.addView(scroll, scrollAt);
        LinearLayout.LayoutParams rightAt = new LinearLayout.LayoutParams(Ui.dp(this, 330), -1);
        rightAt.setMarginStart(Ui.dp(this, 40));
        content.addView(right, rightAt);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        check.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.importCredentials();
        store.seen();
        getContentResolver().notifyChange(StatusProvider.STATUS, null);
        getContentResolver().registerContentObserver(StatusProvider.STATUS, false, changes);
        DoorbellService.start(this);
        selected = "";
        render();
        // Opening the screen is asking to see the door: fetch a picture unless one is fresh.
        if (store.linked() && System.currentTimeMillis() - store.latestFile().lastModified() > 60_000) checkNow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getContentResolver().unregisterContentObserver(changes);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ring.shutdownNow();
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        RingApi.Device camera = store.camera();
        title.setText(camera == null ? getString(R.string.doorbell) : camera.name);
        status.removeAllViews();
        status.addView(Ui.statusCard(this, store), new LinearLayout.LayoutParams(-1, -2));

        View focused = getCurrentFocus();
        String focusedId = focused != null && focused.getParent() == list && focused.getTag() instanceof String
                ? (String) focused.getTag() : null;
        list.removeAllViews();
        List<Store.Event> events = store.events();
        if (events.isEmpty()) {
            list.addView(Ui.overline(this, getString(R.string.recent)), Ui.gapTop(this, -1, 14));
            TextView none = Ui.text(this, getString(R.string.no_events), 15, Ui.DIM);
            list.addView(none, Ui.gapTop(this, -1, 10));
        }
        String day = "";
        for (Store.Event e : events) {
            String header = Ui.day(this, e.at);
            if (!header.equals(day)) {
                day = header;
                list.addView(Ui.overline(this, header), Ui.gapTop(this, -1, list.getChildCount() == 0 ? 14 : 20));
            }
            list.addView(row(e), Ui.gapTop(this, -1, 8));
            if (!store.snapshotFile(e.id).isFile()) fetch(e);   // fills the thumbnails, newest first
        }
        if (focusedId != null) {
            View again = list.findViewWithTag(focusedId);
            if (again != null) again.requestFocus();
        }
        // Keep what is on show; otherwise the newest of the two: the last Check now, or the newest event.
        if (selected.isEmpty()) {
            File latest = store.latestFile();
            Store.Event newest = events.isEmpty() ? null : events.get(0);
            selected = newest == null || latest.isFile() && latest.lastModified() >= newest.at ? LATEST : newest.id;
        }
        showSelected();
    }

    private View row(Store.Event e) {
        LinearLayout row = new LinearLayout(this);
        row.setTag(e.id);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        FrameLayout thumb = new FrameLayout(this);
        thumb.setBackground(Ui.rounded(this, 0xFF1C2028, 12, 0));
        Ui.clip(thumb, Ui.dp(this, 12));
        Bitmap small = thumbnail(store.snapshotFile(e.id));
        if (small != null) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(small);
            thumb.addView(image, new FrameLayout.LayoutParams(-1, -1));
        } else {
            thumb.addView(Ui.icon(this, e.ding() ? R.drawable.ic_door : R.drawable.ic_motion, 0x66FFFFFF, 24),
                    new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER));
        }
        View stripe = new View(this);
        stripe.setBackgroundColor(Ui.color(e));
        thumb.addView(stripe, new FrameLayout.LayoutParams(Ui.dp(this, 4), -1, Gravity.START));
        row.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 50)));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(Ui.dp(this, 14), 0, 0, 0);
        TextView kind = Ui.medium(this, Ui.kind(this, e), 17, e.ding() ? Ui.RING : Ui.WHITE);
        lines.addView(kind);
        TextView when = Ui.text(this, Ui.clock(this, e.at) + (e.missed ? " · " + getString(R.string.missed) : ""), 13, Ui.DIM);
        lines.addView(when);
        row.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnFocusChangeListener((v, has) -> {
            if (has && !e.id.equals(selected)) {
                selected = e.id;
                showSelected();
            }
        });
        Ui.focusCard(this, row, 16, Ui.FAINT, kind, when);
        row.setOnClickListener(v -> startActivity(new Intent(this, PreviewActivity.class).putExtra(PreviewActivity.EVENT, e.id)));
        return row;
    }

    private static Bitmap thumbnail(File file) {
        if (!file.isFile()) return null;
        BitmapFactory.Options small = new BitmapFactory.Options();
        small.inSampleSize = 8;
        return BitmapFactory.decodeFile(file.getPath(), small);
    }

    private void showSelected() {
        if (LATEST.equals(selected)) {
            File latest = store.latestFile();
            boolean has = latest.isFile();
            picture.setImageBitmap(has ? BitmapFactory.decodeFile(latest.getPath()) : null);
            Ui.caption(this, frame, has ? Ui.badge(this, getString(R.string.badge_newest), Ui.MOTION) : null,
                    has ? getString(R.string.picture_from, Ui.when(this, latest.lastModified())) : null);
            Ui.empty(frame, getString(checking ? R.string.checking : R.string.no_picture_yet));
            subtitle.setText(has ? getString(checking ? R.string.checking : R.string.hint_newest) : "");
            return;
        }
        Store.Event e = event(selected);
        if (e == null) return;
        File file = store.snapshotFile(e.id);
        Bitmap bitmap = file.isFile() ? BitmapFactory.decodeFile(file.getPath()) : null;
        picture.setImageBitmap(bitmap);
        Ui.caption(this, frame, Ui.badge(this, Ui.kind(this, e), Ui.color(e)), Ui.when(this, e.at));
        subtitle.setText(e.missed ? getString(R.string.missed_line) : "");
        if (bitmap == null) {
            fetch(e);
            Ui.empty(frame, getString(failed.contains(e.id) ? R.string.no_picture_event : R.string.fetching_event));
        }
    }

    private Store.Event event(String id) {
        for (Store.Event e : store.events()) if (e.id.equals(id)) return e;
        return null;
    }

    /** Asks Ring once per visit for the picture at an event's moment, then refreshes the list with its thumbnail. */
    private void fetch(Store.Event e) {
        if (!store.linked() || !asked.add(e.id)) return;
        ring.execute(() -> {
            try {
                byte[] jpeg = store.api().snapshot(DoorbellService.token(store), e.deviceId, e.at, store.linkedAt());
                store.write(store.snapshotFile(e.id), jpeg);
                android.util.Log.i(DoorbellService.TAG, "event picture: " + jpeg.length / 1024 + " KB");
            } catch (InterruptedException stopped) {
                return;
            } catch (Exception error) {
                failed.add(e.id);
                android.util.Log.w(DoorbellService.TAG, "event picture: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
            main.post(this::render);
        });
    }

    private void checkNow() {
        if (checking || !store.linked()) {
            if (!store.linked()) subtitle.setText(Ui.state(this, store));
            return;
        }
        checking = true;
        selected = LATEST;
        showSelected();
        new Thread(() -> {
            boolean ok;
            try {
                if (store.camera() == null) DoorbellService.refreshDevices(store);
                RingApi.Device camera = store.camera();
                if (camera == null) throw new IllegalStateException("no camera shared with the app");
                byte[] jpeg = store.api().snapshot(DoorbellService.token(store), camera.id, 0, store.linkedAt());
                store.write(store.latestFile(), jpeg);
                android.util.Log.i(DoorbellService.TAG, "check now: " + jpeg.length / 1024 + " KB");
                ok = true;
            } catch (Exception failed) {
                ok = false;
                android.util.Log.w(DoorbellService.TAG, "check now: " + failed.getClass().getSimpleName() + ": " + failed.getMessage());
            }
            boolean done = ok;
            main.post(() -> {
                checking = false;
                if (isFinishing()) return;
                selected = LATEST;
                render();
                if (!done) subtitle.setText(getString(store.camera() == null ? R.string.no_camera : R.string.picture_failed));
            });
        }, "ring-check").start();
    }
}
