package com.fongmi.android.tv.discovery;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Explicit catalog metadata only; names, plot descriptions and performers are not age labels. */
public final class DiscoveryContentGate {
    private static final Pattern ADULT = Pattern.compile(
            "18\\s*\\+|(?:^|[^a-z0-9])(?:r[- ]?18\\+?|xxx|porn(?:ographic)?|hentai|adult|nc[- ]?17)(?:$|[^a-z0-9])|"
                    + "成人(?:向|片|電影|电影|視頻|视频|影視|影视|頻道|频道|專區|专区|內容|内容)?|情色|色情|倫理|伦理|限制[級级]",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> METADATA = Set.of("tag", "tags", "genre", "genres", "category",
            "categories", "type", "typename", "vodtype", "vodtypename", "vodclass", "vodtag", "vodtags",
            "class", "contenttype", "classification", "contentrating", "agerating", "agelimit", "ageclassification", "certification", "rated", "cardsubtitle",
            "vodage", "vodrating", "vodcontentrating", "vodagerating");
    private static final Set<String> FLAGS = Set.of("adult", "isadult", "adultcontent", "isadultcontent", "vodadult");
    private static final Set<String> AGE = Set.of("agerating", "agelimit", "ageclassification", "contentrating", "vodage", "vodrating", "vodcontentrating", "vodagerating");

    private DiscoveryContentGate() {}

    public static boolean isAdult(String... metadata) {
        if (metadata == null) return false;
        for (String value : metadata) {
            if (value == null) continue;
            // An education category describes its audience, not restricted entertainment.
            String cleaned = value.replaceAll("成人(?:教育|高考|考试|考試|培训|培訓|学校|學校|学习|學習|礼|禮)", "")
                    .replaceAll("(?i)adult\\s+(?:education|learning|training)", "");
            if (ADULT.matcher(cleaned.toLowerCase(Locale.ROOT)).find()) return true;
        }
        return false;
    }

    /** Numeric values carry age meaning only in a declared age/content-rating facet. */
    public static boolean isAdultAgeChoice(String key, String label, String... values) {
        String normalizedKey = normalize(key).replaceAll("[_\\- ]", "");
        String normalizedLabel = normalize(label).replaceAll("[_\\- ]", "");
        boolean ageFacet = AGE.contains(normalizedKey) || AGE.contains(normalizedLabel)
                || normalizedLabel.matches("(?:年龄|年齡|年龄分级|年齡分級|年龄限制|年齡限制|内容分级|內容分級)");
        if (!ageFacet || values == null) return false;
        for (String value : values) if (value != null && value.trim().matches("(?:18|19|20|21)(?:岁|歲)?")) return true;
        return false;
    }

    public static boolean isMovie(String label) {
        String value = normalize(label);
        if (isTelevision(value) || isVariety(value)) return false;
        if (value.contains("电影") || value.contains("電影") || value.contains("影片")) return true;
        if (value.matches(".*(?:动作|動作|喜剧|喜劇|爱情|愛情|科幻|动画|動畫|悬疑|懸疑|犯罪|惊悚|驚悚|冒险|冒險|"
                + "音乐|音樂|历史|歷史|奇幻|恐怖|战争|戰爭|传记|傳記|歌舞|武侠|武俠|灾难|災難|西部|剧情|劇情|"
                + "纪录|紀錄|伦理|倫理|情色|色情|成人|限制级|限制級)片.*")) return true;
        return value.matches(".*(?:^|[^a-z])(?:movie|movies|film|films|cinema)(?:$|[^a-z]).*");
    }

    public static boolean isTelevision(String label) {
        String value = normalize(label);
        if (value.equals("电视") || value.equals("電視") || value.contains("电视剧") || value.contains("電視劇") || value.contains("剧集") || value.contains("劇集")
                || value.contains("连续剧") || value.contains("連續劇") || value.contains("短剧") || value.contains("短劇")) return true;
        if (value.matches(".*(?:国产|國產|大陆|大陸|港台|香港|台湾|台灣|欧美|歐美|韩|韓|日|泰|港|台|美|英)[剧劇].*")) return true;
        return value.matches(".*(?:^|[^a-z])(?:tv|television|drama|series|serial)(?:$|[^a-z]).*");
    }

    public static boolean isVariety(String label) {
        String value = normalize(label);
        return value.contains("综艺") || value.contains("綜藝")
                || value.matches(".*(?:^|[^a-z])(?:variety|shows?)(?:$|[^a-z]).*");
    }

    public static boolean isAdultMetadata(JsonObject object) {
        if (object == null) return false;
        for (var field : object.entrySet()) {
            String key = field.getKey().replaceAll("[_\\- ]", "").toLowerCase(Locale.ROOT);
            JsonElement value = field.getValue();
            if (FLAGS.contains(key) && flag(value)) return true;
            if (METADATA.contains(key) && explicit(value, AGE.contains(key))) return true;
            if (List.of("subject", "item", "metadata").contains(key) && value.isJsonObject()
                    && isAdultMetadata(value.getAsJsonObject())) return true;
        }
        return false;
    }

    private static boolean flag(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return false;
        String text = value.getAsString();
        return text.equalsIgnoreCase("true") || text.equals("1") || isAdult(text);
    }

    private static boolean explicit(JsonElement value, boolean age) {
        if (value == null || value.isJsonNull()) return false;
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) if (explicit(item, age)) return true;
        } else if (value.isJsonObject()) {
            for (String key : List.of("name", "label", "value"))
                if (explicit(value.getAsJsonObject().get(key), age)) return true;
        } else if (value.isJsonPrimitive()) {
            String text = value.getAsString();
            if (isAdult(text)) return true;
            if (age && text.matches("(?:18|19|20|21)(?:岁|歲)?")) return true;
        }
        return false;
    }

    private static String normalize(String label) {
        return label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
    }
}
