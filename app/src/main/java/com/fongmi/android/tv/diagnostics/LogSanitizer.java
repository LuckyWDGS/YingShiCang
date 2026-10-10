package com.fongmi.android.tv.diagnostics;

public final class LogSanitizer {
    private LogSanitizer() {}
    public static String clean(String value) {
        if (value == null) return "";
        // Bound regex work, remove entire headers (including bearer values/cookie lists),
        // and remove URLs entirely: signed URLs may hide credentials in any path segment.
        String text = value.substring(0, Math.min(value.length(), 8192));
        text = text.replaceAll("(?im)(authorization|cookie|set-cookie)\\s*[:=][^\\r\\n]*", "$1=[redacted]");
        text = text.replaceAll("(?i)https?://\\S+", "[url]");
        text = text.replaceAll("(?i)(token|password|secret|pwd|access_key|refresh_token)[\\\"']?\\s*[:=]\\s*[\\\"']?[^\\s,;}\\\"']+", "$1=[redacted]");
        return text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }
}
