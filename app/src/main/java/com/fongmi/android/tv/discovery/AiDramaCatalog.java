package com.fongmi.android.tv.discovery;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A native source catalog: labels are displayed, original ids/keys/values are sent unchanged. */
public final class AiDramaCatalog {
    public static final String CATEGORY = "__category";
    public static final String SORT = "sort";
    public static final String LATEST = "online_time";

    public static final class Option {
        public final String label, value;
        public Option(String label, String value) {
            this.label = text(label).trim(); this.value = text(value);
        }
    }

    public static final class Group {
        public final String key, label, initial;
        public final List<Option> options;
        public Group(String key, String label, String initial, List<Option> options) {
            this.key = text(key); this.label = text(label).trim(); this.initial = text(initial);
            Map<String, Option> unique = new LinkedHashMap<>();
            if (options != null) for (Option option : options)
                if (option != null && !option.label.isEmpty()) unique.putIfAbsent(option.value, option);
            this.options = List.copyOf(unique.values());
        }
        Option option(String value) {
            return options.stream().filter(option -> option.value.equals(value)).findFirst().orElse(null);
        }
        String defaultValue() {
            return option(initial) != null ? initial : options.isEmpty() ? "" : options.get(0).value;
        }
    }

    public static final class Category {
        public final String id, label;
        public final List<Group> groups;
        public Category(String id, String label, List<Group> groups) {
            this.id = text(id); this.label = text(label).trim();
            Map<String, Group> unique = new LinkedHashMap<>();
            if (groups != null) for (Group group : groups)
                if (group != null && !group.key.isEmpty() && !group.key.equals(CATEGORY)
                        && !group.options.isEmpty()) unique.putIfAbsent(group.key, group);
            this.groups = List.copyOf(unique.values());
        }
    }

    private final List<Category> categories;
    private final Group categoryGroup;

    public AiDramaCatalog(List<Category> categories) {
        Map<String, Category> unique = new LinkedHashMap<>();
        if (categories != null) for (Category category : categories)
            if (category != null && !category.id.isEmpty() && !category.label.isEmpty()) unique.putIfAbsent(category.id, category);
        this.categories = List.copyOf(unique.values());
        categoryGroup = new Group(CATEGORY, "类型", "", this.categories.stream()
                .map(category -> new Option(category.label, category.id)).toList());
    }

    public List<Category> categories() { return categories; }
    public boolean isEmpty() { return categories.isEmpty(); }
    public Category category(String id) {
        return categories.stream().filter(category -> category.id.equals(id)).findFirst().orElse(null);
    }
    public List<Group> groups(State state) {
        if (isEmpty() || state == null) return List.of();
        Category category = category(state.categoryId());
        if (category == null) return List.of();
        List<Group> result = new ArrayList<>(); result.add(categoryGroup); result.addAll(category.groups);
        return List.copyOf(result);
    }

    /** Saved choices are accepted only after the same source scope supplies their current declaration. */
    public static final class State {
        private final AiDramaCatalog catalog;
        private final String scope;
        private String categoryId;
        private final Map<String, String> values = new LinkedHashMap<>();

        public State(AiDramaCatalog catalog, String scope) {
            this.catalog = Objects.requireNonNull(catalog); this.scope = text(scope);
            categoryId = catalog.isEmpty() ? "" : catalog.categories.get(0).id;
            defaults();
        }
        public String categoryId() { return categoryId; }
        public String value(String key) { return CATEGORY.equals(key) ? categoryId : values.getOrDefault(key, ""); }
        public Map<String, String> extend() { return Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
        public List<Group> groups() { return catalog.groups(this); }

        public boolean select(String key, String value) {
            if (CATEGORY.equals(key)) {
                Category next = catalog.category(value), previous = catalog.category(categoryId);
                if (next == null || categoryId.equals(value)) return false;
                Map<String, Option> compatible = new LinkedHashMap<>();
                if (previous != null) for (Group group : previous.groups) {
                    Option option = group.option(values.get(group.key));
                    if (option != null) compatible.put(group.key, option);
                }
                categoryId = value; defaults();
                for (Group group : next.groups) {
                    Option old = compatible.get(group.key), match = old == null ? null : group.option(old.value);
                    if (match != null && match.label.equals(old.label)) values.put(group.key, match.value);
                }
                return true;
            }
            Category category = catalog.category(categoryId);
            if (category == null) return false;
            Group group = category.groups.stream().filter(item -> item.key.equals(key)).findFirst().orElse(null);
            if (group == null || group.option(value) == null || value.equals(values.get(key))) return false;
            // The audited app endpoint cannot combine online-time ordering with a
            // topic selector. Keep the two controls mutually exclusive in state so
            // persisted requests can never describe an unsupported query.
            values.put(key, value);
            if (SORT.equals(key) && LATEST.equals(value)) {
                Group topic = category.groups.stream().filter(item -> "topic".equals(item.key)).findFirst().orElse(null);
                if (topic != null) values.put(topic.key, topic.defaultValue());
            } else if ("topic".equals(key) && !value.isEmpty()) {
                Group sort = category.groups.stream().filter(item -> SORT.equals(item.key)).findFirst().orElse(null);
                if (sort != null) values.put(sort.key, sort.defaultValue());
            }
            return true;
        }

        private void defaults() {
            values.clear();
            Category category = catalog.category(categoryId);
            if (category != null) for (Group group : category.groups) values.put(group.key, group.defaultValue());
        }
        public State snapshot() {
            State copy = new State(catalog, scope);
            copy.categoryId = categoryId; copy.values.clear(); copy.values.putAll(values);
            return copy;
        }
        public String serialize() {
            JsonObject json = new JsonObject(), extend = new JsonObject();
            json.addProperty("scope", scope); json.addProperty("category", categoryId);
            values.forEach(extend::addProperty); json.add("extend", extend); return json.toString();
        }
        public void restore(String saved) {
            if (saved == null || saved.isEmpty() || scope.isEmpty()) return;
            try {
                JsonElement parsed = JsonParser.parseString(saved);
                if (!parsed.isJsonObject()) return;
                JsonObject json = parsed.getAsJsonObject();
                if (!scope.equals(string(json.get("scope")))) return;
                String restored = string(json.get("category"));
                if (catalog.category(restored) == null) return;
                categoryId = restored; defaults();
                JsonElement extend = json.get("extend");
                if (extend != null && extend.isJsonObject())
                    for (var entry : extend.getAsJsonObject().entrySet()) {
                        if (CATEGORY.equals(entry.getKey())) continue;
                        String value = string(entry.getValue());
                        if (value != null) select(entry.getKey(), value);
                    }
            } catch (RuntimeException ignored) { /* Damaged or obsolete choices retain real defaults. */ }
        }
    }

    private static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
    private static String text(String value) { return value == null ? "" : value; }
}
