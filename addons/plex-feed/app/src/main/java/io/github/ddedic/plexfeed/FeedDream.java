package io.github.ddedic.plexfeed;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The feed's artwork as the TV's screensaver: each backdrop slowly zooms for SLIDE_MS, then crossfades into the
 * next, with its title in a corner and the time. Reads the cache only; it never goes online. Any key wakes it.
 */
public final class FeedDream extends DreamService {
    private static final long SLIDE_MS = 20_000;
    private static final long FADE_MS = 1_500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final List<JSONObject> items = new ArrayList<>();
    private ImageView[] layers;
    private int front;
    private int index = -1;
    private TextView title, subtitle, clock;
    private boolean running;
    private final Runnable next = this::advance;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(DateFormat.getTimeFormat(FeedDream.this).format(new Date()));
            main.postDelayed(this, 30_000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        layers = new ImageView[]{layer(root), layer(root)};
        View scrim = new View(this);
        // Over both picture layers: dark at the bottom for the caption and at the top for the clock.
        scrim.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x40000000, 0x00000000}),
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0x00000000, 0x00000000})}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout caption = new LinearLayout(this);
        caption.setOrientation(LinearLayout.VERTICAL);
        title = text(40, Color.WHITE);
        subtitle = text(18, 0xCCFFFFFF);
        caption.addView(title);
        caption.addView(subtitle);
        FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        captionParams.setMargins(dp(64), 0, dp(64), dp(56));
        root.addView(caption, captionParams);

        clock = text(28, 0xCCFFFFFF);
        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clockParams.setMargins(0, dp(40), dp(56), 0);
        root.addView(clock, clockParams);
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        items.clear();
        for (JSONObject item : new Store(this).readItems()) if (!item.optString("art").isEmpty()) items.add(item);
        Collections.shuffle(items);
        tick.run();
        if (items.isEmpty()) {
            title.setText(R.string.dream_empty);
            return;
        }
        advance();
    }

    @Override
    public void onDreamingStopped() {
        running = false;
        main.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        decoder.shutdownNow();
        super.onDetachedFromWindow();
    }

    private void advance() {
        if (!running || items.isEmpty()) return;
        index = (index + 1) % items.size();
        JSONObject item = items.get(index);
        File file = new File(new Store(this).artDir(), item.optString("art"));
        int width = getResources().getDisplayMetrics().widthPixels;
        decoder.execute(() -> {
            Bitmap bitmap = decode(file, width);
            main.post(() -> {
                if (!running) return;
                if (bitmap != null) show(bitmap, item);
                main.postDelayed(next, bitmap != null ? SLIDE_MS : 1_000);
            });
        });
    }

    /**
     * Crossfade: the incoming layer fades in and starts its own slow zoom while the outgoing one fades out and lets
     * go. Both stay under the scrim and the text; nothing is reordered.
     */
    private void show(Bitmap bitmap, JSONObject item) {
        ImageView in = layers[1 - front], out = layers[front];
        front = 1 - front;
        in.animate().cancel();
        in.setImageBitmap(bitmap);
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(FADE_MS).start();
        android.animation.ObjectAnimator zoom = android.animation.ObjectAnimator.ofPropertyValuesHolder(in,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f));
        zoom.setDuration(SLIDE_MS + FADE_MS);
        zoom.start();
        out.animate().cancel();
        out.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> out.setImageDrawable(null)).start();
        title.setText(item.optString("title"));
        subtitle.setText(Feed.join(item.optString("badge"), item.optString("subtitle")));
    }

    private static Bitmap decode(File file, int maxWidth) {
        if (!file.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (OutOfMemoryError tooBig) {
            return null;
        }
    }

    private ImageView layer(FrameLayout root) {
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        return view;
    }

    private TextView text(int sp, int color) {
        TextView view = new TextView(this);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        view.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
