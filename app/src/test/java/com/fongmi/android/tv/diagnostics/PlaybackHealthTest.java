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

}
