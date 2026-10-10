package com.fongmi.android.tv.model;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/** Close cancellable task resources once, including when cancellation races its callback. */
final class TaskResource implements AutoCloseable {
    private final Callable<?> task;
    private final AtomicBoolean closed = new AtomicBoolean();

    TaskResource(Callable<?> task) { this.task = task; }

    @Override public void close() {
        if (!closed.compareAndSet(false, true) || !(task instanceof AutoCloseable resource)) return;
        try { resource.close(); } catch (Exception ignored) { }
    }
}
