package com.fongmi.android.tv.ui.custom;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

/** Reveals a Home-only row without squeezing its contents or jumping the rows below it. */
public final class HomeHeaderRow extends FrameLayout {
    private static final long DURATION_MS = 280L;
    private float reveal = 1f;
    private boolean expanded = true;
    private ValueAnimator animator;

    public HomeHeaderRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(true);
        setClipToPadding(true);
    }

    public void setExpanded(boolean value, boolean animate) {
        if (expanded == value) return;
        expanded = value;
        cancelAnimation();
        setDescendantFocusability(value ? FOCUS_AFTER_DESCENDANTS : FOCUS_BLOCK_DESCENDANTS);
        setImportantForAccessibility(value ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        float target = value ? 1f : 0f;
        if (!animate || !isLaidOut() || !isAttachedToWindow() || getWindowVisibility() != VISIBLE) {
            finishAnimation();
            return;
        }
        setVisibility(VISIBLE);
        ValueAnimator next = ValueAnimator.ofFloat(reveal, target);
        animator = next;
        next.setDuration(Math.max(90L, Math.round(DURATION_MS * Math.abs(target - reveal))));
        next.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        next.addUpdateListener(animation -> applyReveal((float) animation.getAnimatedValue()));
        next.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator != animation) return;
                animator = null;
                applyReveal(target);
                setVisibility(expanded ? VISIBLE : GONE);
            }
        });
        next.start();
    }

    public void finishAnimation() {
        cancelAnimation();
        applyReveal(expanded ? 1f : 0f);
        setVisibility(expanded ? VISIBLE : GONE);
    }

    private void cancelAnimation() {
        if (animator == null) return;
        ValueAnimator previous = animator;
        animator = null;
        previous.removeAllUpdateListeners();
        previous.removeAllListeners();
        previous.cancel();
    }

    private void applyReveal(float value) {
        reveal = value;
        setAlpha(value);
        // Keep the full-size row behind a shrinking viewport; labels never reflow.
        float offset = -8f * getResources().getDisplayMetrics().density * (1f - value);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setTranslationY(offset);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        setMeasuredDimension(getMeasuredWidth(), Math.round(getMeasuredHeight() * reveal));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return expanded && super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        finishAnimation();
        super.onDetachedFromWindow();
    }
}
