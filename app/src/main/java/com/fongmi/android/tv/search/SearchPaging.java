package com.fongmi.android.tv.search;

import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Further pages of one source in the aggregate search results, with the source's own cursor, automatic
 * retries of a failed page and a short scan past pages whose cards are all filtered out. Main thread.
 */
public final class SearchPaging {

    static final int MAX_AUTO_RETRIES = 2;
    static final long RETRY_DELAY_MS = 700L;
    static final int MAX_EMPTY_SCANS = 3;

    /** The results screen. */
    public interface Host {
        /** The source shown alone, or "" for every source (which does not page). */
        String selected();

        /** False for a letters search with candidate titles: it has no single query to page. */
        boolean canPage();

        /** The last visible card is within a few of the end. */
        boolean nearEnd();

        Site source(String key);

        String query();

        boolean alive();

        /** Adds a page's cards; says whether any card was new and whether the shown list grew. */
        Appended append(String key, List<Vod> page);

        void updateEmpty();

        void post(Runnable task);

        void postDelayed(Runnable task, long delayMs);

        void remove(Runnable task);
    }

    public record Appended(boolean newCards, boolean shownGrew) {
    }

    public interface Job {
        void cancel();
    }

    public interface Fetcher {
        /**
         * Starts a page; the callbacks may come on any thread.
         *
         * @throws RejectedExecutionException when the page cannot be started now
         */
        Job fetch(Site source, String query, int page, Consumer<List<Vod>> done, Consumer<Throwable> failed);
    }

    private final Host host;
    private final Fetcher fetcher;
    private final Map<String, Integer> pages = new HashMap<>();
    private final Set<String> exhausted = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private final Map<String, Integer> attempts = new HashMap<>();
    private final Map<String, Runnable> pendingRetries = new HashMap<>();
    private String loadingKey;
    private Job job;
    private int epoch;
    private int emptyScans;

    public SearchPaging(Host host, Fetcher fetcher) {
        this.host = host;
        this.fetcher = fetcher;
    }

    /** A new search: every source starts again at its first page. */
    public void reset() {
        cancel();
        pages.clear();
        exhausted.clear();
        failed.clear();
        attempts.clear();
        emptyScans = 0;
    }

    /** The user picked a source; picking another one is a new attempt at its failed page. */
    public void select(String key, boolean changed) {
        if (changed) {
            cancel();
            failed.remove(key);
            attempts.keySet().removeIf(value -> value.startsWith(key + "\u0000"));
        }
        emptyScans = 0;
    }

    /** Stops the page in flight and any retry waiting; their late results are ignored. */
    public void cancel() {
        epoch++;
        Job running = job;
        job = null;
        loadingKey = null;
        if (running != null) running.cancel();
        for (Runnable retry : pendingRetries.values()) host.remove(retry);
        pendingRetries.clear();
    }

    public boolean isExhausted(String key) {
        return exhausted.contains(key);
    }

    public int emptyScans() {
        return emptyScans;
    }

    public boolean isLoading() {
        return loadingKey != null;
    }

    /**
     * @param retry   a failed page may be asked again
     * @param scanning the previous page added nothing visible; counts towards the scan limit
     * @param force   ask even when the list is not scrolled near its end
     */
    public void next(boolean retry, boolean scanning, boolean force) {
        if (!host.canPage()) return;
        String key = host.selected();
        if (key.isEmpty() || loadingKey != null || exhausted.contains(key) || (!retry && failed.contains(key))) return;
        if (scanning && emptyScans >= MAX_EMPTY_SCANS) return;
        if (!force && !host.nearEnd()) return;
        Site source = host.source(key);
        if (source == null) return;
        int page = pages.getOrDefault(key, 1) + 1;
        int token = ++epoch;
        loadingKey = key;
        failed.remove(key);
        try {
            Job started = fetcher.fetch(source, host.query(), page,
                    list -> host.post(() -> done(token, key, page, list)),
                    error -> host.post(() -> failed(token, key, page)));
            if (token == epoch && loadingKey != null) job = started;
        } catch (RejectedExecutionException rejected) {
            loadingKey = null;
            failed.add(key);
            scheduleRetry(key, page, token);
        }
    }

    private void done(int token, String key, int page, List<Vod> list) {
        if (token != epoch || !host.alive()) return;
        job = null;
        loadingKey = null;
        Appended appended = host.append(key, list);
        if (appended.newCards()) pages.put(key, page);
        else exhausted.add(key);
        attempts.remove(retryKey(key, page));
        if (!host.selected().equals(key)) return;
        // A filtered-out page still advances the source's cursor. Scan a few pages while
        // nothing new shows, then leave it to a click on the source.
        if (appended.newCards() && !appended.shownGrew()) {
            emptyScans++;
            host.updateEmpty();
            host.post(() -> next(false, true, false));
        } else {
            emptyScans = 0;
        }
    }

    private void failed(int token, String key, int page) {
        if (token != epoch || !host.alive()) return;
        job = null;
        loadingKey = null;
        failed.add(key);
        scheduleRetry(key, page, token);
    }

    private void scheduleRetry(String key, int page, int token) {
        if (!host.selected().equals(key) || !host.alive()) return;
        String retryKey = retryKey(key, page);
        int attempt = attempts.getOrDefault(retryKey, 0);
        if (attempt >= MAX_AUTO_RETRIES) {
            host.updateEmpty();
            return;
        }
        attempts.put(retryKey, attempt + 1);
        Runnable retry = () -> {
            pendingRetries.remove(retryKey);
            if (token != epoch || !host.selected().equals(key) || !host.alive()) return;
            next(true, false, true);
        };
        Runnable old = pendingRetries.put(retryKey, retry);
        if (old != null) host.remove(old);
        host.postDelayed(retry, RETRY_DELAY_MS * (attempt + 1L));
    }

    private static String retryKey(String key, int page) {
        return key + "\u0000" + page;
    }
}
