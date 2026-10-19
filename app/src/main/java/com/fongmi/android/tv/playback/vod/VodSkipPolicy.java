package com.fongmi.android.tv.playback.vod;

/** Keeps the user selected opening/ending skips inside a playable interval. */
public final class VodSkipPolicy {

    private static final long MIN_PLAYABLE_GAP_MS = 1_000;

    private VodSkipPolicy() {
    }

    public record Window(long openingMs, long endingMs) {
    }

    public static Window normalize(long openingMs, long endingMs, long durationMs) {
        long opening = Math.max(0, openingMs);
        long ending = Math.max(0, endingMs);
        if (durationMs <= 0) return new Window(opening, ending);

        long gap = Math.min(MIN_PLAYABLE_GAP_MS, Math.max(1, durationMs));
        long maxSkip = Math.max(0, durationMs - gap);
        opening = Math.min(opening, maxSkip);
        ending = Math.min(ending, maxSkip);
        if (opening + ending >= durationMs) {
            // Preserve the opening anchor when both values compete for the
            // final playable interval. This keeps a newly chosen opening from
            // being silently moved by an older ending value.
            ending = Math.min(ending, Math.max(0, durationMs - opening - gap));
        }
        return new Window(opening, ending);
    }

    public static long startPosition(long positionMs, long openingMs, long endingMs, long durationMs) {
        Window window = normalize(openingMs, endingMs, durationMs);
        long position = Math.max(0, positionMs);
        if (durationMs <= 0) return Math.max(position, window.openingMs());
        long gap = Math.min(MIN_PLAYABLE_GAP_MS, Math.max(1, durationMs));
        long playableEnd = Math.max(window.openingMs(), durationMs - window.endingMs() - gap);
        return Math.min(Math.max(position, window.openingMs()), playableEnd);
    }

    public static boolean shouldAdvance(long positionMs, long durationMs, long openingMs, long endingMs) {
        if (durationMs <= 0 || positionMs < 0) return false;
        Window window = normalize(openingMs, endingMs, durationMs);
        return window.endingMs() > 0 && positionMs >= durationMs - window.endingMs();
    }
}
