package app.oneclick.tgproxy;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

final class PowerButton extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF iconArc = new RectF();
    private final RectF busyArc = new RectF();
    private float progress;
    private boolean busy;

    PowerButton(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription("Включить прокси");
        setClickable(true);
        setFocusable(true);
        ring.setStyle(Paint.Style.STROKE);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
    }

    void setEnabledState(boolean enabled, boolean animate) {
        float target = enabled ? 1f : 0f;
        setContentDescription(enabled ? "Выключить прокси" : "Включить прокси");
        if (!animate) {
            progress = target;
            invalidate();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(350);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(value -> {
            progress = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    void setBusy(boolean value) {
        busy = value;
        setEnabled(!value);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = size * 0.36f;
        int blue = Color.rgb(42, 171, 238);
        int dark = Color.rgb(35, 52, 70);
        int fillColor = blend(Color.WHITE, blue, progress);
        int iconColor = blend(dark, Color.WHITE, progress);

        fill.setColor(fillColor);
        fill.setShadowLayer(size * .08f, 0, size * .03f,
                Color.argb((int) (38 + progress * 30), 42, 171, 238));
        canvas.drawCircle(cx, cy, radius, fill);

        ring.setStrokeWidth(size * .014f);
        ring.setColor(blend(Color.rgb(218, 230, 240), blue, progress));
        canvas.drawCircle(cx, cy, radius + size * .055f, ring);

        icon.setColor(iconColor);
        icon.setStrokeWidth(size * .032f);
        float iconRadius = size * .105f;
        iconArc.set(cx - iconRadius, cy - iconRadius, cx + iconRadius, cy + iconRadius);
        canvas.drawArc(iconArc, -48, 276, false, icon);
        canvas.drawLine(cx, cy - size * .14f, cx, cy - size * .005f, icon);

        if (busy) {
            ring.setColor(blue);
            ring.setStrokeWidth(size * .018f);
            busyArc.set(cx - radius - size * .055f, cy - radius - size * .055f,
                    cx + radius + size * .055f, cy + radius + size * .055f);
            canvas.drawArc(busyArc, (System.currentTimeMillis() / 5) % 360, 72, false, ring);
            postInvalidateDelayed(16);
        }
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        setAlpha(isPressed() ? .82f : 1f);
    }

    private static int blend(int from, int to, float amount) {
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * amount);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * amount);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount);
        return Color.rgb(r, g, b);
    }
}
