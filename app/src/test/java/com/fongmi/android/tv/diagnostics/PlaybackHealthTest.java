package com.fongmi.android.tv.diagnostics;

import com.fongmi.android.tv.player.media.PlaySpec;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackHealthTest {

    @Test
    public void blankStreamDoesNotBecomeAnActivePlaybackSession() {
        PlaybackHealth health = new PlaybackHealth();
        health.start(PlaySpec.from("blank", "  ", Map.of(), null));

        assertFalse(PlaybackHealth.active());
        assertTrue(PlaybackHealth.lastStream() == null
                || !"  ".equals(PlaybackHealth.lastStream().url()));
    }

    @Test
    public void anErrorThePlayerRecoveredFromKeepsTheSession() {
        PlaybackHealth health = new PlaybackHealth(() -> 1_000);
        health.start(PlaySpec.from("live", "http://127.0.0.1/live.m3u8", Map.of(), null));
        health.state(3);
        health.recovered("ERROR_CODE_BEHIND_LIVE_WINDOW");
        assertTrue(PlaybackHealth.active());
        health.error("ERROR_CODE_IO_UNSPECIFIED");
        assertFalse(PlaybackHealth.active());
    }

}
