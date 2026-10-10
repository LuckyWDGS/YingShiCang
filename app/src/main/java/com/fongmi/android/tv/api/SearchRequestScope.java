package com.fongmi.android.tv.api;

import com.fongmi.android.tv.Constant;
import com.github.catvod.net.OkHttp;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** One search source shares a deadline and cancellable HTTP calls across all of its stages. */
public final class SearchRequestScope implements AutoCloseable {
    private final Supplier<OkHttpClient> client;
    private final long deadline;
    private final Set<Call> calls = new HashSet<>();
    private volatile boolean closed;

    SearchRequestScope(long budgetMillis) { this(OkHttp::client, budgetMillis); }

    public SearchRequestScope(OkHttpClient client, long budgetMillis) { this(() -> client, budgetMillis); }

    private SearchRequestScope(Supplier<OkHttpClient> client, long budgetMillis) {
        this.client = client;
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, Math.min(Constant.TIMEOUT_SEARCH, budgetMillis)));
    }

    public void checkCancellation() throws InterruptedIOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Search cancelled");
    }

    public long remainingMillis() throws InterruptedIOException {
        checkCancellation();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new InterruptedIOException("Search deadline exceeded");
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    public String execute(Request request) throws IOException { return execute(request, Long.MAX_VALUE); }

    public String execute(Request request, long maximumMillis) throws IOException {
        long timeout = Math.min(remainingMillis(), maximumMillis);
        if (timeout <= 0) throw new InterruptedIOException("Search deadline exceeded");
        OkHttpClient bounded = client.get().newBuilder().callTimeout(timeout, TimeUnit.MILLISECONDS).build();
        Call call = bounded.newCall(request);
        synchronized (this) {
            checkCancellation();
            calls.add(call);
        }
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("Search source unavailable");
            return response.body().string();
        } finally {
            synchronized (this) { calls.remove(call); }
        }
    }

    @Override public void close() {
        ArrayList<Call> active;
        synchronized (this) {
            if (closed) return;
            closed = true;
            active = new ArrayList<>(calls);
            calls.clear();
        }
        active.forEach(Call::cancel);
    }
}
