package com.fongmi.android.tv.discovery;

import com.fongmi.android.tv.bean.Vod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared discovery controls and the nonadult home/hot/ranking provider catalog. */
public final class DiscoveryCatalog {
    public static final int PAGE_SIZE = 20;
    private static final String API = "https://m.douban.com/rexxar/api/v2/";
    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "Mozilla/5.0",
            "Referer", "https://movie.douban.com/");
    private static final List<List<Group>> GROUPS = createGroups();

    private DiscoveryCatalog() {}

    public static final class Option {
        public final String label, value;
        private Option(String label, String value) { this.label = label; this.value = value; }
    }

    public static final class Group {
        public final String key, label;
        public final List<Option> options;
        private Group(String key, String label, List<Option> options) {
            this.key = key; this.label = label; this.options = List.copyOf(options);
        }
        private boolean contains(String value) {
            return options.stream().anyMatch(option -> option.value.equals(value));
        }
    }

    public static List<Group> groups(int tab) {
        return tab >= 0 && tab < GROUPS.size() ? GROUPS.get(tab) : Collections.emptyList();
    }

    /** Values are validated against this tab's options when restoring saved settings. */
    public static final class State {
        public final int tab;
        private final Map<String, String> values = new LinkedHashMap<>();

        public State(int tab) {
            this.tab = tab;
            for (Group group : groups(tab)) values.put(group.key, group.options.get(0).value);
        }

        public String value(String key) { return values.getOrDefault(key, ""); }

        public boolean select(String key, String value) {
            Group group = groups(tab).stream().filter(item -> item.key.equals(key)).findFirst().orElse(null);
            if (group == null || !group.contains(value)) return false;
            Map<String, String> previous = new LinkedHashMap<>(values);
            values.put(key, value);
            if (tab == 5) {
                if (key.equals("tv_form") && !value.isEmpty()) {
                    values.put("type", "电视剧"); values.put("show_form", "");
                } else if (key.equals("show_form") && !value.isEmpty()) {
                    values.put("type", "综艺"); values.put("tv_form", "");
                } else if (key.equals("type")) {
                    if (!value.equals("电视剧")) values.put("tv_form", "");
                    if (!value.equals("综艺")) values.put("show_form", "");
                }
            }
            return !previous.equals(values);
        }

        public String serialize() {
            JsonObject object = new JsonObject();
            // Adult content is an explicit choice for this visit, never a saved preference.
            values.forEach((key, value) -> { if (!key.equals("content")) object.addProperty(key, value); });
            return object.toString();
        }

        /** A request copy retains the current explicit choice without using persistence. */
        public State snapshot() {
            State copy = new State(tab);
            copy.values.putAll(values);
            return copy;
        }

        public void restore(String json) {
            if (values.containsKey("content")) values.put("content", "");
            if (json == null || json.isEmpty()) return;
            try {
                JsonElement element = JsonParser.parseString(json);
                if (!element.isJsonObject()) return;
                JsonObject object = element.getAsJsonObject();
                for (Group group : groups(tab)) {
                    if (group.key.equals("content")) continue;
                    JsonElement saved = object.get(group.key);
                    if (saved == null || !saved.isJsonPrimitive() || !saved.getAsJsonPrimitive().isString()) continue;
                    String restored = text(object, group.key);
                    if (group.contains(restored)) values.put(group.key, restored);
                }
                if (tab == 5) {
                    // Invalid or older saved combinations must never send two competing forms.
                    if (!value("type").equals("电视剧")) values.put("tv_form", "");
                    if (!value("type").equals("综艺")) values.put("show_form", "");
                }
            } catch (RuntimeException ignored) { /* Keep valid defaults after damaged preferences. */ }
        }
    }

    public static final class Request {
        public final String url;
        public final int start, pageSize;
        public final Map<String, String> headers;
        /** Same list from the mobile API, for when the rate-limited web endpoint refuses (403). */
        public final Request fallback;
        private Request(String url, int start) {
            this(url, start, null);
        }
        private Request(String url, int start, Request fallback) {
            this.url = url; this.start = start; this.pageSize = PAGE_SIZE; this.headers = HEADERS; this.fallback = fallback;
        }
        /** The web endpoint while it answers; its fallback for a while after Douban refused it. */
        public Request effective() {
            return fallback != null && System.nanoTime() - webBlockedUntil < 0 ? fallback : this;
        }
    }

    private static final long WEB_BLOCK_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
    private static volatile long webBlockedUntil = System.nanoTime();

    /** Douban rate-limits movie.douban.com/j/search_subjects per IP; refusals mean "use the mobile API". */
    public static boolean refused(int code) {
        return code == 403 || code == 418 || code == 429;
    }

    public static void webRefused() {
        webBlockedUntil = System.nanoTime() + WEB_BLOCK_NANOS;
    }

    public static List<Request> requests(State state, int page) {
        int start = Math.max(0, page) * PAGE_SIZE;
        return switch (state.tab) {
            case 0 -> List.of(search("movie", "热门", "recommend", start), search("tv", "热门", "recommend", start));
            case 1 -> List.of(search("movie", state.value("region").isEmpty() ? "热门" : state.value("region"), state.value("sort"), start));
            case 2 -> List.of(recent("tv", state.value("category"), start));
            case 3 -> List.of(recent("show", state.value("category"), start));
            case 4, 5 -> List.of(filtered(state, start));
            case 6, 7 -> List.of(new Request(API + "subject_collection/" + state.value("rank") + "/items?start=" + start + "&count=" + PAGE_SIZE, start));
            default -> Collections.emptyList();
        };
    }

    private static Request search(String type, String tag, String sort, int start) {
        return new Request("https://movie.douban.com/j/search_subjects?type=" + type + "&tag=" + encode(tag)
                + "&sort=" + sort + "&page_limit=" + PAGE_SIZE + "&page_start=" + start, start, mobile(type, tag, sort, start));
    }

    /** The mobile API's recent-hot list matching a web search: sort → category, region tag → type. */
    private static Request mobile(String type, String tag, String sort, int start) {
        if (type.equals("tv")) return recent("tv", "tv", start);
        String category = switch (sort) { case "time" -> "最新"; case "rank" -> "豆瓣高分"; default -> "热门"; };
        String region = tag.equals("热门") ? "全部" : tag;
        return new Request(API + "subject/recent_hot/movie?category=" + encode(category) + "&type=" + encode(region)
                + "&start=" + start + "&limit=" + PAGE_SIZE, start);
    }

    private static Request recent(String category, String type, int start) {
        return new Request(API + "subject/recent_hot/tv?category=" + category + "&type=" + type
                + "&start=" + start + "&limit=" + PAGE_SIZE, start);
    }

    private static Request filtered(State state, int start) {
        JsonObject categories = new JsonObject();
        List<String> tags = new ArrayList<>();
        String genre = state.tab == 4 ? state.value("genre") : state.value("type").equals("综艺") ? state.value("show_form") : state.value("tv_form");
        categories.addProperty("类型", genre);
        if (state.tab == 5 && !state.value("type").isEmpty()) categories.addProperty("形式", state.value("type"));
        categories.addProperty("地区", state.value("region"));
        if (!genre.isEmpty()) tags.add(genre);
        else if (state.tab == 5 && !state.value("type").isEmpty()) tags.add(state.value("type"));
        for (String key : List.of("region", "year", "platform", "content")) if (!state.value(key).isEmpty()) tags.add(state.value(key));
        String url = API + (state.tab == 4 ? "movie" : "tv") + "/recommend?refresh=0&start=" + start
                + "&count=" + PAGE_SIZE + "&sort=" + state.value("sort")
                + "&selected_categories=" + encode(categories.toString())
                + "&tags=" + encode(String.join(",", tags)) + "&score_range=0%2C10&uncollect=false";
        return new Request(url, start);
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20"); }
        catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    public static final class Entry {
        public final String id, title, cover, score, remarks, year;
        private Entry(String id, String title, String cover, String score, String remarks, String year) {
            this.id = id; this.title = title; this.cover = cover; this.score = score; this.remarks = remarks; this.year = year;
        }
        public Vod toVod() {
            Vod vod = new Vod();
            vod.setId(id); vod.setName(title); vod.setPic(cover); vod.setScore(score); vod.setRemarks(remarks); vod.setYear(year);
            vod.setAction("search:" + title);
            return vod;
        }
    }

    public static final class Page {
        public final List<Entry> entries;
        public final List<String> rawIds;
        public final int total, rawCount;
        private Page(List<Entry> entries, int total, int rawCount, List<String> rawIds) {
            this.entries = List.copyOf(entries); this.total = total; this.rawCount = rawCount;
            this.rawIds = List.copyOf(rawIds);
        }
        public boolean hasMore(Request request) {
            return rawCount > 0 && (total >= 0 ? request.start + rawCount < total : rawCount >= request.pageSize);
        }
    }

    /** Accepts legacy search responses and the current official recommendation/chart response shapes. */
    public static Page parse(String body) {
        JsonElement root;
        try { root = JsonParser.parseString(body); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Invalid discovery response", error); }
        JsonArray array = null;
        int total = -1;
        if (root != null && root.isJsonArray()) array = root.getAsJsonArray();
        else if (root != null && root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            for (String name : List.of("subjects", "items", "subject_collection_items", "data")) {
                JsonElement value = object.get(name);
                if (value != null && value.isJsonArray()) { array = value.getAsJsonArray(); break; }
            }
            total = integer(object.get("total"), -1);
        }
        if (array == null) throw new IllegalArgumentException("Discovery response has no item list");
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            // Retain wrapper evidence before unwrapping: a clean subject does not override it.
            if (DiscoveryContentGate.isAdultMetadata(object)) continue;
            // Some provider cards wrap the actual movie/TV subject.
            if (object.has("subject") && object.get("subject").isJsonObject()) object = object.getAsJsonObject("subject");
            if (DiscoveryContentGate.isAdultMetadata(object)) continue;
            if (!isSubject(object)) continue;
            String id = text(object, "id"), title = text(object, "title");
            if (id.isEmpty() || title.isEmpty() || !seen.add(id)) continue;
            String cover = first(text(object, "cover"), text(object, "cover_url"));
            JsonElement pic = object.get("pic");
            if (cover.isEmpty() && pic != null && pic.isJsonObject()) cover = first(text(pic.getAsJsonObject(), "normal"), text(pic.getAsJsonObject(), "large"));
            if (cover.isEmpty()) cover = text(object, "image");
            if (cover.startsWith("//")) cover = "https:" + cover;
            if (!cover.startsWith("https://") && !cover.startsWith("http://")) cover = "";
            String rate = text(object, "rate");
            JsonElement rating = object.get("rating");
            if (rate.isEmpty() && rating != null && rating.isJsonObject()) rate = text(rating.getAsJsonObject(), "value");
            String year = text(object, "year");
            if (year.isEmpty()) {
                String subtitle = text(object, "card_subtitle");
                if (subtitle.matches("^\\d{4}(?:\\s|/).*")) year = subtitle.substring(0, 4);
            }
            entries.add(new Entry(id, title, cover, score(rate), text(object, "episodes_info"), year));
        }
        List<String> rawIds = new ArrayList<>();
        for (JsonElement element : array) {
            JsonElement raw = element;
            if (raw.isJsonObject() && raw.getAsJsonObject().has("subject")) raw = raw.getAsJsonObject().get("subject");
            String id = raw != null && raw.isJsonObject() ? text(raw.getAsJsonObject(), "id") : "";
            rawIds.add(id.isEmpty() ? "raw:" + element.toString().hashCode() : id);
        }
        return new Page(entries, total, array.size(), rawIds);
    }

    private static boolean isSubject(JsonObject object) {
        String type = text(object, "type"), card = text(object, "card"), uri = text(object, "uri");
        // Recommendation responses also contain chart promos with item_type="movie".
        // Their type/card and subject_collection URI identify a list, not a searchable film.
        if (!type.isEmpty() && !List.of("movie", "tv", "show").contains(type)) return false;
        if (!card.isEmpty() && !card.equals("subject")) return false;
        if (!uri.isEmpty() && !uri.matches("douban://douban\\.com/(?:movie|tv|show)/\\d+(?:[/?#].*)?")) return false;
        // The legacy search_subjects payload has none of these three discriminator fields.
        return true;
    }

    private static String first(String left, String right) { return left.isEmpty() ? right : left; }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && !value.getAsJsonPrimitive().isBoolean() ? value.getAsString().trim() : "";
    }

    private static int integer(JsonElement value, int fallback) {
        try { return value != null && value.isJsonPrimitive() ? Math.max(-1, value.getAsInt()) : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String score(String value) {
        try {
            BigDecimal score = new BigDecimal(value);
            if (score.signum() <= 0 || score.compareTo(BigDecimal.TEN) > 0) return "";
            return score.stripTrailingZeros().scale() <= 0 ? score.toPlainString().split("\\.")[0] + ".0" : score.stripTrailingZeros().toPlainString();
        } catch (RuntimeException ignored) { return ""; }
    }

    private static List<List<Group>> createGroups() {
        // A temporary adult scope, interpreted using each source's real browse metadata.
        Group content = mapped("content", "内容", "全部", "", "18+（成人向）", "情色");
        Group movieYears = labels("year", "年代", "全部年代", "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019", "2010年代", "2000年代", "90年代", "80年代", "70年代", "60年代", "更早");
        Group tvYears = labels("year", "年代", "全部", "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2019", "2010年代", "2000年代", "90年代", "80年代", "70年代", "60年代", "更早");
        return List.of(
                List.of(),
                List.of(mapped("sort", "排序", "热度", "recommend", "最新", "time", "评分", "rank"), labels("region", "地区", "全部", "华语", "欧美", "韩国", "日本")),
                List.of(mapped("category", "分类", "综合", "tv", "国产剧", "tv_domestic", "欧美剧", "tv_american", "日剧", "tv_japanese", "韩剧", "tv_korean", "动画", "tv_animation")),
                List.of(mapped("category", "分类", "综合", "show", "国内", "show_domestic", "国外", "show_foreign")),
                List.of(content, labels("genre", "类型", "全部类型", "喜剧", "爱情", "动作", "科幻", "动画", "悬疑", "犯罪", "惊悚", "冒险", "音乐", "历史", "奇幻", "恐怖", "战争", "传记", "歌舞", "武侠", "灾难", "西部"),
                        labels("region", "地区", "全部地区", "华语", "欧美", "韩国", "日本", "中国大陆", "美国", "中国香港", "中国台湾", "英国", "法国", "德国", "意大利", "西班牙", "印度", "泰国", "俄罗斯"),
                        mapped("sort", "排序", "近期热度", "U", "首映时间", "R", "高分优先", "S"), movieYears),
                List.of(content, labels("type", "类型", "不限", "电视剧", "综艺"),
                        labels("tv_form", "电视剧形式", "不限", "喜剧", "爱情", "悬疑", "动画", "武侠", "古装", "家庭", "犯罪", "科幻", "恐怖", "历史", "战争", "动作", "冒险", "传记", "剧情", "奇幻", "惊悚"),
                        labels("show_form", "综艺形式", "不限", "真人秀", "脱口秀", "音乐", "歌舞"),
                        labels("region", "地区", "全部地区", "华语", "欧美", "国外", "韩国", "日本", "中国大陆", "中国香港", "美国", "英国", "泰国", "中国台湾", "意大利", "法国", "德国", "西班牙", "俄罗斯"),
                        mapped("sort", "排序", "近期热度", "U", "首播时间", "R", "高分优先", "S"), tvYears,
                        labels("platform", "平台", "全部", "腾讯视频", "爱奇艺", "优酷", "湖南卫视", "Netflix", "HBO", "BBC", "NHK", "CBS", "NBC", "tvN")),
                List.of(mapped("rank", "榜单", "实时热门电影", "movie_real_time_hotest", "一周口碑电影榜", "movie_weekly_best", "豆瓣电影Top250", "movie_top250")),
                List.of(mapped("rank", "榜单", "实时热门电视", "tv_real_time_hotest", "华语口碑剧集榜", "tv_chinese_best_weekly", "全球口碑剧集榜", "tv_global_best_weekly", "国内口碑综艺榜", "show_chinese_best_weekly", "国外口碑综艺榜", "show_global_best_weekly")));
    }

    private static Group labels(String key, String label, String... labels) {
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) options.add(new Option(labels[i], i == 0 ? "" : labels[i]));
        return new Group(key, label, options);
    }

    private static Group mapped(String key, String label, String... pairs) {
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) options.add(new Option(pairs[i], pairs[i + 1]));
        return new Group(key, label, options);
    }
}
