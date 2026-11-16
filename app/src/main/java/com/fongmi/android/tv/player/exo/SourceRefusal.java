package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import androidx.media3.datasource.HttpDataSource;

import com.github.catvod.net.NetTrace;

import java.nio.charset.StandardCharsets;

/**
 * The play address is on this device (the source plugin's own local server, or the app's proxy to it)
 * and it answered with an HTTP error, so the failure is the source's, not the network's. Phone
 * 2026-11-16: every 短剧漫剧 episode's address 127.0.0.1:6677/proxy answered 500 with the text
 * "End of input at character 0 of" (the plugin's upstream call came back empty) on 3.3.1, 3.5.0 and
 * 3.6.0 alike, and the log only said BAD_HTTP_STATUS.
 */
public record SourceRefusal(int port, int status, String text) {

    private static final int BODY = 300;

    /** null unless the HTTP error in the cause chain came from a loopback address. */
    public static SourceRefusal of(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (!(error instanceof HttpDataSource.InvalidResponseCodeException response)) continue;
            Uri uri = response.dataSpec == null ? null : response.dataSpec.uri;
            if (uri == null) return null;
            return of(uri.getHost(), uri.getPort(), response.responseCode, response.responseBody);
        }
        return null;
    }

    static SourceRefusal of(String host, int port, int status, byte[] body) {
        if (!isLocal(host) || status < 400) return null;
        return new SourceRefusal(port, status, text(body));
    }

    static boolean isLocal(String host) {
        if (host == null) return false;
        return host.equals("localhost") || host.startsWith("127.") || host.equals("::1") || host.equals("[::1]");
    }

    /** What the server said, with anything that could name a request or a person removed; empty if nothing is left. */
    private static String text(byte[] body) {
        if (body == null || body.length == 0) return "";
        String value = new String(body, 0, Math.min(body.length, BODY), StandardCharsets.UTF_8).replace('�', ' ').trim();
        if (value.isEmpty()) return "";
        String readable = NetTrace.readable(value);
        return "other".equals(readable) ? "" : readable;
    }

    /** For the diagnostic log: the port names no person or source. */
    public String log() {
        return "source=local" + (port > 0 ? port : "") + " status=" + status + (text.isEmpty() ? "" : " text=" + text);
    }
}
