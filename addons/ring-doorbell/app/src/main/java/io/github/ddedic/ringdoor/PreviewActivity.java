package io.github.ddedic.ringdoor;

import android.app.Activity;
import android.content.Intent;
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
import android.widget.TextView;

import java.io.File;

/**
 * The quick look the door pill (or a pop-up's OK) opens over whatever was on screen: one big picture from the door,
 * Check now for a newer one, and the way to every event. With an event id it shows that event's picture instead.
 * Opening it clears the pill's badge.
 */
public final class PreviewActivity extends Activity {
    static final String EVENT = "event";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private FrameLayout frame;
    private ImageView picture;
    private TextView title, caption;
    private boolean checking;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        FrameLayout root = new FrameLayout(this);
        // Dark enough that Home's text and tiles do not read through around the card (no blur on Android 11).
        root.setBackgroundColor(0xEB000000);
        root.setOnClickListener(v -> finish());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        card.setPadding(pad, pad, pad, Ui.dp(this, 16));
        card.setBackground(Ui.rounded(this, Ui.CARD, 30, 0x1FFFFFFF));
        card.setElevation(Ui.dp(this, 16));
        card.setClickable(true);

        frame = Ui.pictureFrame(this, getString(R.string.no_picture_yet), 20);
        picture = (ImageView) frame.getChildAt(1);
        card.addView(frame, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, 0);
        bar.addView(Ui.disc(this, R.drawable.ic_door, Ui.RING, 44));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 12), 0);
        title = Ui.medium(this, "", 22, Ui.WHITE);
        lines.addView(title);
        caption = Ui.text(this, "", 14, Ui.DIM);
        caption.setMaxLines(1);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lines.addView(caption);
        bar.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        View check = Ui.button(this, R.drawable.ic_refresh, getString(R.string.check_now));
        check.setOnClickListener(v -> checkNow());
        bar.addView(check);
        View all = Ui.button(this, 0, getString(R.string.all_events));
        all.setOnClickListener(v -> { startActivity(new Intent(this, MainActivity.class)); finish(); });
        bar.addView(all, Ui.gapStart(this, 10));
        card.addView(bar);

        // 16:9 picture plus the bar must fit the 540 dp tall screen with room to breathe.
        root.addView(card, new FrameLayout.LayoutParams(Ui.dp(this, 700), -2, Gravity.CENTER));
        setContentView(root);
        check.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.importCredentials();
        store.seen();
        getContentResolver().notifyChange(StatusProvider.STATUS, null);
        DoorbellService.start(this);
        RingApi.Device camera = store.camera();
        title.setText(camera == null ? getString(R.string.doorbell) : camera.name);
        Store.Event event = event(getIntent().getStringExtra(EVENT));
        if (event == null) event = newestRing();
        File latest = store.latestFile();
        if (event != null && store.snapshotFile(event.id).isFile()
                && (!latest.isFile() || latest.lastModified() < event.at)) {
            showEvent(event);
            return;
        }
        if (latest.isFile()) showLatest();
        else caption.setText(Ui.state(this, store));
        if (store.linked() && System.currentTimeMillis() - latest.lastModified() > 60_000) checkNow();
    }

    private Store.Event event(String id) {
        if (id == null) return null;
        for (Store.Event e : store.events()) if (e.id.equals(id)) return e;
        return null;
    }

    /** A ring in the last ten minutes: the pill was most likely pressed because of it. */
    private Store.Event newestRing() {
        for (Store.Event e : store.events()) {
            if (e.ding() && System.currentTimeMillis() - e.at < 10 * 60_000) return e;
        }
        return null;
    }

    /** Asks Ring for the newest picture the doorbell has stored. */
    private void checkNow() {
        RingApi.Device camera = store.camera();
        if (checking || camera == null || !store.linked()) {
            if (!store.linked()) caption.setText(Ui.state(this, store));
            return;
        }
        checking = true;
        caption.setText(getString(R.string.checking));
        new Thread(() -> {
            String error = null;
            try {
                byte[] jpeg = store.api().snapshot(DoorbellService.token(store), camera.id, 0, store.linkedAt());
                store.write(store.latestFile(), jpeg);
            } catch (Exception failed) {
                error = getString(R.string.picture_failed);
                android.util.Log.w(DoorbellService.TAG, "check now: " + failed.getClass().getSimpleName() + ": " + failed.getMessage());
            }
            String message = error;
            main.post(() -> {
                checking = false;
                if (isFinishing()) return;
                if (message != null) caption.setText(message);
                else showLatest();
            });
        }, "ring-check").start();
    }

    private void showLatest() {
        File file = store.latestFile();
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath());
        if (bitmap == null) return;
        picture.setImageBitmap(bitmap);
        Ui.caption(this, frame, Ui.badge(this, getString(R.string.badge_newest), Ui.MOTION), Ui.clock(this, file.lastModified()));
        caption.setText(getString(R.string.hint_newest));
    }

    private void showEvent(Store.Event e) {
        Bitmap bitmap = BitmapFactory.decodeFile(store.snapshotFile(e.id).getPath());
        picture.setImageBitmap(bitmap);
        Ui.caption(this, frame, Ui.badge(this, Ui.kind(this, e), Ui.color(e)), Ui.when(this, e.at));
        caption.setText(getString(e.ding() ? R.string.someone_rang : "human".equals(e.subType)
                ? R.string.person_seen : R.string.motion_seen));
    }
}
