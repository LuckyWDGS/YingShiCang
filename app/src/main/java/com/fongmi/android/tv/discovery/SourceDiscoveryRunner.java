package com.fongmi.android.tv.discovery;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.OkHttpClient;

/** Isolated category browsing. Each source/category owns its raw-page cursor. */
public final class SourceDiscoveryRunner {
    private static final SourceDiscoveryWorkers WORKERS = new SourceDiscoveryWorkers();
    public record Batch(List<Vod> items, boolean more, boolean failed, int unsupported, boolean finished) {}

    /** Metadata only, never movie data, and scoped to the exact configuration revision. */
    public static final class MetadataCache {
        private long revision = -1;
        record Metadata(List<SourceDiscoveryPlanner.Category> categories, String extension) {
            Metadata { categories = List.copyOf(categories); }
        }
        private final Map<String, Metadata> entries = new HashMap<>();
        synchronized Metadata get(long scope, String key) {
            if (scope < revision) return null;
            if (revision != scope) { entries.clear(); revision = scope; }
            return entries.get(key);
        }
        synchronized void put(long scope, String key, Metadata value) {
            if (revision == scope) entries.put(key, value);
        }
        public synchronized void clear() { entries.clear(); revision = -1; }
    }
    /** A manual retry starts a fresh bounded automatic-retry budget for that failed page. */
    static final class RetryBudget {
        private int attempts;
        int attempts() { return attempts; }
        boolean available() { return attempts < 2; }
        void start(boolean manual) { attempts = manual ? 0 : attempts + 1; }
        void succeeded() { attempts = 0; }
    }
    private static final class PageSlot {
        final SourceDiscoveryPlanner.Query query;
        final SourcePageCursor cursor = new SourcePageCursor();
        PageSlot(SourceDiscoveryPlanner.Query query) { this.query = query; }
    }
    private static final class SourceSlot {
        final Site site;
        final Site requestSite;
        final String signature;
        final List<PageSlot> pages = new ArrayList<>();
        final Map<String, Vod> items = new LinkedHashMap<>();
        final RetryBudget retryBudget = new RetryBudget();
        boolean initialized, failed;
        int turn, categoryCount, adultCategoryCount;
        SourceSlot(Site site) {
            this.site = site; signature = signature(site);
            requestSite = site.getType() == 4 ? App.gson().fromJson(App.gson().toJson(site), Site.class) : site;
        }
        PageSlot next() {
            for (int i = 0; i < pages.size(); i++) {
                PageSlot page = pages.get((turn + i) % pages.size());
                if (!page.cursor.exhausted()) return page;
            }
            return null;
        }
        boolean available() { return !initialized || next() != null; }
        boolean more() { return !failed && available(); }
    }
    private static final class Work {
        final SourceSlot slot;
        final PageSlot page;
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final long deadline;
        volatile boolean cancelled;
        Future<Outcome> future;
        Work(SourceSlot slot) {
            this.slot = slot; page = slot.next();
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(20000, Math.max(5000, slot.site.getTimeout())));
        }
        void track(Call call) {
            // Extension, metadata and page requests share the source's original budget.
            call.timeout().deadlineNanoTime(deadline);
            calls.add(call); if (cancelled) call.cancel();
        }
        void cancel() {
            cancelled = true; calls.forEach(Call::cancel);
            if (future != null) future.cancel(true);
        }
    }
    private record Outcome(List<SourceDiscoveryPlanner.Query> queries, Result result,
                           SourceDiscoveryRoundStats.ErrorType errorType, int categoryCount, int adultCategoryCount) {
        boolean error() { return errorType != null; }
    }
    private final VodConfig config = VodConfig.get();
    private final long revision = VodConfig.getDiscoveryRevision();
    private final DiscoveryCatalog.State state;
    private final List<SourceSlot> sources = new ArrayList<>();
    private final Map<String, Vod> visible = new LinkedHashMap<>();
    private final MetadataCache cache;
    private final OkHttpClient client;
    private final AtomicLong epoch = new AtomicLong();
    private final List<Work> running = new CopyOnWriteArrayList<>();
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "discovery-coordinator"); thread.setDaemon(true); return thread;
    });
    private Future<?> round;
    private Batch published;
    private volatile boolean closed;

    public SourceDiscoveryRunner(DiscoveryCatalog.State state, OkHttpClient client, MetadataCache cache) {
        this.state = state.snapshot(); this.client = client; this.cache = cache;
        Set<String> keys = new HashSet<>();
        for (Site site : new ArrayList<>(config.getSites())) if (!site.getKey().isEmpty() && keys.add(site.getKey())) sources.add(new SourceSlot(site));
    }
    private static String signature(Site site) {
        // Retained only in memory; never log APIs/extensions/headers, which can contain credentials.
        return site.getKey() + "\0" + site.getName() + "\0" + site.getType() + "\0" + site.getApi()
                + "\0" + site.getExt() + "\0" + site.getJar() + "\0" + site.getCategories() + "\0" + site.getHeader();
    }
    public boolean current() {
        if (closed || config != VodConfig.get() || revision != VodConfig.getDiscoveryRevision()) return false;
        if (sources.size() != config.getSites().stream().map(Site::getKey).filter(key -> !key.isEmpty()).distinct().count()) return false;
        for (SourceSlot slot : sources)
            if (config.getSite(slot.site.getKey()) != slot.site || !slot.signature.equals(signature(slot.site))) return false;
        return true;
    }
    public boolean owns(Vod item) {
        return current() && item.getSite() != null && sources.stream().anyMatch(slot -> slot.site == item.getSite());
    }
    /** Retry budget belongs to the failed source/page, not to healthy paging rounds. */
    public int retryAttempt() {
        return sources.stream().filter(slot -> slot.failed && slot.available())
                .mapToInt(slot -> slot.retryBudget.attempts()).min().orElse(2);
    }
    public void next(boolean retry, Consumer<Batch> callback) {
        next(retry, false, callback);
    }
    /** Reissue only failed sources at their unchanged raw cursor; keep all healthy results. */
    public void retryFailed(Consumer<Batch> callback) {
        next(true, true, callback);
    }
    private void next(boolean retry, boolean manual, Consumer<Batch> callback) {
        if (closed) return;
        long token = epoch.incrementAndGet();
        // Let runRound finish an invalidated scope so its callback can release the UI's
        // loading state even when the configuration changed just before this call.
        round = coordinator.submit(() -> runRound(token, retry, manual, callback));
    }
    private boolean valid(long token) {
        return !closed && token == epoch.get() && config == VodConfig.get()
                && revision == VodConfig.getDiscoveryRevision() && !Thread.currentThread().isInterrupted();
    }
    private boolean valid(Work work, long token) {
        return valid(token) && !work.cancelled && config.getSite(work.slot.site.getKey()) == work.slot.site
                && work.slot.signature.equals(signature(work.slot.site));
    }

    private void runRound(long token, boolean retry, boolean manual, Consumer<Batch> callback) {
        SourceDiscoveryRoundStats stats = BuildConfig.DEBUG ? new SourceDiscoveryRoundStats() : null;
        Deque<SourceSlot> pending = new ArrayDeque<>();
        // Failed sources retain their exact raw-page cursor, but never hold up
        // healthy-source pagination. Only the explicit retry round reissues them.
        for (SourceSlot slot : sources) if (slot.available() && (retry ? slot.failed && (manual || slot.retryBudget.available()) : !slot.failed)) pending.add(slot);
        List<Work> active = new ArrayList<>();
        BlockingQueue<Work> completed = new LinkedBlockingQueue<>();
        try {
            while (valid(token) && (!pending.isEmpty() || !active.isEmpty())) {
                int crawlers = (int) active.stream().filter(work -> work.slot.site.getType() == 3).count();
                int networks = active.size() - crawlers;
                for (Iterator<SourceSlot> iterator = pending.iterator(); iterator.hasNext();) {
                    SourceSlot slot = iterator.next();
                    boolean plugin = slot.site.getType() == 3;
                    if ((plugin ? crawlers : networks) >= WORKERS.limit(plugin)) continue;
                    iterator.remove();
                    Work work = new Work(slot);
                    if (retry) slot.retryBudget.start(manual);
                    work.slot.failed = false;
                    try {
                        work.future = WORKERS.submit(plugin, () -> execute(work, token), result -> completed.offer(work));
                        active.add(work); running.add(work);
                        if (plugin) crawlers++; else networks++;
                    } catch (RejectedExecutionException unavailable) {
                        work.slot.failed = true;
                        if (stats != null) stats.error(SourceDiscoveryRoundStats.ErrorType.RejectedExecutionException);
                    }
                }
                if (!active.isEmpty()) {
                    long deadline = active.stream().mapToLong(work -> work.deadline).min().orElse(System.nanoTime());
                    completed.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    completed.clear();
                }
                boolean changed = false;
                for (Iterator<Work> iterator = active.iterator(); iterator.hasNext();) {
                    Work work = iterator.next();
                    if (!work.future.isDone() && System.nanoTime() < work.deadline) continue;
                    Outcome outcome;
                    try {
                        if (!work.future.isDone()) {
                            work.cancel();
                            outcome = new Outcome(null, null, SourceDiscoveryRoundStats.ErrorType.TimeoutException, 0, 0);
                        }
                        else outcome = work.future.get();
                    } catch (Exception error) { outcome = new Outcome(null, null, SourceDiscoveryRoundStats.errorType(error), 0, 0); }
                    if (!valid(token)) return;
                    apply(work, outcome, stats);
                    work.calls.clear(); running.remove(work); iterator.remove(); changed = true;
                }
                // Sort each arriving batch. Do not hide fast results until the slowest
                // configured source finishes; already visible cards remain in place.
                if (changed && (!pending.isEmpty() || !active.isEmpty()))
                    publish(token, false, callback);
            }
            if (valid(token)) {
                if (stats != null && current()) debugSummary(stats, token, retry);
                publish(token, true, callback);
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally {
            active.forEach(Work::cancel); running.removeAll(active); WORKERS.purge();
            if (!closed && token == epoch.get() && !current()) App.post(() -> {
                if (!closed && token == epoch.get()) callback.accept(new Batch(List.of(), false, true, 0, true));
            });
        }
    }
    private Outcome execute(Work work, long token) {
        List<SourceDiscoveryPlanner.Query> queries = null;
        int categoryCount = 0, adultCategoryCount = 0;
        try {
            if (!valid(work, token)) throw new CancellationException();
            if (!work.slot.initialized) {
                MetadataCache.Metadata cached = cache.get(revision, work.slot.signature);
                List<SourceDiscoveryPlanner.Category> categories;
                if (cached == null) {
                    SiteApi.discoveryExtension(work.slot.requestSite, client, work::track);
                    if (!valid(work, token)) throw new CancellationException();
                    Result metadata = SiteApi.discoveryClasses(work.slot.requestSite, client, work::track);
                    categories = categories(metadata);
                    if (valid(work, token)) cache.put(revision, work.slot.signature,
                            new MetadataCache.Metadata(categories, work.slot.requestSite.getType() == 4 ? work.slot.requestSite.getExt() : ""));
                } else {
                    categories = cached.categories();
                    if (work.slot.requestSite.getType() == 4) work.slot.requestSite.setExt(cached.extension());
                }
                // Source branding is not a content declaration. Require real category
                // or filter evidence before treating a mixed catalog as adult content.
                categoryCount = categories.size();
                adultCategoryCount = (int) categories.stream().filter(category -> category.adultDeclared()
                        || DiscoveryContentGate.isAdult(category.name())).count();
                queries = SourceDiscoveryPlanner.plan(state, categories, false);
            }
            SourceDiscoveryPlanner.Query query = work.page == null ? queries == null || queries.isEmpty() ? null : queries.get(0) : work.page.query;
            if (query == null) return new Outcome(queries, null, null, categoryCount, adultCategoryCount);
            if (!valid(work, token)) throw new CancellationException();
            Result result = SiteApi.discoveryPage(work.slot.requestSite, query.categoryId(), work.page == null ? 1 : work.page.cursor.next(), query.extend(), client, work::track);
            return new Outcome(queries, result, null, categoryCount, adultCategoryCount);
        } catch (Exception error) { return new Outcome(queries, null, SourceDiscoveryRoundStats.errorType(error), categoryCount, adultCategoryCount); }
    }
    private static List<SourceDiscoveryPlanner.Category> categories(Result result) {
        List<SourceDiscoveryPlanner.Category> categories = new ArrayList<>();
        for (com.fongmi.android.tv.bean.Class type : result.getTypes()) {
            if (type.getTypeId().isEmpty() || type.isFolder()) continue;
            List<SourceDiscoveryPlanner.Facet> facets = new ArrayList<>();
            for (Filter filter : type.getFilters()) {
                if (filter == null || filter.getKey() == null || filter.getKey().isEmpty()) continue;
                List<SourceDiscoveryPlanner.Choice> choices = new ArrayList<>();
                filter.getValue().stream().filter(Objects::nonNull).forEach(value -> choices.add(new SourceDiscoveryPlanner.Choice(value.getN(), value.getV(), value.isDiscoveryAdult())));
                facets.add(new SourceDiscoveryPlanner.Facet(filter.getKey(), filter.getName(), filter.getInit(), choices));
            }
            categories.add(new SourceDiscoveryPlanner.Category(type.getTypeId(), type.getTypeName(), facets, type.isDiscoveryAdult()));
        }
        return List.copyOf(categories);
    }
    private void apply(Work work, Outcome outcome, SourceDiscoveryRoundStats stats) {
        SourceSlot slot = work.slot;
        if (outcome.queries != null && !slot.initialized) {
            outcome.queries.forEach(query -> slot.pages.add(new PageSlot(query))); slot.initialized = true;
            slot.categoryCount = outcome.categoryCount; slot.adultCategoryCount = outcome.adultCategoryCount;
        }
        slot.failed = outcome.error();
        if (!outcome.error()) slot.retryBudget.succeeded();
        if (stats != null) stats.error(outcome.errorType);
        if (outcome.error() || outcome.result == null) return;
        PageSlot page = work.page == null ? slot.next() : work.page;
        if (page == null) return;
        List<String> raw = new ArrayList<>();
        for (Vod item : outcome.result.getList()) {
            if (item == null) continue;
            if (!item.getId().isEmpty()) raw.add(item.getId());
            boolean usable = !item.getId().isEmpty() && !item.getName().isEmpty() && !item.isFolder();
            boolean accepted = usable && SourceDiscoveryPlanner.accepts(state, page.query, item.getTypeName(), item.getDiscoveryTags(), item.getYear(), item.getArea());
            if (stats != null) stats.item(DiscoveryContentGate.isAdult(item.getTypeName(), item.getDiscoveryTags()), accepted,
                    usable && page.query.itemKindRequired() && !DiscoveryContentGate.isMovie(item.getTypeName())
                            && !DiscoveryContentGate.isTelevision(item.getTypeName()) && !DiscoveryContentGate.isVariety(item.getTypeName()));
            if (!accepted) continue;
            item.setSite(slot.site);
            // Category cards are source detail links, regardless of optional action strings.
            item.setAction("");
            slot.items.putIfAbsent(item.getId(), item);
        }
        page.cursor.accept(raw, outcome.result.getPageCount());
        slot.turn = (slot.pages.indexOf(page) + 1) % slot.pages.size();
    }
    private void debugSummary(SourceDiscoveryRoundStats stats, long token, boolean retry) {
        if (!BuildConfig.DEBUG) return;
        List<SourceDiscoveryPlanner.Query> queries = sources.stream().flatMap(slot -> slot.pages.stream()).map(page -> page.query).toList();
        android.util.Log.d("DiscoverySummary", stats.summary(state.tab, !state.value("content").isEmpty(), token, retry,
                sources.size(), (int) sources.stream().filter(slot -> slot.initialized).count(),
                (int) sources.stream().filter(slot -> slot.failed).count(),
                (int) sources.stream().filter(slot -> slot.initialized && slot.pages.isEmpty()).count(),
                sources.stream().mapToInt(slot -> slot.categoryCount).sum(), sources.stream().mapToInt(slot -> slot.adultCategoryCount).sum(),
                (int) queries.stream().filter(SourceDiscoveryPlanner.Query::adult).count(),
                (int) queries.stream().filter(query -> !query.adult()).count(),
                (int) queries.stream().filter(SourceDiscoveryPlanner.Query::itemKindRequired).count(),
                sources.stream().mapToInt(slot -> slot.items.size()).sum(), sources.stream().anyMatch(SourceSlot::more)).toString());
    }
    private void publish(long token, boolean finished, Consumer<Batch> callback) {
        if (!valid(token) || !current()) return;
        boolean more = sources.stream().anyMatch(SourceSlot::more);
        boolean failed = sources.stream().anyMatch(slot -> slot.failed);
        int unsupported = (int) sources.stream().filter(slot -> slot.initialized && slot.pages.isEmpty()).count();
        int accepted = sources.stream().mapToInt(slot -> slot.items.size()).sum();
        if (!finished && published != null && accepted == published.items().size()
                && more == published.more() && failed == published.failed() && unsupported == published.unsupported()) return;
        List<List<Vod>> lists = sources.stream().map(slot -> new ArrayList<>(slot.items.values())).map(list -> (List<Vod>) list).toList();
        List<Vod> merged = new ArrayList<>();
        int count = lists.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < count; i++) for (List<Vod> list : lists) if (i < list.size()) merged.add(list.get(i));
        if (state.value("sort").equals("S")) merged.sort(Comparator.comparingDouble((Vod item) -> number(item.getScore())).reversed());
        else if (state.value("sort").equals("R")) merged.sort(Comparator.comparingDouble((Vod item) -> number(item.getYear())).reversed());
        // Append newly arrived pages; never insert cards above the user's focused/visible page.
        for (Vod item : merged) visible.putIfAbsent(item.getSiteKey() + "\0" + item.getId(), item);
        Batch batch = new Batch(List.copyOf(visible.values()), more, failed, unsupported, finished);
        published = batch;
        App.post(() -> { if (valid(token) && current()) callback.accept(batch); });
    }
    private static double number(String text) {
        try { return Double.parseDouble(text); } catch (RuntimeException ignored) { return 0; }
    }
    public void cancel() {
        closed = true; epoch.incrementAndGet();
        if (round != null) round.cancel(true);
        running.forEach(Work::cancel); running.clear(); coordinator.shutdownNow(); WORKERS.purge();
    }
}
