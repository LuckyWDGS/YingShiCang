package com.fongmi.android.tv.discovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps canonical UI choices only to ids and values actually declared by a source. */
public final class SourceDiscoveryPlanner {
    public record Choice(String label, String value, boolean adultDeclared) {
        public Choice { label = clean(label); value = clean(value); }
        public Choice(String label, String value) { this(label, value, false); }
    }
    public record Facet(String key, String label, String initial, List<Choice> choices) {
        public Facet { key = clean(key); label = clean(label); initial = clean(initial); choices = List.copyOf(choices); }
    }
    public record Category(String id, String name, List<Facet> facets, boolean adultDeclared) {
        public Category { id = clean(id); name = clean(name); facets = List.copyOf(facets); }
        public Category(String id, String name, List<Facet> facets) {
            this(id, name, facets, false);
        }
    }
    public record Query(String categoryId, String categoryName, Map<String, String> extend, boolean adult,
                        Set<String> mappedConstraints, boolean itemKindRequired) {
        public Query {
            categoryId = clean(categoryId); categoryName = clean(categoryName);
            extend = Collections.unmodifiableMap(new LinkedHashMap<>(extend));
            mappedConstraints = Collections.unmodifiableSet(new LinkedHashSet<>(mappedConstraints));
        }
        public Query(String categoryId, String categoryName, Map<String, String> extend, boolean adult) {
            this(categoryId, categoryName, extend, adult, Set.of(), false);
        }
        public Query(String categoryId, String categoryName, Map<String, String> extend, boolean adult,
                     Set<String> mappedConstraints) {
            this(categoryId, categoryName, extend, adult, mappedConstraints, false);
        }
    }
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:18|19|20)\\d{2}(?!\\d)");
    private SourceDiscoveryPlanner() {}

    public static List<Query> plan(DiscoveryCatalog.State state, List<Category> categories, boolean adultSite) {
        if (state == null || (state.tab != 4 && state.tab != 5) || categories == null) return List.of();
        boolean adult = !state.value("content").isEmpty();
        if (!adult && adultSite) return List.of();
        List<Query> result = new ArrayList<>();
        for (Category original : categories) {
            if (original == null || original.id().isEmpty()) continue;
            for (Category category : kindScopes(state, original, adult)) {
            boolean adultCategory = category.adultDeclared() || DiscoveryContentGate.isAdult(category.name());
            // A generic adult catalog can contain several media kinds. Query it only
            // after explicit selection and require each card to establish its own kind.
            boolean itemKindRequired = adult && adultCategory && !knownKind(category.name());
            if (!adult && adultCategory) continue;
            if (!itemKindRequired && !categoryKind(state, category.name(), adultCategory)) continue;
            Map<String, String> defaults = defaults(category.facets(), adult);
            if (defaults == null) continue;
            if (adult && !adultSite && !adultCategory && !selectedAdult(category, defaults)) {
                for (Facet facet : category.facets()) {
                    Choice choice = facet.choices().stream().filter(c -> adultChoice(facet, c)).findFirst().orElse(null);
                    if (choice != null) { defaults.put(facet.key(), choice.value()); break; }
                }
            }
            boolean declaredAdult = adultSite || adultCategory || selectedAdult(category, defaults);
            List<Map<String, String>> parameters = new ArrayList<>(); parameters.add(defaults);
            Set<String> mapped = new LinkedHashSet<>();
            String form = state.tab == 4 ? state.value("genre") : state.value("type").equals("综艺")
                    ? state.value("show_form") : state.value("tv_form");
            if (!apply(state.tab == 4 ? "genre" : "form", form, category, parameters, mapped, adult)) continue;
            if (state.tab == 5 && !apply("type", state.value("type"), category, parameters, mapped, adult)) continue;
            if (itemKindRequired) mapped.remove("type");
            if (!apply("region", state.value("region"), category, parameters, mapped, adult)) continue;
            if (!apply("year", state.value("year"), category, parameters, mapped, adult)) continue;
            if (!apply("platform", state.value("platform"), category, parameters, mapped, adult)) continue;
            // A source without a matching sort exposes its declared default; the runner can sort T/S locally.
            Facet sort = facet(category, "sort");
            if (sort != null) {
                Choice choice = match(sort, "sort", state.value("sort"), adult);
                if (choice != null) for (Map<String, String> extend : parameters) extend.put(sort.key(), choice.value());
            }
            for (Map<String, String> extend : parameters) {
                boolean scoped = adultSite || adultCategory || selectedAdult(category, extend);
                // Sources can declare the restriction on individual items instead of
                // their movie/TV category. Those pages remain unscoped candidates:
                // accepts() requires explicit adult item metadata before displaying them.
                // A selected adult facet must never be overwritten by an ordinary genre.
                if (!adult && scoped || adult && declaredAdult && !scoped) continue;
                result.add(new Query(category.id(), category.name(), extend, scoped, mapped, itemKindRequired));
            }
            }
        }
        if (adult) result.sort((left, right) -> Boolean.compare(right.adult(), left.adult()));
        return List.copyOf(result);
    }

    public static boolean accepts(DiscoveryCatalog.State state, Query query, String typeName, String tags,
                                  String year, String area) {
        if (state == null || query == null || (state.tab != 4 && state.tab != 5)) return false;
        boolean adult = !state.value("content").isEmpty();
        boolean adultItem = DiscoveryContentGate.isAdult(typeName, tags);
        if (!adult && (query.adult() || adultItem)) return false;
        if (adult && !query.adult() && !adultItem) return false;
        boolean explicitKind = knownKind(typeName);
        if (query.itemKindRequired() && !explicitKind) return false;
        String kind = explicitKind ? typeName : query.categoryName();
        if (explicitKind && knownKind(query.categoryName())
                && (DiscoveryContentGate.isMovie(typeName) != DiscoveryContentGate.isMovie(query.categoryName())
                || DiscoveryContentGate.isTelevision(typeName) != DiscoveryContentGate.isTelevision(query.categoryName())
                || DiscoveryContentGate.isVariety(typeName) != DiscoveryContentGate.isVariety(query.categoryName()))) return false;
        if (!categoryKind(state, kind, adult && (query.adult() || adultItem))) return false;
        if (state.tab == 5 && !state.value("type").isEmpty() && !query.mappedConstraints().contains("type")) {
            if (state.value("type").equals("综艺") ? !DiscoveryContentGate.isVariety(kind)
                    : !DiscoveryContentGate.isTelevision(kind)) return false;
        }
        String form = state.tab == 4 ? state.value("genre") : state.value("type").equals("综艺")
                ? state.value("show_form") : state.value("tv_form");
        String formKey = state.tab == 4 ? "genre" : "form";
        // Remote filters can omit metadata, but an explicit contradictory item field wins.
        if (!form.isEmpty() && hasGenre(typeName, tags) && !matchesText(form, typeName, tags)) return false;
        if (!form.isEmpty() && !query.mappedConstraints().contains(formKey)
                && !matchesText(form, typeName, tags, query.categoryName())) return false;
        String region = state.value("region");
        if (!region.isEmpty() && !clean(area).isEmpty() && !regionMatches(region, area)) return false;
        String categoryArea = categoryRegion(query.categoryName());
        if (!categoryArea.isEmpty() && !clean(area).isEmpty() && !regionMatches(categoryArea, area)) return false;
        if (!region.isEmpty() && !query.mappedConstraints().contains("region") && !regionMatches(region, area)) return false;
        String selectedYear = state.value("year");
        if (!selectedYear.isEmpty() && !clean(year).isEmpty() && !yearMatches(selectedYear, year)) return false;
        if (!selectedYear.isEmpty() && !query.mappedConstraints().contains("year") && !yearMatches(selectedYear, year)) return false;
        return state.value("platform").isEmpty() || query.mappedConstraints().contains("platform");
    }

    private static boolean categoryKind(DiscoveryCatalog.State state, String name, boolean adult) {
        if (state.tab == 4) return DiscoveryContentGate.isMovie(name)
                || (adult && DiscoveryContentGate.isAdult(name) && !DiscoveryContentGate.isTelevision(name) && !DiscoveryContentGate.isVariety(name)
                && state.value("genre").isEmpty());
        if (state.value("type").equals("综艺")) return DiscoveryContentGate.isVariety(name);
        if (state.value("type").equals("电视剧")) return DiscoveryContentGate.isTelevision(name);
        return DiscoveryContentGate.isTelevision(name) || DiscoveryContentGate.isVariety(name);
    }

    /** A broad catalog can establish kind only through a real declared movie/TV/show choice. */
    private static List<Category> kindScopes(DiscoveryCatalog.State state, Category category, boolean adult) {
        if (knownKind(category.name())) return List.of(category);
        Facet kind = category.facets().stream().filter(SourceDiscoveryPlanner::kindFacet).findFirst().orElse(null);
        if (kind == null) return List.of(category);
        List<Category> scopes = new ArrayList<>();
        for (Choice choice : kind.choices()) {
            if ((!adult && adultChoice(kind, choice)) || !knownKind(choice.label())
                    || !categoryKind(state, choice.label(), false)) continue;
            List<Facet> facets = new ArrayList<>();
            for (Facet facet : category.facets()) facets.add(facet == kind
                    ? new Facet(facet.key(), facet.label(), choice.value(), List.of(choice)) : facet);
            scopes.add(new Category(category.id(), choice.label() + " / " + category.name(), facets, category.adultDeclared()));
        }
        return scopes;
    }

    private static boolean knownKind(String label) {
        return DiscoveryContentGate.isMovie(label) || DiscoveryContentGate.isTelevision(label)
                || DiscoveryContentGate.isVariety(label);
    }

    private static boolean kindFacet(Facet facet) {
        String label = normalized(facet.label()), key = normalized(facet.key());
        return (Set.of("type", "form", "category", "kind", "media", "mediatype").contains(label)
                || label.matches("(?:类型|類型|形式|分类|分類|媒体|媒體|内容类型|內容類型)")
                || Set.of("type", "form", "category", "kind", "media", "mediatype", "class").contains(key))
                && facet.choices().stream().anyMatch(c -> DiscoveryContentGate.isTelevision(c.label())
                || DiscoveryContentGate.isVariety(c.label())
                || Set.of("电影", "電影", "影片", "movie", "movies", "film", "films", "cinema").contains(normalized(c.label())));
    }

    private static Map<String, String> defaults(List<Facet> facets, boolean adult) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Facet facet : facets) {
            if (facet.key().isEmpty() || facet.choices().isEmpty()) return null;
            Choice choice = facet.choices().stream().filter(c -> c.value().equals(facet.initial()))
                    .filter(c -> adult || !adultChoice(facet, c)).findFirst().orElse(null);
            if (choice == null) choice = facet.choices().stream().filter(c -> !adultChoice(facet, c)).findFirst().orElse(null);
            if (choice == null && adult) choice = facet.choices().get(0);
            if (choice == null) return null;
            values.put(facet.key(), choice.value());
        }
        return values;
    }

    private static boolean apply(String key, String selected, Category category,
                                 List<Map<String, String>> parameters, Set<String> mapped, boolean adult) {
        if (selected.isEmpty()) return true;
        Facet facet = facet(category, key);
        if (facet != null) {
            Choice choice = match(facet, key, selected, adult);
            if (choice != null) {
                for (Map<String, String> extend : parameters) extend.put(facet.key(), choice.value());
                mapped.add(key); return true;
            }
            if (key.equals("year")) {
                int[] range = yearRange(selected);
                if (range != null && range[1] - range[0] <= 10) {
                    List<Choice> years = facet.choices().stream().filter(c -> adult || !adultChoice(facet, c))
                            .filter(c -> exactYear(c.label()) >= range[0] && exactYear(c.label()) < range[1]).toList();
                    if (!years.isEmpty() && years.size() <= 10) {
                        List<Map<String, String>> expanded = new ArrayList<>();
                        for (Map<String, String> current : parameters) for (Choice year : years) {
                            Map<String, String> extend = new LinkedHashMap<>(current); extend.put(facet.key(), year.value()); expanded.add(extend);
                        }
                        parameters.clear(); parameters.addAll(expanded); mapped.add(key); return true;
                    }
                }
            }
            return false;
        }
        if (key.equals("type")) { mapped.add(key); return true; } // categoryKind already verified the kind.
        if (key.equals("genre") || key.equals("form")) {
            if (!matchesText(selected, category.name())) return false;
            mapped.add(key); return true;
        }
        if (key.equals("region")) {
            String declared = categoryRegion(category.name());
            if (!declared.isEmpty()) {
                if (!regionMatches(selected, declared)) return false;
                mapped.add(key);
            }
            return true; // Unmarked categories require explicit item area in accepts().
        }
        return key.equals("year"); // No platform metadata reaches accepts; unsupported platform is excluded.
    }

    private static Facet facet(Category category, String kind) {
        for (Facet facet : category.facets()) {
            String label = normalized(facet.label()), key = normalized(facet.key());
            boolean matches = switch (kind) {
                case "genre", "form" -> Set.of("genre", "genres", "style", "form", "category", "type").contains(label)
                        || label.matches(".*(?:类型|類型|题材|題材|形式|风格|風格|分类|分類).*" )
                        || Set.of("class", "genre", "genres", "style", "form", "category").contains(key);
                case "type" -> kindFacet(facet)
                        && facet.choices().stream().anyMatch(c -> DiscoveryContentGate.isTelevision(c.label()) || DiscoveryContentGate.isVariety(c.label()));
                case "region" -> Set.of("area", "region", "country").contains(label) || label.matches(".*(?:地区|地區|区域|區域|国家|國家).*" )
                        || Set.of("area", "region", "country", "countries").contains(key);
                case "year" -> Set.of("year", "years", "decade").contains(label) || label.matches(".*(?:年|年代|时间|時間).*" ) || Set.of("year", "years", "date").contains(key);
                case "platform" -> Set.of("platform", "network", "channel").contains(label) || label.matches(".*(?:平台|频道|頻道|电视台|電視台).*" )
                        || Set.of("platform", "network", "channel").contains(key);
                case "sort" -> Set.of("sort", "order", "orderby").contains(label) || label.matches(".*(?:排序|顺序|順序).*" ) || Set.of("sort", "by", "order", "orderby").contains(key);
                default -> false;
            };
            if (matches) {
                if ((kind.equals("genre") || kind.equals("form")) && kindFacet(facet)) continue;
                return facet;
            }
        }
        return null;
    }

    private static Choice match(Facet facet, String kind, String selected, boolean adult) {
        for (Choice choice : facet.choices()) {
            if (!adult && adultChoice(facet, choice)) continue;
            boolean matches = switch (kind) {
                case "region" -> regionEquivalent(selected, choice.label());
                case "year" -> yearEquivalent(selected, choice.label());
                case "sort" -> sortEquivalent(selected, choice.label(), choice.value());
                case "type" -> selected.equals("综艺") ? DiscoveryContentGate.isVariety(choice.label())
                        : DiscoveryContentGate.isTelevision(choice.label());
                default -> textEquivalent(selected, choice.label());
            };
            if (matches) return choice;
        }
        return null;
    }

    private static boolean selectedAdult(Category category, Map<String, String> extend) {
        for (Facet facet : category.facets()) for (Choice choice : facet.choices())
            if (choice.value().equals(extend.get(facet.key())) && adultChoice(facet, choice)) return true;
        return false;
    }
    private static boolean adultChoice(Facet facet, Choice choice) {
        return choice.adultDeclared() || DiscoveryContentGate.isAdult(choice.label(), choice.value())
                || DiscoveryContentGate.isAdultAgeChoice(facet.key(), facet.label(), choice.label(), choice.value());
    }
    private static boolean matchesText(String selected, String... labels) {
        for (String label : labels) {
            if (textEquivalent(selected, label)) return true;
            for (String part : clean(label).split("[,，/|;；\\s]+")) if (textEquivalent(selected, part)) return true;
        }
        return false;
    }
    private static boolean hasGenre(String... labels) {
        Set<String> genres = Set.of("喜剧", "爱情", "动作", "科幻", "动画", "悬疑", "犯罪", "惊悚", "冒险", "音乐", "历史", "奇幻",
                "恐怖", "战争", "传记", "歌舞", "武侠", "灾难", "西部", "剧情", "古装", "家庭", "真人秀", "脱口秀");
        for (String label : labels) for (String part : clean(label).split("[,，/|;；\\s]+"))
            if (genres.contains(genre(normalized(part)))) return true;
        return false;
    }
    private static boolean textEquivalent(String a, String b) {
        return !normalized(a).isEmpty() && genre(normalized(a)).equals(genre(normalized(b)));
    }
    private static String genre(String value) {
        String text = value.replace("動作", "动作").replace("喜劇", "喜剧").replace("愛情", "爱情")
                .replace("動畫", "动画").replace("懸疑", "悬疑").replace("驚悚", "惊悚").replace("冒險", "冒险")
                .replace("歷史", "历史").replace("戰爭", "战争").replace("紀錄", "纪录").replace("劇情", "剧情")
                .replace("電視劇", "电视剧").replace("綜藝", "综艺").replace("古裝", "古装")
                .replaceAll("(?:电影|電影|电视剧|剧集|片|类|類)$", "")
                .replaceAll("(?<!喜)[剧劇]$", "");
        return switch (text) {
            case "comedy" -> "喜剧"; case "action" -> "动作"; case "romance" -> "爱情";
            case "sciencefiction", "scifi" -> "科幻"; case "animation", "animated" -> "动画";
            case "mystery", "suspense" -> "悬疑"; case "crime" -> "犯罪"; case "thriller" -> "惊悚";
            case "adventure" -> "冒险"; case "horror" -> "恐怖"; case "history", "historical" -> "历史";
            case "war" -> "战争"; case "fantasy" -> "奇幻"; case "family" -> "家庭";
            case "reality", "realityshow" -> "真人秀"; case "talkshow" -> "脱口秀";
            case "music" -> "音乐"; default -> text;
        };
    }
    private static boolean regionEquivalent(String a, String b) { return region(a).equals(region(b)) && !region(a).isEmpty(); }
    private static boolean regionMatches(String selected, String metadata) {
        if (clean(metadata).isEmpty()) return false;
        for (String part : metadata.split("[,，/|;；\\s]+")) {
            String value = region(part), wanted = region(selected);
            if (wanted.equals(value)) return true;
            if (wanted.equals("华语") && Set.of("中国大陆", "中国香港", "中国台湾").contains(value)) return true;
            if (wanted.equals("欧美") && Set.of("美国", "英国", "法国", "德国", "意大利", "西班牙", "俄罗斯", "加拿大").contains(value)) return true;
            if (wanted.equals("国外") && !value.isEmpty() && !Set.of("华语", "中国大陆", "中国香港", "中国台湾").contains(value)) return true;
        }
        return false;
    }
    private static String region(String label) {
        return switch (normalized(label)) {
            case "大陆", "大陸", "中国", "中國", "中国大陆", "中國大陸", "内地", "內地", "国产", "國產", "国产剧", "國產劇", "china", "cn" -> "中国大陆";
            case "香港", "中国香港", "中國香港", "港剧", "港劇", "hongkong", "hk" -> "中国香港";
            case "台湾", "台灣", "中国台湾", "中國台灣", "台剧", "台劇", "taiwan", "tw" -> "中国台湾";
            case "韩国", "韓國", "韩剧", "韓劇", "korea", "southkorea", "kr" -> "韩国";
            case "日本", "日剧", "日劇", "japan", "jp" -> "日本";
            case "美国", "美國", "美剧", "美劇", "usa", "unitedstates", "us" -> "美国";
            case "英国", "英國", "英剧", "英劇", "uk", "unitedkingdom" -> "英国";
            case "华语", "華語", "港台" -> "华语";
            case "欧美", "歐美", "欧美剧", "歐美劇" -> "欧美";
            case "海外", "国外", "國外" -> "国外";
            default -> normalized(label);
        };
    }
    private static String categoryRegion(String name) {
        for (String prefix : List.of("国产", "國產", "大陆", "大陸", "欧美", "歐美", "韩", "韓", "日", "港", "台", "美", "英"))
            if (name.startsWith(prefix) && DiscoveryContentGate.isTelevision(name))
                return region(prefix.replace("國產", "国产").replace("大陸", "大陆")
                        .replace("歐美", "欧美").replace("韓", "韩") + "剧");
        return "";
    }
    private static boolean yearEquivalent(String a, String b) {
        if (normalized(a).equals(normalized(b))) return true;
        int[] left = yearRange(a), right = yearRange(b);
        return left != null && right != null && left[0] == right[0] && left[1] == right[1];
    }
    private static boolean yearMatches(String selected, String metadata) {
        int[] range = yearRange(selected);
        if (range == null) return false;
        Matcher matcher = YEAR.matcher(clean(metadata));
        while (matcher.find()) { int year = Integer.parseInt(matcher.group()); if (year >= range[0] && year < range[1]) return true; }
        return false;
    }
    private static int exactYear(String label) {
        String value = clean(label);
        return value.matches("(?:18|19|20)\\d{2}年?") ? Integer.parseInt(value.substring(0, 4)) : -1;
    }
    private static int[] yearRange(String label) {
        String value = normalized(label);
        int year = exactYear(value);
        if (year >= 0) return new int[]{year, year + 1};
        if (value.matches("(?:19|20)\\d0年代")) { int start = Integer.parseInt(value.substring(0, 4)); return new int[]{start, start + 10}; }
        if (value.matches("[6-9]0年代")) { int start = 1900 + Integer.parseInt(value.substring(0, 2)); return new int[]{start, start + 10}; }
        if (value.equals("更早")) return new int[]{1800, 1960};
        return null;
    }
    private static boolean sortEquivalent(String selected, String label, String value) {
        String text = normalized(label);
        return switch (selected) {
            case "U" -> text.matches(".*(?:热|熱門|人气|人氣|推荐|推薦|默认|默認).*" ) || Set.of("hits", "hit", "hot", "popularity").contains(normalized(value));
            case "T", "R" -> text.matches(".*(?:时间|時間|最新|上映|首播|首映).*" ) || Set.of("time", "new", "latest", "date").contains(normalized(value));
            case "S" -> text.matches(".*(?:评分|評分|分数|分數|高分).*" ) || Set.of("score", "rating", "rate").contains(normalized(value));
            default -> false;
        };
    }
    private static String normalized(String value) { return clean(value).toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]", ""); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
