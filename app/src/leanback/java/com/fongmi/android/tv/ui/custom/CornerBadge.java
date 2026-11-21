package com.fongmi.android.tv.ui.custom;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A red speech bubble holding a white up arrow over an anchor view's top-right corner, its tail pointing down at
 * the anchor (user 2026-11-21: a bare dot on 设置 did not say what it meant, and an arrow reads better than
 * text). It is drawn in the overlay of an ancestor that has room around the anchor, so it can stick out of the
 * anchor without any view's clipping being changed (an overlay on the anchor itself is cut at the anchor's
 * bounds) and without adding views to the anchor's row.
 */
public final class CornerBadge extends Drawable implements View.OnLayoutChangeListener {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bubble = new Path();
    private final Path head = new Path();
    private final RectF body = new RectF();
    private final Rect anchorRect = new Rect();
    private final float size, tail, overhangX, overhangY, stroke;
    private ViewGroup host;
    private View anchor;

    public CornerBadge(Resources resources) {
        fill.setColor(0xFFE53935);
        arrow.setColor(0xFFFFFFFF);
        size = dp(resources, 26);
        tail = dp(resources, 6);
        overhangX = dp(resources, 8);
        overhangY = dp(resources, 10);
        stroke = dp(resources, 2.5f);
        arrow.setStrokeWidth(stroke);
        arrow.setStrokeCap(Paint.Cap.ROUND);
        arrow.setStrokeJoin(Paint.Join.ROUND);
    }

    private static float dp(Resources resources, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.getDisplayMetrics());
    }

    /** Shows the bubble on anchor, drawn by host (an ancestor of anchor), or removes it when anchor is null. */
    public void attach(@Nullable View anchor, @NonNull ViewGroup host) {
        if (this.anchor == anchor && this.host == host) return;
        if (this.anchor != null) this.anchor.removeOnLayoutChangeListener(this);
        if (this.host != null) this.host.getOverlay().remove(this);
        this.anchor = anchor;
        this.host = anchor == null ? null : host;
        if (anchor == null) return;
        host.getOverlay().add(this);
        anchor.addOnLayoutChangeListener(this);
        place();
    }

    @Override
    public void onLayoutChange(View view, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
        place();
    }

    private void place() {
        if (anchor == null || host == null || anchor.getParent() == null) return;
        anchorRect.set(0, 0, anchor.getWidth(), anchor.getHeight());
        try {
            host.offsetDescendantRectToMyCoords(anchor, anchorRect);
        } catch (IllegalArgumentException detached) {
            return;
        }
        int right = Math.round(anchorRect.right + overhangX);
        int top = Math.max(0, Math.round(anchorRect.top - overhangY));
        setBounds(Math.round(right - size), top, right, Math.round(top + size + tail));
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        // A round body with a short tail at its lower left, pointing down at the anchor's corner.
        body.set(bounds.left, bounds.top, bounds.right, bounds.top + size);
        float radius = size / 2f;
        bubble.reset();
        bubble.addRoundRect(body, radius, radius, Path.Direction.CW);
        Path pointer = new Path();
        pointer.moveTo(body.left + size * 0.22f, body.bottom - size * 0.18f);
        pointer.lineTo(body.left + size * 0.10f, body.bottom + tail);
        pointer.lineTo(body.left + size * 0.48f, body.bottom - size * 0.06f);
        pointer.close();
        bubble.op(pointer, Path.Op.UNION);
        // An up arrow: stem plus a chevron head.
        float cx = body.centerX(), half = size * 0.22f;
        head.reset();
        head.moveTo(cx - half, body.top + size * 0.46f);
        head.lineTo(cx, body.top + size * 0.24f);
        head.lineTo(cx + half, body.top + size * 0.46f);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.drawPath(bubble, fill);
        arrow.setStyle(Paint.Style.STROKE);
        canvas.drawLine(body.centerX(), body.top + size * 0.26f, body.centerX(), body.top + size * 0.76f, arrow);
        canvas.drawPath(head, arrow);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        arrow.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        arrow.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
