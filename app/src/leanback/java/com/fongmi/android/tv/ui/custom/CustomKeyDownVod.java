package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.playback.MediaSeekPolicy;
import com.fongmi.android.tv.utils.KeyUtil;

public class CustomKeyDownVod extends GestureDetector.SimpleOnGestureListener {

    private final GestureDetector detector;
    private final Listener listener;
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
    }

    public boolean onTouchEvent(MotionEvent e) {
        if (!full) return false;
        return detector.onTouchEvent(e);
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
    }
}
