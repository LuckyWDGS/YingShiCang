package com.fongmi.android.tv.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic, context-bound ranking for source/line candidates. */
public final class SourceQuality {

    private SourceQuality() {
    }

    public static final class Sample {
        public final String id;
        public final boolean playable;
        public final long startupMs;
        public final long bufferedMs;
        public final long playedMs;
        public final long bytes;
        public final long elapsedMs;
        public final int sampleCount;

        public Sample(String id, boolean playable, long startupMs, long bufferedMs, long playedMs,
                      long bytes, long elapsedMs, int sampleCount) {
            this.id = id;
            this.playable = playable;
            this.startupMs = startupMs;
            this.bufferedMs = bufferedMs;
            this.playedMs = playedMs;
            this.bytes = bytes;
            this.elapsedMs = elapsedMs;
            this.sampleCount = sampleCount;
        }
    }

    public static double score(Sample sample) {
        if (!sample.playable || sample.sampleCount <= 0) return 0.0;
        double startup = 1.0 - Math.min(Math.max(0, sample.startupMs) / 15_000.0, 1.0);
        double rebuffer = sample.playedMs <= 0 ? 0.0 : 1.0 - Math.min(Math.max(0, sample.bufferedMs) / (double) sample.playedMs, 1.0);
        double throughput = sample.elapsedMs <= 0 ? 0.0 : Math.min((sample.bytes * 8.0 / sample.elapsedMs) / 10_000.0, 1.0);
        double confidence = Math.min(sample.sampleCount / 5.0, 1.0);
        return (0.40 * rebuffer + 0.25 * throughput + 0.20 * startup + 0.15 * confidence) * 100.0;
    }

    public static List<Sample> rank(List<Sample> samples) {
        List<Sample> result = new ArrayList<>(samples);
        result.sort(Comparator.comparingDouble(SourceQuality::score).reversed());
        return result;
    }
}
