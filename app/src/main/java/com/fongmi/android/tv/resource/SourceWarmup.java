package com.fongmi.android.tv.resource;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;
import com.fongmi.android.tv.utils.BackgroundThreads;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Starts source plugins while Home sits idle, so the first title or search after launch does not wait for
 * them (user 2026-11-14: 玩偶哥哥 and others need a while on a cold start). Sources the user actually uses
 * come first (home, favorites, recent history); the search sources follow only on devices with room for
 * them. One plugin at a time, so a title opened meanwhile still finds free start permits, and it stops as
 * soon as Home is left.
 */
public final class SourceWarmup {

    static final long DELAY_MS = 10_000;
    static final long RECENT_MS = TimeUnit.DAYS.toMillis(30);
    static final long SEARCH_MEMORY_BYTES = 3L * 1024 * 1024 * 1024;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(BackgroundThreads.factory("source-warmup"));
    private static final Runnable START = SourceWarmup::start;
    private static volatile long warmedRevision = Long.MIN_VALUE;
    private static volatile boolean onHome;

    private SourceWarmup() {
    }

    /** Home is on screen with its configuration loaded. */
    public static void resume() {
        onHome = true;
        App.removeCallbacks(START);
        if (warmedRevision != VodConfig.getDiscoveryRevision()) App.post(START, DELAY_MS);
    }

    public static void pause() {
        onHome = false;
        App.removeCallbacks(START);
    }

    private static void start() {
        if (!onHome || !VodConfig.get().isReady()) return;
        long revision = VodConfig.getDiscoveryRevision();
        if (warmedRevision == revision) return;
        warmedRevision = revision;
        List<Site> used = used();
        List<Site> search = searchAllowed() ? VodConfig.get().getSites() : List.of();
        WORKER.execute(() -> warm(order(used, search), revision));
    }

    private static void warm(List<Site> sites, long revision) {
        long started = SystemClock.elapsedRealtime();
        int warmed = 0;
        String stopped = "done";
        for (Site site : sites) {
            if (!onHome) { stopped = "left_home"; break; }
            if (revision != VodConfig.getDiscoveryRevision()) { stopped = "config_changed"; break; }
            if (SiteApi.warm(site)) warmed++;
        }
        // Leaving Home early: the rest is tried on the next Home visit.
        if (!"done".equals(stopped) && revision == VodConfig.getDiscoveryRevision()) warmedRevision = Long.MIN_VALUE;
        DiagnosticLog.write(App.get(), "source_warmup", "sources=" + sites.size() + " started=" + warmed
                + " ms=" + (SystemClock.elapsedRealtime() - started) + " outcome=" + stopped);
    }

    /** Home, favorites and recent history first, then the search sources, each once. */
    static List<Site> order(List<Site> used, List<Site> search) {
        Set<Site> sites = new LinkedHashSet<>(used);
        for (Site site : search) if (site.isSearchable()) sites.add(site);
        sites.removeIf(site -> site == null || site.getType() != 3);
        return new ArrayList<>(sites);
    }

    private static List<Site> used() {
        List<Site> sites = new ArrayList<>();
        VodConfig config = VodConfig.get();
        sites.add(config.getHome());
        int cid = VodConfig.getCid();
        try {
            for (Keep item : Keep.getVod()) if (item.getCid() == cid) sites.add(config.getSite(item.getSiteKey()));
            long oldest = System.currentTimeMillis() - RECENT_MS;
            for (History item : History.get()) if (item.getCreateTime() >= oldest) sites.add(config.getSite(item.getSiteKey()));
        } catch (RuntimeException ignored) {
            // Diagnostics-free best effort: the database may be busy; the home site still warms.
        }
        sites.removeIf(site -> site == null || site.isEmpty());
        return sites;
    }

    /** Starting every search plugin costs memory a 2 GB TV may not have (TCL t982, native crash while searching). */
    private static boolean searchAllowed() {
        try {
            ActivityManager manager = (ActivityManager) App.get().getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return !manager.isLowRamDevice() && info.totalMem >= SEARCH_MEMORY_BYTES;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
