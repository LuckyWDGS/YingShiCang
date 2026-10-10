package com.fongmi.android.tv.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Debounced, cancellable suggestions. Request preparation and parsing belong to the transport. */
public final class SearchSuggestionController<T> implements AutoCloseable {
    public static final long DEBOUNCE_MILLIS = 150;
    public static final long CACHE_TTL_MILLIS = 5 * 60 * 1000;
    public static final int CACHE_LIMIT = 32;

    public interface Cancelable { void cancel(); }
    public interface Scheduler {
        Cancelable schedule(long delayMillis, Runnable action);
        void post(Runnable action);
    }
    public interface Clock { long nowMillis(); }
    public interface Listener<T> { void onSuggestions(String query, List<T> suggestions); }
    public interface Callback<T> {
        void onResult(List<T> suggestions);
        void onFailure();
    }
    public interface Transport<T> { Cancelable request(String query, Callback<T> callback); }

    private static final class Entry<T> {
        final long created;
        final List<T> values;
        Entry(long created, List<T> values) { this.created = created; this.values = values; }
    }

    private final Scheduler scheduler;
    private final Transport<T> transport;
    private final Clock clock;
    private final Listener<T> listener;
    private final Map<String, Entry<T>> cache = new LinkedHashMap<>(CACHE_LIMIT, 0.75f, true);
    private String query;
    private long generation;
    private boolean suspended, closed, debouncePending, requestPending;
    private Cancelable debounce, request;

    public SearchSuggestionController(Scheduler scheduler, Transport<T> transport, Clock clock, Listener<T> listener) {
        this.scheduler = scheduler;
        this.transport = transport;
        this.clock = clock;
        this.listener = listener;
    }

    /** Call on the UI scheduler. Identical normalized input does not repeat work or clear the list. */
    public synchronized void update(String text) {
        if (closed) return;
        String next = normalize(text);
        if (suspended) { query = next; return; }
        if (next.equals(query)) return;
        invalidate();
        query = next;
        long current = generation;
        if (next.isEmpty()) {
            listener.onSuggestions(next, Collections.emptyList());
            return;
        }
        Entry<T> entry = cached(next);
        if (entry != null) {
            listener.onSuggestions(next, entry.values);
            return;
        }
        // Keep the displayed candidates while the replacement query is pending.
        // Only a current nonempty result or explicitly cleared input changes the UI.
        if (!isCurrent(current, next)) return;
        debouncePending = true;
        Cancelable scheduled = scheduler.schedule(DEBOUNCE_MILLIS, () -> begin(current, next));
        if (isCurrent(current, next) && debouncePending) debounce = scheduled;
        else cancel(scheduled);
    }

    /** Invalidates queued deliveries as well as cancelling scheduled and in-flight work. */
    public synchronized void suspend() {
        if (closed || suspended) return;
        suspended = true;
        invalidate();
    }

    public synchronized void resume(String text) {
        if (closed) return;
        if (suspended) { suspended = false; query = null; }
        update(text);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        invalidate();
        cache.clear();
    }

    private synchronized void begin(long current, String text) {
        if (!isCurrent(current, text) || !debouncePending) return;
        debouncePending = false;
        debounce = null;
        requestPending = true;
        try {
            Cancelable started = transport.request(text, new Callback<T>() {
                @Override public void onResult(List<T> values) {
                    List<T> snapshot = immutable(values);
                    scheduler.post(() -> deliver(current, text, snapshot, true));
                }
                @Override public void onFailure() {
                    scheduler.post(() -> deliver(current, text, Collections.emptyList(), false));
                }
            });
            // A synchronous callback or lifecycle change may have already finished this request.
            if (isCurrent(current, text) && requestPending) request = started;
            else cancel(started);
        } catch (RuntimeException failure) {
            scheduler.post(() -> deliver(current, text, Collections.emptyList(), false));
        }
    }

    private synchronized void deliver(long current, String text, List<T> values, boolean successful) {
        if (!isCurrent(current, text) || !requestPending) return;
        requestPending = false;
        request = null;
        if (successful && !values.isEmpty()) {
            cache.put(text, new Entry<>(clock.nowMillis(), values));
            while (cache.size() > CACHE_LIMIT) cache.remove(cache.keySet().iterator().next());
        }
        // Failed/empty responses preserve the last displayed candidates and allow a later retry.
        if (!values.isEmpty()) listener.onSuggestions(text, values);
    }

    private Entry<T> cached(String text) {
        Entry<T> entry = cache.get(text);
        if (entry == null) return null;
        long age = clock.nowMillis() - entry.created;
        if (age >= 0 && age < CACHE_TTL_MILLIS) return entry;
        cache.remove(text);
        return null;
    }

    private boolean isCurrent(long current, String text) {
        return !closed && !suspended && generation == current && text.equals(query);
    }

    private void invalidate() {
        // Cancellation is allowed to invoke its callback synchronously.
        generation++;
        Cancelable oldDebounce = debounce, oldRequest = request;
        debounce = request = null;
        debouncePending = requestPending = false;
        cancel(oldDebounce);
        cancel(oldRequest);
    }

    private static void cancel(Cancelable work) {
        if (work != null) try { work.cancel(); } catch (RuntimeException ignored) { }
    }

    private static String normalize(String value) { return value == null ? "" : value.trim(); }
    private static <T> List<T> immutable(List<T> values) {
        return values == null || values.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
