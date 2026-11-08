package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RelativeLayout;

import com.fongmi.android.tv.R;

/** The history card also appears below Home's two navigation rows. */
public final class HistoryCardView extends RelativeLayout {
    public HistoryCardView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int width, int height) {
        super.onMeasure(width, height);
        if (!(getParent() instanceof HomeGridView grid)) return;
        View image = findViewById(R.id.image);
        int footer = findViewById(R.id.name).getMeasuredHeight();
        int naturalHeight = Math.round(getMeasuredWidth() * 4f / 3f) + footer;
        int imageHeight = Math.max(1, grid.fitCardHeight(naturalHeight) - footer);
        if (image.getLayoutParams().height == imageHeight) return;
        image.getLayoutParams().height = imageHeight;
        super.onMeasure(width, height);
    }
}
