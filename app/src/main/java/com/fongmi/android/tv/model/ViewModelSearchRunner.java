package com.fongmi.android.tv.model;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
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
        Future<?> submitted;
        Future<?> future;
        Job(Callable<Result> task, boolean crawler) { resource = new TaskResource(task); this.crawler = crawler; }
        void cancel() {
            resource.close();
            if (submitted != null) submitted.cancel(true);
            if (future != null) future.cancel(true);
        }
    }
    private final List<Job> jobs = new CopyOnWriteArrayList<>();
    private final AtomicInteger epoch = new AtomicInteger();
    private ArrayDeque<Site> pending;
    private int total, completed, failed, hits;
    private String lastError = "";
    private Consumer<String> status;
    synchronized void start(List<Site> sites, Function<Site, Callable<Result>> factory, Consumer<Result> results, Consumer<String> progress) {
        stop(); int current = epoch.get();
        pending = new ArrayDeque<>(sites); total = sites.size(); completed = failed = hits = 0; lastError = ""; status = progress;
        report();
        dispatch(current,factory,results);
    }
    synchronized void stop() {
        epoch.incrementAndGet(); jobs.forEach(Job::cancel); jobs.clear();
        activeHttp = activeCrawlers = 0;
        if(pending!=null)pending.clear();
        Task.purgeSearchTasks();
    }
    private synchronized void dispatch(int current, Function<Site,Callable<Result>> factory,Consumer<Result> results) {
        if(epoch.get()!=current || dispatching)return;
        dispatching = true;
        try {
            Site site;
            while (epoch.get() == current && (site = nextAvailable()) != null) {
                boolean crawler = site.getType() == 3;
                Callable<Result> task = factory.apply(site);
                Job job = new Job(task, crawler);
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
    private Site nextAvailable() {
        for (Iterator<Site> iterator = pending.iterator(); iterator.hasNext();) {
            Site site = iterator.next();
            if (site.getType() == 3 ? activeCrawlers < crawlerParallelism : activeHttp < parallelism) {
                iterator.remove(); return site;
            }
        }
        return null;
    }
    private synchronized void finish(int current,Function<Site,Callable<Result>> factory,Consumer<Result> results,Job job,Result value,Throwable error) {
        job.resource.close();
        if (error != null && job.submitted != null) job.submitted.cancel(true);
        if(epoch.get()!=current)return;
        jobs.remove(job); completed++;
        if (job.crawler) activeCrawlers--; else activeHttp--;
        Task.purgeSearchTasks();
        if(error!=null){
            failed++;
            String message=String.valueOf(error);
            lastError=message.contains("架构") ? "插件架构不兼容" : message.contains("Timeout") ? "部分站点超时" : "部分站点加载失败";
        } else if(value!=null){
            // Keep only entries that can lead to a real detail page. Empty shells and
            // parser status rows otherwise make the aggregate search look successful.
            List<Vod> valid = value.getList().stream()
                    .filter(item -> item != null && !item.getName().isEmpty() && !item.getId().isEmpty())
                    .collect(Collectors.toList());
            if (!valid.isEmpty()) {
                value.setList(valid);
                hits += valid.size();
                results.accept(value);
            }
        }
        report(); dispatch(current,factory,results);
    }
    private void report() {
        if(total==0){status.accept("没有启用的搜索源");return;}
        status.accept((completed==total ? "搜索完成" : "搜索中")+" "+completed+"/"+total+" · "+hits+" 个有效结果 · "+failed+" 个失败"
                +(lastError.isEmpty()?"":" · "+lastError));
    }
}
