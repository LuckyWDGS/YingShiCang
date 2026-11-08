package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.recyclerview.widget.RecyclerView;

/** Keeps downward remote navigation in the cards, including while laying out the next row. */
public final class HomeGridView extends RecyclerView {
    private boolean holdingDirection;

    public HomeGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public boolean isHoldingDirection() {
        return holdingDirection;
    }

    /** Keep the entire focused card and its footer inside the visible list. */
    public int fitCardHeight(int naturalHeight) {
        // Includes the 12dp row spacing supplied by HomeActivity's decoration.
        int available = getMeasuredHeight() - getPaddingTop() - getPaddingBottom()
                - Math.round(12 * getResources().getDisplayMetrics().density);
        return available > 0 ? Math.min(naturalHeight, available) : naturalHeight;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean vertical = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP;
        holdingDirection = vertical && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0;
        if (holdingDirection) stopScroll();
        // ViewRoot moves focus after dispatchKeyEvent returns. Keep the repeat
        // state until key-up so requestChildRectangleOnScreen can scroll the
        // newly focused row immediately instead of cancelling it on each repeat.
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) holdingDirection = false;
    }

    @Override
    public View focusSearch(View focused, int direction) {
        // Let RecyclerView attach/scroll the next row before deciding that there
        // is nowhere to go. An Activity-level FocusFinder check skips that work.
        View next = super.focusSearch(focused, direction);
        if (direction == FOCUS_DOWN && contains(focused) && !contains(next)) return focused;
        return next;
    }

    private boolean contains(View view) {
        if (view == null) return false;
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == this) return true;
        }
        return false;
    }
}
