package com.fongmi.android.tv.diagnostics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MemorySignalTest {

    @Test public void aNewLevelIsLoggedAtOnceTheSameOneEveryThirtySeconds() {
        assertTrue(MemorySignal.shouldLog("5", 1_000));
        assertFalse(MemorySignal.shouldLog("5", 20_000));
        assertTrue(MemorySignal.shouldLog("15", 20_500));
        assertTrue(MemorySignal.shouldLog("5", 21_000));
        assertFalse(MemorySignal.shouldLog("5", 50_999));
        assertTrue(MemorySignal.shouldLog("5", 51_000));
    }
}
