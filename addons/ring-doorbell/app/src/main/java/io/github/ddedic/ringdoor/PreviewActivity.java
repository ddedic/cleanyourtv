package io.github.ddedic.ringdoor;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * The quick look the door pill (or a pop-up's OK) opens over whatever was on screen: the newest picture from the
 * door, Check now for a newer one, and the last few rings. Opening it clears the pill's badge.
 */
public final class PreviewActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private ImageView picture;
    private TextView title, caption, check;
    private LinearLayout recent;
    private boolean checking;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xB3000000);
        root.setOnClickListener(v -> finish());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 24);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Ui.rounded(this, Ui.CARD, 30, 0x22FFFFFF));
        card.setClickable(true);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(Ui.rounded(this, 0xFF1C2028, 20, 0));
        Ui.clip(frame, Ui.dp(this, 20));
        frame.addView(Ui.icon(this, R.drawable.ic_door, 0x44FFFFFF, 64),
                new FrameLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64), Gravity.CENTER));
        picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        int width = Ui.dp(this, 780);
        card.addView(frame, new LinearLayout.LayoutParams(-1, (width - 2 * pad) * 9 / 16));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 2), Ui.dp(this, 18), 0, 0);
        bar.addView(Ui.icon(this, R.drawable.ic_door, Ui.RING, 28));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(Ui.dp(this, 14), 0, 0, 0);
        title = Ui.medium(this, "", 24, 0xFFFFFFFF);
        lines.addView(title);
        caption = Ui.text(this, "", 15, Ui.DIM);
        lines.addView(caption);
        bar.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        check = Ui.button(this, getString(R.string.check_now), true);
        check.setOnClickListener(v -> checkNow());
        bar.addView(check);
        TextView all = Ui.button(this, getString(R.string.all_events), false);
        all.setOnClickListener(v -> { startActivity(new Intent(this, MainActivity.class)); finish(); });
        bar.addView(all, Ui.gapStart(this, 10));
        card.addView(bar);

        recent = new LinearLayout(this);
        LinearLayout.LayoutParams recentAt = new LinearLayout.LayoutParams(-1, -2);
        recentAt.topMargin = Ui.dp(this, 16);
        card.addView(recent, recentAt);

        root.addView(card, new FrameLayout.LayoutParams(width, -2, Gravity.CENTER));
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
        title.setText(camera == null ? getString(R.string.app_name) : camera.name);
        File latest = store.latestFile();
        if (latest.isFile()) show(latest);
        else caption.setText(Ui.state(this, store));
        if (store.linked() && System.currentTimeMillis() - latest.lastModified() > 60_000) checkNow();
        Ui.recent(this, recent, store.events(), 4);
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
                else show(store.latestFile());
            });
        }, "ring-check").start();
    }

    private void show(File file) {
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath());
        if (bitmap == null) return;
        picture.setImageBitmap(bitmap);
        caption.setText(getString(R.string.picture_from, Ui.when(this, file.lastModified())));
    }
}
