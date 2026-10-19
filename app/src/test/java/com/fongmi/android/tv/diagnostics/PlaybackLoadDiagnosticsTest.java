package com.fongmi.android.tv.diagnostics;

import org.junit.Test;

import java.io.IOException;

import static com.fongmi.android.tv.diagnostics.PlaybackLoadDiagnostics.Event.*;
import static org.junit.Assert.*;

public class PlaybackLoadDiagnosticsTest {

    private static String load(PlaybackLoadDiagnostics diagnostics, PlaybackLoadDiagnostics.Event event,
                               long now, long task, int retry) {
        return diagnostics.load(event, now, task, 100, 20, 1, 2, retry, false, -1, null);
    }

    @Test
    public void eventKindsAreIndependentlyLimitedAndBandwidthHasLongerWindow() {
        PlaybackLoadDiagnostics diagnostics = new PlaybackLoadDiagnostics();
        diagnostics.reset();
        assertNotNull(load(diagnostics, STARTED, 0, 1, 0));
        assertNull(load(diagnostics, STARTED, 999, 2, 0));
        assertNotNull(load(diagnostics, COMPLETED, 999, 1, -1));
        assertNotNull(load(diagnostics, STARTED, 1_000, 3, 0));
        assertNotNull(diagnostics.bandwidth(0, 100, 200, 16_000));
        assertNull(diagnostics.bandwidth(4_999, 100, 200, 16_000));
        assertTrue(diagnostics.bandwidth(5_000, 100, 200, 16_000).contains("suppressed=2"));
    }

    @Test
    public void ordinaryFloodCannotConsumeReservedErrorsAndTotalBudgetIsFinite() {
        PlaybackLoadDiagnostics diagnostics = new PlaybackLoadDiagnostics();
        diagnostics.reset();
        int ordinary = 0;
        for (int i = 0; i < PlaybackLoadDiagnostics.MAX_EVENTS + 10; i++) {
            if (load(diagnostics, COMPLETED, i * 1_000L, i, -1) != null) ordinary++;
        }
        assertEquals(PlaybackLoadDiagnostics.MAX_EVENTS - PlaybackLoadDiagnostics.RESERVED_ERROR_EVENTS, ordinary);
        int errors = 0;
        for (int i = 0; i < PlaybackLoadDiagnostics.MAX_EVENTS; i++) {
            if (load(diagnostics, ERROR, i * 500L, i, -1) != null) errors++;
        }
        assertEquals(PlaybackLoadDiagnostics.RESERVED_ERROR_EVENTS, errors);
        assertNull(load(diagnostics, ERROR, Long.MAX_VALUE, 500, -1));
        diagnostics.reset();
        assertTrue(load(diagnostics, STARTED, 0, 1, 0).contains("observation=1 suppressed=0"));
    }

    @Test
    public void suppressedStartsStillTrackRetriesAndTrackingIsBoundedAndCleared() {
        PlaybackLoadDiagnostics diagnostics = new PlaybackLoadDiagnostics();
        diagnostics.reset();
        load(diagnostics, STARTED, 0, 1, 0);
        assertNull(load(diagnostics, STARTED, 0, 1, 3));
        assertTrue(load(diagnostics, ERROR, 0, 1, -1).contains("retryCount=3"));
        for (int i = 2; i <= PlaybackLoadDiagnostics.MAX_TRACKED_TASKS + 1; i++) {
            load(diagnostics, STARTED, 0, i, i);
        }
        assertTrue(load(diagnostics, COMPLETED, 0, 1, -1).contains("retryCount=-1"));
        assertTrue(load(diagnostics, COMPLETED, 1_000, 2, -1).contains("retryCount=2"));
        assertTrue(load(diagnostics, ERROR, 1_000, 2, -1).contains("retryCount=-1"));
        diagnostics.stop();
        assertNull(load(diagnostics, STARTED, 5_000, 100, 9));
        assertNull(diagnostics.bandwidth(5_000, 100, 200, 16_000));
        diagnostics.reset();
        assertTrue(load(diagnostics, COMPLETED, 0, 3, -1).contains("retryCount=-1"));
    }

    @Test
    public void onlyErrorClassAndNumericStatusAreExposedAndInvalidNumbersAreNormalized() {
        PlaybackLoadDiagnostics diagnostics = new PlaybackLoadDiagnostics();
        diagnostics.reset();
        IOException error = new IOException("https://private.invalid/movie?token=secret Cookie: private") {
            @Override public String toString() { throw new AssertionError("must not format exceptions"); }
            @Override public String getMessage() { throw new AssertionError("must not read exception messages"); }
        };
        String sample = diagnostics.load(ERROR, -10, -4, -10, -20, -1, -1, -1, true, 503, error);
        assertTrue(sample.contains("taskId=-1 bytes=0 durationMs=0 retryCount=-1"));
        assertTrue(sample.contains("httpStatus=503 errorType=unknown"));
        assertFalse(sample.contains("private"));
        assertFalse(sample.contains("secret"));
        assertTrue(diagnostics.load(ERROR, 500, 1, 0, 0, 1, 2, -1, false, 999, new IOException("secret"))
                .contains("httpStatus=-1 errorType=IOException"));
    }
}
