package com.fongmi.android.tv.follow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * How many episodes each followed title had when last looked at, keyed like History. The count only
 * goes up: a line vanishing for a day and coming back is not a new episode. The first look at a title
 * sets its count and reports nothing.
 */
public final class FollowList {

    static final long CHECK_EVERY_MS = TimeUnit.HOURS.toMillis(6);
    // One episode so far is most likely a film, though a series can start that way.
    static final long CHECK_SINGLE_EVERY_MS = TimeUnit.DAYS.toMillis(3);
    static final int MAX_ENTRIES = 300;

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private static final class Entry {
        int count;
        long checked;
        boolean updated, told;
    }

    FollowList(String saved) {
        load(saved);
    }

    /** The titles to look at now, those waiting longest first. */
    synchronized List<String> due(List<String> keys, long now, int limit) {
        List<String> due = new ArrayList<>();
        for (String key : keys) if (isDue(entries.get(key), now)) due.add(key);
        due.sort(Comparator.comparingLong(key -> entries.containsKey(key) ? entries.get(key).checked : 0));
        return new ArrayList<>(due.subList(0, Math.min(limit, due.size())));
    }

    private boolean isDue(Entry entry, long now) {
        if (entry == null) return true;
        long age = now - entry.checked;
        // A negative age means the clock moved back since: look again rather than wait for it to catch up.
        return age < 0 || age >= (entry.count == 1 ? CHECK_SINGLE_EVERY_MS : CHECK_EVERY_MS);
    }

    /** count 0: the source did not answer, so only the time is noted. True when there are new episodes. */
    synchronized boolean checked(String key, int count, long now) {
        Entry entry = entries.get(key);
        boolean first = entry == null;
        if (first) entries.put(key, entry = new Entry());
        entry.checked = now;
        if (count <= entry.count) return false;
        entry.count = count;
        if (first) return false;
        entry.updated = true;
        entry.told = false;
        return true;
    }

    /** The user opened the title. True when it was marked as updated. */
    synchronized boolean seen(String key, int count) {
        Entry entry = entries.computeIfAbsent(key, item -> new Entry());
        boolean updated = entry.updated;
        entry.count = Math.max(entry.count, count);
        entry.updated = false;
        return updated;
    }

    synchronized boolean updated(String key) {
        Entry entry = entries.get(key);
        return entry != null && entry.updated;
    }

    /** Updated titles not announced yet; each is returned once. */
    synchronized List<String> untold() {
        List<String> keys = new ArrayList<>();
        entries.forEach((key, entry) -> {
            if (!entry.updated || entry.told) return;
            entry.told = true;
            keys.add(key);
        });
        return keys;
    }

    private void load(String saved) {
        if (saved == null || saved.isEmpty()) return;
        try {
            JsonObject object = JsonParser.parseString(saved).getAsJsonObject();
            Gson gson = new Gson();
            for (String key : object.keySet()) {
                long[] value = gson.fromJson(object.get(key), long[].class);
                Entry entry = new Entry();
                entry.count = (int) value[0];
                entry.checked = value[1];
                entry.updated = (value[2] & 1) != 0;
                entry.told = (value[2] & 2) != 0;
                entries.put(key, entry);
            }
        } catch (RuntimeException ignored) {
            entries.clear();
        }
    }

    synchronized String save() {
        trim();
        JsonObject object = new JsonObject();
        Gson gson = new Gson();
        entries.forEach((key, entry) -> object.add(key, gson.toJsonTree(new long[]{entry.count, entry.checked, (entry.updated ? 1 : 0) | (entry.told ? 2 : 0)})));
        return object.toString();
    }

    private void trim() {
        if (entries.size() <= MAX_ENTRIES) return;
        List<String> oldest = new ArrayList<>(entries.keySet());
        oldest.removeIf(this::updated);
        oldest.sort(Comparator.comparingLong(key -> entries.get(key).checked));
        for (String key : oldest) {
            if (entries.size() <= MAX_ENTRIES) return;
            entries.remove(key);
        }
    }
}
