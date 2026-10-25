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
    private static final long REFRESH_MS = 6 * 60 * 60 * 1000L;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(BackgroundThreads.factory("short-drama-titles"));

    private record Entry(String title, String initials, String pinyin) {}

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
                    List<String> titles = fetch(site);
                    if (!titles.isEmpty()) {
                        index = build(titles);
                        Prefers.put(PREF, App.gson().toJson(titles));
                        loadedAt = System.currentTimeMillis();
                        loadedRevision = revision;
                    }
                } catch (Throwable ignored) {
                    // Keep the saved index; the next search page opening retries.
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
                int initials = position(entry.initials, q), pinyin = position(entry.pinyin, q);
                at = initials < 0 ? pinyin : pinyin < 0 ? initials : Math.min(initials, pinyin);
            } else at = entry.title.toLowerCase(Locale.ROOT).indexOf(q);
            if (at == 0) first.add(entry.title);
            else if (at > 0) rest.add(entry.title);
            if (first.size() >= limit) break;
        }
        first.addAll(rest);
        return first.subList(0, Math.min(limit, first.size()));
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
        List<Entry> entries = new ArrayList<>(titles.size());
        for (String title : titles) {
            String initials = "", pinyin = "";
            if (latin != null) {
                StringBuilder first = new StringBuilder(), full = new StringBuilder();
                for (String part : latin.transliterate(title).split("[^a-z0-9]+")) {
                    if (part.isEmpty()) continue;
                    first.append(Character.isDigit(part.charAt(0)) ? part : part.substring(0, 1));
                    full.append(part);
                }
                initials = first.toString();
                pinyin = full.toString();
            }
            entries.add(new Entry(title, initials, pinyin));
        }
        return entries;
    }
}
