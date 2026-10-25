package io.github.ddedic.ringdoor;

import android.app.Activity;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import java.util.List;

/**
 * The doorbell, full screen: the chosen event's picture large on the left with where things stand under it, every
 * recent ring and motion on the right. Check now and Settings sit above the list.
 */
public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private ImageView picture;
    private TextView title, subtitle, connection, chime;
    private LinearLayout list;
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
        content.setPadding(Ui.dp(this, 56), Ui.dp(this, 36), Ui.dp(this, 56), Ui.dp(this, 28));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView overline = Ui.text(this, getString(R.string.app_name).toUpperCase(), 13, Ui.DIM);
        overline.setLetterSpacing(0.25f);
        left.addView(overline);
        title = Ui.text(this, "", 32, 0xFFFFFFFF);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        left.addView(title);
        subtitle = Ui.text(this, "", 15, Ui.DIM);
        left.addView(subtitle);
        FrameLayout frame = Ui.pictureFrame(this, getString(R.string.no_picture_yet));
        picture = (ImageView) frame.getChildAt(frame.getChildCount() - 1);
        LinearLayout.LayoutParams frameAt = new LinearLayout.LayoutParams(-1, -2);
        frameAt.topMargin = Ui.dp(this, 18);
        left.addView(frame, frameAt);

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14));
        status.setBackground(Ui.rounded(this, 0x10FFFFFF, 18, 0));
        connection = Ui.text(this, "", 14, 0xDDFFFFFF);
        status.addView(connection);
        chime = Ui.text(this, "", 14, Ui.DIM);
        status.addView(chime);
        LinearLayout.LayoutParams statusAt = new LinearLayout.LayoutParams(-1, -2);
        statusAt.topMargin = Ui.dp(this, 14);
        left.addView(status, statusAt);
        content.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout actions = new LinearLayout(this);
        TextView check = Ui.button(this, getString(R.string.check_now), true);
        check.setOnClickListener(v -> checkNow());
        actions.addView(check);
        TextView settings = Ui.button(this, getString(R.string.settings), false);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        actions.addView(settings, Ui.gapStart(this, 10));
        right.addView(actions);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams scrollAt = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollAt.topMargin = Ui.dp(this, 18);
        right.addView(scroll, scrollAt);
        LinearLayout.LayoutParams rightAt = new LinearLayout.LayoutParams(Ui.dp(this, 380), -1);
        rightAt.setMarginStart(Ui.dp(this, 36));
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
        render();
        // Opening the screen is asking to see the door: fetch a picture unless one is fresh.
        if (store.linked() && System.currentTimeMillis() - store.latestFile().lastModified() > 60_000) checkNow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getContentResolver().unregisterContentObserver(changes);
    }

    private void render() {
        RingApi.Device camera = store.camera();
        title.setText(camera == null ? getString(R.string.app_name) : camera.name);
        connection.setText(Ui.state(this, store));
        RingApi.Device c = store.chime();
        chime.setVisibility(c == null ? View.GONE : View.VISIBLE);
        if (c != null) {
            String line = c.name + " · " + getString(!c.online ? R.string.chime_offline : c.snoozed ? R.string.chime_snoozed : R.string.chime_on);
            if (c.volume >= 0) line += " · " + getString(R.string.chime_volume, c.volume);
            chime.setText(line);
        }
        View focused = getCurrentFocus();
        boolean listFocused = focused != null && focused.getParent() == list;
        list.removeAllViews();
        List<Store.Event> events = store.events();
        TextView header = Ui.text(this, getString(R.string.recent).toUpperCase(), 13, Ui.DIM);
        header.setLetterSpacing(0.25f);
        list.addView(header);
        if (events.isEmpty()) {
            list.addView(Ui.text(this, getString(R.string.no_events), 16, Ui.DIM));
            File latest = store.latestFile();
            if (latest.isFile()) show(latest, getString(R.string.picture_from, Ui.when(this, latest.lastModified())));
            else subtitle.setText("");
            return;
        }
        View first = null;
        for (Store.Event e : events) {
            View row = row(e);
            if (first == null) first = row;
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, -2);
            at.topMargin = Ui.dp(this, 8);
            list.addView(row, at);
        }
        if (listFocused) first.requestFocus();
        select(events.get(0));
    }

    private View row(Store.Event e) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        row.setFocusable(true);
        row.setBackground(Ui.rounded(this, 0x10FFFFFF, 16, 0));
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(Ui.rounded(this, 0xFF1C2028, 10, 0));
        Ui.clip(thumb, Ui.dp(this, 10));
        File file = store.snapshotFile(e.id);
        if (file.isFile()) {
            BitmapFactory.Options small = new BitmapFactory.Options();
            small.inSampleSize = 4;
            thumb.setImageBitmap(BitmapFactory.decodeFile(file.getPath(), small));
        } else {
            thumb.setImageDrawable(getDrawable(e.ding() ? R.drawable.ic_door : R.drawable.ic_motion));
            thumb.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            thumb.setColorFilter(0x55FFFFFF);
        }
        row.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 63)));
        LinearLayout lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(Ui.dp(this, 16), 0, 0, 0);
        lines.addView(Ui.medium(this, Ui.kind(this, e), 17, e.ding() ? Ui.RING : 0xFFFFFFFF));
        lines.addView(Ui.text(this, e.device + " · " + Ui.when(this, e.at)
                + (e.missed ? " · " + getString(R.string.missed) : ""), 13, Ui.DIM));
        row.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnFocusChangeListener((v, has) -> {
            v.setBackground(Ui.rounded(this, has ? 0x33FFFFFF : 0x10FFFFFF, 16, has ? 0xFFFFFFFF : 0));
            if (has) select(e);
        });
        return row;
    }

    private void select(Store.Event e) {
        File file = store.snapshotFile(e.id);
        String line = Ui.kind(this, e) + " · " + Ui.when(this, e.at);
        if (file.isFile()) show(file, line);
        else {
            picture.setImageDrawable(null);
            subtitle.setText(line + " · " + getString(R.string.no_picture));
        }
    }

    private void show(File file, String line) {
        picture.setImageBitmap(BitmapFactory.decodeFile(file.getPath()));
        subtitle.setText(line);
    }

    private void checkNow() {
        if (checking || !store.linked()) {
            connection.setText(Ui.state(this, store));
            return;
        }
        checking = true;
        subtitle.setText(getString(R.string.checking));
        new Thread(() -> {
            boolean ok;
            try {
                if (store.camera() == null) DoorbellService.refreshDevices(store);
                RingApi.Device camera = store.camera();
                if (camera == null) throw new IllegalStateException("no camera shared with the app");
                store.write(store.latestFile(), store.api().snapshot(DoorbellService.token(store), camera.id, 0, store.linkedAt()));
                ok = true;
            } catch (Exception failed) {
                ok = false;
                android.util.Log.w(DoorbellService.TAG, "check now: " + failed.getClass().getSimpleName() + ": " + failed.getMessage());
            }
            boolean done = ok;
            main.post(() -> {
                checking = false;
                if (isFinishing()) return;
                if (done) show(store.latestFile(), getString(R.string.picture_from, Ui.when(this, store.latestFile().lastModified())));
                else subtitle.setText(getString(store.camera() == null ? R.string.no_camera : R.string.picture_failed));
                render();
            });
        }, "ring-check").start();
    }
}
