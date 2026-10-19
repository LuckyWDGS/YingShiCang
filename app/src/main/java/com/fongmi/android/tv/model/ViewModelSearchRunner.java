package com.fongmi.android.tv.model;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.resource.ResourceHealth;
import com.fongmi.android.tv.utils.Task;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import java.util.stream.Collectors;

/** Bounded parallel dispatch: a few slow sources must not occupy the entire first wave. */
final class ViewModelSearchRunner {
    // One bounded retry is enough for an intermittent transport failure. Parser,
    // schema and authentication failures are deterministic and only add latency
    // when retried, which was the main cause of the long tail on real sources.
    private static final int MAX_RETRIES = 1;
    private static final long RETRY_DELAY_MS = 700;
    private final com.google.common.util.concurrent.ListeningExecutorService workers;
    private final com.google.common.util.concurrent.ListeningExecutorService crawlerWorkers;
    private final ScheduledExecutorService scheduler;
    private final long timeout;
    private final int parallelism;
    private final int crawlerParallelism;
    private int activeHttp, activeCrawlers;
    private boolean dispatching;
    ViewModelSearchRunner() {
        this(Task.searchExecutor(), Task.searchExecutor(true), Task.scheduler(), Constant.TIMEOUT_SEARCH,
                Task.SEARCH_PARALLELISM, Task.SEARCH_CRAWLER_PARALLELISM);
    }
    ViewModelSearchRunner(com.google.common.util.concurrent.ListeningExecutorService workers, ScheduledExecutorService scheduler, long timeout) {
        this(workers, scheduler, timeout, 4);
    }
    ViewModelSearchRunner(com.google.common.util.concurrent.ListeningExecutorService workers, ScheduledExecutorService scheduler, long timeout, int parallelism) {
        this(workers, workers, scheduler, timeout, parallelism, parallelism);
    }
    ViewModelSearchRunner(com.google.common.util.concurrent.ListeningExecutorService workers,
                         com.google.common.util.concurrent.ListeningExecutorService crawlerWorkers,
                         ScheduledExecutorService scheduler, long timeout, int parallelism, int crawlerParallelism) {
        this.workers = workers; this.crawlerWorkers = crawlerWorkers;
        this.scheduler = scheduler; this.timeout = timeout;
        this.parallelism = Math.max(1, parallelism); this.crawlerParallelism = Math.max(1, crawlerParallelism);
    }
    private static final class Job {
        final TaskResource resource;
        final boolean crawler;
        final Attempt attempt;
        Future<?> submitted;
        Future<?> future;
        Job(Callable<Result> task, Attempt attempt) {
            resource = new TaskResource(task); this.attempt = attempt; crawler = attempt.site.getType() == 3;
        }
        void cancel() {
            resource.close();
            if (submitted != null) submitted.cancel(true);
            if (future != null) future.cancel(true);
        }
    }
    private final List<Job> jobs = new CopyOnWriteArrayList<>();
    private record Attempt(Site site, int retries) {}
    private final List<Future<?>> retries = new ArrayList<>();
    private final AtomicInteger epoch = new AtomicInteger();
    private ArrayDeque<Attempt> pending;
    private int total, completed, failed, hits;
    private String lastError = "";
    private Consumer<String> status;
    private long revision;
    synchronized void start(List<Site> sites, Function<Site, Callable<Result>> factory, Consumer<Result> results, Consumer<String> progress) {
        stop(); int current = epoch.get();
        pending = new ArrayDeque<>();
        revision = VodConfig.getDiscoveryRevision();
        sites.forEach(site -> pending.add(new Attempt(site, 0)));
        total = sites.size(); completed = failed = hits = 0; lastError = ""; status = progress;
        report();
        dispatch(current,factory,results);
    }
    synchronized void stop() {
        epoch.incrementAndGet(); jobs.forEach(Job::cancel); jobs.clear();
        retries.forEach(retry -> retry.cancel(false)); retries.clear();
        activeHttp = activeCrawlers = 0;
        if(pending!=null)pending.clear();
        Task.purgeSearchTasks();
    }
    private synchronized void dispatch(int current, Function<Site,Callable<Result>> factory,Consumer<Result> results) {
        if(epoch.get()!=current || dispatching)return;
        dispatching = true;
        try {
            Attempt attempt;
            while (epoch.get() == current && (attempt = nextAvailable()) != null) {
                boolean crawler = attempt.site.getType() == 3;
                Callable<Result> task = factory.apply(attempt.site);
                Job job = new Job(task, attempt);
                if (crawler) activeCrawlers++; else activeHttp++;
                jobs.add(job);
                try {
                    Task.purgeSearchTasks();
                    var submitted = (crawler ? crawlerWorkers : workers).submit(task);
                    job.submitted = submitted;
                    FluentFuture<Result> future = FluentFuture.from(submitted)
                            .withTimeout(timeout,TimeUnit.MILLISECONDS,scheduler);
                    job.future = future;
                    future.addCallback(Task.callback(
                        value -> finish(current,factory,results,job,value,null),
                        error -> finish(current,factory,results,job,null,error)
                    ), MoreExecutors.directExecutor());
                } catch (RejectedExecutionException rejected) {
                    job.cancel();
                    finish(current,factory,results,job,null,rejected);
                }
            }
        } finally { dispatching = false; }
    }
    private Attempt nextAvailable() {
        for (Iterator<Attempt> iterator = pending.iterator(); iterator.hasNext();) {
            Attempt attempt = iterator.next();
            if (attempt.site.getType() == 3 ? activeCrawlers < crawlerParallelism : activeHttp < parallelism) {
                iterator.remove(); return attempt;
            }
        }
        return null;
    }
    private synchronized void finish(int current,Function<Site,Callable<Result>> factory,Consumer<Result> results,Job job,Result value,Throwable error) {
        job.resource.close();
        if (error != null && job.submitted != null) job.submitted.cancel(true);
        if(epoch.get()!=current)return;
        if(revision != VodConfig.getDiscoveryRevision()) { stop(); return; }
        // A timeout callback and the underlying task completion can race.  The
        // timeout closes/cancels the task, but an uninterruptible crawler may
        // still return and invoke the original future callback afterwards.
        // Process each job exactly once or the aggregate counters and lane
        // permits drift (for example, reporting 2/1 or dispatching too many
        // sources), which makes a search appear stuck or finish prematurely.
        if (!jobs.remove(job)) return;
        if (job.crawler) activeCrawlers--; else activeHttp--;
        Task.purgeSearchTasks();
        if (isTransientFailure(error) && job.attempt.retries < MAX_RETRIES) {
            try {
                // Release the lane during backoff. Fresh sources retain priority and
                // each retry gets a new cancellable task with its own source deadline.
                retries.add(scheduler.schedule(() -> retry(current, factory, results, job.attempt),
                        RETRY_DELAY_MS * (job.attempt.retries + 1), TimeUnit.MILLISECONDS));
                report(); dispatch(current, factory, results);
                return;
            } catch (RejectedExecutionException unavailable) { /* Report the final failure below. */ }
        }
        completed++;
        if(error!=null){
            failed++;
            String message=String.valueOf(error);
            lastError=message.contains("架构") ? "插件架构不兼容" : message.contains("Timeout") ? "部分站点超时" : "部分站点加载失败";
        } else if(value!=null){
            // Keep only entries that can lead to a real detail page. Empty shells and
            // parser status rows otherwise make the aggregate search look successful.
            List<Vod> raw = value.getList().stream().filter(java.util.Objects::nonNull)
                    .peek(item -> { if (item.getSite() == null) item.setSite(job.attempt.site); }).collect(Collectors.toList());
            List<Vod> valid = raw.stream()
                    .filter(ResourceHealth::isSearchCard)
                    .filter(item -> !ResourceHealth.isFiltered(revision, item.getSiteKey(), item.getId()))
                    .collect(Collectors.toList());
            if (!raw.isEmpty()) {
                value.setSearchRawList(raw);
                value.setList(valid);
                hits += valid.size();
                results.accept(value);
            }
        }
        report(); dispatch(current,factory,results);
    }

    private static boolean isTransientFailure(Throwable error) {
        // A transport wrapper can retain a lower-level cancellation/deadline
        // cause (for example an SSLException around SearchRequestScope's
        // InterruptedIOException). Check the complete chain first so that a
        // transient-looking outer exception never revives an explicitly
        // stopped search.
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof java.util.concurrent.CancellationException
                    || cause instanceof InterruptedException
                    || isExplicitSearchStop(cause)) return false;
        }
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof java.io.InterruptedIOException
                    || current instanceof java.util.concurrent.TimeoutException) {
                String message = current.getMessage();
                // Keep genuine timeout-shaped transport failures retryable,
                // while unknown/empty timeout messages remain terminal.
                if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("timeout")) return true;
                if (current instanceof java.net.SocketTimeoutException) return true;
                continue;
            }
            if (current instanceof java.net.SocketException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.io.EOFException
                    // DNS lookups and TLS handshakes happen before an HTTP
                    // response exists. OkHttp surfaces those as IOException
                    // subclasses that are otherwise indistinguishable from a
                    // deterministic source/parser failure, so allow the
                    // single bounded retry for these transient transports too.
                    || current instanceof java.net.UnknownHostException
                    ) return true;
            if (current instanceof javax.net.ssl.SSLException && isRetryableTlsFailure(current)) return true;
        }
        return false;
    }

    private static boolean isRetryableTlsFailure(Throwable error) {
        // A peer closing a handshake or resetting the TLS protocol is commonly
        // transient on public source hosts. Certificate, hostname and trust
        // failures are deterministic and should finish immediately instead of
        // adding another full source deadline.
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.util.concurrent.CancellationException
                    || current instanceof InterruptedException) return false;
            if (current instanceof java.security.cert.CertificateException
                    || current instanceof javax.net.ssl.SSLPeerUnverifiedException
                    || current instanceof java.security.cert.CertPathValidatorException) return false;
            String message = current.getMessage();
            if (message == null) continue;
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("certificate") || lower.contains("hostname")
                    || lower.contains("peer not authenticated") || lower.contains("trust anchor")
                    || lower.contains("pkix") || lower.contains("unable to find valid certification path")
                    || lower.contains("certificate_unknown") || lower.contains("bad_certificate")
                    || lower.contains("pin")) return false;
        }
        return true;
    }

    private static boolean isExplicitSearchStop(Throwable error) {
        String message = error.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("deadline") || lower.contains("cancel") || message.contains("取消");
    }
    private synchronized void retry(int current, Function<Site, Callable<Result>> factory,
                                    Consumer<Result> results, Attempt previous) {
        if (epoch.get() != current) return;
        pending.addLast(new Attempt(previous.site, previous.retries + 1));
        retries.removeIf(Future::isDone);
        dispatch(current, factory, results);
    }
    private void report() {
        if(total==0){status.accept("没有启用的搜索源");return;}
        status.accept((completed==total ? "搜索完成" : "搜索中")+" "+completed+"/"+total+" · "+hits+" 个有效结果 · "+failed+" 个失败"
                +(lastError.isEmpty()?"":" · "+lastError));
    }
}
