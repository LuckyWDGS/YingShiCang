package com.fongmi.android.tv.playback;

import com.fongmi.android.tv.Constant;

/**
 * Seek increments used for dedicated media fast-forward/rewind keys.
 *
 * <p>The media key is consumed by the player activity and applied once on
 * {@code ACTION_UP}. Some remotes report a repeat count only on the down
 * event, so callers pass both the measured hold time and the event repeat
 * count. This keeps the short press at ten seconds and promotes a long or
 * repeated press to thirty seconds without depending on a particular
 * firmware's {@link android.view.KeyEvent} bookkeeping.</p>
 */
public final class MediaSeekPolicy {

    /** Duration at which a media seek key is considered held. */
    public static final long LONG_PRESS_TIMEOUT_MS = 500L;

    private MediaSeekPolicy() {
    }

    public static long intervalMs(long heldMs, int repeatCount) {
        long duration = Math.max(0L, heldMs);
        return repeatCount > 0 || duration >= LONG_PRESS_TIMEOUT_MS
                ? Constant.INTERVAL_SEEK_LONG
                : Constant.INTERVAL_SEEK;
    }
}
