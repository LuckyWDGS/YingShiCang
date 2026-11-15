package com.fongmi.android.tv.api.loader;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Source classes a plugin package turned out not to hold, kept across launches for that exact package
 * (Xiaomi TV: the configuration names csp_XPathGuard, the package has no such class, and every launch
 * tried it again). A new package, or a month, asks again.
 */
final class MissingClasses {

    static final long KEEP_MS = 30L * 24 * 60 * 60 * 1000;
    private static final int MAX = 16;

    private record Entry(String artifact, long time) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    MissingClasses(String saved) {
        if (saved == null) return;
        for (String line : saved.split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty()) continue;
            try {
                entries.put(parts[0], new Entry(parts[1], Long.parseLong(parts[2])));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /** Whether anything is kept for this class; the package is hashed only then. */
    synchronized boolean tracks(String api) {
        return entries.containsKey(api);
    }

    synchronized boolean known(String api, String artifact, long now) {
        Entry entry = entries.get(api);
        if (entry == null || artifact == null || !entry.artifact().equals(artifact)) return false;
        long age = now - entry.time();
        return age >= 0 && age < KEEP_MS;
    }

    /** True when what is kept changed. */
    synchronized boolean remember(String api, String artifact, long now) {
        if (api == null || api.isEmpty() || artifact == null || artifact.isEmpty()) return false;
        if (known(api, artifact, now)) return false;
        entries.remove(api);
        entries.put(api, new Entry(artifact, now));
        Iterator<String> oldest = entries.keySet().iterator();
        while (entries.size() > MAX && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    /** The class is there after all (another package now): true when something was dropped. */
    synchronized boolean forget(String api) {
        return entries.remove(api) != null;
    }

    synchronized String save() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(item.getKey()).append('\t').append(item.getValue().artifact()).append('\t').append(item.getValue().time());
        }
        return builder.toString();
    }
}
