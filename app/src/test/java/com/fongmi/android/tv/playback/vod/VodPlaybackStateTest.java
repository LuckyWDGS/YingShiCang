package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.bean.Vod;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class VodPlaybackStateTest {
    @Test public void slowSearchBatchesRetainPreviousCandidatesAndDifferentSourceIds() {
        VodPlaybackState state = new VodPlaybackState();
        Vod first = item("a", "same"), second = item("b", "same");
        state.appendSources(List.of(first));
        state.appendSources(List.of(item("a", "same"), second));
        state.appendSources(List.of());
        assertEquals(2, state.getSources().size());
        assertSame(first, state.removeFirstSource());
        assertSame(second, state.removeFirstSource());
    }
    @Test public void failedCandidateIsScopedToSourceAndCanBeRetriedInNewSession() {
        VodPlaybackState state = new VodPlaybackState();
        state.addFailedId("a", "same");
        assertTrue(state.hasFailedId("a", "same"));
        assertFalse(state.hasFailedId("b", "same"));
        state.setFallbackSearchStarted(true);
        state.reset();
        assertFalse(state.hasFailedId("a", "same"));
        assertFalse(state.hasFallbackSearchStarted());
    }
    private static Vod item(String site, String id) {
        return new Vod() {
            @Override public String getSiteKey() { return site; }
            @Override public String getId() { return id; }
        };
    }
}
