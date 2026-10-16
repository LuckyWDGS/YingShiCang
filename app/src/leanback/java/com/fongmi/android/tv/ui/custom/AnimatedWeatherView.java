package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import com.fongmi.android.tv.weather.WeatherInfo;

/** Small native weather illustration with quiet, continuous motion. */
public class AnimatedWeatherView extends View {
    private static final float[] PRECIPITATION_PHASE = {0.02f, 0.19f, 0.37f, 0.56f, 0.74f, 0.91f, 0.46f, 0.67f};
    private static final float[] PRECIPITATION_SPEED = {0.66f, 0.79f, 0.72f, 0.91f, 0.75f, 0.84f, 0.61f, 0.88f};
    private static final float[] PRECIPITATION_X = {15f, 21.5f, 28f, 34.5f, 41f, 47.5f, 18f, 44f};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Path path = new Path();
    private final Shader sunGlow = new RadialGradient(0, 0, 19,
            new int[]{0x50F8BE59, 0x17EFA846, 0x00EFA846}, new float[]{0, .66f, 1}, Shader.TileMode.CLAMP);
    private final Shader sunSurface = new RadialGradient(-3, -4, 16,
            new int[]{0xFFFFE9AE, 0xFFFFCC65, 0xFFF5A947}, null, Shader.TileMode.CLAMP);
    private final Shader moonSurface = new LinearGradient(-7, -10, 6, 12,
            0xFFF0F5FC, 0xFFA7C3DD, Shader.TileMode.CLAMP);
    private final Shader cloudLight = new LinearGradient(0, 4, 0, 31,
            new int[]{0xFFF4F7F9, 0xFFD9E5EC, 0xFF9CB5C7}, null, Shader.TileMode.CLAMP);
    private final Shader cloudShade = new LinearGradient(0, 4, 0, 31,
            0xFFB2C4D2, 0xFF657E94, Shader.TileMode.CLAMP);
    private WeatherInfo.Kind kind = WeatherInfo.Kind.UNKNOWN;
    private boolean day = true, active;
    private long animationStart;
    private boolean animating;

    public AnimatedWeatherView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setWillNotDraw(false);
    }

    public void setWeather(int code, boolean day) {
        this.kind = WeatherInfo.kind(code);
        this.day = day;
        setContentDescription(WeatherInfo.describe(code));
        updateAnimation();
        invalidate();
    }

    public void setActive(boolean active) {
        this.active = active;
        updateAnimation();
    }

    private void updateAnimation() {
        boolean enabled = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
        boolean run = active && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE
                && enabled && kind != WeatherInfo.Kind.UNKNOWN;
        if (run && !animating) {
            animationStart = SystemClock.uptimeMillis();
            animating = true;
            postInvalidateOnAnimation();
        } else if (!run) {
            animating = false;
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        animating = false;
        super.onDetachedFromWindow();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        updateAnimation();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateAnimation();
    }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(color);
        paint.setAlpha((color >>> 24) & 0xff);
    }

    private void stroke(int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setShader(null);
        paint.setColor(color);
        paint.setAlpha((color >>> 24) & 0xff);
        paint.setStrokeWidth(width);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float seconds = animating ? (SystemClock.uptimeMillis() - animationStart) / 1000f : 0f;
        canvas.save();
        float scale = Math.min(getWidth() / 64f, getHeight() / 48f);
        canvas.translate((getWidth() - 64 * scale) / 2, (getHeight() - 48 * scale) / 2);
        canvas.scale(scale, scale);
        switch (kind) {
            case CLEAR -> sunOrMoon(canvas, 31, 24, 10, seconds);
            case PARTLY_CLOUDY -> {
                sunOrMoon(canvas, 22, 18, 8, seconds);
                cloud(canvas, 5 + cloudDrift(seconds, .42f), 4 + cloudLift(seconds, .2f), 0xFFE1EBF0);
            }
            case CLOUDY -> {
                cloud(canvas, -6 + cloudDrift(seconds, .25f), -1 + cloudLift(seconds, .1f), 0xFF7891A5);
                cloud(canvas, 2 + cloudDrift(seconds + 1.8f, .34f), 5 + cloudLift(seconds, 1.6f), 0xFFDCE7ED);
            }
            case FOG -> {
                cloud(canvas, cloudDrift(seconds, .3f), -2 + cloudLift(seconds, .6f), 0xFFC2D2DD);
                fog(canvas, seconds);
            }
            case RAIN, SLEET, SNOW, THUNDER -> {
                cloud(canvas, cloudDrift(seconds, .3f), -3 + cloudLift(seconds, .4f), 0xFFC8DAE5);
                precipitation(canvas, seconds, kind == WeatherInfo.Kind.SNOW, kind == WeatherInfo.Kind.SLEET);
                if (kind == WeatherInfo.Kind.THUNDER) lightning(canvas, seconds);
            }
            default -> {
                cloud(canvas, cloudDrift(seconds, .2f), 0, 0xFF8EA4B1);
                stroke(0xB5D3E0E8, 1.5f);
                canvas.drawLine(25, 34, 39, 34, paint);
            }
        }
        canvas.restore();
        if (animating) postInvalidateOnAnimation();
    }

    private float cloudDrift(float seconds, float speed) {
        return (float) Math.sin(seconds * speed + speed * 2.1f) * 1.7f;
    }

    private float cloudLift(float seconds, float phase) {
        return (float) Math.sin(seconds * .52f + phase) * .45f;
    }

    private void sunOrMoon(Canvas canvas, float x, float y, float radius, float seconds) {
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(radius / 10, radius / 10);
        x = y = 0;
        radius = 10;
        if (!day) {
            // A soft halo and a clean crescent read better than a rotating moon.
            fill(0x183F6582);
            canvas.drawCircle(x, y, radius + 7, paint);
            fill(0xFFDDE9F8);
            paint.setShader(moonSurface);
            path.reset();
            path.moveTo(x + 5, y - 11);
            path.cubicTo(x - 13, y - 12, x - 13, y + 13, x + 7, y + 11);
            path.cubicTo(x - 1, y + 7, x - 3, y - 3, x + 5, y - 11);
            path.close();
            canvas.drawPath(path, paint);
            float twinkle = .75f + .25f * Math.max(0, (float) Math.sin(seconds * .7f));
            fill(0xB8BFD9EA);
            paint.setAlpha((int) (180 * twinkle));
            canvas.drawCircle(x + 14, y - 7, 1.3f, paint);
            paint.setAlpha((int) (130 * (.7f + .3f * Math.max(0, (float) Math.sin(seconds * .53f + 1.3f)))));
            canvas.drawCircle(x - 13, y - 6, .9f, paint);
            paint.setAlpha((int) (110 * (.7f + .3f * Math.max(0, (float) Math.sin(seconds * .61f + 2.5f)))));
            canvas.drawCircle(x + 14, y + 9, .8f, paint);
            canvas.restore();
            return;
        }
        float breathe = 1f + (float) Math.sin(seconds * .75f) * .018f;
        float rayBreathe = .35f + .65f * (float) ((Math.sin(seconds * .75f) + 1) * .5f);
        fill(0xFFFFFFFF);
        paint.setShader(sunGlow);
        canvas.drawCircle(x, y, 19 * breathe, paint);
        stroke(0xADF0B65D, 1.2f);
        for (int i = 0; i < 8; i++) {
            canvas.save();
            canvas.rotate(i * 45f + 22.5f, x, y);
            float start = radius + 3.5f;
            canvas.drawLine(x, y - start, x, y - start - 3.2f - rayBreathe, paint);
            canvas.restore();
        }
        fill(0xFFFFFFFF);
        paint.setShader(sunSurface);
        canvas.drawCircle(x, y, radius * breathe, paint);
        canvas.restore();
    }

    private void cloud(Canvas canvas, float dx, float dy, int color) {
        canvas.save();
        canvas.translate(dx, dy);
        path.reset();
        path.moveTo(18, 30);
        path.cubicTo(6, 30, 6, 13, 19, 13);
        path.cubicTo(23, -1, 42, 0, 44, 15);
        path.cubicTo(56, 14, 58, 30, 46, 30);
        path.close();
        fill(0xFFFFFFFF);
        paint.setShader(color == 0xFF7891A5 ? cloudShade : cloudLight);
        canvas.drawPath(path, paint);
        // A soft upper contour gives volume without sharp overlapping circles.
        stroke(0x35FFFFFF, .8f);
        path.reset();
        path.moveTo(23, 12);
        path.cubicTo(27, 5, 37, 5, 41, 14);
        canvas.drawPath(path, paint);
        canvas.restore();
    }

    private void fog(Canvas canvas, float seconds) {
        for (int i = 0; i < 3; i++) {
            float offset = (float) Math.sin(seconds * .32f + i * 1.5f) * 2.5f;
            stroke(i == 1 ? 0xA6B7CED8 : 0x749EB8C4, 1.7f);
            path.reset();
            path.moveTo(10 + offset, 34 + i * 5);
            path.cubicTo(19 + offset, 31 + i * 5, 31 + offset, 37 + i * 5, 53 + offset, 33 + i * 5);
            canvas.drawPath(path, paint);
        }
    }

    private void precipitation(Canvas canvas, float seconds, boolean snow, boolean sleet) {
        for (int i = 0; i < PRECIPITATION_PHASE.length; i++) {
            boolean flake = snow || (sleet && i % 2 == 0);
            float t = fraction(seconds * PRECIPITATION_SPEED[i] * (flake ? .45f : 1.3f) + PRECIPITATION_PHASE[i]);
            float fade = Math.min(1f, Math.min(t * 5f, (1f - t) * 3f));
            float sway = (float) Math.sin(seconds * .55f + i * 1.7f) * (snow ? 1.8f : .8f);
            float x = PRECIPITATION_X[i] + sway - t * 3.4f;
            float y = 24 + t * 23;
            if (flake) {
                stroke(0xD8E9F5FF, 1.05f);
                paint.setAlpha((int) (215 * fade));
                float size = i % 3 == 0 ? 2.5f : 2f;
                canvas.save();
                canvas.rotate(seconds * (i % 2 == 0 ? 10f : -8f) + i * 14f, x, y);
                canvas.drawLine(x - size, y, x + size, y, paint);
                canvas.drawLine(x, y - size, x, y + size, paint);
                canvas.restore();
            } else {
                stroke(0xD16FC4E8, i % 3 == 0 ? 1.55f : 1.8f);
                paint.setAlpha((int) (220 * fade));
                canvas.drawLine(x + 1.1f, y - 3.4f, x - .9f, y + 1.6f, paint);
            }
        }
    }

    private void lightning(Canvas canvas, float seconds) {
        float flash = (float) Math.pow(Math.max(0, Math.sin(seconds * .38f + .7f)), 26);
        if (flash <= .01f) return;
        path.reset();
        path.moveTo(35, 25);
        path.lineTo(28, 36);
        path.lineTo(34, 35);
        path.lineTo(31, 45);
        path.lineTo(42, 31);
        path.lineTo(35, 32);
        path.close();
        fill(0x40FFE39A);
        paint.setAlpha((int) (64 + flash * 90));
        canvas.drawPath(path, paint);
        fill(0xFFFFD46F);
        paint.setAlpha((int) (140 + flash * 115));
        canvas.drawPath(path, paint);
    }

    private float fraction(float value) {
        return value - (float) Math.floor(value);
    }
}
