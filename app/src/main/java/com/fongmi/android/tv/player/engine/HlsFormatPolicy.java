package com.fongmi.android.tv.player.engine;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Evidence based HLS detection used by engine format recovery. */
public final class HlsFormatPolicy {

    private HlsFormatPolicy() {
    }

    /**
     * Returns true only when the source explicitly declares HLS or an otherwise
     * unspecified source has an .m3u8 path. A generic I/O error is not enough to
     * reinterpret a progressive/cloud URL as an HLS manifest.
     */
    public static boolean hasHlsEvidence(String format, String url) {
        String mime = normalizeMime(format);
        if (isHlsMime(mime)) return true;
        if (!isUnspecifiedMime(mime)) return false;
        if (url == null || url.isBlank()) return false;
        try {
            String path = new URI(url.trim()).getPath();
            return path != null && path.toLowerCase(Locale.ROOT).endsWith(".m3u8");
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String normalizeMime(String format) {
        if (format == null) return "";
        int params = format.indexOf(';');
        return (params < 0 ? format : format.substring(0, params)).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isHlsMime(String mime) {
        return switch (mime) {
            case "application/x-mpegurl", "application/vnd.apple.mpegurl",
                    "audio/mpegurl", "audio/x-mpegurl", "hls", "m3u8" -> true;
            default -> false;
        };
    }

    private static boolean isUnspecifiedMime(String mime) {
        return mime.isEmpty() || "application/octet-stream".equals(mime)
                || "binary/octet-stream".equals(mime);
    }
}
