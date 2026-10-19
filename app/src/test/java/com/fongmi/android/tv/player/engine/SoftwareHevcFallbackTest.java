package com.fongmi.android.tv.player.engine;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SoftwareHevcFallbackTest {

    @Test
    public void targetRequiresFfmpegHevcAnd4k() {
        assertTrue(SoftwareHevcFallback.isTarget("ffmpegLavc63.3.100-hevc", "video/hevc", 3840, 2160));
        assertTrue(SoftwareHevcFallback.isTarget("ffmpegLavc63.3.100-hevc", "video/hevc", 2160, 3840));
        assertTrue(SoftwareHevcFallback.isTarget("OMX.google.hevc.decoder", "video/hevc", 3840, 2160));
        assertTrue(SoftwareHevcFallback.isTarget("c2.android.hevc.decoder", "video/hevc", 3840, 2160));
        assertFalse(SoftwareHevcFallback.isTarget("ffmpegLavc63.3.100-hevc", "video/hevc", 1920, 1080));
        assertFalse(SoftwareHevcFallback.isTarget("OMX.qcom.video.decoder.hevc", "video/hevc", 3840, 2160));
        assertFalse(SoftwareHevcFallback.isTarget("ffmpegLavc63.3.100-hevc", "video/avc", 3840, 2160));
        assertFalse(SoftwareHevcFallback.isTarget(null, "video/hevc", 3840, 2160));
    }

    @Test
    public void softwareNameMatchingIsCaseInsensitiveAndHardwareNamesStayOut() {
        assertTrue(SoftwareHevcFallback.isSoftwareDecoder("FFMPEG-LAVC-hevc"));
        assertTrue(SoftwareHevcFallback.isSoftwareDecoder("decoder.software.hevc"));
        assertFalse(SoftwareHevcFallback.isSoftwareDecoder("OMX.qcom.video.decoder.hevc"));
        assertFalse(SoftwareHevcFallback.isSoftwareDecoder(""));
    }

    @Test
    public void fallbackRequiresMpvAndExcludesDrmAndSmb() {
        assertTrue(SoftwareHevcFallback.isAllowed(true, false, "https"));
        assertFalse(SoftwareHevcFallback.isAllowed(false, false, "https"));
        assertFalse(SoftwareHevcFallback.isAllowed(true, true, "https"));
        assertFalse(SoftwareHevcFallback.isAllowed(true, false, "smb"));
        assertFalse(SoftwareHevcFallback.isAllowed(true, false, "SMB"));
    }

    @Test
    public void automaticFallbackOnlyRunsOnArmRuntimes() {
        assertTrue(SoftwareHevcFallback.isArmRuntime(new String[]{"arm64-v8a", "armeabi-v7a"}));
        assertTrue(SoftwareHevcFallback.isArmRuntime(new String[]{"armeabi-v7a"}));
        assertFalse(SoftwareHevcFallback.isArmRuntime(new String[]{"x86_64", "x86"}));
        // Houdini may advertise ARM as a secondary ABI, but the process is
        // still running on x86 and must keep the Exo software decoder.
        assertFalse(SoftwareHevcFallback.isArmRuntime(new String[]{"x86_64", "arm64-v8a"}));
        assertFalse(SoftwareHevcFallback.isArmRuntime(new String[]{"x86", "armeabi-v7a"}));
        // JVM/unit-test environments do not expose an ABI list; keep the
        // fallback decision backwards compatible there.
        assertTrue(SoftwareHevcFallback.isArmRuntime(null));
        assertTrue(SoftwareHevcFallback.isArmRuntime(new String[0]));
    }

    @Test
    public void freshPlaybackAllowsFallbackAndManualExoChoiceBlocksIt() {
        SoftwareHevcFallback.ManualSelectionPolicy<Object> policy = new SoftwareHevcFallback.ManualSelectionPolicy<>();
        Object spec = new Object();

        assertFalse(policy.allows(null));
        policy.onPlaybackStarted(spec);
        assertTrue(policy.allows(spec));

        policy.onManualSelection(spec);
        assertFalse(policy.allows(spec));
    }

    @Test
    public void sameResourceRestartsRetainManualExoChoice() {
        SoftwareHevcFallback.ManualSelectionPolicy<Object> policy = new SoftwareHevcFallback.ManualSelectionPolicy<>();
        Object spec = new Object();
        policy.onManualSelection(spec);

        // Subtitle, format and decoder retries keep the same PlaySpec object.
        policy.onPlaybackStarted(spec);
        policy.onPlaybackStarted(spec);

        assertFalse(policy.allows(spec));
    }

    @Test
    public void equalButDistinctResourcesStartANewFallbackDecision() {
        SoftwareHevcFallback.ManualSelectionPolicy<String> policy = new SoftwareHevcFallback.ManualSelectionPolicy<>();
        String firstSpec = new String("same-resource-value");
        String nextSpec = new String("same-resource-value");
        assertTrue(firstSpec.equals(nextSpec));
        assertFalse(firstSpec == nextSpec);
        policy.onManualSelection(firstSpec);

        policy.onPlaybackStarted(nextSpec);

        assertTrue(policy.allows(nextSpec));
        // Returning to an old object after a fresh playback does not revive an
        // earlier decision; the manual choice belongs to the current session.
        policy.onPlaybackStarted(firstSpec);
        assertTrue(policy.allows(firstSpec));
    }

    @Test
    public void clearRestoresAutomaticFallbackForTheSameResource() {
        SoftwareHevcFallback.ManualSelectionPolicy<Object> policy = new SoftwareHevcFallback.ManualSelectionPolicy<>();
        Object spec = new Object();
        policy.onManualSelection(spec);

        policy.clear();
        assertTrue(policy.allows(spec));
        assertFalse(policy.allows(null));

        policy.onPlaybackStarted(spec);
        assertTrue(policy.allows(spec));
    }

    @Test
    public void nullManualSelectionDoesNotOptOutFuturePlayback() {
        SoftwareHevcFallback.ManualSelectionPolicy<Object> policy = new SoftwareHevcFallback.ManualSelectionPolicy<>();
        policy.onManualSelection(null);
        assertFalse(policy.allows(null));

        Object firstSpec = new Object();
        policy.onPlaybackStarted(firstSpec);
        assertTrue(policy.allows(firstSpec));

        policy.onManualSelection(firstSpec);
        policy.onPlaybackStarted(null);
        Object nextSpec = new Object();
        policy.onPlaybackStarted(nextSpec);
        assertTrue(policy.allows(nextSpec));
    }
}
