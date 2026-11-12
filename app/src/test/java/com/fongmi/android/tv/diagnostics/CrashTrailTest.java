package com.fongmi.android.tv.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;

public class CrashTrailTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static byte[] stored(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8), file = new byte[CrashTrail.SIZE];
        file[0] = (byte) (bytes.length >> 8);
        file[1] = (byte) bytes.length;
        System.arraycopy(bytes, 0, file, 2, bytes.length);
        return file;
    }

    @Test
    public void callsStillRunningAreNamedWithTheirAgeWhenTheProcessDied() {
        CrashTrail.State state = new CrashTrail.State();
        state.screen("VideoActivity");
        long search = state.begin("csp_AiNewMuOuGuard.search", 1_000);
        long player = state.begin("csp_AiNewMuOuGuard.player", 9_000);
        state.end(search);
        state.begin("proxy.media", 9_500);
        String left = CrashTrail.decode(stored(state.encode(9_600)));
        assertEquals("idle=400ms screen=VideoActivity calls=2 csp_AiNewMuOuGuard.player:1000ms,proxy.media:500ms",
                CrashTrail.describe(left, 10_000));
        assertTrue(state.end(player));
        assertFalse(state.end(player));
    }

    @Test
    public void anIdleScreenIsStillAnAnswer() {
        CrashTrail.State state = new CrashTrail.State();
        state.screen("HomeActivity");
        assertEquals("idle=67000ms screen=HomeActivity calls=0", CrashTrail.describe(state.encode(1_000), 68_000));
        assertFalse(state.screen("HomeActivity"));
    }

    @Test
    public void onlyTheNewestCallsAreListedButAllAreCounted() {
        CrashTrail.State state = new CrashTrail.State();
        for (int i = 0; i < 70; i++) state.begin("csp_S" + i + ".search", 1_000 + i);
        String encoded = state.encode(2_000);
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length < CrashTrail.SIZE - 2);
        assertTrue(encoded.startsWith("at=2000 screen= calls=70 csp_S62.search@1062,"));
        assertTrue(encoded.endsWith(",csp_S69.search@1069"));
    }

    @Test
    public void namesCarryNoAddressesOrTitles() {
        CrashTrail.State state = new CrashTrail.State();
        state.begin("https://example.com/a b?x=1 片名", 5);
        assertEquals("at=9 screen= calls=1 httpsexample.comabx1@5", state.encode(9));
    }

    @Test
    public void aTornOrEmptyFileSaysNothing() {
        assertEquals("", CrashTrail.decode(new byte[CrashTrail.SIZE]));
        assertEquals("", CrashTrail.decode(stored("at=12 scr")));
        byte[] wrongLength = stored("at=1 screen=A calls=0");
        wrongLength[0] = 0x7f;
        assertEquals("", CrashTrail.decode(wrongLength));
        assertEquals("", CrashTrail.describe("", 5));
        // A clock set back between the write and the exit record gives no negative ages.
        assertEquals("idle=0ms screen=A calls=1 x:0ms", CrashTrail.describe("at=900 screen=A calls=1 x@800", 100));
        // No exit record (before Android 11): ages count to the last change.
        assertEquals("idle=0ms screen=A calls=1 x:100ms", CrashTrail.describe("at=900 screen=A calls=1 x@800", -1));
    }

    @Test
    public void theNextProcessReadsWhatTheLastOneLeft() throws Exception {
        java.io.File file = folder.newFile("trail");
        java.nio.file.Files.write(file.toPath(), stored("at=1000 screen=LiveActivity calls=1 csp_A.detail@400"));
        CrashTrail.install(file);
        assertEquals("idle=500ms screen=LiveActivity calls=1 csp_A.detail:1100ms", CrashTrail.left(1_500));
        assertTrue(CrashTrail.leftCalls());
        long token = CrashTrail.begin("csp_B.player");
        byte[] now = java.nio.file.Files.readAllBytes(file.toPath());
        assertTrue(CrashTrail.decode(now).contains(" calls=1 csp_B.player@"));
        CrashTrail.end(token);
        assertTrue(CrashTrail.decode(java.nio.file.Files.readAllBytes(file.toPath())).endsWith(" calls=0"));
    }
}
