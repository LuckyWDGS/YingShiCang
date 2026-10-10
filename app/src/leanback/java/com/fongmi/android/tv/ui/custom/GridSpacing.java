package com.fongmi.android.tv.ui.custom;

import android.graphics.Rect;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import com.fongmi.android.tv.utils.ResUtil;

public class GridSpacing extends RecyclerView.ItemDecoration {
    private final int columns, gap;
    public GridSpacing(int columns, int gap) { this.columns = columns; this.gap = ResUtil.dp2px(gap); }
    @Override public void getItemOffsets(Rect out, View view, RecyclerView parent, RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        if (position < 0) return;
        int column = position % columns;
        out.left = column * gap / columns;
        out.right = gap - (column + 1) * gap / columns;
        out.bottom = gap;
    }
}
