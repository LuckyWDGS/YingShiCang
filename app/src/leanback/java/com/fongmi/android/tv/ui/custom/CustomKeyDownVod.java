package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.utils.KeyUtil;

public class CustomKeyDownVod extends GestureDetector.SimpleOnGestureListener {

    // A swipe across the whole screen moves two minutes, in either direction.
    private static final long SWIPE_FULL_WIDTH_MS = 120_000;

    private static final int SWIPE_NONE = 0, SWIPE_SEEK = 1, SWIPE_VOLUME = 2, SWIPE_HOLD = 3;
    // Holding a finger on the left or right third rewinds or fast-forwards: one step per tick, 10s steps,
    // 30s once held as long as the remote's long step (user 2026-11-02).
    private static final long HOLD_TICK_MS = 300;
    // Left/Right step 10s; held this long they step 30s, announced once per hold (user 2026-10-24).
    private static final long LONG_HOLD_MS = 2_000;
    // Presses closer together than this add up to one seek with the bubble kept up. At 250ms ordinary
    // taps committed one by one: the bubble hid, the buffering spinner flashed and the shown time lagged
    // behind the presses (user 2026-10-24: the time in the middle looked odd). 700ms made a single press
    // feel slow to land (user 2026-10-30).
    private static final long SEEK_COMMIT_MS = 400;

    private final GestureDetector detector;
    private final Listener listener;
    private final int touchSlop;
    private int swipe;
    private float downX, downY;
    private long swipeTime;
    private View touchView;
    private int holdDirection;
    private long holdStart;
    private long holdSeek;
    private final Runnable holdTick = this::holdTick;
    private boolean changeSpeed;
    private boolean full;
    private long holdTime;
    private boolean longStep;
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
     * center bubble follows the finger, the seek happens on release), a vertical swipe changes the volume,
     * a finger held on the left or right third rewinds or fast-forwards until it lifts;
     * taps keep toggling the controls and double taps pause.
     */
    public boolean onTouchEvent(View view, MotionEvent e) {
        if (!full) return false;
        int action = e.getActionMasked();
        if (swipe == SWIPE_HOLD) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) endHold(action == MotionEvent.ACTION_UP);
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            touchView = view;
            swipe = SWIPE_NONE;
            downX = e.getX();
            downY = e.getY();
        } else if (action == MotionEvent.ACTION_MOVE && swipe == SWIPE_NONE && e.getPointerCount() == 1) {
            startSwipe(view, e);
        }
        if (swipe == SWIPE_NONE) return detector.onTouchEvent(e);
        if (swipe == SWIPE_SEEK) seekSwipe(view, e, action);
        else if (action == MotionEvent.ACTION_MOVE) listener.onVolumeSwipe((downY - e.getY()) / Math.max(1, view.getHeight()));
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (swipe == SWIPE_VOLUME) listener.onVolumeSwipeEnd();
            swipe = SWIPE_NONE;
        }
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

    /** A finger held still on the left or right third: rewind or fast-forward until it lifts. */
    @Override
    public void onLongPress(@NonNull MotionEvent e) {
        View view = touchView;
        if (!full || swipe != SWIPE_NONE || view == null || e.getPointerCount() > 1) return;
        float third = view.getWidth() / 3f;
        if (e.getX() < third) holdDirection = -1;
        else if (e.getX() > third * 2) holdDirection = 1;
        else return;
        swipe = SWIPE_HOLD;
        commitPendingSeek();
        cancelView(view, e, false);
        holdSeek = 0;
        holdStart = e.getEventTime();
        holdTick();
    }

    private void holdTick() {
        long held = android.os.SystemClock.uptimeMillis() - holdStart;
        holdSeek += holdDirection * (held >= LONG_HOLD_MS ? Constant.INTERVAL_SEEK_LONG : Constant.INTERVAL_SEEK);
        listener.onSeeking(holdSeek);
        App.post(holdTick, HOLD_TICK_MS);
    }

    private void endHold(boolean commit) {
        App.removeCallbacks(holdTick);
        swipe = SWIPE_NONE;
        listener.onSeekEnd(commit ? holdSeek : 0);
        holdSeek = 0;
    }

    /** Past the touch slop the gesture is a swipe, not a tap: cancel the view's click and the detector's tap tracking. */
    private void startSwipe(View view, MotionEvent e) {
        float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
        if (dx > touchSlop && dx > dy * 1.5f) swipe = SWIPE_SEEK;
        else if (dy > touchSlop && dy > dx * 1.5f) swipe = SWIPE_VOLUME;
        else return;
        swipeTime = 0;
        commitPendingSeek();
        // Measure from here so the volume starts moving with the finger, not with a jump.
        if (swipe == SWIPE_VOLUME) downY = e.getY();
        cancelView(view, e, true);
    }

    private void cancelView(View view, MotionEvent e, boolean detectorToo) {
        MotionEvent cancel = MotionEvent.obtain(e);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        if (detectorToo) detector.onTouchEvent(cancel);
        view.onTouchEvent(cancel);
        cancel.recycle();
    }

    public void setFull(boolean full) {
        this.full = full;
        if (!full && swipe == SWIPE_HOLD) endHold(false);
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
            listener.onSeeking(subTime(event));
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isRightKey(event)) {
            cancelPendingSeekEnd();
            listener.onSeeking(addTime(event));
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

    private long addTime(KeyEvent event) {
        return holdTime = holdTime + seekStep(event);
    }

    private long subTime(KeyEvent event) {
        return holdTime = holdTime - seekStep(event);
    }

    private long seekStep(KeyEvent event) {
        if (event.getRepeatCount() == 0) longStep = false;
        boolean held = event.getEventTime() - event.getDownTime() >= LONG_HOLD_MS;
        if (held && !longStep) listener.onLongSeekStep(Constant.INTERVAL_SEEK_LONG);
        longStep = held;
        return held ? Constant.INTERVAL_SEEK_LONG : Constant.INTERVAL_SEEK;
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
        App.post(pendingSeekEnd, SEEK_COMMIT_MS);
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

        /** A held Left/Right now moves this far per step. */
        default void onLongSeekStep(long stepMs) {
        }

        /** A vertical swipe, as the share of the view's height moved since it started (up is positive). */
        default void onVolumeSwipe(float fraction) {
        }

        default void onVolumeSwipeEnd() {
        }
    }
}
