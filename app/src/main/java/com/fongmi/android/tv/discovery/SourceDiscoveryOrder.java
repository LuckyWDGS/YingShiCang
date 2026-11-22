package com.fongmi.android.tv.discovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The order of 电影筛选 / 电视筛选 cards (user 2026-11-22: the source filters looked broken: one source's old
 * films filled the first rows, 近期热度 ordered nothing, and each title came once per language version).
 * Sources that cannot sort by 热度 are ordered newest first, the sources then take turns, 首映时间 / 高分优先
 * sort the whole list, and versions of one title (language marks, the same film from several sources, which
 * these categories do not label) share one card. Pure Java: the runner supplies the fields.
 */
public final class SourceDiscoveryOrder {
    public interface Fields<T> {
        String title(T item);
        String year(T item);
        String score(T item);
    }

    private static final String MARK = "(?:粤|粵|粤语|粵語|粤配|粵配|国|國|国语|國語|普通话|普通話|国粤|國粵|国粤双语|國粵雙語"
            + "|双语|雙語|国配|國配|原声|原聲|英语|英語)(?:版)?";
    private static final Pattern LANGUAGE = Pattern.compile("\\s*[(（\\[【]\\s*" + MARK + "\\s*[)）\\]】]\\s*$");
    private static final Pattern CANTONESE = Pattern.compile("[(（\\[【]\\s*(?:粤|粵|粤语|粵語|粤配|粵配)(?:版)?\\s*[)）\\]】]\\s*$");
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:18|19|20)\\d{2}(?!\\d)");

    private static final int NONE = Integer.MIN_VALUE;

    private SourceDiscoveryOrder() {}

    /**
     * slots: each source's accepted cards in arrival order; byYear[i]: that source could not sort by 热度
     * itself, so its cards go newest first. sort: the selected 排序 value ("" or U 热度, R 首映时间, S 高分优先).
     */
    public static <T> List<T> order(List<List<T>> slots, boolean[] byYear, String sort, Fields<T> fields) {
        boolean heat = sort == null || sort.isEmpty() || sort.equals("U");
        List<List<T>> lists = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            List<T> list = new ArrayList<>(slots.get(i));
            if (heat && i < byYear.length && byYear[i]) list.sort(newestFirst(fields));
            lists.add(list);
        }
        List<T> merged = new ArrayList<>();
        int count = lists.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < count; i++) for (List<T> list : lists) if (i < list.size()) merged.add(list.get(i));
        if ("S".equals(sort)) merged.sort(Comparator.comparingDouble((T item) -> number(fields.score(item))).reversed());
        else if ("R".equals(sort)) merged.sort(newestFirst(fields));
        return dedupe(merged, fields);
    }

    /** One card per title: the first position of the title is kept, showing a non-Cantonese version when there is one. */
    public static <T> List<T> dedupe(List<T> items, Fields<T> fields) {
        List<List<T>> groups = new ArrayList<>();
        Groups<T> index = new Groups<>(fields);
        for (T item : items) {
            int group = index.find(item);
            if (group == NONE) {
                index.add(item, groups.size());
                List<T> members = new ArrayList<>(); members.add(item); groups.add(members);
            } else groups.get(group).add(item);
        }
        List<T> result = new ArrayList<>();
        for (List<T> members : groups) {
            T chosen = members.get(0);
            if (cantonese(fields.title(chosen))) for (T member : members) if (!cantonese(fields.title(member))) { chosen = member; break; }
            result.add(chosen);
        }
        return result;
    }

    /** Titles already shown; once the user is in the cards, later versions of them are left out. */
    public static final class Groups<T> {
        private final Fields<T> fields;
        private final Map<String, List<int[]>> byTitle = new HashMap<>();

        public Groups(Fields<T> fields) { this.fields = fields; }

        /** Adds item unless it is a version of a title already added; returns whether it was added. */
        public boolean admit(T item) {
            if (find(item) != NONE) return false;
            add(item, -1);
            return true;
        }

        public void clear() { byTitle.clear(); }

        /** The group of an earlier version of item, or NONE. */
        int find(T item) {
            int year = year(fields.year(item));
            for (int[] entry : byTitle.getOrDefault(baseTitle(fields.title(item)), List.of()))
                if (year == 0 || entry[0] == 0 || Math.abs(entry[0] - year) <= 1) return entry[1];
            return NONE;
        }

        void add(T item, int group) {
            byTitle.computeIfAbsent(baseTitle(fields.title(item)), key -> new ArrayList<>()).add(new int[]{year(fields.year(item)), group});
        }
    }

    /** The title without trailing language marks such as (粤) or （国语）, case and spaces ignored. */
    public static String baseTitle(String title) {
        String value = title == null ? "" : title.trim();
        for (Matcher matcher = LANGUAGE.matcher(value); matcher.find(); matcher = LANGUAGE.matcher(value))
            value = value.substring(0, matcher.start()).trim();
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    static boolean cantonese(String title) {
        return title != null && CANTONESE.matcher(title.trim()).find();
    }

    static int year(String text) {
        Matcher matcher = YEAR.matcher(text == null ? "" : text);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }

    private static <T> Comparator<T> newestFirst(Fields<T> fields) {
        // Stable: equal years (and items without a year, which go last) keep the source's own order.
        return Comparator.comparingInt((T item) -> year(fields.year(item))).reversed();
    }

    private static double number(String text) {
        try { return Double.parseDouble(text == null ? "" : text.trim()); } catch (RuntimeException ignored) { return 0; }
    }
}
