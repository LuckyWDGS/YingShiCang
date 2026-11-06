package com.fongmi.android.tv.resource;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;
import com.github.catvod.utils.Crypto;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Sources that keep failing sit out for a while, so a search or a filter does not wait on them every
 * time. The caller reports a failed round only when another source answered in it: with the network
 * down everything fails and nothing here should change. A source that fails again after its rest goes
 * straight back; one that answers is forgotten.
 */
public final class SourceBench {

    static final int FAILED_ROUNDS = 3;
    static final long REST_MS = TimeUnit.MINUTES.toMillis(30);

    private static volatile SourceBench search, discovery;

    private final Map<String, Entry> entries = new HashMap<>();
    private final LongSupplier clock;
    private final Consumer<String> saver;
    private final Consumer<String> report;

    private static final class Entry {
        int rounds;
        long until;
    }

    public SourceBench() {
        this(System::currentTimeMillis, "", value -> {}, message -> {});
    }

    SourceBench(LongSupplier clock, String saved, Consumer<String> saver, Consumer<String> report) {
        this.clock = clock;
        this.saver = saver;
        this.report = report;
        load(saved);
    }

    public static synchronized SourceBench search() {
        if (search == null) search = lane("search");
        return search;
    }

    public static synchronized SourceBench discovery() {
        if (discovery == null) discovery = lane("discovery");
        return discovery;
    }

    private static synchronized SourceBench lane(String name) {
        String key = "source_bench_" + name;
        return new SourceBench(System::currentTimeMillis, Prefers.getString(key), value -> Prefers.put(key, value),
                message -> DiagnosticLog.write(App.get(), "source_bench", "lane=" + name + " " + message));
    }

    /** The items not resting, or all of them when every one is: better a slow answer than none. */
    public synchronized <T> List<T> active(List<T> items, Function<T, String> id) {
        List<T> active = new ArrayList<>();
        for (T item : items) if (!resting(id.apply(item))) active.add(item);
        if (active.isEmpty()) return items;
        if (active.size() < items.size()) report.accept("skipped=" + (items.size() - active.size()) + " of=" + items.size());
        return active;
    }

    public synchronized boolean resting(String id) {
        return remaining(id) > 0;
    }

    public synchronized long remaining(String id) {
        Entry entry = entries.get(id);
        if (entry == null || entry.until == 0) return 0;
        long left = entry.until - clock.getAsLong();
        // Further ahead than a rest can be: the clock moved back, as on TVs without a battery-backed clock.
        return left > 0 && left <= REST_MS ? left : 0;
    }

    public synchronized void failed(String id) {
        if (id == null || id.isEmpty() || resting(id)) return;
        Entry entry = entries.computeIfAbsent(id, key -> new Entry());
        entry.rounds = Math.min(entry.rounds + 1, FAILED_ROUNDS);
        if (entry.rounds >= FAILED_ROUNDS) {
            entry.until = clock.getAsLong() + REST_MS;
            report.accept("rest=" + Crypto.md5(id));
        }
        save();
    }

    public synchronized void answered(String id) {
        if (entries.remove(id) != null) save();
    }

    private void load(String saved) {
        if (saved == null || saved.isEmpty()) return;
        try {
            JsonObject object = JsonParser.parseString(saved).getAsJsonObject();
            for (String id : object.keySet()) {
                long[] value = new Gson().fromJson(object.get(id), long[].class);
                Entry entry = new Entry();
                entry.rounds = (int) value[0];
                entry.until = value[1];
                entries.put(id, entry);
            }
        } catch (RuntimeException ignored) {
            entries.clear();
        }
    }

    private void save() {
        JsonObject object = new JsonObject();
        Gson gson = new Gson();
        entries.forEach((id, entry) -> object.add(id, gson.toJsonTree(new long[]{entry.rounds, entry.until})));
        saver.accept(object.toString());
    }
}
