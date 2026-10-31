package com.fongmi.android.tv.playback.vod;

/**
 * In-place retries left for the playing line before falling back to the next one (user 2026-10-31:
 * retry twice, then switch). A line that then plays for a minute gets its retries back, so rare
 * hiccups in a long film are each retried too.
 */
final class VodRetryBudget {

    static final int RETRIES = 2;
    static final long REFILL_MS = 60_000;

    private VodPlayRequest request;
    private int used;
    // First READY since the last failure, -1 until then.
    private long readyAt = -1;

    /** Whether this failure of {@code playing} may retry the same line; counts the retry when it may. */
    boolean take(VodPlayRequest playing, long now) {
        if (playing == null) return false;
        if (!playing.matches(request) || (readyAt >= 0 && now - readyAt >= REFILL_MS)) {
            request = playing;
            used = 0;
        }
        readyAt = -1;
        if (used >= RETRIES) return false;
        used++;
        return true;
    }

    void ready(long now) {
        if (readyAt < 0) readyAt = now;
    }
}
