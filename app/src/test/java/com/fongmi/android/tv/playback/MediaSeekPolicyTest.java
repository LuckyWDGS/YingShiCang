package com.fongmi.android.tv.playback;

import com.fongmi.android.tv.Constant;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MediaSeekPolicyTest {

    @Test
    public void shortPressUsesTenSeconds() {
        assertEquals(Constant.INTERVAL_SEEK, MediaSeekPolicy.intervalMs(499, 0));
        assertEquals(Constant.INTERVAL_SEEK, MediaSeekPolicy.intervalMs(-1, 0));
    }

    @Test
    public void longPressUsesThirtySecondsAtThreshold() {
        assertEquals(Constant.INTERVAL_SEEK_LONG,
                MediaSeekPolicy.intervalMs(MediaSeekPolicy.LONG_PRESS_TIMEOUT_MS, 0));
        assertEquals(Constant.INTERVAL_SEEK_LONG, MediaSeekPolicy.intervalMs(0, 1));
    }
}
