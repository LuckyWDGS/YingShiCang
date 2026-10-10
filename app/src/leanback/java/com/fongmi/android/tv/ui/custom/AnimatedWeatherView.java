package com.fongmi.android.tv.ui.custom;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;
import com.fongmi.android.tv.weather.WeatherInfo;

/** Small native vector-style weather animation; no network/media decoder or always-on timer. */
public class AnimatedWeatherView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
    private WeatherInfo.Kind kind = WeatherInfo.Kind.UNKNOWN;
    private boolean day = true, active;
    private float phase;
    private long lastFrame;

    public AnimatedWeatherView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        animator.setDuration(4000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            phase = (float) value.getAnimatedValue();
            long now = SystemClock.uptimeMillis();
            if (now - lastFrame >= 32) { lastFrame = now; invalidate(); }
        });
    }
    public void setWeather(int code, boolean day) {
        this.kind = WeatherInfo.kind(code); this.day = day;
        setContentDescription(WeatherInfo.describe(code));
        updateAnimation(); invalidate();
    }
    public void setActive(boolean active) { this.active = active; updateAnimation(); }
    private void updateAnimation() {
        boolean enabled = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
        boolean run = active && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE && enabled && kind != WeatherInfo.Kind.UNKNOWN;
        if (run && !animator.isStarted()) animator.start();
        else if (!run && animator.isStarted()) animator.cancel();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation(); }
    @Override protected void onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow(); }
    @Override public void onVisibilityAggregated(boolean visible) { super.onVisibilityAggregated(visible); if (animator != null) updateAnimation(); }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (animator != null) updateAnimation();
    }
    private void fill(int color) { paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setAlpha(255); }
    private void stroke(int color, float width) { paint.setStyle(Paint.Style.STROKE); paint.setColor(color); paint.setAlpha(255); paint.setStrokeWidth(width); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        float scale = Math.min(getWidth() / 64f, getHeight() / 48f);
        canvas.translate((getWidth() - 64 * scale) / 2, (getHeight() - 48 * scale) / 2);
        canvas.scale(scale, scale);
        float drift = (float) Math.sin(phase * Math.PI * 2) * 1.6f;
        switch (kind) {
            case CLEAR -> sunOrMoon(canvas, 31, 24, 10);
            case PARTLY_CLOUDY -> { sunOrMoon(canvas, 23, 18, 8); cloud(canvas, drift + 3, 5, 0xFFE0EAF0); }
            case CLOUDY -> { cloud(canvas, -5 - drift, -2, 0xFF7F99AC); cloud(canvas, 3 + drift, 5, 0xFFDCE7ED); }
            case FOG -> {
                cloud(canvas, drift, -2, 0xFFC2D2DD);
                stroke(0xFF9EB9C7, 2.2f);
                for (int i = 0; i < 3; i++) canvas.drawLine(15 + drift * (i + 1), 33 + i * 5, 47 + drift, 33 + i * 5, paint);
            }
            case RAIN, SLEET, SNOW, THUNDER -> {
                cloud(canvas, drift, -3, 0xFFC8DAE5);
                precipitation(canvas, kind == WeatherInfo.Kind.SNOW, kind == WeatherInfo.Kind.SLEET);
                if (kind == WeatherInfo.Kind.THUNDER) {
                    fill(0xFFFFD16C); paint.setAlpha((int) (150 + 80 * Math.sin(phase * Math.PI * 2)));
                    path.reset(); path.moveTo(34,26); path.lineTo(28,36); path.lineTo(34,35); path.lineTo(31,45); path.lineTo(42,31); path.lineTo(35,32); path.close(); canvas.drawPath(path,paint);
                }
            }
            default -> { stroke(0xFF8EA4B1, 2); canvas.drawCircle(32,24,12,paint); canvas.drawLine(27,24,37,24,paint); }
        }
        canvas.restore();
    }
    private void sunOrMoon(Canvas canvas, float x, float y, float radius) {
        if (!day) {
            fill(0xFFDBE6F9);
            path.reset(); path.moveTo(x + 5,y - 11); path.cubicTo(x-17,y-13,x-15,y+14,x+7,y+11); path.cubicTo(x-2,y+7,x-4,y-3,x+5,y-11); path.close(); canvas.drawPath(path,paint);
            fill(0xFFBDD4EA); canvas.drawCircle(x+14,y-7,1.4f+(float)Math.sin(phase*6.28)*.4f,paint);
            return;
        }
        fill(0xFFFFD578); canvas.drawCircle(x,y,radius,paint);
        canvas.save(); canvas.rotate(phase * 90, x,y); stroke(0xFFEEC277,2);
        for (int i=0;i<8;i++) { canvas.drawLine(x,y-radius-4,x,y-radius-8,paint); canvas.rotate(45,x,y); }
        canvas.restore();
    }
    private void cloud(Canvas canvas, float dx, float dy, int color) {
        canvas.save(); canvas.translate(dx,dy); fill(color);
        path.reset(); path.moveTo(14,29); path.cubicTo(4,27,9,16,18,18); path.cubicTo(18,4,39,4,41,18);
        path.cubicTo(53,13,61,28,49,30); path.lineTo(14,30); path.close(); canvas.drawPath(path,paint);
        canvas.restore();
    }
    private void precipitation(Canvas canvas, boolean snow, boolean sleet) {
        for (int i=0;i<4;i++) {
            float t=(phase*2 + i*.27f)%1f;
            float x=18+i*9+(snow ? (float)Math.sin(t*6.28+i)*2 : -t*2), y=30+t*14;
            stroke(snow || (sleet && i%2==0) ? 0xFFEAF4FF : 0xFF79C6ED, snow ? 1.3f : 1.9f);
            paint.setAlpha((int)(230*(1-t*.55f)));
            if (snow || (sleet && i%2==0)) {
                canvas.save();canvas.rotate(t*90,x,y);
                for(int n=0;n<3;n++){canvas.drawLine(x-2.2f,y,x+2.2f,y,paint);canvas.rotate(60,x,y);} canvas.restore();
            } else canvas.drawLine(x+1,y-2.6f,x-1,y+1,paint);
        }
    }
}
