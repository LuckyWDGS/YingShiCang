package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Keeps the poster geometry relative to its real column, including narrow result panes. */
public class PosterFrame extends FrameLayout {
    private float heightRatio = 1.32f;
    public PosterFrame(Context context, AttributeSet attrs) { super(context, attrs); }
    public void setHeightRatio(float ratio) { heightRatio = ratio; requestLayout(); }
    @Override protected void onMeasure(int width, int height) {
        super.onMeasure(width, height);
        super.onMeasure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(getMeasuredWidth() * heightRatio), MeasureSpec.EXACTLY));
    }
}
