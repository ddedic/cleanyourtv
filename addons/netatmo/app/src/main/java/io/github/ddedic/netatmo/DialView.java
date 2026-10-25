package io.github.ddedic.netatmo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The big thermostat dial: a 270° ring from 7 to 30 °C with the target as a glowing arc, a dot where the room
 * is now, and the room temperature in large type in the middle.
 */
final class DialView extends View {
    static final int WARM = 0xFFFF8A3D, COOL = 0xFF5CC8FF;
    private static final float START = 135f, SWEEP = 270f, MIN = 7f, MAX = 30f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG),
            glow = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG),
            big = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float density;
    private double measured = Double.NaN, target = Double.NaN;
    private float shown = Float.NaN;
    private boolean heating, pending;
    private String caption = "";
    private ValueAnimator animator;

    DialView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // the blur mask on the glow needs it
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(0x26FFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xCCFFFFFF);
        big.setColor(Color.WHITE);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        small.setTextAlign(Paint.Align.CENTER);
        small.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setFocusable(true);
    }

    void set(double measured, double target, boolean heating, boolean pending, String caption) {
        this.measured = measured;
        this.heating = heating;
        this.pending = pending;
        this.caption = caption;
        float to = Double.isNaN(target) ? Float.NaN : (float) target;
        if (animator != null) animator.cancel();
        if (Float.isNaN(shown) || Float.isNaN(to)) {
            shown = to;
        } else if (shown != to) {
            animator = ValueAnimator.ofFloat(shown, to).setDuration(260);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> { shown = (float) a.getAnimatedValue(); invalidate(); });
            animator.start();
        }
        this.target = target;
        invalidate();
    }

    private static float fraction(double value) {
        return (float) Math.max(0, Math.min(1, (value - MIN) / (MAX - MIN)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        float stroke = 16 * density, cx = getWidth() / 2f, cy = getHeight() / 2f, r = size / 2f - 30 * density;
        box.set(cx - r, cy - r, cx + r, cy + r);
        int accent = heating || pending ? WARM : COOL;

        track.setStrokeWidth(stroke);
        canvas.drawArc(box, START, SWEEP, false, track);
        if (!Float.isNaN(shown)) {
            float sweep = Math.max(1f, SWEEP * fraction(shown));
            SweepGradient shader = new SweepGradient(cx, cy, new int[]{COOL, accent, accent},
                    new float[]{0f, 0.5f, 1f});
            android.graphics.Matrix turn = new android.graphics.Matrix();
            turn.setRotate(START - 5, cx, cy);
            shader.setLocalMatrix(turn);
            glow.setShader(shader);
            glow.setStrokeWidth(stroke * 2.2f);
            glow.setAlpha(110);
            glow.setMaskFilter(new BlurMaskFilter(18 * density, BlurMaskFilter.Blur.NORMAL));
            canvas.drawArc(box, START, sweep, false, glow);
            arc.setShader(shader);
            arc.setStrokeWidth(stroke);
            canvas.drawArc(box, START, sweep, false, arc);
            // The knob at the target's end.
            double a = Math.toRadians(START + sweep);
            dot.setColor(Color.WHITE);
            canvas.drawCircle(cx + (float) (r * Math.cos(a)), cy + (float) (r * Math.sin(a)), stroke * 0.72f, dot);
        }
        if (!Double.isNaN(measured)) {
            // Where the room is now: a small hollow tick on the ring.
            double a = Math.toRadians(START + SWEEP * fraction(measured));
            dot.setColor(0xFF0E1117);
            float x = cx + (float) (r * Math.cos(a)), y = cy + (float) (r * Math.sin(a));
            canvas.drawCircle(x, y, stroke * 0.42f, dot);
            dot.setColor(Color.WHITE);
            canvas.drawCircle(x, y, stroke * 0.22f, dot);
        }
        if (hasFocus() || isFocused()) {
            ring.setStrokeWidth(2 * density);
            canvas.drawCircle(cx, cy, r + stroke + 8 * density, ring);
        }

        big.setTextSize(size * 0.26f);
        String now = Double.isNaN(measured) ? "–" : Status.degrees(measured);
        canvas.drawText(now, cx + size * 0.03f, cy + size * 0.08f, big);
        small.setTextSize(size * 0.055f);
        small.setColor(0x99FFFFFF);
        canvas.drawText(caption, cx, cy - size * 0.17f, small);
        small.setTextSize(size * 0.075f);
        small.setColor(accent);
        String goal = Double.isNaN(target) ? "" : getContext().getString(R.string.target, Status.degrees(target));
        canvas.drawText(goal, cx, cy + size * 0.24f, small);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gainFocus, direction, previous);
        invalidate();
    }
}
