package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;

/** Row-major episode grid with native touch/wheel scrolling and D-pad focus. */
public class EpisodeGridView extends RecyclerView {
    /** Existing TV detail density for ordinary episode names. */
    public static final int DEFAULT_COLUMN_COUNT = 6;
    private static final int MIN_COLUMN_COUNT = 2;
    private static final int MAX_COLUMN_COUNT = 6;

    private OnChildViewHolderSelectedListener listener;
    private int columnCount = DEFAULT_COLUMN_COUNT;
    private GridSpacing spacing;

    public EpisodeGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutManager(new GridLayoutManager(context, columnCount));
        setItemAnimator(null);
        // Keep a small visual separation without wasting a full card-height
        // gap between rows. The compact item layout still leaves a 28dp focus
        // target for reliable D-pad operation while showing more episodes.
        spacing = new GridSpacing(columnCount, 4);
        addItemDecoration(spacing);
    }

    /**
     * Long source file names need fewer, wider cards. Rebuild only the grid geometry;
     * the adapter keeps the same item identity and selection state.
     */
    public void setColumnCount(int count) {
        int next = Math.max(MIN_COLUMN_COUNT, Math.min(MAX_COLUMN_COUNT, count));
        if (next == columnCount) return;
        columnCount = next;
        ((GridLayoutManager) getLayoutManager()).setSpanCount(columnCount);
        removeItemDecoration(spacing);
        spacing = new GridSpacing(columnCount, 4);
        addItemDecoration(spacing);
        invalidateItemDecorations();
    }

    public int getColumnCount() {
        return columnCount;
    }
    public void addOnChildViewHolderSelectedListener(OnChildViewHolderSelectedListener listener) { this.listener = listener; }
    @Override public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        View item = findContainingItemView(focused);
        if (item != null && listener != null) listener.onChildViewHolderSelected(this, getChildViewHolder(item), getChildAdapterPosition(item), 0);
    }
    public void setSelectedPosition(int position) {
        if (position < 0 || getAdapter() == null || position >= getAdapter().getItemCount()) return;
        scrollToPosition(position);
        if (!isInTouchMode()) post(() -> {
            ViewHolder holder = findViewHolderForAdapterPosition(position);
            if (holder != null) holder.itemView.requestFocus();
        });
    }
}
