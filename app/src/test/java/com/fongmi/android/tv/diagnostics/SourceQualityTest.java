package com.fongmi.android.tv.diagnostics;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SourceQualityTest {

    @Test
    public void failedSourceIsNeverRankedAbovePlayableSource() {
        SourceQuality.Sample failed = new SourceQuality.Sample("failed", false, 100, 0, 10_000, 1_000_000, 1_000, 5);
        SourceQuality.Sample stable = new SourceQuality.Sample("stable", true, 800, 0, 10_000, 4_000_000, 1_000, 5);
        List<SourceQuality.Sample> ranked = SourceQuality.rank(List.of(failed, stable));
        assertEquals("stable", ranked.get(0).id);
        assertTrue(SourceQuality.score(failed) == 0.0);
    }

    @Test
    public void repeatedBufferingLowersScore() {
        SourceQuality.Sample smooth = new SourceQuality.Sample("smooth", true, 800, 0, 20_000, 4_000_000, 1_000, 5);
        SourceQuality.Sample stalled = new SourceQuality.Sample("stalled", true, 800, 12_000, 20_000, 4_000_000, 1_000, 5);
        assertTrue(SourceQuality.score(smooth) > SourceQuality.score(stalled));
    }
}
