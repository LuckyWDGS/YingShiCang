package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** Keeps the poster geometry relative to its real column, including narrow result panes. */
public class PosterFrame extends FrameLayout {
    public PosterFrame(Context context, AttributeSet attrs) { super(context, attrs); }
    @Override protected void onMeasure(int width, int height) {
        super.onMeasure(width, height);
        super.onMeasure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(getMeasuredWidth() * 4f / 3f)
                        + Math.round(36 * getResources().getDisplayMetrics().density), MeasureSpec.EXACTLY));
    }
}
