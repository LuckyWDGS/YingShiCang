package com.fongmi.android.tv.player.exo;

import org.junit.Test;
import static org.junit.Assert.*;

public class FormatRecoveryTest {
    @Test public void invalidMediaCannotLoopBetweenParsers() {
        FormatRecovery recovery = new FormatRecovery();
        assertTrue(recovery.allow(null, "hls"));
        assertTrue(recovery.allow("hls", "octet-stream"));
        assertFalse(recovery.allow("octet-stream", "hls"));
        assertFalse(recovery.allow("octet-stream", "another"));
        recovery.reset();
        assertTrue(recovery.allow(null, "hls"));
    }
    @Test public void sameOrUnknownFormatDoesNotRetry() {
        FormatRecovery recovery = new FormatRecovery();
        assertFalse(recovery.allow("hls", "hls"));
        assertFalse(recovery.allow("hls", null));
    }
}
