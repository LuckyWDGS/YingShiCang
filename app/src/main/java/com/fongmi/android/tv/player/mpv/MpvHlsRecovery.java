package com.fongmi.android.tv.player.mpv;

import androidx.media3.common.MimeTypes;

import com.fongmi.android.tv.player.engine.HlsFormatPolicy;

/** A generic I/O failure is not evidence that a media URL contains an HLS manifest. */
final class MpvHlsRecovery {

    private boolean attempted;

    void reset() {
        attempted = false;
    }

    boolean allow(String format, String url) {
        // MediaItem normalizes this MIME case-insensitively already. Repeating the
        // same HLS load cannot repair a transport/authentication failure.
        if (attempted || MimeTypes.APPLICATION_M3U8.equalsIgnoreCase(format)) return false;
        if (!HlsFormatPolicy.hasHlsEvidence(format, url)) return false;
        attempted = true;
        return true;
    }
}
