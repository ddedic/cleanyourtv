package io.github.ddedic.ringdoor;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

/** The few view helpers every screen here shares: text, rounded shapes, tinted icons, relative times. */
final class Ui {
    static final int DIM = 0x99FFFFFF, CARD = 0xF2111419, ACCENT = 0xFF4FB3FF, RING = 0xFFFF8A3D;

    private Ui() { }

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, CharSequence value, int sp, int color) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    static TextView medium(Context c, CharSequence value, int sp, int color) {
        TextView view = text(c, value, sp, color);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    static GradientDrawable rounded(Context c, int fill, int radiusDp, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) shape.setStroke(dp(c, 2), stroke);
        return shape;
    }

    static ImageView icon(Context c, int res, int tint, int sizeDp) {
        ImageView view = new ImageView(c);
        Drawable drawable = c.getDrawable(res);
        if (drawable != null) drawable.mutate().setTint(tint);
        view.setImageDrawable(drawable);
        view.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return view;
    }

    /** The screens' backdrop: a warm glow from the top left over near-black. */
    static android.graphics.drawable.Drawable backdrop(Context c) {
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.15f, 0.1f);
        glow.setGradientRadius(c.getResources().getDisplayMetrics().widthPixels * 0.9f);
        glow.setColors(new int[]{0xFF2A1C12, 0xFF0D0E12, 0xFF08090C});
        return glow;
    }

    /** A 16:9 picture frame: a soft empty state (door icon and a line) under the picture's ImageView, last child. */
    static android.widget.FrameLayout pictureFrame(Context c, String empty) {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(c) {
            @Override protected void onMeasure(int w, int h) {
                int width = android.view.View.MeasureSpec.getSize(w);
                super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec(width * 9 / 16, android.view.View.MeasureSpec.EXACTLY));
            }
        };
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF1A1E26, 0xFF101318});
        fill.setCornerRadius(dp(c, 24));
        fill.setStroke(dp(c, 1), 0x1FFFFFFF);
        frame.setBackground(fill);
        clip(frame, dp(c, 24));
        android.widget.LinearLayout placeholder = new android.widget.LinearLayout(c);
        placeholder.setOrientation(android.widget.LinearLayout.VERTICAL);
        placeholder.setGravity(android.view.Gravity.CENTER);
        placeholder.addView(icon(c, R.drawable.ic_door, 0x55FFFFFF, 56));
        TextView line = text(c, empty, 15, 0x77FFFFFF);
        line.setPadding(0, dp(c, 12), 0, 0);
        line.setGravity(android.view.Gravity.CENTER);
        placeholder.addView(line);
        frame.addView(placeholder, new android.widget.FrameLayout.LayoutParams(-1, -1));
        ImageView picture = new ImageView(c);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(picture, new android.widget.FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    /** Clips a view (a picture) to rounded corners. */
    static void clip(View view, int radiusPx) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
            }
        });
        view.setClipToOutline(true);
    }

    /** "just now", "4 minutes ago", "Yesterday, 19:02". */
    static CharSequence when(Context c, long at) {
        long now = System.currentTimeMillis();
        if (now - at < DateUtils.MINUTE_IN_MILLIS) return c.getString(R.string.just_now);
        if (now - at < DateUtils.DAY_IN_MILLIS) {
            return DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS);
        }
        return DateUtils.getRelativeDateTimeString(c, at, DateUtils.DAY_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0);
    }

    static String kind(Context c, Store.Event e) {
        if (e.ding()) return c.getString(R.string.kind_ding);
        return c.getString("human".equals(e.subType) ? R.string.kind_person : R.string.kind_motion);
    }

    /** A pill button: white with black text when focused; the primary one carries the ring colour. */
    static TextView button(Context c, String label, boolean primary) {
        TextView b = medium(c, label, 16, 0xFFFFFFFF);
        b.setPadding(dp(c, 22), dp(c, 12), dp(c, 22), dp(c, 12));
        b.setFocusable(true);
        int rest = primary ? 0x33FF8A3D : 0x1FFFFFFF;
        b.setBackground(rounded(c, rest, 22, 0));
        b.setOnFocusChangeListener((v, has) -> {
            v.setBackground(rounded(c, has ? 0xFFFFFFFF : rest, 22, 0));
            ((TextView) v).setTextColor(has ? 0xFF000000 : 0xFFFFFFFF);
        });
        return b;
    }

    static android.widget.LinearLayout.LayoutParams gapStart(Context c, int dpGap) {
        android.widget.LinearLayout.LayoutParams p = new android.widget.LinearLayout.LayoutParams(-2, -2);
        p.setMarginStart(dp(c, dpGap));
        return p;
    }

    /** One line on where the connection stands, for screens with nothing else to show. */
    static String state(Context c, Store store) {
        if (!store.configured()) return c.getString(R.string.setup);
        if (!store.enabled()) return c.getString(R.string.state_off);
        if (!store.linked()) return c.getString(R.string.state_unlinked);
        if (store.connected()) return c.getString(R.string.listening);
        return store.lastError().isEmpty() ? c.getString(R.string.connecting) : c.getString(R.string.failed, store.lastError());
    }

    /** Chips for the last few events: the kind, the time, and a mark for those that came while the TV was off. */
    static void recent(Context c, android.widget.LinearLayout row, java.util.List<Store.Event> events, int count) {
        row.removeAllViews();
        for (int i = 0; i < events.size() && i < count; i++) {
            Store.Event e = events.get(i);
            android.widget.LinearLayout chip = new android.widget.LinearLayout(c);
            chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(c, 12), dp(c, 8), dp(c, 14), dp(c, 8));
            chip.setBackground(rounded(c, 0x14FFFFFF, 16, 0));
            chip.addView(icon(c, e.ding() ? R.drawable.ic_door : R.drawable.ic_motion, e.ding() ? RING : DIM, 18));
            TextView label = text(c, kind(c, e) + " · " + when(c, e.at) + (e.missed ? " · " + c.getString(R.string.missed) : ""),
                    13, 0xDDFFFFFF);
            label.setPadding(dp(c, 8), 0, 0, 0);
            chip.addView(label);
            row.addView(chip, i == 0 ? new android.widget.LinearLayout.LayoutParams(-2, -2) : gapStart(c, 8));
        }
        if (events.isEmpty()) row.addView(text(c, c.getString(R.string.no_events), 14, DIM));
    }
}
