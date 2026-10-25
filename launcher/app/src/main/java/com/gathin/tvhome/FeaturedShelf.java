package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * featured_feed: a rotating shelf above the dock, filled by another app's content provider
 * (docs/specs/feed.md). The launcher itself never goes online: the feed app fetches and caches, and this
 * reads its rows and pictures over the content provider while Home is on screen. One picture is decoded at a
 * time, at most screen width, and the rotation stops with Home.
 */
final class FeaturedShelf {

    /** What Home needs from the shelf's owner. */
    interface Host {
        /** The shelf has items to show (true) or none (false): Home hides or shows its app-name shelf. */
        void onFeaturedShown(boolean shown);
        /** Down from the shelf: back to the dock tile focus came from. */
        void focusDock();
        /** Up from the shelf: the top bar. */
        void focusTopBar();
        /** Slide the dock and the grid down by dy (0 puts them back), then run `then`. */
        void slideDock(float dy, Runnable then);
    }

    /** How much of the dock still shows at the bottom of the screen while the shelf has focus. */
    private static final int PEEK_DP = 44;

    static final class Item {
        final String title, subtitle, summary, badge, image, intent, pkg, action;

        Item(String title, String subtitle, String summary, String badge, String image, String intent, String pkg,
             String action) {
            this.title = title;
            this.subtitle = subtitle;
            this.summary = summary;
            this.badge = badge;
            this.image = image;
            this.intent = intent;
            this.pkg = pkg;
            this.action = action;
        }
    }

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** The contract's own limit: a feed longer than this is cut, the rotation stays short. */
    static final int MAX_ITEMS = 20;

    private final Activity activity;
    private final Host host;
    private final Uri items;
    private final String authority;
    private final long intervalMs;
    private final boolean animations;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> pictures = new LruCache<>(3);
    /** featured_tint: each picture's colour, by image URI, for the gradient and the label. */
    private final java.util.Map<String, Integer> colours = new java.util.HashMap<>();
    private final boolean tint;
    private final GradientDrawable left;
    private final ImageView art;
    private final View scrim;
    private final LinearLayout block;
    private final TextView badge, title, meta, summary, action;
    private final LinearLayout dots;
    private List<Item> list = new ArrayList<>();
    private int index;
    private boolean started;
    private boolean placed;
    /** The block's resting y, just above the dock; while focused it sits lower by the dock's slide. */
    private float restY;
    private float dockTop;
    /** Focus is in the grid below the dock, which scrolls up into the shelf's place: faded out meanwhile. */
    private boolean suppressed;
    private int generation;
    private final Runnable advance = () -> step(1, true);
    private final ContentObserver observer;

    FeaturedShelf(Activity activity, FrameLayout root, int behind, String authority, int seconds, boolean animations,
                  boolean tint, Host host) {
        this.tint = tint;
        this.activity = activity;
        this.host = host;
        this.authority = authority;
        this.items = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority).path("items").build();
        this.intervalMs = Math.max(4, Math.min(60, seconds)) * 1000L;
        this.animations = animations;
        density = activity.getResources().getDisplayMetrics().density;
        observer = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) { if (started) load(false); }
        };

        // Backdrop: the item's 16:9 art over the whole screen, faded to black on the left and at the bottom so the
        // text and the dock stay readable. Placed right above `behind` (the ambient glow), under the header.
        art = new ImageView(activity);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setAlpha(0f);
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrim = new View(activity);
        left = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF2000000, 0x99000000, 0x00000000});
        GradientDrawable bottom = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x33000000, 0xFF000000});
        scrim.setBackground(new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{left, bottom}));
        scrim.setAlpha(0f);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int at = root.indexOfChild(root.findViewById(behind)) + 1;
        root.addView(art, at, new FrameLayout.LayoutParams(-1, -1));
        root.addView(scrim, at + 1, new FrameLayout.LayoutParams(-1, -1));

        block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setAlpha(0f);
        // Focusable only while Up from the dock (or Down from the top bar) puts focus here: otherwise Home's
        // first focus would land on the shelf, which comes before the grid in the view order.
        block.setFocusable(false);
        block.setPadding(dp(20), dp(12), dp(20), dp(10));
        badge = text(13, 0xB3FFFFFF, R.string.font_medium);
        badge.setLetterSpacing(0.14f);
        badge.setAllCaps(true);
        title = text(42, Color.WHITE, R.string.font_light);
        title.setLetterSpacing(-0.02f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        meta = text(17, 0xD9FFFFFF, R.string.font_regular);
        meta.setSingleLine(true);
        summary = text(15, 0xB3FFFFFF, R.string.font_regular);
        summary.setMaxLines(2);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        summary.setLineSpacing(0, 1.15f);
        action = text(16, Color.BLACK, R.string.font_medium);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.WHITE);
        pill.setCornerRadius(dp(22));
        action.setBackground(pill);
        action.setPadding(dp(22), dp(10), dp(22), dp(10));
        // Shown only while the shelf has focus, on a row of fixed height with the dots, so the shelf keeps one
        // height and focusing it never pushes it over the dock.
        action.setVisibility(View.GONE);
        dots = new LinearLayout(activity);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        block.addView(badge, wrap(0));
        block.addView(title, new LinearLayout.LayoutParams(Math.round(width * 0.55f), -2));
        LinearLayout.LayoutParams metaParams = wrap(dp(8));
        block.addView(meta, metaParams);
        block.addView(summary, sized(Math.round(width * 0.42f), dp(10)));
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
        actionParams.setMarginEnd(dp(20));
        footer.addView(action, actionParams);
        footer.addView(dots, new LinearLayout.LayoutParams(-2, dp(6)));
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-2, dp(46));
        footerParams.topMargin = dp(14);
        block.addView(footer, footerParams);
        FrameLayout.LayoutParams blockParams = new FrameLayout.LayoutParams(-2, -2);
        blockParams.setMarginStart(dp(48));
        root.addView(block, at + 2, blockParams);

        block.setOnFocusChangeListener((v, focused) -> {
            action.setVisibility(focused && !list.isEmpty() ? View.VISIBLE : View.GONE);
            if (focused) {
                main.removeCallbacks(advance);
                spotlight(true, null);
            } else {
                block.setFocusable(false);
                spotlight(false, null);
                schedule();
            }
        });
        block.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_UP && isOk(code)) { open(); return true; }
                return isOk(code) || code == KeyEvent.KEYCODE_BACK;
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: step(-1, false); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: step(1, false); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_BACK:
                    // Back to the dock once it has slid up again, so its focus glass lands where the tile is.
                    spotlight(false, host::focusDock);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP: host.focusTopBar(); return true;
                default: return isOk(code);
            }
        });
    }

    /** Home came to the front: ask the feed to refresh if it is stale, show what it has, rotate. */
    void start() {
        if (started) return;
        started = true;
        try {
            activity.getContentResolver().registerContentObserver(items, false, observer);
        } catch (RuntimeException unavailable) {
            // The feed app is gone or not exported: the shelf just stays empty.
        }
        load(true);
    }

    /** Home left the screen: no rotation, no observer, nothing decoded in the background. */
    void stop() {
        if (!started) return;
        started = false;
        generation++;
        main.removeCallbacks(advance);
        try { activity.getContentResolver().unregisterContentObserver(observer); }
        catch (RuntimeException ignored) { }
    }

    /** Memory is short: drop the decoded pictures; the current one is decoded again when needed. */
    void trim() {
        pictures.evictAll();
    }

    boolean isShowing() {
        return !list.isEmpty() && block.getAlpha() > 0f;
    }

    /** Focus the shelf (Up from the dock, Down from the top bar). False when there is nothing to focus. */
    boolean focus() {
        if (!isShowing()) return false;
        block.setFocusable(true);
        if (block.requestFocus()) return true;
        block.setFocusable(false);
        return false;
    }

    boolean hasFocus() {
        return block.hasFocus();
    }

    /** Puts the text block's bottom edge just above the dock, where the app-name shelf sat, and shows it again. */
    void place(float dockTopInRoot) {
        block.post(() -> {
            dockTop = dockTopInRoot;
            restY = dockTopInRoot - dp(28) - block.getHeight();
            if (!block.hasFocus()) block.setY(restY);
            placed = true;
            setSuppressed(false);
        });
    }

    /**
     * Focused, the shelf is the screen: the dock and the grid slide down until only the top of the dock shows,
     * and the text follows them down and grows a little. Unfocused, everything goes back.
     */
    private void spotlight(boolean on, Runnable then) {
        View parent = (View) block.getParent();
        float shift = on && parent != null ? Math.max(0f, parent.getHeight() - dp(PEEK_DP) - dockTop) : 0f;
        long duration = animations ? 320 : 0;
        block.setPivotX(0f);
        block.setPivotY(block.getHeight());
        block.animate().cancel();
        block.animate().y(restY + shift).scaleX(on ? 1.06f : 1f).scaleY(on ? 1.06f : 1f).setDuration(duration)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        host.slideDock(shift, then != null ? then : () -> { });
    }

    /** Focus left the dock for the grid below it (true), or came back (false). */
    void setSuppressed(boolean hidden) {
        if (suppressed == hidden && (hidden || block.getAlpha() > 0f || list.isEmpty())) return;
        suppressed = hidden;
        if (hidden) {
            main.removeCallbacks(advance);
            for (View view : new View[]{block, art, scrim}) view.animate().alpha(0f).setDuration(animations ? 180 : 0).start();
            return;
        }
        if (list.isEmpty()) return;
        reveal();
        if (art.getDrawable() != null) art.animate().alpha(1f).setDuration(animations ? 300 : 0).start();
        schedule();
    }

    private void load(boolean refresh) {
        int load = ++generation;
        ContentResolver resolver = activity.getContentResolver();
        LOADER.execute(() -> {
            if (refresh) {
                try { resolver.call(items, "refresh", null, null); }
                catch (RuntimeException unavailable) { /* an older feed app without refresh: show what it has */ }
            }
            List<Item> loaded = FeedRows.read(query(resolver));
            main.post(() -> {
                if (load != generation || !started) return;
                boolean same = FeedRows.sameTitles(list, loaded);
                list = loaded;
                if (!same) index = 0;
                if (list.isEmpty()) {
                    hide();
                    return;
                }
                index = Math.min(index, list.size() - 1);
                show(false);
                schedule();
            });
        });
    }

    private Cursor query(ContentResolver resolver) {
        try {
            return resolver.query(items, null, null, null, null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private void schedule() {
        main.removeCallbacks(advance);
        if (started && !suppressed && list.size() > 1 && !block.hasFocus()) main.postDelayed(advance, intervalMs);
    }

    private void step(int by, boolean automatic) {
        if (list.isEmpty()) return;
        index = Math.floorMod(index + by, list.size());
        show(true);
        if (automatic) schedule();
    }

    private void show(boolean animate) {
        Item item = list.get(index);
        Runnable fill = () -> {
            badge.setText(item.badge);
            badge.setVisibility(item.badge.isEmpty() ? View.GONE : View.VISIBLE);
            title.setText(item.title);
            meta.setText(item.subtitle);
            meta.setVisibility(item.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
            summary.setText(item.summary);
            summary.setVisibility(item.summary.isEmpty() ? View.GONE : View.VISIBLE);
            action.setText(item.action.isEmpty() ? activity.getString(R.string.library_open) : item.action);
            fillDots();
        };
        if (animate && animations) {
            block.animate().cancel();
            block.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                fill.run();
                if (placed && !suppressed) block.animate().alpha(1f).setDuration(220).start();
            }).start();
        } else {
            fill.run();
            reveal();
        }
        picture(item, animate);
        host.onFeaturedShown(true);
    }

    private void reveal() {
        if (!placed || suppressed) return;   // its place is known once the dock has been laid out
        block.setAlpha(1f);
        scrim.setAlpha(1f);
    }

    private void hide() {
        main.removeCallbacks(advance);
        if (block.hasFocus()) host.focusDock();
        block.setAlpha(0f);
        art.animate().cancel();
        art.setAlpha(0f);
        art.setImageDrawable(null);
        scrim.setAlpha(0f);
        host.onFeaturedShown(false);
    }

    private void picture(Item item, boolean animate) {
        if (item.image.isEmpty()) {
            art.animate().alpha(0f).setDuration(animate && animations ? 300 : 0).start();
            return;
        }
        Bitmap cached = pictures.get(item.image);
        if (cached != null) {
            setPicture(cached, animate);
            applyTint(item);
            return;
        }
        int load = generation;
        int maxWidth = activity.getResources().getDisplayMetrics().widthPixels;
        ContentResolver resolver = activity.getContentResolver();
        LOADER.execute(() -> {
            Bitmap bitmap = decode(resolver, item.image, maxWidth);
            Integer colour = tint && bitmap != null ? colourOf(bitmap) : null;
            main.post(() -> {
                if (load != generation || !started || bitmap == null) return;
                pictures.put(item.image, bitmap);
                if (colour != null) colours.put(item.image, colour);
                if (index < list.size() && list.get(index) == item) {
                    setPicture(bitmap, animate);
                    applyTint(item);
                }
            });
        });
    }

    private void setPicture(Bitmap bitmap, boolean animate) {
        art.animate().cancel();
        float target = suppressed ? 0f : 1f;
        if (!animate || !animations || art.getAlpha() == 0f) {
            art.setImageBitmap(bitmap);
            art.animate().alpha(target).setDuration(animations ? 400 : 0).start();
            return;
        }
        art.animate().alpha(0.15f).setDuration(200).withEndAction(() -> {
            art.setImageBitmap(bitmap);
            art.animate().alpha(target).setDuration(450).start();
        }).start();
    }

    /** featured_tint: the picture's colour from a 16x9 copy, on the loader thread. */
    private static Integer colourOf(Bitmap bitmap) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(bitmap, 16, 9, true);
            int[] pixels = new int[16 * 9];
            small.getPixels(pixels, 0, 16, 0, 0, 16, 9);
            if (small != bitmap) small.recycle();
            return FeedRows.accent(pixels);
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            return null;
        }
    }

    /** featured_tint: the side gradient and the small label take the picture's colour; plain black and grey without. */
    private void applyTint(Item item) {
        if (!tint) return;
        Integer colour = colours.get(item.image);
        if (colour == null) return;
        left.setColors(new int[]{FeedRows.shade(colour, 0.36f, 0xF2), FeedRows.shade(colour, 0.5f, 0xA6), 0x00000000});
        badge.setTextColor(FeedRows.light(colour));
    }

    /** Only this feed's own pictures, decoded to at most screen width in RGB_565 (half the memory of ARGB). */
    private Bitmap decode(ContentResolver resolver, String image, int maxWidth) {
        Uri uri = Uri.parse(image);
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) || !authority.equals(uri.getAuthority())) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = resolver.openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = FeedRows.sampleSize(bounds.outWidth, maxWidth);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = resolver.openInputStream(uri)) { return BitmapFactory.decodeStream(in, null, options); }
        } catch (Exception | OutOfMemoryError unavailable) {
            return null;
        }
    }

    /** OK: the item's own intent, or else its app; never one of this launcher's own screens. */
    private void open() {
        if (list.isEmpty()) return;
        Item item = list.get(index);
        Intent intent = FeedRows.parseIntent(item.intent, activity.getPackageName());
        if (intent != null) {
            try {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                // Fall back to opening the app below.
            }
        }
        if (item.pkg.isEmpty() || item.pkg.equals(activity.getPackageName())) return;
        PackageManager pm = activity.getPackageManager();
        Intent app = pm.getLeanbackLaunchIntentForPackage(item.pkg);
        if (app == null) app = pm.getLaunchIntentForPackage(item.pkg);
        if (app == null) return;
        try {
            activity.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    private void fillDots() {
        dots.removeAllViews();
        if (list.size() < 2) return;
        for (int i = 0; i < list.size(); i++) {
            View dot = new View(activity);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(i == index ? Color.WHITE : 0x59FFFFFF);
            shape.setCornerRadius(dp(3));
            dot.setBackground(shape);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(i == index ? 18 : 6), dp(6));
            params.setMarginEnd(dp(6));
            dots.addView(dot, params);
        }
    }

    private static boolean isOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private TextView text(int sp, int color, int font) {
        TextView view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(UiFont.get(activity, font));
        view.setIncludeFontPadding(false);
        view.setShadowLayer(dp(8), 0, 0, 0x99000000);
        return view;
    }

    private static LinearLayout.LayoutParams wrap(int top) {
        return sized(-2, top);
    }

    private static LinearLayout.LayoutParams sized(int width, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = top;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
