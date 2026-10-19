package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VodSkipPolicyTest {
    @Test
    public void openingAndEndingAlwaysLeaveAPlayableSecond() {
        VodSkipPolicy.Window window = VodSkipPolicy.normalize(173_000, 7_000, 180_000);
        assertEquals(173_000, window.openingMs());
        assertEquals(6_000, window.endingMs());
        assertFalse(VodSkipPolicy.shouldAdvance(173_000, 180_000, window.openingMs(), window.endingMs()));
        assertTrue(VodSkipPolicy.shouldAdvance(174_000, 180_000, window.openingMs(), window.endingMs()));
    }

    @Test
    public void invalidPersistedValuesAreClampedBeforeResume() {
        assertEquals(173_000, VodSkipPolicy.startPosition(179_000, 173_000, 7_000, 180_000));
        VodSkipPolicy.Window window = VodSkipPolicy.normalize(-10, 999_000, 180_000);
        assertEquals(0, window.openingMs());
        assertEquals(179_000, window.endingMs());
    }

    @Test
    public void unknownDurationOnlySanitizesNegativeValues() {
        VodSkipPolicy.Window window = VodSkipPolicy.normalize(-1, -2, 0);
        assertEquals(0, window.openingMs());
        assertEquals(0, window.endingMs());
        assertEquals(21_000, VodSkipPolicy.startPosition(21_000, -1, -2, 0));
        assertFalse(VodSkipPolicy.shouldAdvance(21_000, 0, 0, 0));
    }
}
