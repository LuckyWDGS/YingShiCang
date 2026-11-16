package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;

/**
 * Keeps downward remote navigation in the cards, including while laying out the next row.
 * While Up/Down is held, poster loads wait until the key is released: rows passed through at
 * speed are not fetched or decoded, and the rows the key stops on load at once. Posters already in
 * memory wait too: showing them (a request per card, its miss answered on the main thread) cost
 * most of the gain on LDPlayer (frame median 5ms paused, 9-11ms memory-only, 11-25ms before).
 */
public class CardGridView extends RecyclerView {
    private boolean holdingDirection;
    private RequestManager pausedImages;
    private Runnable onHoldEnd;

    public CardGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public boolean isHoldingDirection() {
        return holdingDirection;
    }

    /** Runs on the main thread when a held Up/Down is released (or the window loses focus). */
    public void setOnHoldEnd(Runnable onHoldEnd) {
        this.onHoldEnd = onHoldEnd;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean vertical = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP;
        boolean wasHolding = holdingDirection;
        holdingDirection = vertical && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0;
        if (holdingDirection) {
            stopScroll();
            pauseImages();
        }
        // ViewRoot moves focus after dispatchKeyEvent returns. Keep the repeat
        // state until key-up so requestChildRectangleOnScreen can scroll the
        // newly focused row immediately instead of cancelling it on each repeat.
        boolean handled = super.dispatchKeyEvent(event);
        if (wasHolding && !holdingDirection) endHold();
        return handled;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus && holdingDirection) {
            holdingDirection = false;
            endHold();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        holdingDirection = false;
        resumeImages();
        super.onDetachedFromWindow();
    }

    private void pauseImages() {
        if (pausedImages != null) return;
        try {
            pausedImages = Glide.with(this);
            pausedImages.pauseRequests();
        } catch (IllegalArgumentException destroyed) {
            pausedImages = null;
        }
    }

    private void resumeImages() {
        RequestManager manager = pausedImages;
        pausedImages = null;
        if (manager != null) manager.resumeRequests();
    }

    private void endHold() {
        resumeImages();
        if (onHoldEnd != null) onHoldEnd.run();
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
