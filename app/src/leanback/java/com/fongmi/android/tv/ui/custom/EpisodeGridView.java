package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewTreeObserver;
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
    // Row the remote is moving to while it is still being laid out; held keys continue from it.
    private int pendingFocus = NO_POSITION;
    // A held Up/Down key: rows scroll into view at once instead of animating, since the next repeat
    // arrives before a smooth scroll ends and the two fought (user 2026-10-25: fast scrolling stuttered).
    private boolean held;

    public EpisodeGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutManager(new GridLayoutManager(context, columnCount) {
            @Override
            public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate, boolean focusedChildVisible) {
                return super.requestChildRectangleOnScreen(parent, child, rect, immediate || held, focusedChildVisible);
            }
        });
        setItemAnimator(null);
        // Keep a small visual separation without wasting a full card-height
        // gap between rows. The taller item layout gives the two metadata
        // lines room to breathe while preserving reliable D-pad operation.
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
    /**
     * Up/Down between rows move focus here, one row per key. Left to the framework, a held Down
     * outran the layout: the next row was not attached yet, or the focused card was recycled, and
     * focus fell to the first focusable view, the preview at the top left (user 2026-10-24). The
     * first row's Up and the last row's Down still use their focus links.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_DPAD_DOWN && code != KeyEvent.KEYCODE_DPAD_UP) return super.dispatchKeyEvent(event);
        View item = getFocusedChild() == null ? null : findContainingItemView(getFocusedChild());
        int count = getAdapter() == null ? 0 : getAdapter().getItemCount();
        int from = pendingFocus != NO_POSITION ? pendingFocus : item == null ? NO_POSITION : getChildAdapterPosition(item);
        if (from == NO_POSITION || from >= count) return super.dispatchKeyEvent(event);
        boolean down = code == KeyEvent.KEYCODE_DPAD_DOWN;
        held = event.getRepeatCount() > 0;
        int row = from / columnCount, lastRow = (count - 1) / columnCount;
        if (down ? row == lastRow : row == 0) return pendingFocus != NO_POSITION || super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        int target = down ? Math.min(count - 1, from + columnCount) : from - columnCount;
        focusPosition(target);
        playSoundEffect(SoundEffectConstants.getContantForFocusDirection(down ? FOCUS_DOWN : FOCUS_UP));
        return true;
    }

    /**
     * Focuses the card at position in this frame when it can. A card one row past the edge is not
     * laid out yet; scrolling one row lays it out at once, where scrollToPosition waited for the next
     * layout, during which focus returned to the card it had left and lit it again (user 2026-10-24).
     */
    private void focusPosition(int position) {
        ViewHolder holder = hasPendingAdapterUpdates() ? null : findViewHolderForAdapterPosition(position);
        if (holder == null && !hasPendingAdapterUpdates() && getFocusedChild() != null) {
            View from = getFocusedChild();
            int step = getLayoutManager().getDecoratedMeasuredHeight(from);
            scrollBy(0, position > getChildAdapterPosition(from) ? step : -step);
            holder = findViewHolderForAdapterPosition(position);
        }
        if (holder != null) {
            pendingFocus = NO_POSITION;
            holder.itemView.requestFocus();
            return;
        }
        pendingFocus = position;
        scrollToPosition(position);
        post(() -> {
            if (pendingFocus != position) return;
            pendingFocus = NO_POSITION;
            ViewHolder target = findViewHolderForAdapterPosition(position);
            if (target != null) target.itemView.requestFocus();
        });
    }

    /**
     * Shows the playing card. Focus follows only when the grid already has it: picking a line selects
     * that line's episode, and pulling focus down to it moved the remote off the lines (user
     * 2026-10-24).
     */
    public void setSelectedPosition(int position) {
        setSelectedPosition(position, false);
    }

    /**
     * Focuses the card at position once the grid is laid out (it may have been hidden in full screen, so its
     * holders are stale); the frame waits for the move so the old card never draws focused.
     */
    public void focusAfterLayout(int position) {
        if (position < 0 || getAdapter() == null || position >= getAdapter().getItemCount()) return;
        ViewHolder holder = findViewHolderForAdapterPosition(position);
        if (holder != null && !isLayoutRequested()) {
            holder.itemView.requestFocus();
            return;
        }
        scrollToPosition(position);
        getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                getViewTreeObserver().removeOnPreDrawListener(this);
                ViewHolder target = findViewHolderForAdapterPosition(position);
                if (target == null) return true;
                target.itemView.requestFocus();
                return false;
            }
        });
    }

    /** takeFocus moves the remote onto the card even when the grid does not have focus yet. */
    public void setSelectedPosition(int position, boolean takeFocus) {
        if (position < 0 || getAdapter() == null || position >= getAdapter().getItemCount()) return;
        scrollToPosition(position);
        if (!isInTouchMode() && (takeFocus || hasFocus())) post(() -> {
            ViewHolder holder = findViewHolderForAdapterPosition(position);
            if (holder != null) holder.itemView.requestFocus();
        });
    }
}
