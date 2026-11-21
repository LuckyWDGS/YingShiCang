package com.fongmi.android.tv.ui.custom;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A red pill with white text over an anchor view's top-right corner (user 2026-11-21: a bare dot on 设置 did not
 * say what it meant; it reads 新版本). It is drawn in the overlay of an ancestor that has room around the anchor,
 * so it can stick out of the anchor without any view's clipping being changed (an overlay on the anchor itself
 * is cut at the anchor's bounds) and without adding views to the anchor's row.
 */
public final class CornerBadge extends Drawable implements View.OnLayoutChangeListener {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Rect anchorRect = new Rect();
    private final String label;
    private final float paddingX, height, overhangX, overhangY;
    private ViewGroup host;
    private View anchor;

    public CornerBadge(Resources resources, String label) {
        this.label = label;
        fill.setColor(0xFFE53935);
        text.setColor(0xFFFFFFFF);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, resources.getDisplayMetrics()));
        paddingX = dp(resources, 6);
        height = dp(resources, 18);
        overhangX = dp(resources, 6);
        overhangY = dp(resources, 9);
    }

    private static float dp(Resources resources, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.getDisplayMetrics());
    }

    /** Shows the badge on anchor, drawn by host (an ancestor of anchor), or removes it when anchor is null. */
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
        int width = Math.round(text.measureText(label) + paddingX * 2);
        int right = Math.round(anchorRect.right + overhangX);
        int top = Math.max(0, Math.round(anchorRect.top - overhangY));
        setBounds(right - width, top, right, top + Math.round(height));
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        box.set(getBounds());
        float radius = box.height() / 2f;
        canvas.drawRoundRect(box, radius, radius, fill);
        Paint.FontMetrics metrics = text.getFontMetrics();
        float baseline = box.centerY() - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(label, box.left + paddingX, baseline, text);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        text.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        text.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
