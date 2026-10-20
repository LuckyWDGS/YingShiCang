package com.fongmi.android.tv.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CrashSummaryTest {

    @Test
    public void includesTopFramesAndCauseChain() {
        IllegalStateException error = new IllegalStateException("outer", new java.io.IOException("inner"));
        String summary = DiagnosticLog.crashSummary(error);
        assertTrue(summary.startsWith("java.lang.IllegalStateException: outer at "));
        assertTrue(summary.contains("CrashSummaryTest.includesTopFramesAndCauseChain"));
        assertTrue(summary.contains(" | caused by java.io.IOException: inner"));
    }

    @Test
    public void boundsFramesAndCauseDepth() {
        Throwable error = new RuntimeException("level0");
        for (int i = 1; i <= 5; i++) error = new RuntimeException("level" + i, error);
        StackTraceElement[] frames = new StackTraceElement[40];
        for (int i = 0; i < frames.length; i++) frames[i] = new StackTraceElement("C", "m" + i, "C.java", i);
        error.setStackTrace(frames);
        String summary = DiagnosticLog.crashSummary(error);
        assertEquals(2, summary.split(" \\| caused by ").length - 1);
        assertTrue(summary.contains("C.m11(C.java:11)"));
        assertTrue(!summary.contains("C.m12(C.java:12)"));
        assertTrue(summary.contains("...28 more"));
    }
}
