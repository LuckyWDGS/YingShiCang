package com.fongmi.android.tv.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackFrameDiagnosticsTest {

    @Test
    public void firstSampleEstablishesBaselineAndNextSampleReportsDeltas() {
        PlaybackFrameDiagnostics diagnostics = new PlaybackFrameDiagnostics();
        diagnostics.setPlaybackActive(true);
        PlaybackFrameDiagnostics.Delta first = diagnostics.sample(
                100, 10, 2, 1, 3, -40, 4);
        PlaybackFrameDiagnostics.Delta second = diagnostics.sample(
                350, 15, 5, 2, 5, -10, 6);

        assertTrue(first.baseline());
        assertFalse(first.counterReset());
        assertEquals(0, first.elapsedMs());
        assertEquals(5, second.renderedDelta());
        assertEquals(3, second.droppedDelta());
        assertEquals(1, second.skippedDelta());
        assertEquals(5, second.maxConsecutiveDropped());
        assertEquals(30, second.processingOffsetDeltaUs());
        assertEquals(2, second.processingFramesDelta());
        assertEquals(250, second.elapsedMs());
        // Tiny initialization/event windows still retain counts but cannot
        // establish a useful integer frame rate.
        assertFalse(second.rateEligible());
        assertEquals(-1, second.renderedPerSecond());
        assertEquals(-1, first.renderedPerSecond());
    }

    @Test
    public void counterResetProducesNonNegativeDeltasAndMarksReset() {
        PlaybackFrameDiagnostics diagnostics = new PlaybackFrameDiagnostics();
        diagnostics.setPlaybackActive(true);
        diagnostics.sample(100, 50, 8, 4, 4, 100, 10);
        PlaybackFrameDiagnostics.Delta reset = diagnostics.sample(
                2100, 3, 1, 0, 1, -20, 2);

        assertTrue(reset.counterReset());
        assertEquals(3, reset.renderedDelta());
        assertEquals(1, reset.droppedDelta());
        assertEquals(0, reset.skippedDelta());
        assertEquals(-20, reset.processingOffsetDeltaUs());
        assertEquals(2, reset.processingFramesDelta());
        assertFalse(reset.rateEligible());
        assertEquals(-1, reset.renderedPerSecond());
        assertEquals(25, diagnostics.sample(3100, 28, 1, 0, 1, -20, 27).renderedPerSecond());
    }

    @Test
    public void pauseAndResumeBetweenSamplesCannotDiluteTheNextRate() {
        PlaybackFrameDiagnostics diagnostics = new PlaybackFrameDiagnostics();
        diagnostics.setPlaybackActive(true);
        diagnostics.sample(100, 0, 0, 0, 0, 0, 0);
        assertEquals(25, diagnostics.sample(1100, 25, 1, 2, 1, 0, 25).renderedPerSecond());

        // Even without a diagnostic write while paused, state callbacks break
        // the old rate window. Keep every accumulated counter delta separately.
        diagnostics.setPlaybackActive(false);
        diagnostics.setPlaybackActive(true);
        PlaybackFrameDiagnostics.Delta resumed = diagnostics.sample(601100, 50, 2, 4, 1, 0, 50);
        assertEquals(600000, resumed.elapsedMs());
        assertEquals(25, resumed.renderedDelta());
        assertEquals(1, resumed.droppedDelta());
        assertEquals(2, resumed.skippedDelta());
        assertFalse(resumed.baseline());
        assertFalse(resumed.rateEligible());
        assertEquals(-1, resumed.renderedPerSecond());
        assertEquals("sample_unavailable", PlaybackFrameDiagnostics.rateStatus(25, 60, resumed.renderedPerSecond()));
        assertEquals(25, diagnostics.sample(602100, 75, 2, 4, 1, 0, 75).renderedPerSecond());
    }

    @Test
    public void pausedOrBufferingSamplesAndTheirResumeBoundaryStayUnclassified() {
        PlaybackFrameDiagnostics diagnostics = new PlaybackFrameDiagnostics();
        diagnostics.setPlaybackActive(true);
        diagnostics.sample(0, 0, 0, 0, 0, 0, 0);
        assertEquals(25, diagnostics.sample(1000, 25, 0, 0, 0, 0, 25).renderedPerSecond());

        // isPlaying is false for both a user pause and READY -> BUFFERING.
        diagnostics.setPlaybackActive(false);
        assertEquals(-1, diagnostics.sample(11000, 25, 0, 0, 0, 0, 25).renderedPerSecond());
        assertEquals(-1, diagnostics.sample(21000, 25, 0, 0, 0, 0, 25).renderedPerSecond());
        diagnostics.setPlaybackActive(true);
        assertEquals(-1, diagnostics.sample(22000, 50, 0, 0, 0, 0, 50).renderedPerSecond());
        assertEquals(25, diagnostics.sample(23000, 75, 0, 0, 0, 0, 75).renderedPerSecond());
    }

    @Test
    public void bufferedSeekOrOutputChangeInvalidatesOnlyTheRateWindow() {
        PlaybackFrameDiagnostics diagnostics = new PlaybackFrameDiagnostics();
        diagnostics.setPlaybackActive(true);
        diagnostics.sample(0, 100, 3, 5, 2, 0, 100);
        assertEquals(25, diagnostics.sample(1000, 125, 3, 5, 2, 0, 125).renderedPerSecond());
        diagnostics.invalidateRateWindow();
        PlaybackFrameDiagnostics.Delta seek = diagnostics.sample(11000, 126, 4, 6, 2, 0, 126);
        assertEquals(1, seek.renderedDelta());
        assertEquals(1, seek.droppedDelta());
        assertEquals(1, seek.skippedDelta());
        assertEquals(-1, seek.renderedPerSecond());
        assertEquals(25, diagnostics.sample(12000, 151, 4, 6, 2, 0, 151).renderedPerSecond());
    }

    @Test
    public void steadyPlaybackStillReportsRealFrameStarvation() {
        PlaybackFrameDiagnostics diagnostics = new PlaybackFrameDiagnostics();
        diagnostics.setPlaybackActive(true);
        diagnostics.sample(0, 100, 0, 0, 0, 0, 100);
        PlaybackFrameDiagnostics.Delta stalled = diagnostics.sample(10000, 100, 0, 250, 0, 0, 100);
        assertTrue(stalled.rateEligible());
        assertEquals(0, stalled.renderedPerSecond());
        assertEquals("decoder_or_surface_limited", PlaybackFrameDiagnostics.rateStatus(25, 60, stalled.renderedPerSecond()));
    }

    @Test
    public void negativeInputsAreClampedExceptProcessingOffset() {
        PlaybackFrameDiagnostics diagnostics = new PlaybackFrameDiagnostics();
        PlaybackFrameDiagnostics.Delta sample = diagnostics.sample(
                -1, -4, -3, -2, -1, -50, -6);

        assertEquals(0, sample.elapsedMs());
        assertEquals(0, sample.renderedDelta());
        assertEquals(0, sample.droppedDelta());
        assertEquals(0, sample.skippedDelta());
        assertEquals(0, sample.maxConsecutiveDropped());
        assertEquals(-50, sample.processingOffsetDeltaUs());
        assertEquals(0, sample.processingFramesDelta());
        assertEquals(-1, sample.renderedPerSecond());
    }

    @Test
    public void sixtyFpsCapabilityRequiresBothSourceAndDisplay() {
        assertTrue(PlaybackFrameDiagnostics.supports60Fps(60.0f, 60.0f));
        assertTrue(PlaybackFrameDiagnostics.supports60Fps(59.94f, 120.0f));
        assertFalse(PlaybackFrameDiagnostics.supports60Fps(30.0f, 120.0f));
        assertFalse(PlaybackFrameDiagnostics.supports60Fps(60.0f, 50.0f));
    }

    @Test
    public void rateStatusSeparatesDisplayAndDecoderLimits() {
        assertEquals("sample_unavailable", PlaybackFrameDiagnostics.rateStatus(25.0f, 60.0f, -1));
        assertEquals("display_limited", PlaybackFrameDiagnostics.rateStatus(60.0f, 50.0f, 60));
        assertEquals("decoder_or_surface_limited", PlaybackFrameDiagnostics.rateStatus(60.0f, 60.0f, 45));
        assertEquals("60fps_capable", PlaybackFrameDiagnostics.rateStatus(60.0f, 60.0f, 59));
        assertEquals("source_supported", PlaybackFrameDiagnostics.rateStatus(25.0f, 60.0f, 25));
        assertEquals("unknown_source", PlaybackFrameDiagnostics.rateStatus(-1.0f, 60.0f, 60));
    }
}
