package com.fongmi.android.tv;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdaterMirrorSpeedTest {

    @Test
    public void slowStartIsNotJudgedBeforeTheProbeWindow() {
        assertFalse(Updater.mirrorTooSlow(Updater.MIRROR_PROBE_MS - 1, 0));
    }

    @Test
    public void crawlingMirrorGivesWayAfterTheProbeWindow() {
        // 68 KB/s, as measured on a TV's poor route to the mirror.
        assertTrue(Updater.mirrorTooSlow(20_000, 68L * 1024 * 20));
        assertTrue(Updater.mirrorTooSlow(154_000, 10_534_619));
    }

    @Test
    public void usableMirrorKeepsDownloading() {
        assertFalse(Updater.mirrorTooSlow(20_000, Updater.MIRROR_MIN_BYTES_PER_SECOND * 20));
        assertFalse(Updater.mirrorTooSlow(27_000, 84_665_061));
    }
}
