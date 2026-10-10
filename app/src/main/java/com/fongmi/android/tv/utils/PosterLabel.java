package com.fongmi.android.tv.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only explicit totals/progress are episode counts; quality and dates are not ratings. */
public final class PosterLabel {
    private static final Pattern TOTAL = Pattern.compile("(?:全|共)\\s*0*([1-9]\\d{0,3})\\s*集|0*([1-9]\\d{0,3})\\s*集\\s*(?:全|已?完结)");
    private static final Pattern UPDATE = Pattern.compile("(?:更新(?:至|到)?|更至)\\s*第?\\s*0*([1-9]\\d{0,3})(?!\\d|[pP])\\s*集?");
    private static final Pattern SCORE = Pattern.compile("(?:评分\\s*[:：]?\\s*)?([0-9]{1,2}(?:\\.[0-9]{1,2})?)\\s*分?");
    private PosterLabel() {}

    public static String text(String remarks, String score) {
        String note = remarks == null ? "" : remarks.trim();
        Matcher total = TOTAL.matcher(note);
        if (total.find()) return "共" + (total.group(1) != null ? total.group(1) : total.group(2)) + "集";
        Matcher update = UPDATE.matcher(note);
        if (update.find()) return "更新至" + update.group(1) + "集";
        String rating = rating(score);
        if (!rating.isEmpty()) return rating;
        // Bare integers in vod_remarks may mean an episode number, not a score.
        return note.contains(".") || note.contains("评分") || note.endsWith("分") ? rating(note) : "";
    }

    private static String rating(String value) {
        Matcher match = SCORE.matcher(value == null ? "" : value.trim());
        if (!match.matches()) return "";
        double number = Double.parseDouble(match.group(1));
        return number > 0 && number <= 10 ? match.group(1) : "";
    }
}
