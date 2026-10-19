package com.fongmi.android.tv.diagnostics;

/**
 * Computes bounded, privacy-safe deltas for decoder counters.
 *
 * <p>Media3 exposes decoder counters as cumulative values. Keeping the previous
 * sample here makes a short diagnostic window useful without retaining a media
 * item, URL, title or request headers. Counter resets are detected and reported
 * instead of producing misleading negative frame counts.</p>
 */
public final class PlaybackFrameDiagnostics {

    private static final long MIN_RATE_WINDOW_MS = 1000;

    /**
     * Returns whether a source can be presented at 60 fps on a display. This is
     * deliberately a capability check, not an interpolation claim: a 24/30 fps
     * source remains 24/30 fps even when the panel is 60 Hz or faster.
     */
    public static boolean supports60Fps(float sourceFps, float displayRefreshHz) {
        return isPositiveFinite(sourceFps) && sourceFps >= 59.0f
                && isPositiveFinite(displayRefreshHz) && displayRefreshHz >= 59.0f;
    }

    /**
     * Classifies the first likely limit seen in a diagnostic sample. The
     * rendered rate is a decoder-counter rate, so this method never labels a
     * sample as surface-perfect; Surface/Flinger evidence is still required for
     * a final presentation-rate claim.
     */
    public static String rateStatus(float sourceFps, float displayRefreshHz,
                                    long renderedPerSecond) {
        if (!isPositiveFinite(sourceFps)) return "unknown_source";
        if (renderedPerSecond < 0) return "sample_unavailable";
        if (isPositiveFinite(displayRefreshHz) && displayRefreshHz + 1.0f < sourceFps) {
            return "display_limited";
        }
        if (renderedPerSecond >= 0) {
            float tolerance = Math.max(2.0f, sourceFps * 0.05f);
            if (renderedPerSecond + tolerance < sourceFps) return "decoder_or_surface_limited";
        }
        return supports60Fps(sourceFps, displayRefreshHz) ? "60fps_capable" : "source_supported";
    }

    public record Delta(long elapsedMs, long renderedDelta, long droppedDelta, long skippedDelta,
                        long maxConsecutiveDropped, long processingOffsetDeltaUs,
                        long processingFramesDelta, boolean baseline, boolean counterReset,
                        boolean rateEligible) {
        /**
         * Returns the decoder's observed rendered-frame rate for this sample window.
         * A negative value means that the window is too short, inactive, interrupted
         * by a seek/state change, or contains a counter reset. This is a decoder-counter
         * rate, not a Surface presentation-rate measurement.
         */
        public long renderedPerSecond() {
            if (!rateEligible || elapsedMs < MIN_RATE_WINDOW_MS) return -1;
            if (renderedDelta <= 0) return 0;
            if (renderedDelta > Long.MAX_VALUE / 1000) return Long.MAX_VALUE;
            return (renderedDelta * 1000) / elapsedMs;
        }

        public String toLogString() {
            return "frameBaseline=" + baseline
                    + " frameCounterReset=" + counterReset
                    + " frameRateEligible=" + rateEligible
                    + " frameElapsedMs=" + elapsedMs
                    + " renderedDelta=" + renderedDelta
                    + " renderedPerSec=" + renderedPerSecond()
                    + " droppedDelta=" + droppedDelta
                    + " skippedDelta=" + skippedDelta
                    + " maxConsecutiveDropped=" + maxConsecutiveDropped
                    + " processingFramesDelta=" + processingFramesDelta
                    + " processingOffsetDeltaUs=" + processingOffsetDeltaUs;
        }
    }

    private boolean initialized;
    private boolean playbackActive;
    private boolean rateWindowInterrupted = true;
    private long sampleAtMs;
    private long rendered;
    private long dropped;
    private long skipped;
    private long processingOffsetUs;
    private long processingFrames;

    public void reset() {
        initialized = false;
        playbackActive = false;
        rateWindowInterrupted = true;
        sampleAtMs = rendered = dropped = skipped = processingOffsetUs = processingFrames = 0;
    }

    /** State changes invalidate only the rate window; cumulative counter deltas remain intact. */
    public void setPlaybackActive(boolean active) {
        if (playbackActive != active) invalidateRateWindow();
        playbackActive = active;
    }

    /** Also used for buffered seeks and Surface changes that need not change isPlaying. */
    public void invalidateRateWindow() {
        rateWindowInterrupted = true;
    }

    public Delta sample(long nowMs, long renderedOutputBuffers, long droppedBuffers,
                        long skippedOutputBuffers, long maxConsecutiveDroppedBuffers,
                        long totalProcessingOffsetUs, long processingOffsetFrames) {
        long currentRendered = nonNegative(renderedOutputBuffers);
        long currentDropped = nonNegative(droppedBuffers);
        long currentSkipped = nonNegative(skippedOutputBuffers);
        long currentProcessingFrames = nonNegative(processingOffsetFrames);
        long currentMaxConsecutiveDropped = nonNegative(maxConsecutiveDroppedBuffers);
        long currentOffset = totalProcessingOffsetUs;
        long currentAtMs = nonNegative(nowMs);

        if (!initialized) {
            initialized = true;
            rateWindowInterrupted = !playbackActive;
            sampleAtMs = currentAtMs;
            rendered = currentRendered;
            dropped = currentDropped;
            skipped = currentSkipped;
            processingOffsetUs = currentOffset;
            processingFrames = currentProcessingFrames;
            return new Delta(0, currentRendered, currentDropped, currentSkipped,
                    currentMaxConsecutiveDropped, currentOffset, currentProcessingFrames,
                    true, false, false);
        }

        long elapsed = Math.max(0, currentAtMs - sampleAtMs);
        // DecoderCounters can be reset when a renderer is recreated. Do not turn
        // that reset into a negative delta; expose the new values as this sample.
        boolean counterReset = currentRendered < rendered
                || currentDropped < dropped
                || currentSkipped < skipped
                || currentProcessingFrames < processingFrames;
        long renderedDelta = counterReset ? currentRendered : currentRendered - rendered;
        long droppedDelta = counterReset ? currentDropped : currentDropped - dropped;
        long skippedDelta = counterReset ? currentSkipped : currentSkipped - skipped;
        long processingFramesDelta = counterReset
                ? currentProcessingFrames : currentProcessingFrames - processingFrames;
        long offsetDelta = counterReset ? currentOffset : currentOffset - processingOffsetUs;
        boolean rateEligible = playbackActive && !rateWindowInterrupted && !counterReset
                && elapsed >= MIN_RATE_WINDOW_MS;

        sampleAtMs = currentAtMs;
        rateWindowInterrupted = !playbackActive;
        rendered = currentRendered;
        dropped = currentDropped;
        skipped = currentSkipped;
        processingOffsetUs = currentOffset;
        processingFrames = currentProcessingFrames;
        return new Delta(elapsed, renderedDelta, droppedDelta, skippedDelta,
                currentMaxConsecutiveDropped, offsetDelta, processingFramesDelta,
                false, counterReset, rateEligible);
    }

    private static long nonNegative(long value) {
        return Math.max(0, value);
    }

    private static boolean isPositiveFinite(float value) {
        return Float.isFinite(value) && value > 0;
    }
}
