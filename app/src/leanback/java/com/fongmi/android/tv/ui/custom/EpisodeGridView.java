package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;

/** Row-major episode grid with native touch/wheel scrolling and D-pad focus. */
public class EpisodeGridView extends RecyclerView {
    private OnChildViewHolderSelectedListener listener;
    public EpisodeGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutManager(new GridLayoutManager(context, 6));
        setItemAnimator(null);
        addItemDecoration(new GridSpacing(6, 8));
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
