package io.github.ddedic.ringdoor;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;

/**
 * The card that slides in over whatever is playing when the doorbell rings: the picture from the door, who rang,
 * and a thin bar that runs down until the card leaves by itself. OK opens the doorbell screen, Back dismisses.
 */
final class DoorOverlay {

    private static FrameLayout shown;
    private static String shownId = "";
    private static ImageView picture;
    private static Runnable hide;

    private DoorOverlay() { }

    static void show(Context context, Store.Event event, int seconds) {
        long showMs = seconds * 1000L;
        Context app = context.getApplicationContext();
        if (!Settings.canDrawOverlays(app)) {
            // Without the overlay grant, the preview screen is the next best thing.
            app.startActivity(new Intent(app, PreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        dismiss(app);
        WindowManager wm = app.getSystemService(WindowManager.class);
        FrameLayout root = new FrameLayout(app) {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                    if (e.getAction() == KeyEvent.ACTION_UP) dismiss(app);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                    if (e.getAction() == KeyEvent.ACTION_UP) {
                        dismiss(app);
                        app.startActivity(new Intent(app, PreviewActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    }
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        LinearLayout card = new LinearLayout(app);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(app, 16);
        card.setPadding(pad, pad, pad, Ui.dp(app, 14));
        card.setBackground(Ui.rounded(app, Ui.CARD, 26, 0x33FFFFFF));
        card.setElevation(Ui.dp(app, 12));

        FrameLayout frame = new FrameLayout(app);
        frame.setBackground(Ui.rounded(app, 0xFF1C2028, 18, 0));
        Ui.clip(frame, Ui.dp(app, 18));
        ImageView placeholder = Ui.icon(app, R.drawable.ic_door, 0x55FFFFFF, 56);
        frame.addView(placeholder, new FrameLayout.LayoutParams(Ui.dp(app, 56), Ui.dp(app, 56), Gravity.CENTER));
        picture = new ImageView(app);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        TextView live = Ui.medium(app, app.getString(event.ding() ? R.string.at_the_door : R.string.motion_badge), 12, 0xFFFFFFFF);
        live.setLetterSpacing(0.15f);
        live.setPadding(Ui.dp(app, 10), Ui.dp(app, 4), Ui.dp(app, 10), Ui.dp(app, 4));
        live.setBackground(Ui.rounded(app, event.ding() ? Ui.RING : Ui.ACCENT, 12, 0));
        FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        badge.setMargins(Ui.dp(app, 12), Ui.dp(app, 12), 0, 0);
        frame.addView(live, badge);
        int width = Ui.dp(app, 520);
        card.addView(frame, new LinearLayout.LayoutParams(-1, (width - 2 * pad) * 9 / 16));

        LinearLayout title = new LinearLayout(app);
        title.setOrientation(LinearLayout.HORIZONTAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(Ui.dp(app, 4), Ui.dp(app, 14), Ui.dp(app, 4), 0);
        title.addView(Ui.icon(app, R.drawable.ic_door, Ui.RING, 26));
        LinearLayout lines = new LinearLayout(app);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(Ui.dp(app, 14), 0, 0, 0);
        lines.addView(Ui.medium(app, event.device, 22, 0xFFFFFFFF));
        lines.addView(Ui.text(app, app.getString(event.ding() ? R.string.someone_rang
                : "human".equals(event.subType) ? R.string.person_seen : R.string.motion_seen), 15, Ui.DIM));
        title.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        TextView hint = Ui.text(app, app.getString(R.string.overlay_hint), 13, Ui.DIM);
        title.addView(hint);
        card.addView(title);

        ProgressBar bar = new ProgressBar(app, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.RING));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x22FFFFFF));
        LinearLayout.LayoutParams barAt = new LinearLayout.LayoutParams(-1, Ui.dp(app, 3));
        barAt.topMargin = Ui.dp(app, 12);
        card.addView(bar, barAt);

        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(width, -2, Gravity.BOTTOM | Gravity.END);
        root.addView(card, at);
        root.setPadding(0, 0, Ui.dp(app, 48), Ui.dp(app, 40));

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        wm.addView(root, params);
        shown = root;
        shownId = event.id;
        root.requestFocus();

        card.setTranslationY(Ui.dp(app, 60));
        card.setAlpha(0f);
        card.animate().translationY(0).alpha(1f).setDuration(320).start();
        android.animation.ObjectAnimator.ofInt(bar, "progress", 1000, 0).setDuration(showMs).start();
        hide = () -> dismiss(app);
        root.postDelayed(hide, showMs);

        File ready = new Store(app).snapshotFile(event.id);
        if (ready.isFile()) picture(event.id, ready);
    }

    /** The snapshot arrived: fade it in if the card for that event is still up. */
    static void picture(String eventId, File file) {
        if (shown == null || !shownId.equals(eventId) || picture == null) return;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath());
        if (bitmap == null) return;
        picture.setAlpha(0f);
        picture.setImageBitmap(bitmap);
        picture.animate().alpha(1f).setDuration(300).start();
    }

    static void dismiss(Context context) {
        if (shown == null) return;
        View gone = shown;
        shown = null;
        picture = null;
        shownId = "";
        if (hide != null) gone.removeCallbacks(hide);
        try {
            context.getSystemService(WindowManager.class).removeView(gone);
        } catch (IllegalArgumentException alreadyGone) {
            // removed already
        }
    }
}
