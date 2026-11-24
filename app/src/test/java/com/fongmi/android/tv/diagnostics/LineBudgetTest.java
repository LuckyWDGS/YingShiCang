package com.fongmi.android.tv.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LineBudgetTest {

    @Test public void onlyTheFirstLinesOfEachWindowAreWritten() {
        LineBudget budget = new LineBudget(20, 60_000);
        int written = 0;
        // One search over an aggregate configuration: 700 failures in a few seconds.
        for (int i = 0; i < 700; i++) if (budget.take(1_000 + i * 5)) written++;
        assertEquals(20, written);
        assertFalse(budget.take(60_999));
        assertTrue(budget.take(61_000));
        // A clock set back opens a new window rather than silencing the log.
        for (int i = 0; i < 30; i++) budget.take(61_000);
        assertTrue(budget.take(5));
    }
}
