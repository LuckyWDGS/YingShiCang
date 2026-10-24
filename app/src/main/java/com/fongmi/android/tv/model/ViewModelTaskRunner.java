package com.fongmi.android.tv.model;

import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.api.SiteApi;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

final class ViewModelTaskRunner<T extends Enum<T>> {

    private final Map<T, ListenableFuture<?>> futures;
    private final Map<T, AtomicInteger> taskIds;
    private final Map<T, TaskResource> resources;
    private final ListeningExecutorService workers;
    private final ScheduledExecutorService scheduler;

    ViewModelTaskRunner(Class<T> typeClass) {
        this(typeClass, Task.executor(), Task.scheduler());
    }

    ViewModelTaskRunner(Class<T> typeClass, ListeningExecutorService workers, ScheduledExecutorService scheduler) {
        this.workers = workers;
        this.scheduler = scheduler;
        futures = new EnumMap<>(typeClass);
        taskIds = new EnumMap<>(typeClass);
        resources = new EnumMap<>(typeClass);
        for (T type : typeClass.getEnumConstants()) taskIds.put(type, new AtomicInteger(0));
    }

    <R> void execute(T type, long timeoutMs, Callable<R> callable, Consumer<R> onSuccess, Consumer<Throwable> onError) {
        execute(type, timeoutMs, null, callable, onSuccess, onError);
    }

    /** With {@code paused}, time it reports (milliseconds, monotonic) does not count against the timeout. */
    <R> void execute(T type, long timeoutMs, LongSupplier paused, Callable<R> callable, Consumer<R> onSuccess, Consumer<Throwable> onError) {
        AtomicInteger taskId = Objects.requireNonNull(taskIds.get(type));
        int currentId = taskId.incrementAndGet();
        TaskResource oldResource = resources.get(type);
        if (oldResource != null) oldResource.close();
        ListenableFuture<?> old = futures.get(type);
        if (old != null) old.cancel(true);
        TaskResource resource = new TaskResource(callable);
        resources.put(type, resource);
        Task.purgeSearchTasks();
        ListeningExecutorService dispatchWorkers = callable instanceof SiteApi.SearchTask searchTask
                ? Task.searchExecutor(searchTask.isCrawler()) : workers;
        FluentFuture<R> future;
        try {
            ListenableFuture<R> submitted = dispatchWorkers.submit(callable);
            future = paused == null ? FluentFuture.from(submitted).withTimeout(timeoutMs, TimeUnit.MILLISECONDS, scheduler)
                    : FluentFuture.from(pausableTimeout(submitted, timeoutMs, paused));
        }
        catch (RejectedExecutionException rejected) {
            resource.close(); futures.remove(type);
            if (taskId.get() == currentId) onError.accept(rejected);
            return;
        }
        futures.put(type, future);
        future.addCallback(Task.callback(
                result -> {
                    resource.close();
                    if (taskId.get() == currentId) onSuccess.accept(result);
                },
                error -> {
                    resource.close();
                    if (error instanceof CancellationException) return;
                    if (taskId.get() == currentId) onError.accept(error);
                }
        ), MoreExecutors.directExecutor());
    }

    private <R> ListenableFuture<R> pausableTimeout(ListenableFuture<R> task, long timeoutMs, LongSupplier paused) {
        SettableFuture<R> result = SettableFuture.create();
        // Not setFuture: that counts as set, and the timeout below could no longer complete it.
        Futures.addCallback(task, new FutureCallback<>() {
            @Override public void onSuccess(R value) { result.set(value); }
            @Override public void onFailure(Throwable error) {
                if (error instanceof CancellationException) result.cancel(false);
                else result.setException(error);
            }
        }, MoreExecutors.directExecutor());
        result.addListener(() -> { if (result.isCancelled()) task.cancel(true); }, MoreExecutors.directExecutor());
        long started = System.nanoTime(), base = paused.getAsLong();
        ScheduledFuture<?> watchdog = scheduler.scheduleWithFixedDelay(() -> {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) - (paused.getAsLong() - base);
            if (elapsed >= timeoutMs && result.setException(new TimeoutException("Timed out after " + timeoutMs + "ms")))
                task.cancel(true);
        }, 250, 250, TimeUnit.MILLISECONDS);
        result.addListener(() -> watchdog.cancel(false), MoreExecutors.directExecutor());
        return result;
    }

    void cancelAll() {
        taskIds.values().forEach(AtomicInteger::incrementAndGet);
        resources.values().forEach(TaskResource::close);
        resources.clear();
        futures.values().forEach(future -> future.cancel(true));
        futures.clear();
        Task.purgeSearchTasks();
    }
}
