package com.fongmi.android.tv.player.mpv;

import androidx.media3.common.MimeTypes;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvHlsRecoveryTest {

    @Test
    public void progressiveAndOpaqueCloudUrlsDoNotBecomeHls() {
        MpvHlsRecovery recovery = new MpvHlsRecovery();
        assertFalse(recovery.allow(null, "https://media.example/video.mp4?sign=value"));
        assertFalse(recovery.allow(null, "https://media.example/video.MKV"));
        assertFalse(recovery.allow(null, "https://cloud.example/download/opaque-id"));
        assertFalse(recovery.allow("application/octet-stream", "https://cloud.example/opaque"));
        assertFalse(recovery.allow(null, null));
        assertFalse(recovery.allow(null, ""));
    }

    @Test
    public void hlsPathAllowsOneRecoveryAndResetsForNextMedia() {
        MpvHlsRecovery recovery = new MpvHlsRecovery();
        assertTrue(recovery.allow(null, "https://media.example/index.M3U8?sign=value#fragment"));
        assertFalse(recovery.allow(null, "https://media.example/index.m3u8"));
        assertFalse(recovery.allow("hls", "https://media.example/opaque"));
        recovery.reset();
        assertTrue(recovery.allow("application/octet-stream", "https://media.example/index%2Em3u8?sign=value"));
    }

    @Test
    public void queryAndFragmentDoNotIdentifyTheMediaContainer() {
        MpvHlsRecovery recovery = new MpvHlsRecovery();
        assertFalse(recovery.allow(null, "https://cloud.example/video.mp4?file=index.m3u8"));
        assertFalse(recovery.allow(null, "https://cloud.example/play?url=https://cdn.example/index.m3u8"));
        assertFalse(recovery.allow(null, "https://cloud.example/video.mp4#index.m3u8"));
        assertFalse(recovery.allow(null, "https://cloud.example/path.m3u8/video.mp4"));
        assertFalse(recovery.allow(null, "https://cloud.example/index.m3u"));
        assertFalse(recovery.allow(null, "https://cloud.example/index.m3u8/"));
    }

    @Test
    public void explicitNonHlsFormatIsNotOverriddenByFilename() {
        for (String mime : new String[]{"video/mp4", "video/x-matroska", "application/dash+xml", "audio/mpeg", "application/json"}) {
            assertFalse(mime, new MpvHlsRecovery().allow(mime, "https://media.example/index.m3u8"));
        }
    }

    @Test
    public void explicitHlsAliasesCanRecoverOpaqueOrMisleadingPaths() {
        for (String mime : new String[]{"application/vnd.apple.mpegurl", "audio/mpegurl", "audio/x-mpegurl", "hls", "m3u8", " application/x-mpegURL; charset=UTF-8 "}) {
            MpvHlsRecovery recovery = new MpvHlsRecovery();
            assertTrue(mime, recovery.allow(mime, "https://media.example/download.mp4"));
            assertFalse(mime, recovery.allow(mime, "https://media.example/download.mp4"));
        }
    }

    @Test
    public void canonicalHlsAlreadyLoadsWithHlsOptionsAndDoesNotRetry() {
        MpvHlsRecovery recovery = new MpvHlsRecovery();
        assertFalse(recovery.allow(MimeTypes.APPLICATION_M3U8, "https://media.example/opaque"));
        assertFalse(recovery.allow("APPLICATION/X-MPEGURL", "https://media.example/opaque"));
    }

    @Test
    public void malformedAndOpaqueUrisNeedExplicitHlsEvidence() {
        MpvHlsRecovery recovery = new MpvHlsRecovery();
        assertFalse(recovery.allow(null, "https://media.example/%zz.m3u8"));
        assertFalse(recovery.allow(null, "custom:index.m3u8"));
        assertTrue(recovery.allow("application/vnd.apple.mpegurl", "custom:opaque"));
    }
}
