package com.fongmi.android.tv.discovery;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.utils.BackgroundThreads;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import okhttp3.Call;
import okhttp3.OkHttpClient;

/** Independent native AiDj browsing; source order, category parameters and origin ids are retained. */
public final class AiDramaRunner {
    public record Batch(AiDramaCatalog catalog, AiDramaCatalog.State state, List<Vod> items,
                        boolean more, boolean failed, boolean metadataFailed, boolean finished) {}

    public static final class MetadataCache {
        private static final int MAX_ENTRIES = 32;
        private static final long TTL_NANOS = TimeUnit.MINUTES.toNanos(30);
        private record Entry(AiDramaCatalog catalog, long cachedAt) {}
        private final LongSupplier clock;
        private long revision = -1;
        private final Map<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);
        public MetadataCache() { this(System::nanoTime); }
        MetadataCache(LongSupplier clock) { this.clock = clock; }
        synchronized AiDramaCatalog get(long scope, String signature) {
            if (scope < revision) return null;
            if (scope != revision) { revision = scope; entries.clear(); }
            Entry entry = entries.get(signature);
            if (entry == null) return null;
            // Read access affects LRU order, never the successful fetch's lifetime.
            if (clock.getAsLong() - entry.cachedAt >= TTL_NANOS) {
                entries.remove(signature); return null;
            }
            return entry.catalog;
        }
        synchronized void put(long scope, String signature, AiDramaCatalog catalog) {
            // Empty/error catalogs are retryable, and old work cannot replace a newer scope.
            if (scope == revision && retainedMetadata(catalog) != null) {
                entries.put(signature, new Entry(catalog, clock.getAsLong()));
                while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
            }
        }
        synchronized Restored restore(long scope, String signature, String saved) {
            AiDramaCatalog catalog = get(scope, signature);
            if (catalog == null) return null;
            AiDramaCatalog.State state = new AiDramaCatalog.State(catalog, signature);
            state.restore(saved);
            return new Restored(catalog, state);
        }
        public synchronized void clear() { revision = -1; entries.clear(); }
    }
    record Restored(AiDramaCatalog catalog, AiDramaCatalog.State state) {}

    static AiDramaCatalog retainedMetadata(AiDramaCatalog metadata) {
        return metadata != null && metadata.complete() && !metadata.isEmpty() ? metadata : null;
    }

    static AiDramaCatalog metadataForSession(AiDramaCatalog retained, MetadataCache cache, long revision, String signature) {
        // A browsing session keeps its successful choices stable; new sessions obey cache expiry.
        AiDramaCatalog successful = retainedMetadata(retained);
        return successful == null ? cache.get(revision, signature) : successful;
    }

    /** Failure leaves the exact raw page available; accepted pages preserve source order and identity. */
    static final class Pages<T> {
        // AiDj's default API reports a placeholder pagecount; allow short gaps but stop a repeated tail.
        private static final int MAX_PAGES = 500, MAX_UNPRODUCTIVE_PAGES = 3;
        private final Set<String> identities = new HashSet<>();
        private final Map<String, T> items = new LinkedHashMap<>();
        private int next = 1, unproductive;
        private boolean exhausted;
        int next() { return next; }
        boolean more() { return !exhausted; }
        List<T> items() { return List.copyOf(items.values()); }
        void accept(List<String> raw, int pageCount, Map<String, T> accepted) {
            if (exhausted) return;
            boolean added = false;
            for (String id : raw) if (id != null && !id.isEmpty() && identities.add(id)) added = true;
            accepted.forEach(items::putIfAbsent);
            unproductive = added ? 0 : unproductive + 1;
            exhausted = pageCount > 0 && next >= pageCount || next >= MAX_PAGES
                    || unproductive >= MAX_UNPRODUCTIVE_PAGES;
            next++;
        }
        boolean accept(int page, List<String> raw, int pageCount, Map<String, T> accepted) {
            if (page != next || exhausted) return false;
            accept(raw, pageCount, accepted);
            return true;
        }
        void reset() { next = 1; unproductive = 0; exhausted = false; identities.clear(); items.clear(); }
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
    private volatile AiDramaCatalog sessionMetadata;
    private AiDramaCatalog.State state;
    private Work active;
    private Future<?> round;
    private volatile boolean closed;
    private boolean busy, failed, metadataFailed;

    private static final class Work {
        final long token, deadline;
        final int page;
        int requestedPage;
        final AiDramaCatalog.State state;
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final List<Future<?>> children = new CopyOnWriteArrayList<>();
        volatile boolean cancelled;
        volatile boolean pageAccepted;
        volatile boolean metadataAccepted;
        boolean metadataOnly;
        Work(long token, int page, AiDramaCatalog.State state, long timeout) {
            this.token = token; this.page = page; this.state = state;
            requestedPage = page;
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        }
        void track(Call call) {
            call.timeout().deadlineNanoTime(deadline); calls.add(call);
            if (cancelled) call.cancel();
        }
        void child(Future<?> future) {
            children.add(future);
            if (cancelled) future.cancel(true);
        }
        void cancel() {
            cancelled = true; calls.forEach(Call::cancel);
            children.forEach(future -> future.cancel(true));
        }
    }
    private record Outcome(AiDramaCatalog catalog, AiDramaCatalog.State state, Result result, boolean failed, boolean metadataFailed) {}

    public AiDramaRunner(OkHttpClient client, MetadataCache cache, String savedState) {
        this.client = client; this.cache = cache; this.savedState = savedState;
        site = site(config);
        signature = site == null ? "" : signature(site);
        // Successful metadata is enough for UI restoration; spider initialization stays off this path.
        Restored restored = site == null ? null : cache.restore(revision, signature, savedState);
        if (restored != null) { catalog = restored.catalog; sessionMetadata = restored.catalog; state = restored.state; }
    }

    private static ThreadPoolExecutor pool(int threads, int queue, String name) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue),
                BackgroundThreads.factory(name), new ThreadPoolExecutor.AbortPolicy());
    }
    /** The configured 短剧漫剧 (AiDj) site, or null. */
    public static Site site(VodConfig config) {
        return config.getSites().stream().filter(candidate -> candidate.getType() == 3
                && !candidate.getKey().isEmpty() && supportedApi(candidate.getApi())).findFirst().orElse(null);
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
        invalidate(); pages.reset(); failed = false; metadataFailed = false; return true;
    }
    private boolean valid(Work work) {
        return !work.cancelled && work.token == epoch.get() && current() && !Thread.currentThread().isInterrupted();
    }

    /** false loads the first/next page, true retries the failed page without advancing it. */
    public synchronized void load(boolean retry, Consumer<Batch> callback) {
        if (closed || busy) return;
        failure = "";
        if (site == null) { failure = "no_site"; failed = true; publish(null, true, callback); return; }
        if (!current()) { failure = "config_changed"; failed = true; publish(null, true, callback); return; }
        boolean metadataOnly = retry && metadataFailed && !failed && !pages.items().isEmpty();
        if (!pages.more() && !metadataOnly) { publish(null, true, callback); return; }
        long token = epoch.incrementAndGet();
        Work work = new Work(token, pages.next(), state == null ? null : state.snapshot(),
                Math.min(20000, Math.max(5000, site.getTimeout())));
        work.metadataOnly = metadataOnly;
        active = work; busy = true; failed = false; metadataFailed = false;
        try {
            round = coordinator.submit(() -> run(work, callback));
        } catch (RuntimeException unavailable) { busy = false; failed = true; publish(work, true, callback); }
    }
    private void run(Work work, Consumer<Batch> callback) {
        Outcome outcome;
        try {
            String selection = work.state == null ? savedState : work.state.serialize();
            if (work.metadataOnly) {
                AiDramaCatalog metadata = fromResult(await(work, submit(work, () -> SiteApi.discoveryClasses(site, client, work::track))));
                if (metadata.isEmpty()) throw new IllegalStateException("AI metadata unavailable");
                if (!valid(work)) throw new CancellationException();
                cache.put(revision, signature, metadata);
                if (!selectionRestorable(metadata, signature, selection)) {
                    App.post(() -> apply(work, new Outcome(null, null, null, false, true), callback));
                    return;
                }
                AiDramaCatalog.State requested = restored(metadata, selection);
                if (!metadata.complete() && !sameQuery(work.state, requested)) {
                    // Incomplete refreshes cannot invalidate the query whose cards are already visible.
                    App.post(() -> apply(work, new Outcome(null, null, null, false, true), callback));
                    return;
                }
                if (!sameQuery(work.state, requested)) {
                    synchronized (this) {
                        if (!valid(work)) throw new CancellationException();
                        pages.reset(); work.requestedPage = 1; work.metadataOnly = false;
                    }
                }
                metadataReady(work, metadata, requested, callback);
                Result page = work.metadataOnly ? null : await(work, submit(work, () -> requestPage(work, requested, 1)));
                App.post(() -> apply(work, new Outcome(metadata, requested, page, false, !metadata.complete()), callback));
                return;
            }
            AiDramaCatalog metadata = metadataForSession(sessionMetadata, cache, revision, signature);
            if (metadata == null) {
                // Read the actual native type declarations on a bounded worker.
                // Its static home response contains no web topic request.
                AiDramaCatalog nativeMetadata = fromResult(await(work, submit(work, () ->
                        SiteApi.discoveryNativeAiClasses(site))));
                if (nativeMetadata.isEmpty()) throw new IllegalStateException("AI native types unavailable");
                AiDramaCatalog.State nativeState = restored(nativeMetadata, selection);
                if (nativeStateEligible(selection, signature, nativeMetadata)) {
                    outcome = cold(work, callback, nativeMetadata, nativeState, selection);
                    Outcome completed = outcome;
                    App.post(() -> apply(work, completed, callback));
                    return;
                }
                metadata = fromResult(await(work, submit(work, () ->
                        SiteApi.discoveryClasses(site, client, work::track))));
                if (metadata.isEmpty()) throw new IllegalStateException("AI metadata unavailable");
                if (!valid(work)) throw new CancellationException();
                cache.put(revision, signature, metadata);
            }
            if (!selectionRestorable(metadata, signature, selection)) {
                // Partial declarations cannot confirm that a saved finer topic was removed.
                // Keep state untouched so every retry starts from the original selection.
                App.post(() -> apply(work, new Outcome(null, null, null, false, true), callback));
                return;
            }
            AiDramaCatalog.State requested = restored(metadata, selection);
            metadataReady(work, metadata, requested, callback);
            Result page = await(work, submit(work, () -> requestPage(work, requested, work.requestedPage)));
            outcome = new Outcome(metadata, requested, page, false, !metadata.complete());
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            failure = reason(error);
            work.cancel(); outcome = new Outcome(null, null, null,
                    !work.pageAccepted && !work.metadataOnly, !work.metadataAccepted);
        }
        Outcome result = outcome;
        App.post(() -> apply(work, result, callback));
    }

    // The last failed round's cause (exception classes and fixed keywords only), for diagnostics.
    private volatile String failure = "";

    public String failure() {
        return failure;
    }

    private static String reason(Throwable error) {
        Throwable root = error;
        for (int i = 0; i < 8 && root.getCause() != null && root.getCause() != root; i++) root = root.getCause();
        String outcome = com.github.catvod.net.NetTrace.reason(error);
        return root == error ? outcome : outcome + "<" + com.github.catvod.net.NetTrace.reason(root);
    }

    /** Each cold lane is consumed when it finishes, independent of the other lane. */
    private Outcome cold(Work work, Consumer<Batch> callback, AiDramaCatalog nativeMetadata,
                         AiDramaCatalog.State nativeState, String selection) throws Exception {
        CompletionService<Result> completed = new ExecutorCompletionService<>(WORKERS);
        Future<Result> metadataFuture = submit(work, completed, () -> SiteApi.discoveryClasses(site, client, work::track));
        Future<Result> pageFuture = submit(work, completed, () -> requestPage(work, nativeState, work.requestedPage));
        AiDramaCatalog ready = null;
        AiDramaCatalog.State requested = nativeState;
        boolean metadataDone = false, pageDone = false, metadataFailed = false, pageFailed = false;
        while (!metadataDone || !pageDone) {
            Future<Result> event = completed.poll(remaining(work), TimeUnit.NANOSECONDS);
            if (event == null) throw new java.util.concurrent.TimeoutException();
            if (!valid(work)) throw new CancellationException();
            if (event == metadataFuture) {
                metadataDone = true;
                try {
                    ready = fromResult(event.get());
                    if (ready.isEmpty()) throw new IllegalStateException("AI metadata unavailable");
                    if (!valid(work)) throw new CancellationException();
                    cache.put(revision, signature, ready);
                    if (!selectionRestorable(ready, signature, selection)) {
                        metadataFailed = true; ready = null; continue;
                    }
                    requested = restored(ready, selection);
                    work.metadataAccepted = ready.complete();
                    metadataFailed = !ready.complete();
                } catch (ExecutionException | IllegalStateException unavailable) {
                    // A failed metadata lane does not discard a valid native page.
                    metadataFailed = true; ready = null; continue;
                }
                if (!sameQuery(nativeState, requested)) {
                    synchronized (this) {
                        if (!valid(work)) throw new CancellationException();
                        pages.reset(); work.pageAccepted = false; work.requestedPage = 1;
                        catalog = ready; sessionMetadata = retainedMetadata(ready); state = requested; publish(work, false, callback);
                    }
                    pageFuture.cancel(true);
                    AiDramaCatalog.State replacement = requested;
                    pageFuture = submit(work, completed, () -> requestPage(work, replacement, 1));
                    pageDone = false; pageFailed = false;
                } else metadataReady(work, ready, requested, callback);
            } else if (event == pageFuture) {
                pageDone = true;
                try {
                    Result page = event.get();
                    progressivePage(work, ready == null ? nativeMetadata : ready, requested, page, callback);
                } catch (ExecutionException unavailable) {
                    // The topic catalog can still complete and make the filters usable after a page failure.
                    pageFailed = true;
                }
            }
        }
        return new Outcome(ready == null ? nativeMetadata : ready, requested, null, pageFailed, metadataFailed);
    }

    private AiDramaCatalog.State restored(AiDramaCatalog metadata, String selection) {
        AiDramaCatalog.State result = new AiDramaCatalog.State(metadata, signature);
        result.restore(selection); return result;
    }

    static boolean selectionRestorable(AiDramaCatalog metadata, String scope, String saved) {
        if (metadata.complete() || saved == null || saved.isEmpty()) return true;
        try {
            var json = com.google.gson.JsonParser.parseString(saved).getAsJsonObject();
            var savedScope = json.get("scope");
            if (savedScope == null || !savedScope.isJsonPrimitive()
                    || !savedScope.getAsJsonPrimitive().isString() || !scope.equals(savedScope.getAsString())) return true;
            var category = json.get("category");
            if (category == null || !category.isJsonPrimitive() || !category.getAsJsonPrimitive().isString()
                    || category.getAsString().isEmpty()) return true;
            var restored = new AiDramaCatalog.State(metadata, scope); restored.restore(saved);
            if (!category.getAsString().equals(restored.categoryId())) return false;
            var extend = json.get("extend");
            if (extend == null || !extend.isJsonObject()) return true;
            for (var entry : extend.getAsJsonObject().entrySet()) {
                var value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                        && !value.getAsString().isEmpty() && !value.getAsString().equals(restored.value(entry.getKey()))) return false;
            }
            return true;
        } catch (RuntimeException invalid) { return true; }
    }

    static boolean sameQuery(AiDramaCatalog.State first, AiDramaCatalog.State second) {
        if (!first.categoryId().equals(second.categoryId())) return false;
        Map<String, String> a = new LinkedHashMap<>(first.extend()), b = new LinkedHashMap<>(second.extend());
        a.values().removeIf(String::isEmpty); b.values().removeIf(String::isEmpty);
        return a.equals(b);
    }

    private Result requestPage(Work work, AiDramaCatalog.State requested, int page) throws Exception {
        // SiteApi.discoveryPage owns crawler creation and the runtime gate.  Do
        // not call site.spider() here as a probe: it initializes a second Spider
        // for every page request and races the metadata/page cold lanes.  That
        // duplicate initialization was a major source of cold-load latency and
        // plugin failures on TV devices.
        if (!valid(work)) throw new CancellationException();
        return SiteApi.discoveryPage(site, requested.categoryId(), page, requested.extend(), client, work::track);
    }

    private synchronized void progressivePage(Work work, AiDramaCatalog metadata,
                                               AiDramaCatalog.State requested, Result result,
                                               Consumer<Batch> callback) {
        if (!valid(work) || result == null) return;
        catalog = metadata; state = requested;
        if (!work.pageAccepted && pages.next() == work.requestedPage) {
            acceptPage(work, result); work.pageAccepted = true;
        }
        publish(work, false, callback);
    }

    private <T> Callable<T> checked(Work work, Callable<T> operation) {
        return () -> {
            if (!valid(work)) throw new CancellationException();
            T result = operation.call();
            if (!valid(work)) throw new CancellationException();
            return result;
        };
    }
    private <T> Future<T> submit(Work work, Callable<T> operation) {
        Future<T> future = WORKERS.submit(checked(work, operation));
        work.child(future); return future;
    }
    private <T> Future<T> submit(Work work, CompletionService<T> completion, Callable<T> operation) {
        Future<T> future = completion.submit(checked(work, operation));
        work.child(future); return future;
    }
    private <T> T await(Work work, Future<T> future) throws Exception {
        return future.get(remaining(work), TimeUnit.NANOSECONDS);
    }

    private static long remaining(Work work) throws java.io.InterruptedIOException {
        long left = work.deadline - System.nanoTime();
        if (left <= 0) throw new java.io.InterruptedIOException("AI source request timed out");
        return left;
    }

    static boolean defaultStateEligible(String saved) {
        if (saved == null || saved.isEmpty()) return true;
        try {
            var json = com.google.gson.JsonParser.parseString(saved);
            if (!json.isJsonObject()) return false;
            String category = json.getAsJsonObject().has("category")
                    ? json.getAsJsonObject().get("category").getAsString() : "";
            // Native classes are read before the parallel lanes start, so either
            // declared AI type can safely request its own default first page.
            if (!category.isEmpty() && !"comic".equals(category) && !"ai".equals(category)) return false;
            var extend = json.getAsJsonObject().get("extend");
            if (extend == null || !extend.isJsonObject()) return true;
            for (var entry : extend.getAsJsonObject().entrySet())
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsString().isEmpty()) return false;
            return true;
        } catch (RuntimeException ignored) { return false; }
    }

    /** Cold native requests are safe only when every saved choice is declared by this exact source. */
    static boolean nativeStateEligible(String saved, String scope, AiDramaCatalog nativeMetadata) {
        if (saved == null || saved.isEmpty()) return true;
        try {
            var json = com.google.gson.JsonParser.parseString(saved).getAsJsonObject();
            if (!json.has("scope") || !scope.equals(json.get("scope").getAsString())) return true;
            if (!json.has("category")) return false;
            AiDramaCatalog.Category category = nativeMetadata.category(json.get("category").getAsString());
            if (category == null) return false;
            var extend = json.get("extend");
            if (extend == null) return true;
            if (!extend.isJsonObject()) return false;
            for (var entry : extend.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) return false;
                String value = entry.getValue().getAsString();
                if (value.isEmpty()) continue;
                if (category.groups.stream().noneMatch(group -> group.key.equals(entry.getKey()) && group.option(value) != null)) return false;
            }
            return true;
        } catch (RuntimeException invalid) { return false; }
    }
    private synchronized void metadataReady(Work work, AiDramaCatalog metadata, AiDramaCatalog.State requested, Consumer<Batch> callback) {
        if (!valid(work)) return;
        work.metadataAccepted = metadata.complete();
        catalog = metadata; sessionMetadata = retainedMetadata(metadata); state = requested; publish(work, false, callback);
    }
    private synchronized void apply(Work work, Outcome outcome, Consumer<Batch> callback) {
        // A timeout cancelled the job, but its failure still completes this current UI request.
        if (closed || work.token != epoch.get() || !current()) return;
        busy = false; active = null; work.calls.clear(); failed = outcome.failed; metadataFailed = outcome.metadataFailed;
        if (outcome.catalog != null) catalog = outcome.catalog;
        if (outcome.state != null) state = outcome.state;
        if (!outcome.failed && outcome.result != null && !work.pageAccepted) acceptPage(work, outcome.result);
        publish(work, true, callback); WORKERS.purge();
    }

    private void acceptPage(Work work, Result result) {
        List<String> raw = new ArrayList<>(); Map<String, Vod> accepted = new LinkedHashMap<>();
        for (Vod item : result.getList()) {
            if (item == null) continue;
            if (!item.getId().isEmpty()) raw.add(item.getId());
            if (item.getId().isEmpty() || item.getName().isEmpty() || item.isFolder()
                    || DiscoveryContentGate.isAdult(item.getTypeName(), item.getDiscoveryTags())) continue;
            item.setSite(site); item.setAction("");
            accepted.putIfAbsent(site.getKey() + "\0" + item.getId(), item);
        }
        pages.accept(raw, result.getPageCount(), accepted);
    }

    private synchronized void publish(Work work, boolean finished, Consumer<Batch> callback) {
        long token = work == null ? epoch.get() : work.token;
        Batch batch = new Batch(catalog, state == null ? null : state.snapshot(), pages.items(),
                site != null && pages.more(), failed, metadataFailed, finished);
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
        return new AiDramaCatalog(categories, !result.isAiTopicsPartial());
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
