package com.fongmi.android.tv.discovery;

import org.junit.Test;

import static org.junit.Assert.*;

public class SourceDiscoveryRetryBudgetTest {
    @Test public void manualRetryAfterExhaustionStartsTwoFreshAutomaticRetries() {
        var budget = new SourceDiscoveryRunner.RetryBudget();
        assertTrue(budget.available());
        budget.start(false);
        assertEquals(1, budget.attempts());
        assertTrue(budget.available());
        budget.start(false);
        assertEquals(2, budget.attempts());
        assertFalse(budget.available());

        budget.start(true);
        assertEquals("The explicit request is not an automatic retry", 0, budget.attempts());
        assertTrue(budget.available());
        budget.start(false);
        assertTrue(budget.available());
        budget.start(false);
        assertFalse("Recovery must not create an unbounded retry loop", budget.available());
    }

    @Test public void successRearmsTheNextFailedPageWithoutAffectingOtherSources() {
        var failedSource = new SourceDiscoveryRunner.RetryBudget();
        var healthySource = new SourceDiscoveryRunner.RetryBudget();
        failedSource.start(false);
        failedSource.start(false);
        healthySource.start(false);

        failedSource.start(true);
        assertEquals(1, healthySource.attempts());
        failedSource.start(false);
        failedSource.succeeded();
        assertEquals(0, failedSource.attempts());
        failedSource.start(false);
        assertTrue("A subsequent raw page has its own automatic retry budget", failedSource.available());
    }
}
