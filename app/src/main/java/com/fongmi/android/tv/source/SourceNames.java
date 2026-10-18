package com.fongmi.android.tv.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Only aggregate metadata names a line; individual sites are not the aggregate. */
public final class SourceNames {
    public static String metadata(JsonObject object) {
        for (String key : new String[]{"name", "title", "config_name"}) {
            JsonElement value = object.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
            String name = value.getAsString().trim();
            if (!name.isEmpty() && name.length() <= 60 && name.chars().noneMatch(Character::isISOControl)) return name;
        }
        return "";
    }
    public static String fallback(String url) {
        URI uri = URI.create(url);
        String path = uri.getPath();
        String file = path == null ? "" : path.substring(path.lastIndexOf('/')+1);
        file = file.replaceFirst("(?i)\\.(json|txt)$", "");
        Set<String> generic = Set.of("", "index", "config", "tvbox", "api", "tv", "all", "vod");
        if (!generic.contains(file.toLowerCase(Locale.ROOT)) && file.length() <= 60
                && !file.contains(".") && !file.matches("[0-9a-fA-F-]{16,}") && file.chars().noneMatch(Character::isISOControl)) return file;
        return SourceInput.host(url);
    }
}
