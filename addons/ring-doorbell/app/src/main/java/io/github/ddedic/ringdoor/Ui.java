package io.github.ddedic.ringdoor;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The view kit every screen here shares, in the Heating add-on's language: generated art behind, light display type,
 * cards that turn white with black text when focused, and the ring colour for anything that rang.
 */
final class Ui {
    static final int WHITE = 0xFFFFFFFF, INK = 0xFF000000, DIM = 0x99FFFFFF, FAINT = 0x14FFFFFF, CARD = 0xF2101318;
    static final int RING = 0xFFFF8A3D, MOTION = 0xFF5CC8FF, LIVE = 0xFF4ADE80, WARN = 0xFFFFC14D;

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

    static TextView light(Context c, CharSequence value, int sp, int color) {
        TextView view = text(c, value, sp, color);
        view.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        return view;
    }

    /** The small spaced capitals over a title or a list: DOORBELL, RECENT, TODAY. */
    static TextView overline(Context c, String value) {
        TextView view = text(c, value.toUpperCase(), 13, DIM);
        view.setLetterSpacing(0.25f);
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
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return view;
    }

    static void tint(ImageView view, int color) {
        Drawable drawable = view.getDrawable();
        if (drawable != null) drawable.mutate().setTint(color);
    }

    /** An icon inside a soft round disc, the leading mark of a status card or a pop-up. */
    static FrameLayout disc(Context c, int res, int color, int sizeDp) {
        FrameLayout disc = new FrameLayout(c);
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL);
        fill.setColor((color & 0x00FFFFFF) | 0x33000000);
        disc.setBackground(fill);
        int inner = sizeDp * 11 / 20;
        disc.addView(icon(c, res, color, inner), new FrameLayout.LayoutParams(dp(c, inner), dp(c, inner), Gravity.CENTER));
        disc.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return disc;
    }

    /** A small coloured dot, for the live state beside a title. */
    static View dot(Context c, int color) {
        View dot = new View(c);
        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.OVAL);
        fill.setColor(color);
        dot.setBackground(fill);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 10), dp(c, 10)));
        return dot;
    }

    /** The screens' backdrop: the generated ember-glow art under a scrim that darkens towards the right. */
    static Drawable backdrop(Context c) {
        GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x66000000, 0xA6000000, 0xE6000000});
        return new LayerDrawable(new Drawable[]{c.getDrawable(R.drawable.bg_door), scrim});
    }

    /** A capsule label: RANG in the ring colour, MOTION in blue. */
    static TextView badge(Context c, String label, int color) {
        TextView badge = medium(c, label.toUpperCase(), 12, color == RING ? INK : WHITE);
        badge.setLetterSpacing(0.12f);
        badge.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        badge.setBackground(rounded(c, color == RING ? RING : 0xCC1E6FA8, 12, 0));
        return badge;
    }

    /**
     * A 16:9 picture frame. Under the picture, a soft empty state (door icon and a line); over it, a bottom shade that
     * carries a caption row. Children: [0] empty state, [1] picture, [2] shade with the caption row.
     */
    static FrameLayout pictureFrame(Context c, String empty, int radiusDp) {
        FrameLayout frame = new FrameLayout(c) {
            @Override protected void onMeasure(int w, int h) {
                int width = View.MeasureSpec.getSize(w);
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(width * 9 / 16, View.MeasureSpec.EXACTLY));
            }
        };
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xE61D222B, 0xE60D1015});
        fill.setCornerRadius(dp(c, radiusDp));
        frame.setBackground(fill);
        clip(frame, dp(c, radiusDp));

        LinearLayout placeholder = new LinearLayout(c);
        placeholder.setOrientation(LinearLayout.VERTICAL);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.addView(disc(c, R.drawable.ic_door, 0x99FFFFFF, 72));
        TextView line = text(c, empty, 15, DIM);
        line.setPadding(dp(c, 40), dp(c, 14), dp(c, 40), 0);
        line.setGravity(Gravity.CENTER);
        placeholder.addView(line);
        frame.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));

        ImageView picture = new ImageView(c);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(picture, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout shade = new LinearLayout(c);
        shade.setGravity(Gravity.CENTER_VERTICAL);
        shade.setPadding(dp(c, 18), dp(c, 36), dp(c, 18), dp(c, 14));
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
        frame.addView(shade, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return frame;
    }

    /** The picture frame's empty-state line, shown while there is no picture. */
    static void empty(FrameLayout frame, CharSequence line) {
        ((TextView) ((LinearLayout) frame.getChildAt(0)).getChildAt(1)).setText(line);
    }

    /** Fills a picture frame's caption: a badge, then a line. Null badge clears it; the shade hides with no line. */
    static void caption(Context c, FrameLayout frame, TextView badge, CharSequence line) {
        LinearLayout shade = (LinearLayout) frame.getChildAt(2);
        shade.removeAllViews();
        if (badge != null) shade.addView(badge);
        if (line != null && line.length() > 0) {
            TextView text = medium(c, line, 15, WHITE);
            text.setShadowLayer(dp(c, 4), 0, 0, 0x99000000);
            shade.addView(text, badge == null ? new LinearLayout.LayoutParams(-2, -2) : gapStart(c, 12));
        }
        shade.setVisibility(shade.getChildCount() == 0 ? View.GONE : View.VISIBLE);
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

    /**
     * Makes a card focusable the TV way: white with black text when focused, a faint glass card otherwise. Texts are
     * given with their resting colours; on focus the first turns black and the rest a dark grey.
     */
    static void focusCard(Context c, View card, int radiusDp, int rest, TextView... texts) {
        int[] colors = new int[texts.length];
        for (int i = 0; i < texts.length; i++) colors[i] = texts[i].getCurrentTextColor();
        card.setFocusable(true);
        card.setBackground(rounded(c, rest, radiusDp, 0));
        View.OnFocusChangeListener old = card.getOnFocusChangeListener();
        card.setOnFocusChangeListener((v, has) -> {
            v.setBackground(rounded(c, has ? WHITE : rest, radiusDp, 0));
            for (int i = 0; i < texts.length; i++) texts[i].setTextColor(has ? (i == 0 ? INK : 0xA6000000) : colors[i]);
            v.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f).setDuration(120).start();
            if (old != null) old.onFocusChange(v, has);
        });
    }

    /** A pill button with an optional icon; white with black text and icon when focused. */
    static LinearLayout button(Context c, int iconRes, String label) {
        LinearLayout b = new LinearLayout(c);
        b.setGravity(Gravity.CENTER);
        boolean round = label == null;
        b.setPadding(dp(c, round ? 0 : 20), dp(c, 12), dp(c, round ? 0 : 22), dp(c, 12));
        ImageView icon = iconRes == 0 ? null : icon(c, iconRes, WHITE, 22);
        if (icon != null) b.addView(icon);
        TextView text = label == null ? null : medium(c, label, 16, WHITE);
        if (text != null) b.addView(text, icon == null ? new LinearLayout.LayoutParams(-2, -2) : gapStart(c, 10));
        b.setFocusable(true);
        b.setBackground(rounded(c, 0x26FFFFFF, 24, 0));
        b.setOnFocusChangeListener((v, has) -> {
            v.setBackground(rounded(c, has ? WHITE : 0x26FFFFFF, 24, 0));
            if (icon != null) tint(icon, has ? INK : WHITE);
            if (text != null) text.setTextColor(has ? INK : WHITE);
        });
        return b;
    }

    static LinearLayout.LayoutParams gapStart(Context c, int dpGap) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMarginStart(dp(c, dpGap));
        return p;
    }

    static LinearLayout.LayoutParams gapTop(Context c, int width, int dpGap) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, -2);
        p.topMargin = dp(c, dpGap);
        return p;
    }

    /** A setting's value as a chip: On is filled, Off an outline, anything else a soft ring-coloured pill. */
    static TextView chip(Context c, String value, String on, String off) {
        TextView chip = medium(c, value, 15, WHITE);
        chip.setPadding(dp(c, 16), dp(c, 7), dp(c, 16), dp(c, 7));
        chipStyle(c, chip, on, off, false);
        return chip;
    }

    static void chipStyle(Context c, TextView chip, String on, String off, boolean focused) {
        String value = chip.getText().toString();
        if (value.equals(on)) {
            chip.setTextColor(focused ? WHITE : INK);
            chip.setBackground(rounded(c, focused ? INK : WHITE, 16, 0));
        } else if (value.equals(off)) {
            chip.setTextColor(focused ? 0x99000000 : DIM);
            chip.setBackground(rounded(c, 0, 16, focused ? 0x55000000 : 0x55FFFFFF));
        } else {
            chip.setTextColor(focused ? INK : WHITE);
            chip.setBackground(rounded(c, focused ? 0x33FF8A3D : 0x40FF8A3D, 16, 0));
        }
    }

    /** "just now", "4 minutes ago", "Yesterday, 19:02". */
    static CharSequence when(Context c, long at) {
        long now = System.currentTimeMillis();
        if (now - at < DateUtils.MINUTE_IN_MILLIS) return c.getString(R.string.just_now);
        if (now - at < DateUtils.HOUR_IN_MILLIS * 6) {
            return DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS);
        }
        return DateUtils.isToday(at) ? clock(c, at)
                : DateUtils.getRelativeDateTimeString(c, at, DateUtils.DAY_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0);
    }

    /** "22:41", or "10:41 PM" where the TV uses a 12-hour clock. */
    static String clock(Context c, long at) {
        return DateFormat.getTimeFormat(c).format(new java.util.Date(at));
    }

    /** The day header a timeline entry falls under: Today, Yesterday, or the date. */
    static String day(Context c, long at) {
        if (DateUtils.isToday(at)) return c.getString(R.string.today);
        if (DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS)) return c.getString(R.string.yesterday);
        return DateUtils.formatDateTime(c, at, DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_NO_YEAR | DateUtils.FORMAT_ABBREV_MONTH);
    }

    static String kind(Context c, Store.Event e) {
        if (e.ding()) return c.getString(R.string.kind_ding);
        return c.getString("human".equals(e.subType) ? R.string.kind_person : R.string.kind_motion);
    }

    static int color(Store.Event e) {
        return e.ding() ? RING : MOTION;
    }

    /** Where the connection stands, as a title and a colour for the status card and the live dot. */
    static String stateTitle(Context c, Store store) {
        if (!store.configured()) return c.getString(R.string.title_setup);
        if (!store.enabled()) return c.getString(R.string.title_off);
        if (!store.linked()) return c.getString(R.string.title_unlinked);
        return c.getString(store.connected() ? R.string.title_listening : R.string.title_connecting);
    }

    static int stateColor(Store store) {
        if (!store.configured() || !store.enabled()) return DIM;
        if (!store.linked() || !store.connected()) return WARN;
        return LIVE;
    }

    /** One line on where the connection stands, under the status card's title. */
    static String state(Context c, Store store) {
        if (!store.configured()) return c.getString(R.string.setup);
        if (!store.enabled()) return c.getString(R.string.state_off);
        if (!store.linked()) return c.getString(R.string.state_unlinked);
        if (store.connected()) {
            RingApi.Device chime = store.chime();
            return chime == null ? c.getString(R.string.listening) : chimeLine(c, chime);
        }
        return store.lastError().isEmpty() ? c.getString(R.string.connecting) : c.getString(R.string.failed, store.lastError());
    }

    static String chimeLine(Context c, RingApi.Device chime) {
        return chime.name + " · " + c.getString(!chime.online ? R.string.chime_offline
                : chime.snoozed ? R.string.chime_snoozed : R.string.chime_on)
                + (chime.volume >= 0 ? " · " + c.getString(R.string.chime_volume, chime.volume) : "");
    }

    /** The card at the top of the main and settings screens: a door disc, where things stand, and a live dot. */
    static LinearLayout statusCard(Context c, Store store) {
        LinearLayout card = new LinearLayout(c);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(c, 18), dp(c, 16), dp(c, 20), dp(c, 16));
        card.setBackground(rounded(c, 0x26FF8A3D, 22, 0));
        card.addView(disc(c, R.drawable.ic_door, RING, 48));
        LinearLayout lines = new LinearLayout(c);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(dp(c, 16), 0, dp(c, 8), 0);
        lines.addView(medium(c, stateTitle(c, store), 20, WHITE));
        TextView sub = text(c, state(c, store), 13, DIM);
        sub.setMaxLines(2);
        lines.addView(sub);
        card.addView(lines, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(dot(c, stateColor(store)));
        return card;
    }
}
