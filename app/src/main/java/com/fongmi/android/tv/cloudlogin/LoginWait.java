package com.fongmi.android.tv.cloudlogin;

import android.os.SystemClock;

/**
 * Time a source spent waiting on its own login dialog (a QR code to scan) while resolving a play
 * address. Play-address deadlines exclude it: scanning took longer than the 30s budget, so the line
 * failed and playback switched to the next line under the QR code (user 2026-10-23). Cancelling the
 * dialog makes the source return, and only that failure moves to the next line.
 */
public final class LoginWait {

    private static long total;
    private static long since = -1;

    private LoginWait() {
    }

    /** Main thread, once per frame while an address resolves: whether a source dialog is showing. */
    static synchronized void update(boolean showing) {
        long now = SystemClock.elapsedRealtime();
        if (showing && since < 0) since = now;
        else if (!showing && since >= 0) {
            total += now - since;
            since = -1;
        }
    }

    /** Monotonic milliseconds spent in source login dialogs so far; deadlines add the growth since their start. */
    public static synchronized long pausedMillis() {
        return total + (since < 0 ? 0 : SystemClock.elapsedRealtime() - since);
    }
}
