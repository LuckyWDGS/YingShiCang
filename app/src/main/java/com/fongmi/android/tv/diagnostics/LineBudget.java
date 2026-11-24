package com.fongmi.android.tv.diagnostics;

/**
 * At most so many lines of one kind per window. A search over an aggregate configuration wrote one
 * source_search_failed line per failing source, 7930 of a 20559-line log (Xiaomi TV, 2026-11-23); the
 * totals are in search_round, and the first lines of each minute still name sources.
 */
public final class LineBudget {

    private final int lines;
    private final long windowMs;
    private long windowStart = Long.MIN_VALUE;
    private int used;

    public LineBudget(int lines, long windowMs) {
        this.lines = lines;
        this.windowMs = windowMs;
    }

    public synchronized boolean take(long now) {
        if (windowStart == Long.MIN_VALUE || now - windowStart >= windowMs || now < windowStart) {
            windowStart = now;
            used = 0;
        }
        return used++ < lines;
    }
}
