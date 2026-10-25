package com.fongmi.android.tv.discovery;

import android.os.Build;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.utils.BackgroundThreads;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Titles from the 短剧漫剧 (AiDj) source for typed search suggestions (user 2026-10-24: the suggestions
 * only covered TV, film and variety). The TV keyboard types letters, usually pinyin initials, which a
 * plugin search cannot match, so the first pages of each AI type are kept as a small local index and
 * matched by Chinese text, initials or full pinyin. Pinyin needs Android 10's ICU transliterator; older
 * systems match Chinese text only.
 */
public final class ShortDramaTitles {

    private static final String PREF = "short_drama_titles";
    private static final int PAGES = 5, MAX_TITLES = 600;
    private static final long REFRESH_MS = 6 * 60 * 60 * 1000L, RETRY_MS = 8_000;
    private static final int ATTEMPTS = 3;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(BackgroundThreads.factory("short-drama-titles"));

    private record Entry(String title, String[] initials, String pinyin) {}

    private static volatile List<Entry> index;
    private static volatile long loadedAt;
    private static volatile long loadedRevision = Long.MIN_VALUE;
    private static boolean loading;

    private ShortDramaTitles() {}

    /** Builds the index from the last saved titles at once and refreshes it from the source in the background. */
    public static void prepare(Site site) {
        if (site == null) return;
        synchronized (ShortDramaTitles.class) {
            long revision = VodConfig.getDiscoveryRevision();
            boolean fresh = revision == loadedRevision && System.currentTimeMillis() - loadedAt < REFRESH_MS;
            if (loading || fresh) return;
            loading = true;
            WORKER.execute(() -> {
                try {
                    if (index == null) index = build(saved());
                    // The plugin may still be starting behind the search warm-up; try a few times.
                    for (int attempt = 1; attempt <= ATTEMPTS && revision == VodConfig.getDiscoveryRevision(); attempt++) {
                        long start = android.os.SystemClock.elapsedRealtime();
                        try {
                            List<String> titles = fetch(site);
                            log("count=" + titles.size() + " ms=" + (android.os.SystemClock.elapsedRealtime() - start) + " attempt=" + attempt);
                            if (titles.isEmpty()) break;
                            index = build(titles);
                            Prefers.put(PREF, App.gson().toJson(titles));
                            loadedAt = System.currentTimeMillis();
                            loadedRevision = revision;
                            break;
                        } catch (Throwable error) {
                            Throwable root = error;
                            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                            log("failed=" + error.getClass().getSimpleName() + "/" + root.getClass().getSimpleName() + " ms=" + (android.os.SystemClock.elapsedRealtime() - start) + " attempt=" + attempt);
                            Thread.sleep(RETRY_MS);
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    synchronized (ShortDramaTitles.class) { loading = false; }
                }
            });
        }
    }

    /** Titles matching the typed text: titles starting with it first, then titles containing it. */
    public static List<String> match(String query, int limit) {
        List<Entry> entries = index;
        String q = query.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (entries == null || q.isEmpty()) return List.of();
        boolean latin = q.chars().allMatch(c -> c < 128);
        List<String> first = new ArrayList<>(), rest = new ArrayList<>();
        for (Entry entry : entries) {
            int at;
            if (latin) {
                int initials = TitleInitials.indexOf(entry.initials, q), pinyin = position(entry.pinyin, q);
                at = initials < 0 ? pinyin : pinyin < 0 ? initials : Math.min(initials, pinyin);
            } else at = entry.title.toLowerCase(Locale.ROOT).indexOf(q);
            if (at == 0) first.add(entry.title);
            else if (at > 0) rest.add(entry.title);
            if (first.size() >= limit) break;
        }
        first.addAll(rest);
        return first.subList(0, Math.min(limit, first.size()));
    }

    private static void log(String detail) {
        com.fongmi.android.tv.diagnostics.DiagnosticLog.write(App.get(), "short_drama_titles", detail);
    }

    private static int position(String text, String query) {
        return text.isEmpty() ? -1 : text.indexOf(query);
    }

    private static List<String> saved() {
        try {
            List<String> titles = App.gson().fromJson(Prefers.getString(PREF, "[]"), TypeToken.getParameterized(List.class, String.class).getType());
            return titles == null ? List.of() : titles;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> fetch(Site site) throws Exception {
        Set<String> titles = new LinkedHashSet<>();
        for (Class type : SiteApi.discoveryNativeAiClasses(site).getTypes()) {
            if (type == null || type.isFolder() || type.isDiscoveryAdult() || DiscoveryContentGate.isAdult(type.getTypeName())) continue;
            for (int page = 1; page <= PAGES && titles.size() < MAX_TITLES; page++) {
                List<Vod> list = SiteApi.discoveryPage(site, type.getTypeId(), page, Map.of(), OkHttp.client(), call -> {}).getList();
                if (list.isEmpty()) break;
                for (Vod vod : list) {
                    if (vod == null || DiscoveryContentGate.isAdult(vod.getTypeName(), vod.getDiscoveryTags())) continue;
                    String name = vod.getName().trim();
                    if (!name.isEmpty()) titles.add(name);
                }
            }
        }
        return new ArrayList<>(titles);
    }

    private static List<Entry> build(List<String> titles) {
        android.icu.text.Transliterator latin = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { latin = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower"); }
            catch (Exception ignored) { /* Chinese text matching still works. */ }
        }
        android.icu.text.Transliterator reader = latin;
        java.util.function.Function<String, String> reading = han -> reader == null ? "" : reader.transliterate(han).replaceAll("[^a-z]", "");
        List<Entry> entries = new ArrayList<>(titles.size());
        for (String title : titles) {
            String pinyin = latin == null ? "" : latin.transliterate(title).replaceAll("[^a-z0-9]", "");
            entries.add(new Entry(title, TitleInitials.slots(title, reading), pinyin));
        }
        return entries;
    }
}
