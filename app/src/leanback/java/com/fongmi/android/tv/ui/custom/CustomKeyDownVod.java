package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.playback.MediaSeekPolicy;
import com.fongmi.android.tv.utils.KeyUtil;

public class CustomKeyDownVod extends GestureDetector.SimpleOnGestureListener {

    // A swipe across the whole screen moves two minutes, in either direction.
    private static final long SWIPE_FULL_WIDTH_MS = 120_000;

    private static final int SWIPE_NONE = 0, SWIPE_SEEK = 1, SWIPE_VOLUME = 2;
    // A swipe across the whole height moves the volume through about 15 steps (the usual media range).
    private static final int VOLUME_STEPS_PER_HEIGHT = 15;

    private final GestureDetector detector;
    private final Listener listener;
    private final int touchSlop;
    private int swipe;
    private float downX, downY;
    private long swipeTime;
    private int volumeSteps;
    private boolean changeSpeed;
    private boolean full;
    private long holdTime;
    private Runnable pendingSeekEnd;

    public static CustomKeyDownVod create(Activity activity) {
        return new CustomKeyDownVod(activity);
    }

    private CustomKeyDownVod(Activity activity) {
        this.detector = new GestureDetector(activity, this);
        this.listener = (Listener) activity;
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
    }

    /**
     * Touch-screen TVs, like the remote's arrows: a horizontal swipe on the full-screen video seeks (the
     * center bubble follows the finger, the seek happens on release), a vertical swipe changes the volume;
     * taps keep toggling the controls and double taps pause.
     */
    public boolean onTouchEvent(View view, MotionEvent e) {
        if (!full) return false;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            swipe = SWIPE_NONE;
            downX = e.getX();
            downY = e.getY();
        } else if (action == MotionEvent.ACTION_MOVE && swipe == SWIPE_NONE && e.getPointerCount() == 1) {
            startSwipe(view, e);
        }
        if (swipe == SWIPE_NONE) return detector.onTouchEvent(e);
        if (swipe == SWIPE_SEEK) seekSwipe(view, e, action);
        else if (action == MotionEvent.ACTION_MOVE) volumeSwipe(view, e);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swipe = SWIPE_NONE;
        return true;
    }

    private void seekSwipe(View view, MotionEvent e, int action) {
        if (action == MotionEvent.ACTION_MOVE) {
            swipeTime = (long) ((e.getX() - downX) / Math.max(1, view.getWidth()) * SWIPE_FULL_WIDTH_MS);
            listener.onSeeking(swipeTime);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            listener.onSeekEnd(action == MotionEvent.ACTION_UP ? swipeTime : 0);
        }
    }

    /** One volume step per 1/15 of the height, up raises; steps already applied are not repeated. */
    private void volumeSwipe(View view, MotionEvent e) {
        float step = Math.max(touchSlop, view.getHeight() / (float) VOLUME_STEPS_PER_HEIGHT);
        int steps = (int) ((downY - e.getY()) / step);
        for (; volumeSteps < steps; volumeSteps++) listener.onSwipeVolume(true);
        for (; volumeSteps > steps; volumeSteps--) listener.onSwipeVolume(false);
    }

    /** Past the touch slop the gesture is a swipe, not a tap: cancel the view's click and the detector's tap tracking. */
    private void startSwipe(View view, MotionEvent e) {
        float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
        if (dx > touchSlop && dx > dy * 1.5f) swipe = SWIPE_SEEK;
        else if (dy > touchSlop && dy > dx * 1.5f) swipe = SWIPE_VOLUME;
        else return;
        swipeTime = 0;
        volumeSteps = 0;
        commitPendingSeek();
        // Measure from here so the first step needs the same travel as every later one.
        if (swipe == SWIPE_VOLUME) downY = e.getY();
        MotionEvent cancel = MotionEvent.obtain(e);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        detector.onTouchEvent(cancel);
        view.onTouchEvent(cancel);
        cancel.recycle();
    }

    public void setFull(boolean full) {
        this.full = full;
    }

    public boolean hasEvent(KeyEvent event) {
        return KeyUtil.isEnterKey(event) || KeyUtil.isUpKey(event) || KeyUtil.isDownKey(event) || KeyUtil.isLeftKey(event) || KeyUtil.isRightKey(event);
    }

    public boolean onKeyDown(KeyEvent event) {
        check(event);
        return true;
    }

    private void check(KeyEvent event) {
        if (KeyUtil.isActionDown(event) && KeyUtil.isLeftKey(event)) {
            cancelPendingSeekEnd();
            listener.onSeeking(subTime(event.getRepeatCount()));
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isRightKey(event)) {
            cancelPendingSeekEnd();
            listener.onSeeking(addTime(event.getRepeatCount()));
        } else if (KeyUtil.isActionUp(event) && (KeyUtil.isLeftKey(event) || KeyUtil.isRightKey(event))) {
            scheduleSeekEnd();
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isUpKey(event)) {
            if (changeSpeed) listener.onSpeedEnd();
            else listener.onKeyUp();
            changeSpeed = false;
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isDownKey(event)) {
            listener.onKeyDown();
        } else if (KeyUtil.isActionUp(event) && KeyUtil.isEnterKey(event)) {
            listener.onKeyCenter();
        } else if (event.isLongPress() && KeyUtil.isUpKey(event)) {
            listener.onSpeedUp();
            changeSpeed = true;
        }
    }

    @Override
    public boolean onDoubleTap(@NonNull MotionEvent e) {
        listener.onDoubleTap();
        return true;
    }

    @Override
    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
        listener.onSingleTap();
        return true;
    }

    private long addTime(int repeatCount) {
        return holdTime = holdTime + seekStep(repeatCount);
    }

    private long subTime(int repeatCount) {
        return holdTime = holdTime - seekStep(repeatCount);
    }

    private long seekStep(int repeatCount) {
        return MediaSeekPolicy.intervalMs(0, repeatCount);
    }

    /** Shares the directional-key accumulator so rapid presses use one seek. */
    public void onMediaSeek(long deltaMs) {
        cancelPendingSeekEnd();
        holdTime += deltaMs;
        listener.onSeeking(holdTime);
        scheduleSeekEnd();
    }

    private void scheduleSeekEnd() {
        cancelPendingSeekEnd();
        long time = holdTime;
        pendingSeekEnd = () -> {
            pendingSeekEnd = null;
            listener.onSeekEnd(time);
        };
        App.post(pendingSeekEnd, 250);
    }

    /** Commits the delayed seek before a pause or another state-changing action. */
    public boolean commitPendingSeek() {
        Runnable pending = pendingSeekEnd;
        if (pending == null) return false;
        App.removeCallbacks(pending);
        pendingSeekEnd = null;
        pending.run();
        return true;
    }

    /** Drops a delayed seek when leaving the player or switching media. */
    public void cancelPendingSeek() {
        cancelPendingSeekEnd();
        reset();
    }

    private void cancelPendingSeekEnd() {
        if (pendingSeekEnd != null) {
            App.removeCallbacks(pendingSeekEnd);
            pendingSeekEnd = null;
        }
    }

    public void reset() {
        holdTime = 0;
    }

    public interface Listener {

        void onSeeking(long time);

        void onSeekEnd(long time);

        void onSpeedUp();

        void onSpeedEnd();

        void onKeyUp();

        void onKeyDown();

        void onKeyCenter();

        void onSingleTap();

        void onDoubleTap();

        /** A vertical swipe: one volume step up or down. */
        default void onSwipeVolume(boolean raise) {
        }
    }
}
