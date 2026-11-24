package com.fongmi.quickjs.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class ScriptCopiesTest {

    private static final String A = "https://scripts.test/a.js";
    private static final long DAY = ScriptCopies.FRESH_MS;

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void aCopyUnderADayOldIsUsedWithoutAskingAndAnOlderOneOnlyWhenTheAddressFails() throws Exception {
        ScriptCopies copies = new ScriptCopies(new File(temp.getRoot(), "js"));
        long saved = 1_800_000_000_000L;
        assertNull(copies.fresh(A, saved, 0));
        assertNull(copies.any(A));
        copies.save(A, "export default {}", saved);
        assertEquals("export default {}", copies.fresh(A, saved + DAY - 1, 0));
        assertNull("a day old: the address is asked first", copies.fresh(A, saved + DAY, 0));
        assertEquals("no answer: the old copy is still better than no source", "export default {}", copies.any(A));
        // Another address has its own copy.
        assertNull(copies.any(A + "?v=2"));
    }

    @Test public void aReloadByTheUserAsksAgainAndANewDownloadReplacesTheCopy() throws Exception {
        ScriptCopies copies = new ScriptCopies(temp.getRoot());
        long saved = 1_800_000_000_000L;
        copies.save(A, "old", saved);
        long reload = saved + 60_000;
        assertNull(copies.fresh(A, reload + 1, reload));
        copies.save(A, "new", reload + 5_000);
        assertEquals("new", copies.fresh(A, reload + 6_000, reload));
        // A clock set back does not make a copy from "the future" fresh.
        assertNull(copies.fresh(A, saved, 0));
        // Nothing empty is ever kept in place of a script.
        copies.save(A, "   ", reload + 9_000);
        assertEquals("new", copies.any(A));
        assertEquals("no temporary files stay behind", 1, temp.getRoot().listFiles().length);
    }

    @Test public void theOldestCopiesGoWhenThereAreTooMany() throws Exception {
        ScriptCopies copies = new ScriptCopies(temp.getRoot());
        long start = 1_800_000_000_000L;
        for (int i = 0; i < ScriptCopies.MAX_FILES + 25; i++) copies.save("https://scripts.test/" + i + ".js", "x" + i, start + i * 1000L);
        copies.prune();
        assertEquals(ScriptCopies.MAX_FILES, temp.getRoot().listFiles().length);
        assertNull(copies.any("https://scripts.test/0.js"));
        assertTrue(copies.any("https://scripts.test/" + (ScriptCopies.MAX_FILES + 24) + ".js").startsWith("x"));
    }
}
