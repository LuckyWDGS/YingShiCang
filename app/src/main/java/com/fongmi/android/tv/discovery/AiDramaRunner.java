package com.fongmi.android.tv.discovery;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.compat.PythonPluginCompatibility;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.OkHttpClient;

/** Independent native AiDj browsing; source order, category parameters and origin ids are retained. */
public final class AiDramaRunner {
    public record Batch(AiDramaCatalog catalog, AiDramaCatalog.State state, List<Vod> items,
                        boolean more, boolean failed, boolean finished) {}

    public static final class MetadataCache {
        private long revision = -1;
        private final Map<String, AiDramaCatalog> entries = new HashMap<>();
        synchronized AiDramaCatalog get(long scope, String signature) {
            if (scope < revision) return null;
            if (scope != revision) { revision = scope; entries.clear(); }
            return entries.get(signature);
        }
        synchronized void put(long scope, String signature, AiDramaCatalog catalog) {
            // Empty/error catalogs are retryable, and old work cannot replace a newer scope.
            if (scope == revision && catalog != null && !catalog.isEmpty()) entries.put(signature, catalog);
        }
        public synchronized void clear() { revision = -1; entries.clear(); }
    }

    /** Failure leaves the exact raw page available; accepted pages preserve source order and identity. */
    static final class Pages<T> {
        private SourcePageCursor cursor = new SourcePageCursor();
        private final Map<String, T> items = new LinkedHashMap<>();
        int next() { return cursor.next(); }
        boolean more() { return !cursor.exhausted(); }
        List<T> items() { return List.copyOf(items.values()); }
        void accept(List<String> raw, int pageCount, Map<String, T> accepted) {
            accepted.forEach(items::putIfAbsent); cursor.accept(raw, pageCount);
        }
        void reset() { cursor = new SourcePageCursor(); items.clear(); }
    }

    // Shared only by this feature: cancelled, interrupt-ignoring plugins cannot create unbounded threads.
    private static final ThreadPoolExecutor WORKERS = pool(2, 4, "ai-drama-source");
    private final ThreadPoolExecutor coordinator = pool(1, 1, "ai-drama-coordinator");
    private final VodConfig config = VodConfig.get();
    private final long revision = VodConfig.getDiscoveryRevision();
    private final Site site;
    private final String signature, savedState;
    private final MetadataCache cache;
    private final OkHttpClient client;
    private final AtomicLong epoch = new AtomicLong();
    private final Pages<Vod> pages = new Pages<>();
    private AiDramaCatalog catalog;
    private AiDramaCatalog.State state;
    private Work active;
    private Future<?> round;
    private volatile boolean closed;
    private boolean busy, failed;

    private static final class Work {
        final long token, deadline;
        final int page;
        final AiDramaCatalog.State state;
        final List<Call> calls = new CopyOnWriteArrayList<>();
        volatile boolean cancelled;
        volatile Future<Outcome> future;
        Work(long token, int page, AiDramaCatalog.State state, long timeout) {
            this.token = token; this.page = page; this.state = state;
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        }
        void track(Call call) {
            call.timeout().deadlineNanoTime(deadline); calls.add(call);
            if (cancelled) call.cancel();
        }
        void cancel() {
            cancelled = true; calls.forEach(Call::cancel);
            if (future != null) future.cancel(true);
        }
    }
    private record Outcome(AiDramaCatalog catalog, AiDramaCatalog.State state, Result result, boolean failed) {}

    public AiDramaRunner(OkHttpClient client, MetadataCache cache, String savedState) {
        this.client = client; this.cache = cache; this.savedState = savedState;
        site = config.getSites().stream().filter(candidate -> candidate.getType() == 3
                && !candidate.getKey().isEmpty() && supportedApi(candidate.getApi())).findFirst().orElse(null);
        signature = site == null ? "" : signature(site);
    }

    private static ThreadPoolExecutor pool(int threads, int queue, String name) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue), runnable -> {
            Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    static boolean supportedApi(String api) {
        return "csp_AiDj".equals(api) || "csp_AiDjGuard".equals(api);
    }
    private static String signature(Site site) {
        String value = site.getKey() + "\0" + site.getType() + "\0" + site.getApi() + "\0" + site.getExt()
                + "\0" + site.getJar() + "\0" + site.getCategories() + "\0" + site.getHeader();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte part : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
            return result.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    public boolean current() {
        if (closed || config != VodConfig.get() || revision != VodConfig.getDiscoveryRevision()) return false;
        if (site == null) return config.getSites().stream().noneMatch(candidate -> candidate.getType() == 3
                && !candidate.getKey().isEmpty() && supportedApi(candidate.getApi()));
        return config.getSite(site.getKey()) == site && signature.equals(signature(site));
    }
    public boolean owns(Vod item) { return current() && site != null && item != null && item.getSite() == site; }
    public synchronized AiDramaCatalog.State state() { return state == null ? null : state.snapshot(); }
    public synchronized List<AiDramaCatalog.Group> groups() { return state == null ? List.of() : catalog.groups(state); }

    /** UI-only state mutation: it never initializes a spider or makes a network request. */
    public synchronized boolean select(String key, String value) {
        if (closed || state == null || !state.select(key, value)) return false;
        invalidate(); pages.reset(); failed = false; return true;
    }
    private boolean valid(Work work) {
        return !work.cancelled && work.token == epoch.get() && current() && !Thread.currentThread().isInterrupted();
    }

    /** false loads the first/next page, true retries the failed page without advancing it. */
    public synchronized void load(boolean retry, Consumer<Batch> callback) {
        if (closed || busy) return;
        if (site == null) { failed = true; publish(null, true, callback); return; }
        if (!current()) { failed = true; publish(null, true, callback); return; }
        if (!pages.more()) { publish(null, true, callback); return; }
        long token = epoch.incrementAndGet();
        Work work = new Work(token, pages.next(), state == null ? null : state.snapshot(),
                Math.min(20000, Math.max(5000, site.getTimeout())));
        active = work; busy = true; failed = false;
        try {
            round = coordinator.submit(() -> run(work, callback));
        } catch (RuntimeException unavailable) { busy = false; failed = true; publish(work, true, callback); }
    }
    private void run(Work work, Consumer<Batch> callback) {
        Outcome outcome;
        try {
            work.future = WORKERS.submit(() -> execute(work, callback));
            if (work.cancelled) work.future.cancel(true);
            outcome = work.future.get(Math.max(1, work.deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            work.cancel(); outcome = new Outcome(null, null, null, true);
        }
        Outcome result = outcome;
        App.post(() -> apply(work, result, callback));
    }
    private Outcome execute(Work work, Consumer<Batch> callback) {
        try {
            if (!valid(work)) throw new CancellationException();
            // An API spelling alone is not proof that a replacement plugin has the audited runtime.
            if (!PythonPluginCompatibility.requiresRuntime(site.spider())) return new Outcome(null, null, null, true);
            if (!valid(work)) throw new CancellationException();
            AiDramaCatalog metadata = cache.get(revision, signature);
            if (metadata == null) {
                metadata = fromResult(SiteApi.discoveryClasses(site, client, work::track));
                if (!valid(work)) throw new CancellationException();
                cache.put(revision, signature, metadata);
            }
            if (metadata.isEmpty()) return new Outcome(metadata, null, null, true);
            AiDramaCatalog.State requested = work.state;
            if (requested == null) {
                requested = new AiDramaCatalog.State(metadata, signature); requested.restore(savedState);
                AiDramaCatalog ready = metadata;
                AiDramaCatalog.State defaults = requested.snapshot();
                App.post(() -> metadataReady(work, ready, defaults, callback));
            }
            if (!valid(work)) throw new CancellationException();
            Result result = SiteApi.discoveryPage(site, requested.categoryId(), work.page, requested.extend(), client, work::track);
            if (!valid(work)) throw new CancellationException();
            return new Outcome(metadata, requested, result, false);
        } catch (Exception error) { return new Outcome(null, null, null, true); }
    }
    private synchronized void metadataReady(Work work, AiDramaCatalog metadata, AiDramaCatalog.State requested, Consumer<Batch> callback) {
        if (!valid(work)) return;
        catalog = metadata; state = requested; publish(work, false, callback);
    }
    private synchronized void apply(Work work, Outcome outcome, Consumer<Batch> callback) {
        // A timeout cancelled the job, but its failure still completes this current UI request.
        if (closed || work.token != epoch.get() || !current()) return;
        busy = false; active = null; work.calls.clear(); failed = outcome.failed;
        if (outcome.catalog != null) catalog = outcome.catalog;
        if (outcome.state != null) state = outcome.state;
        if (!outcome.failed && outcome.result != null) {
            List<String> raw = new ArrayList<>(); Map<String, Vod> accepted = new LinkedHashMap<>();
            for (Vod item : outcome.result.getList()) {
                if (item == null) continue;
                if (!item.getId().isEmpty()) raw.add(item.getId());
                if (item.getId().isEmpty() || item.getName().isEmpty() || item.isFolder()
                        || DiscoveryContentGate.isAdult(item.getTypeName(), item.getDiscoveryTags())) continue;
                item.setSite(site); item.setAction("");
                accepted.putIfAbsent(site.getKey() + "\0" + item.getId(), item);
            }
            pages.accept(raw, outcome.result.getPageCount(), accepted);
        }
        publish(work, true, callback); WORKERS.purge();
    }
    private synchronized void publish(Work work, boolean finished, Consumer<Batch> callback) {
        long token = work == null ? epoch.get() : work.token;
        Batch batch = new Batch(catalog, state == null ? null : state.snapshot(), pages.items(),
                site != null && pages.more(), failed, finished);
        App.post(() -> {
            if (!closed && token == epoch.get() && current()) callback.accept(batch);
        });
    }

    private static AiDramaCatalog fromResult(Result result) {
        List<AiDramaCatalog.Category> categories = new ArrayList<>();
        for (com.fongmi.android.tv.bean.Class type : result.getTypes()) {
            if (type == null || type.isFolder() || type.isDiscoveryAdult() || DiscoveryContentGate.isAdult(type.getTypeName())) continue;
            List<AiDramaCatalog.Group> groups = new ArrayList<>();
            for (Filter filter : type.getFilters()) {
                if (filter == null || filter.getKey() == null || filter.getKey().isEmpty()) continue;
                List<AiDramaCatalog.Option> options = new ArrayList<>();
                for (var value : filter.getValue()) {
                    if (value == null || value.isDiscoveryAdult()
                            || DiscoveryContentGate.isAdultAgeChoice(filter.getKey(), filter.getName(), value.getN(), value.getV())
                            || DiscoveryContentGate.isAdult(value.getN())) continue;
                    options.add(new AiDramaCatalog.Option(value.getN(), value.getV()));
                }
                // Audited AiDj labels this topic facet "分类"; distinguish it from the native type row.
                String label = "topic".equals(filter.getKey()) && "分类".equals(filter.getName()) ? "题材" : filter.getName();
                groups.add(new AiDramaCatalog.Group(filter.getKey(), label, filter.getInit(), options));
            }
            categories.add(new AiDramaCatalog.Category(type.getTypeId(), type.getTypeName(), groups));
        }
        return new AiDramaCatalog(categories);
    }
    private synchronized void invalidate() {
        epoch.incrementAndGet();
        if (active != null) active.cancel();
        if (round != null) round.cancel(true);
        active = null; busy = false; coordinator.purge(); WORKERS.purge();
    }
    public synchronized void cancel() {
        closed = true; invalidate(); coordinator.shutdownNow();
    }
}
