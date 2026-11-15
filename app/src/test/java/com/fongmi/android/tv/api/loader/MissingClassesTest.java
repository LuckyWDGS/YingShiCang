package com.fongmi.android.tv.api.loader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MissingClassesTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    @Test
    public void aMissingClassIsKnownForThatPackageAcrossLaunches() {
        MissingClasses first = new MissingClasses("");
        assertFalse(first.tracks("csp_XPathGuard"));
        assertTrue(first.remember("csp_XPathGuard", "aaa", 1_000));
        assertFalse(first.remember("csp_XPathGuard", "aaa", 2_000));
        MissingClasses next = new MissingClasses(first.save());
        assertTrue(next.tracks("csp_XPathGuard"));
        assertTrue(next.known("csp_XPathGuard", "aaa", 5_000));
        assertFalse(next.tracks("csp_WoGGGuard"));
        assertFalse(next.known("csp_WoGGGuard", "aaa", 5_000));
    }

    @Test
    public void aNewPackageIsAskedAgain() {
        MissingClasses kept = new MissingClasses("");
        kept.remember("csp_XPathGuard", "aaa", 1_000);
        assertFalse(kept.known("csp_XPathGuard", "bbb", 2_000));
        // Missing there too: the newer package replaces the older one.
        assertTrue(kept.remember("csp_XPathGuard", "bbb", 2_000));
        assertFalse(kept.known("csp_XPathGuard", "aaa", 3_000));
        assertTrue(kept.known("csp_XPathGuard", "bbb", 3_000));
        // Present in a later package: nothing is kept.
        assertTrue(kept.forget("csp_XPathGuard"));
        assertFalse(kept.forget("csp_XPathGuard"));
        assertEquals("", kept.save());
    }

    @Test
    public void afterAMonthItIsAskedAgain() {
        MissingClasses kept = new MissingClasses("");
        kept.remember("csp_XPathGuard", "aaa", 1_000);
        assertTrue(kept.known("csp_XPathGuard", "aaa", 1_000 + 29 * DAY));
        assertFalse(kept.known("csp_XPathGuard", "aaa", 1_000 + 31 * DAY));
        // A clock set back does not make it known for longer.
        assertFalse(kept.known("csp_XPathGuard", "aaa", 500));
        assertTrue(kept.remember("csp_XPathGuard", "aaa", 1_000 + 31 * DAY));
        assertTrue(kept.known("csp_XPathGuard", "aaa", 1_000 + 32 * DAY));
    }

    @Test
    public void nothingIsKeptWithoutAPackageHash() {
        MissingClasses kept = new MissingClasses("");
        assertFalse(kept.remember("csp_XPathGuard", "", 1_000));
        assertFalse(kept.remember("csp_XPathGuard", null, 1_000));
        assertFalse(kept.remember("", "aaa", 1_000));
        assertFalse(kept.known("csp_XPathGuard", "", 1_000));
        assertEquals("", kept.save());
    }

    @Test
    public void damagedOrCrowdedSavedTextIsHarmless() {
        MissingClasses kept = new MissingClasses("csp_A\taaa\t10\nbroken\ncsp_B\tbbb\tnot-a-number\n\tccc\t5\ncsp_C\t\t5");
        assertTrue(kept.known("csp_A", "aaa", 20));
        assertFalse(kept.tracks("csp_B"));
        assertFalse(kept.tracks("csp_C"));
        assertFalse(new MissingClasses(null).tracks("csp_A"));
        for (int i = 0; i < 40; i++) kept.remember("csp_N" + i, "hash", 100 + i);
        assertEquals(16, kept.save().split("\n").length);
        assertFalse(kept.tracks("csp_A"));
        assertTrue(kept.known("csp_N39", "hash", 200));
    }
}
