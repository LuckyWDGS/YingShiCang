package com.fongmi.android.tv.player.mpv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GpuNextFailureDetectorTest {

    private static final String VO = "vo/gpu-next";
    private static final String PLACEBO = "vo/gpu-next/libplacebo";

    /** Feeds n frame failures, one every stepMs from startMs; returns how many times it triggered. */
    private static int feed(GpuNextFailureDetector detector, int n, long startMs, long stepMs) {
        int triggers = 0;
        for (int i = 0; i < n; i++) if (detector.onLog(i % 2 == 0 ? VO : PLACEBO, 20, i % 2 == 0 ? "Failed uploading frame!" : "Failed creating framebuffer: GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT", startMs + i * stepMs)) triggers++;
        return triggers;
    }

    @Test public void onlyPerFrameGpuNextErrorsCount() {
        assertTrue(GpuNextFailureDetector.isFrameFailure(VO, 20, "Failed updating frames!"));
        assertTrue(GpuNextFailureDetector.isFrameFailure(PLACEBO, 20, "Failed mapping frame id 3636 with PTS 92.600000"));
        // The one start-up error gpu-next always logs here, other outputs, and lower levels do not count.
        assertFalse(GpuNextFailureDetector.isFrameFailure(PLACEBO, 20, "libplacebo compiled without LittleCMS 2 support!"));
        assertFalse(GpuNextFailureDetector.isFrameFailure("vo/gpu", 20, "Failed uploading frame!"));
        assertFalse(GpuNextFailureDetector.isFrameFailure(VO, 50, "Failed uploading frame!"));
        assertFalse(GpuNextFailureDetector.isFrameFailure(null, 20, "Failed uploading frame!"));
    }

    @Test public void manyFailuresInAShortTimeTriggerOnce() {
        GpuNextFailureDetector detector = new GpuNextFailureDetector();
        // Five messages per frame at 60 fps: about 3 ms apart.
        assertEquals(1, feed(detector, 500, 0, 3));
        assertFalse(detector.onLog(VO, 20, "Failed uploading frame!", 2000));
    }

    @Test public void scatteredFailuresDoNotTrigger() {
        GpuNextFailureDetector detector = new GpuNextFailureDetector();
        // A failure every 100 ms (a surface change now and then) never reaches 100 within 3 s.
        assertEquals(0, feed(detector, 500, 0, 100));
    }

    @Test public void resetStartsAgainForANewPlayback() {
        GpuNextFailureDetector detector = new GpuNextFailureDetector();
        assertEquals(1, feed(detector, GpuNextFailureDetector.FAILURES, 0, 3));
        detector.reset();
        assertEquals(0, feed(detector, GpuNextFailureDetector.FAILURES - 1, 10_000, 3));
        assertEquals(1, feed(detector, 1, 10_400, 3));
    }
}
