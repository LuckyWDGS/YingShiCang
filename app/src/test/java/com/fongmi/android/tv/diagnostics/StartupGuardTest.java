package com.fongmi.android.tv.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StartupGuardTest {

    /** What the next process reads: the state as the last one left it, then its own start. */
    private static StartupGuard.State next(StartupGuard.State last) {
        StartupGuard.State state = StartupGuard.State.parse(last.encode());
        state.started();
        return state;
    }

    @Test public void twoLaunchesThatDieWhileLoadingPauseTheThird() {
        StartupGuard.State state = next(new StartupGuard.State());
        assertFalse(state.paused());
        state.loading(true);
        // The plugin kills the process a second later: nothing below runs.
        state = next(state);
        assertEquals(1, state.deaths);
        assertFalse(state.paused());
        state.loading(true);
        state = next(state);
        assertTrue(state.paused());
        // Paused launches load nothing, so they neither count nor clear: it stays paused.
        state = next(state);
        assertTrue(state.paused());
        assertEquals(2, state.deaths);
    }

    @Test public void aLoadTheUserAsksForStartsAgainAndCountsAgain() {
        StartupGuard.State state = StartupGuard.State.parse("2 0");
        assertTrue(state.paused());
        state.loading(false);
        assertFalse(state.paused());
        // It dies again: one more such launch and it is paused again.
        state = next(state);
        assertEquals(1, state.deaths);
        state.loading(true);
        state = next(state);
        assertTrue(state.paused());
    }

    @Test public void survivingFailingOrLeavingIsNotACrash() {
        StartupGuard.State state = StartupGuard.State.parse("1 0");
        state.loading(true);
        state.stable();
        assertEquals("0 0", state.encode());
        // An ordinary load failure (no network) and going to the background keep the count but do not add to it.
        state = StartupGuard.State.parse("1 0");
        state.loading(true);
        state.disarm();
        assertEquals(1, next(state).deaths);
        // A normal exit long after start-up.
        state = new StartupGuard.State();
        state.loading(true);
        state.stable();
        assertEquals(0, next(state).deaths);
    }

    @Test public void aDamagedFileStartsClean() {
        for (String text : new String[]{null, "", "x", "1", "a b", "3 1 9"}) assertEquals("0 0", StartupGuard.State.parse(text).encode());
        assertEquals("99 1", StartupGuard.State.parse("5000 1").encode());
        assertEquals("0 0", StartupGuard.State.parse("-4 0").encode());
    }
}
