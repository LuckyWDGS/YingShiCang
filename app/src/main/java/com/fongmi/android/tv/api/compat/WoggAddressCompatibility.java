package com.fongmi.android.tv.api.compat;

import com.github.catvod.crawler.Spider;

import java.io.InterruptedIOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.concurrent.locks.ReentrantLock;

/** Retains the audited WoGG instance's address when its synchronous refresh returns an invalid one. */
public final class WoggAddressCompatibility {
    private static final String WOGG = "com.github.catvod.spider.WoGG";
    private static final String GUARD = "com.github.catvod.spider.BaseSpiderGuard";
    private static final ReferenceQueue<Spider> STALE = new ReferenceQueue<>();
    private static final Map<IdentityReference, State> STATES = new HashMap<>();

    private WoggAddressCompatibility() {}

    @FunctionalInterface public interface RemainingMillis { long get() throws Exception; }
    @FunctionalInterface public interface CancellationCheck { void check() throws Exception; }

    public static boolean supports(Spider spider) { return audited(spider) != null; }

    /** Resolve and initialize the spider before calling; every related SiteApi callback must participate. */
    public static <T> T call(Spider spider, long timeoutMillis, CancellationCheck cancellation,
                             Callable<T> operation) throws Exception {
        long started = System.nanoTime();
        return call(spider, () -> timeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                cancellation, operation);
    }

    /** The existing caller's remaining budget includes waiting for this same instance's earlier callback. */
    public static <T> T call(Spider spider, RemainingMillis remaining, CancellationCheck cancellation,
                             Callable<T> operation) throws Exception {
        return call(spider, remaining, () -> 0, cancellation, operation, false);
    }

    /**
     * As above, with time the caller reports as paused (monotonic milliseconds, e.g. the source's own
     * login dialog) extending the deadline; the caller's remaining count must include it too.
     */
    public static <T> T call(Spider spider, RemainingMillis remaining, LongSupplier paused, CancellationCheck cancellation,
                             Callable<T> operation) throws Exception {
        return call(spider, remaining, paused, cancellation, operation, false);
    }

    /**
     * A search callback. Details and play addresses are what the user is waiting on, so one that has to
     * wait for this instance interrupts a search holding it, as leaving the search page would; the
     * search fails like any cancelled search and may be retried.
     */
    public static <T> T search(Spider spider, RemainingMillis remaining, CancellationCheck cancellation,
                               Callable<T> operation) throws Exception {
        return call(spider, remaining, () -> 0, cancellation, operation, true);
    }

    /** How long this thread's last callback waited for the instance lock, in milliseconds. */
    public static long lastWaitMillis() {
        Long value = WAIT.get();
        return value == null ? 0 : value;
    }

    private static final ThreadLocal<Long> WAIT = new ThreadLocal<>();

    /**
     * How long a callback whose caller has given up (its budget is spent, or it was cancelled and is still
     * inside the plugin) keeps later callbacks of the same instance waiting. A plugin call that never
     * returns used to hold the instance for the rest of the process: every later detail and play address
     * of that source waited out its own 30 s and failed (a TV, 2026-11-25: one play-address call still
     * running after 459 s, five requests behind it cancelled).
     */
    static volatile long abandonGraceMillis = 2000;
    private static volatile java.util.function.Consumer<String> abandoned = detail -> { };

    /** Told "reason=deadline|cancelled heldMs=N" each time a stuck callback is no longer waited for. */
    public static void onAbandoned(java.util.function.Consumer<String> listener) {
        abandoned = Objects.requireNonNull(listener);
    }

    private static <T> T call(Spider spider, RemainingMillis remaining, LongSupplier paused, CancellationCheck cancellation,
                              Callable<T> operation, boolean search) throws Exception {
        WAIT.remove();
        Audited audited = audited(spider);
        if (audited == null) return operation.call();
        Objects.requireNonNull(remaining);
        Objects.requireNonNull(cancellation);
        Objects.requireNonNull(operation);
        cancellation.check();
        checkInterrupted();
        Budget budget = new Budget(remaining, paused);
        State state = state(audited.delegate);
        Holder mine = null;
        long waitStart = System.nanoTime();
        try {
            while (mine == null) {
                cancellation.check();
                checkInterrupted();
                if (!search) state.preemptSearch();
                long wait = Math.min(50, budget.remaining());
                ReentrantLock lock = state.lock;
                boolean locked;
                try { locked = lock.tryLock(wait, TimeUnit.MILLISECONDS); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException failure = new InterruptedIOException("Source callback cancelled");
                    failure.initCause(interrupted);
                    throw failure;
                }
                if (locked) {
                    mine = state.hold(lock, budget, search);
                    // Replaced while this thread waited on it: its holder was given up on.
                    if (mine == null) lock.unlock();
                } else {
                    String given = state.abandonStuck(lock, abandonGraceMillis);
                    if (given != null) abandoned.accept(given);
                }
            }
            WAIT.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStart));
            cancellation.check();
            checkInterrupted();
            budget.remaining();
            state.remember(audited);
            try {
                T result = operation.call();
                cancellation.check();
                checkInterrupted();
                budget.remaining();
                return result;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } finally {
                // Restore even on throw/cancellation, without adding a request or changing the response.
                state.finish(audited);
            }
        } finally {
            if (mine != null) {
                state.release(mine);
                mine.lock.unlock();
            }
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Source callback cancelled");
    }

    private static State state(Spider delegate) {
        synchronized (STATES) {
            IdentityReference stale;
            while ((stale = (IdentityReference) STALE.poll()) != null) STATES.remove(stale);
            IdentityReference lookup = new IdentityReference(delegate, null);
            State state = STATES.get(lookup);
            if (state == null) {
                state = new State();
                STATES.put(new IdentityReference(delegate, STALE), state);
            }
            return state;
        }
    }

    private static final class IdentityReference extends WeakReference<Spider> {
        private final int hash;
        IdentityReference(Spider value, ReferenceQueue<Spider> queue) {
            super(value, queue);
            hash = System.identityHashCode(value);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityReference)) return false;
            Spider value = get();
            return value != null && value == ((IdentityReference) other).get();
        }
    }

    /** One callback inside the instance, with the lock it took. */
    private static final class Holder {
        final Thread thread = Thread.currentThread();
        final ReentrantLock lock;
        final Budget budget;
        final boolean search;
        final long since = System.nanoTime();
        boolean preempted;
        long interruptedSince = -1;
        Holder(ReentrantLock lock, Budget budget, boolean search) { this.lock = lock; this.budget = budget; this.search = search; }
    }

    private static final class State {
        volatile ReentrantLock lock = new ReentrantLock(true);
        private Holder holder;

        /** Called with {@code held} locked; null when that lock was replaced meanwhile. */
        synchronized Holder hold(ReentrantLock held, Budget budget, boolean search) {
            if (lock != held) return null;
            return holder = new Holder(held, budget, search);
        }

        /** Interrupts the search holding the lock, once; it cannot have released the lock meanwhile. */
        synchronized void preemptSearch() {
            Holder current = holder;
            if (current == null || !current.search || current.preempted) return;
            current.preempted = true;
            current.thread.interrupt();
        }

        /** Called by the holder: a preemption interrupt must not outlive this callback on a pooled thread. */
        synchronized void release(Holder mine) {
            if (mine.preempted) Thread.interrupted();
            if (holder == mine) holder = null;
        }

        /**
         * Gives later callbacks a new lock when the holder of {@code waited} can no longer be delivered to
         * anyone: its budget ran out, or it was interrupted (its caller cancelled it, or a detail preempted
         * its search) and has stayed inside the plugin for the grace since. It finishes on its own lock.
         */
        synchronized String abandonStuck(ReentrantLock waited, long graceMillis) {
            Holder current = holder;
            if (current == null || lock != waited || current.lock != waited) return null;
            long now = System.nanoTime();
            String reason = null;
            if (current.budget.overdueMillis() >= graceMillis) reason = "deadline";
            else if (!current.thread.isInterrupted()) current.interruptedSince = -1;
            else if (current.interruptedSince < 0) current.interruptedSince = now;
            else if (TimeUnit.NANOSECONDS.toMillis(now - current.interruptedSince) >= graceMillis) reason = "cancelled";
            if (reason == null) return null;
            holder = null;
            lock = new ReentrantLock(true);
            return "reason=" + reason + " heldMs=" + TimeUnit.NANOSECONDS.toMillis(now - current.since) + (current.search ? " search" : "");
        }
        String lastValid;
        void remember(Audited audited) {
            try {
                String current = (String) audited.address.get(audited.delegate);
                if (valid(current)) lastValid = current;
            } catch (IllegalAccessException | RuntimeException ignored) { }
        }
        void finish(Audited audited) {
            try {
                String current = (String) audited.address.get(audited.delegate);
                if (valid(current)) lastValid = current;
                else if (lastValid != null) audited.address.set(audited.delegate, lastValid);
            } catch (IllegalAccessException | RuntimeException ignored) {
                // Best-effort compatibility must never replace the original callback error.
            }
        }
    }

    private static final class Budget {
        final RemainingMillis caller;
        final LongSupplier paused;
        final long initial, started, pausedBase;
        Budget(RemainingMillis caller, LongSupplier paused) throws Exception {
            this.caller = caller;
            this.paused = paused;
            this.started = System.nanoTime();
            this.pausedBase = paused.getAsLong();
            this.initial = caller.get();
            if (initial <= 0) throw deadline();
        }
        long remaining() throws Exception {
            // A fixed ceiling bounds even a constant supplier; only reported paused time extends it.
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) - (paused.getAsLong() - pausedBase);
            long left = Math.min(initial - elapsed, caller.get());
            if (left <= 0) throw deadline();
            return left;
        }
        /** Milliseconds past the budget this callback started with (paused time excluded); negative while inside it. */
        long overdueMillis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) - (paused.getAsLong() - pausedBase) - initial;
        }
    }

    private static InterruptedIOException deadline() { return new InterruptedIOException("Source callback deadline exceeded"); }

    private static boolean valid(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            URI address = new URI(value);
            String scheme = address.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && address.getHost() != null && !address.getHost().isEmpty();
        } catch (Exception invalid) { return false; }
    }

    private static final class Audited {
        final Spider delegate;
        final Field address;
        Audited(Spider delegate, Field address) { this.delegate = delegate; this.address = address; }
    }

    private static Audited audited(Spider spider) {
        IdentityHashMap<Spider, Boolean> seen = new IdentityHashMap<>();
        for (int depth = 0; spider != null && depth < 8; depth++) {
            if (seen.put(spider, Boolean.TRUE) != null) return null;
            try {
                Class<?> type = spider.getClass();
                if (WOGG.equals(type.getName())) {
                    Field address = type.getDeclaredField("O");
                    Field retry = type.getDeclaredField("n");
                    if (address.getType() != String.class || !Modifier.isPrivate(address.getModifiers())
                            || Modifier.isStatic(address.getModifiers()) || Modifier.isFinal(address.getModifiers())
                            || retry.getType() != boolean.class || !Modifier.isPrivate(retry.getModifiers())
                            || !Modifier.isStatic(retry.getModifiers()) || Modifier.isFinal(retry.getModifiers())
                            || !signature(type, "getKey", void.class) || !signature(type, "getSite", void.class)
                            || !signature(type, "refTry", String.class, String.class)
                            || !signature(type, "init", void.class, android.content.Context.class, String.class)
                            || !signature(type, "searchContent", String.class, String.class, boolean.class)) return null;
                    address.setAccessible(true);
                    return new Audited(spider, address);
                }
                if (!trustedGuard(type)) return null;
                spider = delegate(spider);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) { return null; }
        }
        return null;
    }

    private static boolean signature(Class<?> type, String name, Class<?> returns, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, parameters);
        return method.getReturnType() == returns && Modifier.isPublic(method.getModifiers())
                && !Modifier.isStatic(method.getModifiers());
    }

    private static boolean trustedGuard(Class<?> type) throws NoSuchMethodException {
        return GUARD.equals(type.getMethod("init", android.content.Context.class, String.class).getDeclaringClass().getName())
                && GUARD.equals(type.getMethod("searchContent", String.class, boolean.class).getDeclaringClass().getName())
                && forwarded(type, "homeContent", boolean.class) && forwarded(type, "homeVideoContent")
                && forwarded(type, "categoryContent", String.class, String.class, boolean.class, HashMap.class)
                && forwarded(type, "detailContent", List.class)
                && forwarded(type, "searchContent", String.class, boolean.class, String.class)
                && forwarded(type, "playerContent", String.class, String.class, List.class)
                && forwarded(type, "action", String.class);
    }

    private static boolean forwarded(Class<?> type, String method, Class<?>... parameters) throws NoSuchMethodException {
        Class<?> declaring = type.getMethod(method, parameters).getDeclaringClass();
        // The audited BaseSpiderGuard declares and directly forwards each of these methods.
        return GUARD.equals(declaring.getName());
    }

    private static Spider delegate(Spider wrapper) throws IllegalAccessException {
        Field selected = null;
        for (Class<?> type = wrapper.getClass(); type != null && type != Spider.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Spider.class.isAssignableFrom(field.getType())) continue;
                if (selected != null) return null;
                selected = field;
            }
        }
        if (selected == null) return null;
        selected.setAccessible(true);
        return (Spider) selected.get(wrapper);
    }
}
