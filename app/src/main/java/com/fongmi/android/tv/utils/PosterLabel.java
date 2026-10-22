package com.fongmi.android.tv.utils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only explicit totals/progress are episode counts; quality and dates are not ratings. */
public final class PosterLabel {
    private static final Pattern TOTAL = Pattern.compile("(?:全|共)\\s*0*([1-9]\\d{0,3})\\s*集|0*([1-9]\\d{0,3})\\s*集\\s*(?:全|已?完结)");
    private static final Pattern UPDATE = Pattern.compile("(?:更新(?:至|到)?|更至)\\s*第?\\s*0*([1-9]\\d{0,3})(?!\\d|[pP])\\s*集?");
    private static final Pattern SCORE = Pattern.compile("(?:评分\\s*[:：]?\\s*)?([0-9]{1,2}(?:\\.[0-9]{1,2})?)\\s*分?");
    private PosterLabel() {}

    private static final Pattern EPISODE = Pattern.compile("(?:第\\s*)?0*([1-9]\\d{0,3})\\s*(?:集|话|期)?");

    /** AI catalog cards only show source-declared totals, never progress or ratings. */
    public static String totalEpisodes(String remarks) {
        Matcher total = TOTAL.matcher(remarks == null ? "" : remarks.trim());
        return total.find() ? "共" + (total.group(1) != null ? total.group(1) : total.group(2)) + "集" : "";
    }

    /** Search cards describe episodes; ratings never substitute for missing episode data. */
    public static String episodes(String remarks) {
        String note = remarks == null ? "" : remarks.trim();
        Matcher total = TOTAL.matcher(note);
        if (total.find()) return "共" + (total.group(1) != null ? total.group(1) : total.group(2)) + "集";
        Matcher update = UPDATE.matcher(note);
        if (update.find()) return "更新至" + update.group(1) + "集";
        Matcher episode = EPISODE.matcher(note);
        return episode.matches() ? "第" + episode.group(1) + "集" : "";
    }

    private static final Pattern FILE_EPISODE = Pattern.compile("(?i)(?:S\\d{1,2}\\s*E|(?<![A-Za-z])EP?)\\s*0*([1-9]\\d{0,3})(?!\\d)");
    private static final Pattern FILE_NOISE = Pattern.compile("^\\s*\\[[^\\]]*]\\s*|\\.(?i:mkv|mp4|ts|avi|rmvb|flv|mov|wmv|m2ts|iso)\\s*$");

    /**
     * History also has genuine non-numeric names, such as specials and film editions. Cloud-drive file names
     * ([1.51GB]S01E01.mkv) name the episode as S01E01 or EP01; otherwise drop the size tag and the extension.
     */
    public static String history(String remarks) {
        String episode = episodes(remarks);
        if (!episode.isEmpty()) return episode;
        String note = remarks == null ? "" : remarks.trim();
        Matcher file = FILE_EPISODE.matcher(note);
        if (file.find()) return "第" + file.group(1) + "集";
        String clean = FILE_NOISE.matcher(note).replaceAll("").trim();
        return clean.isEmpty() ? note : clean;
    }

    /** Where playback stopped, as 20:19 or 1:02:03; "" before the first minute is watched or after the end. */
    public static String watched(long position, long duration) {
        if (position < 60_000 || (duration > 0 && position >= duration - 10_000)) return "";
        long seconds = position / 1000, hours = seconds / 3600, minutes = seconds / 60 % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, seconds % 60);
    }

    public static String rating(String remarks, String score) {
        String value = rating(score);
        if (!value.isEmpty()) return value;
        String note = remarks == null ? "" : remarks.trim();
        return note.contains(".") || note.contains("评分") || note.endsWith("分") ? rating(note) : "";
    }

    public static String source(String name, String key) {
        return name != null && !name.trim().isEmpty() ? name.trim() : key == null ? "" : key.trim();
    }

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
