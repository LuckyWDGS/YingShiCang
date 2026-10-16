package com.fongmi.android.tv.playback.vod;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ranks declared quality labels without inspecting or rewriting playback URLs. */
public final class VodQualityPreference {
    private static final Pattern RESOLUTION = Pattern.compile("(?<![0-9])(4320|2160|1440|1080|720|576|480|360|240)\\s*[pi]?(?![0-9])");
    private static final Pattern K = Pattern.compile("(?<![0-9])([248])\\s*k(?![a-z0-9])");

    private VodQualityPreference() {}

    /** Null labels represent empty URLs. Unknown/tied labels retain the source default. */
    public static int highest(List<String> labels, int sourceDefault) {
        int best = sourceDefault >= 0 && sourceDefault < labels.size() && labels.get(sourceDefault) != null ? sourceDefault : -1;
        int score = best < 0 ? -1 : rank(labels.get(best));
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i) == null) continue;
            int candidate = rank(labels.get(i));
            if (best < 0 || candidate > score) {
                best = i;
                score = candidate;
            }
        }
        return Math.max(0, best);
    }

    private static int rank(String label) {
        String text = label.toLowerCase(Locale.ROOT).trim();
        int resolution = 0;
        Matcher pixels = RESOLUTION.matcher(text);
        while (pixels.find()) resolution = Math.max(resolution, Integer.parseInt(pixels.group(1)));
        if (resolution > 0) return resolution;
        Matcher k = K.matcher(text);
        while (k.find()) resolution = Math.max(resolution, switch (k.group(1)) { case "8" -> 4320; case "4" -> 2160; default -> 1440; });
        if (resolution > 0) return resolution;
        if (text.contains("uhd")) return 2160;
        if (text.contains("蓝光") || text.contains("藍光") || text.contains("超清") || text.contains("fhd") || text.contains("full hd")) return 1080;
        if (text.contains("高清") || text.matches(".*\\bhd\\b.*")) return 720;
        if (text.contains("标清") || text.contains("標清") || text.matches(".*\\bsd\\b.*")) return 480;
        if (text.contains("流畅") || text.contains("流暢")) return 360;
        return 0;
    }
}
