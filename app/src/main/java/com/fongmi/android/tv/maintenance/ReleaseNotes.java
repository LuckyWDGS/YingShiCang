package com.fongmi.android.tv.maintenance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Release notes Markdown as plain text, or as tagged rows (section heading → tag of each bullet).
 * Publishers mark an important release with hidden comments that GitHub does not render:
 * {@code <!-- ysc-min-version: 1.3.0 -->} and optionally {@code <!-- ysc-important: reason -->}.
 */
public final class ReleaseNotes {

    private static final Pattern COMMENT = Pattern.compile("<!--([\\s\\S]*?)-->");
    private static final Pattern MIN_VERSION = Pattern.compile("^\\s*ysc-min-version\\s*:\\s*(\\S+)\\s*$");
    private static final Pattern IMPORTANT = Pattern.compile("^\\s*ysc-important\\s*:\\s*([\\s\\S]*?)\\s*$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+");
    private static final Pattern BULLET = Pattern.compile("^(?:[-*+]|\\d+[.)])\\s+");
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+");

    public record Item(String tag, String text) {
    }

    private ReleaseNotes() {
    }

    /** The version below which this release is mandatory, or empty when the release is optional. */
    public static String minVersion(String text) {
        return marker(text, MIN_VERSION);
    }

    /** The publisher's reason shown for a mandatory update, or empty. */
    public static String importantReason(String text) {
        return marker(text, IMPORTANT);
    }

    public static String plain(String text) {
        if (text == null) return "";
        return inline(visible(text).replaceAll("(?m)^#{1,6}\\s+", "")).trim();
    }

    public static List<Item> items(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyList();
        List<Item> items = new ArrayList<>();
        String tag = "";
        for (String raw : visible(text).split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (HEADING.matcher(line).find()) {
                String heading = inline(HEADING.matcher(line).replaceFirst("")).trim();
                // "影视仓 1.3.0" titles the whole release; the dialog header already shows the version.
                if (!VERSION.matcher(heading).find()) tag = heading;
                continue;
            }
            String body = inline(BULLET.matcher(line).replaceFirst("")).trim();
            if (!body.isEmpty()) items.add(new Item(tag, body));
        }
        return items;
    }

    private static String marker(String text, Pattern pattern) {
        if (text == null) return "";
        Matcher comment = COMMENT.matcher(text);
        while (comment.find()) {
            Matcher value = pattern.matcher(comment.group(1));
            if (value.matches()) return value.group(1).trim();
        }
        return "";
    }

    private static String visible(String text) {
        return COMMENT.matcher(text).replaceAll("");
    }

    private static String inline(String text) {
        return text.replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("\\[([^\\]]+)]\\([^\\s)]+\\)", "$1")
                .replaceAll("`([^`]+)`", "$1");
    }
}
