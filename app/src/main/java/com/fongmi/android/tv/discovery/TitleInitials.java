package com.fongmi.android.tv.discovery;

import java.util.Map;
import java.util.function.Function;

/**
 * Pinyin initials of a title as one slot per character, where a character read several ways holds
 * every initial: 重生 matches both CS and ZS. A transliterator gives one reading per character only,
 * and titles use the other one often enough (重生, 长生, 还珠) that typing it found nothing.
 */
final class TitleInitials {

    // Initials of the other readings of characters common in drama titles.
    private static final Map<Character, String> OTHER = Map.ofEntries(
            Map.entry('重', "cz"), Map.entry('长', "cz"), Map.entry('行', "xh"), Map.entry('还', "hh"),
            Map.entry('乐', "ly"), Map.entry('传', "cz"), Map.entry('朝', "cz"), Map.entry('降', "jx"),
            Map.entry('藏', "cz"), Map.entry('调', "dt"), Map.entry('弹', "dt"), Map.entry('着', "z"),
            Map.entry('了', "l"), Map.entry('都', "d"), Map.entry('便', "bp"), Map.entry('单', "ds"),
            Map.entry('曾', "zc"), Map.entry('差', "cc"), Map.entry('解', "jx"), Map.entry('仇', "cq"),
            Map.entry('和', "h"), Map.entry('大', "d"), Map.entry('觉', "jj"), Map.entry('薄', "b"),
            Map.entry('校', "xj"), Map.entry('区', "qo"), Map.entry('卡', "kq"), Map.entry('落', "ll"),
            Map.entry('色', "ss"), Map.entry('血', "xx"), Map.entry('会', "hk"), Map.entry('沈', "sc"),
            Map.entry('召', "zs"), Map.entry('参', "csc"), Map.entry('省', "sx"), Map.entry('奇', "qj"),
            Map.entry('率', "ls"), Map.entry('柏', "bb"), Map.entry('秘', "mb"), Map.entry('尉', "wy"),
            Map.entry('盛', "sc"), Map.entry('宿', "sx"), Map.entry('将', "jq"), Map.entry('称', "c"),
            Map.entry('划', "h"), Map.entry('强', "qj"), Map.entry('好', "h"), Map.entry('种', "zc"));

    private TitleInitials() {}

    /**
     * One slot per letter, digit or Han character of the title; reading gives a Han character's main
     * reading (empty when unknown). Other characters are skipped.
     */
    static String[] slots(String title, Function<String, String> reading) {
        String[] slots = new String[title.length()];
        int count = 0;
        for (int i = 0; i < title.length(); ) {
            int c = title.codePointAt(i);
            i += Character.charCount(c);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                String main = reading.apply(new String(Character.toChars(c)));
                String other = c <= 0xFFFF ? OTHER.getOrDefault((char) c, "") : "";
                String letters = (main.isEmpty() ? "" : main.substring(0, 1)) + other;
                slots[count++] = letters; // empty keeps the position of an unknown reading
            } else if (c < 128 && Character.isLetterOrDigit(c)) {
                slots[count++] = String.valueOf((char) Character.toLowerCase(c));
            }
        }
        return java.util.Arrays.copyOf(slots, count);
    }

    /** First slot where query's letters each fall in consecutive slots, or -1. */
    static int indexOf(String[] slots, String query) {
        for (int start = 0; start + query.length() <= slots.length; start++) {
            int j = 0;
            while (j < query.length() && slots[start + j].indexOf(query.charAt(j)) >= 0) j++;
            if (j == query.length()) return start;
        }
        return -1;
    }
}
