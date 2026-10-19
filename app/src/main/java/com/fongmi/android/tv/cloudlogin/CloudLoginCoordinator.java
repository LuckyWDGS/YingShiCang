package com.fongmi.android.tv.cloudlogin;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the race between the three source-owned cloud-login sessions.
 *
 * <p>This class deliberately knows nothing about QR payloads, cookies, tokens
 * or provider method names. A verified provider adapter supplies a Session;
 * late callbacks from an old generation or superseded open attempt are
 * ignored here.</p>
 */
public final class CloudLoginCoordinator<Q, A> {
    public static final int PROVIDER_COUNT = 3;

    public interface Session<Q, A> {
        void start(@NonNull Events<Q, A> events);

        void cancel();
    }

    public interface Factory<Q, A> {
        @NonNull Session<Q, A> open(int providerIndex, int generation) throws Exception;
    }

    public interface Events<Q, A> {
        void onQrReady(@NonNull Q qr, @Nullable String status);

        void onScanned(@Nullable String status);

        void onAuthenticated(@NonNull A account);

        void onExpired(@Nullable String status);

        void onError(@Nullable String status);
    }

    public interface Listener<Q, A> {
        void onQrReady(int providerIndex, @NonNull Q qr, @Nullable String status);

        void onScanned(int providerIndex, @Nullable String status);

        void onExpired(int providerIndex, @Nullable String status);

        void onError(int providerIndex, @Nullable String status);

        void onAuthenticated(int providerIndex, @NonNull A account);

        void onClosed();
    }

    private final Object lock = new Object();
    private final Factory<Q, A> factory;
    private final Listener<Q, A> listener;
    private final Session<Q, A>[] sessions;
    private final int[] generations;
    /**
     * Monotonically advances for every open attempt, including a replacement
     * in the same provider generation.  A generation alone cannot distinguish
     * a concurrent start() open from a retry() open that happened before the
     * first factory call returned.
     */
    private final long[] openTokens;
    private boolean started;
    private boolean closed;
    private boolean completed;
    private boolean closeReported;

    @SuppressWarnings("unchecked")
    public CloudLoginCoordinator(@NonNull Factory<Q, A> factory, @NonNull Listener<Q, A> listener) {
        if (factory == null || listener == null) throw new IllegalArgumentException("factory/listener required");
        this.factory = factory;
        this.listener = listener;
        this.sessions = (Session<Q, A>[]) new Session[PROVIDER_COUNT];
        this.generations = new int[PROVIDER_COUNT];
        this.openTokens = new long[PROVIDER_COUNT];
    }

    /** Starts all three sessions exactly once. */
    public boolean start() {
        synchronized (lock) {
            if (started || closed) return false;
            started = true;
        }
        for (int index = 0; index < PROVIDER_COUNT; index++) open(index);
        return true;
    }

    /** Replaces one failed/expired provider session without touching the others. */
    public boolean retry(int providerIndex) {
        if (providerIndex < 0 || providerIndex >= PROVIDER_COUNT) return false;
        Session<Q, A> previous;
        synchronized (lock) {
            if (!started || closed || completed) return false;
            generations[providerIndex]++;
            previous = sessions[providerIndex];
            sessions[providerIndex] = null;
        }
        cancel(previous);
        open(providerIndex);
        return true;
    }

    /** Cancels all providers and sends the close callback once. */
    public void close() {
        List<Session<Q, A>> pending;
        boolean reportClose;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            // Authentication already completed the user flow. Owners often
            // call close() while dismissing the dialog in that callback; that
            // is cleanup, not a user cancellation, so do not emit onClosed.
            reportClose = !completed;
            pending = takeSessionsLocked();
        }
        cancelAll(pending);
        if (reportClose) reportClosed();
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    private void open(int providerIndex) {
        final int generation;
        final long openToken;
        synchronized (lock) {
            if (closed || completed || !started) return;
            generation = generations[providerIndex];
            openToken = ++openTokens[providerIndex];
        }
        Session<Q, A> session;
        try {
            session = factory.open(providerIndex, generation);
        } catch (Throwable ignored) {
            // A provider adapter can fail before it has a Session (for
            // example while resolving an optional implementation). Keep that
            // failure inside this provider lane instead of crashing the
            // activity and preventing the other two QR cards from opening.
            dispatchError(providerIndex, generation, openToken, null);
            return;
        }
        if (session == null) {
            dispatchError(providerIndex, generation, openToken, null);
            return;
        }
        Session<Q, A> replaced = null;
        boolean stale;
        synchronized (lock) {
            stale = closed || completed || generations[providerIndex] != generation
                    || openTokens[providerIndex] != openToken;
            if (!stale) {
                replaced = sessions[providerIndex];
                sessions[providerIndex] = session;
            }
        }
        if (stale) {
            // Provider cleanup may call back into the coordinator. Never run
            // it while holding the lifecycle monitor.
            cancel(session);
            return;
        }
        // A duplicate open can happen when start/retry overlap.  Replace the
        // old session after invalidating its token, then cancel it outside the
        // monitor so provider cleanup cannot block other callbacks.
        cancel(replaced);
        // Provider cleanup is allowed to re-enter the coordinator.  For
        // example, a replaced session can synchronously request retry(),
        // invalidating this open before its start() call.  Never start a
        // session that has already been superseded or cancelled by that
        // callback.
        if (!isCurrent(providerIndex, generation, openToken)) {
            cancel(session);
            return;
        }
        try {
            session.start(events(providerIndex, generation, openToken));
        } catch (Throwable ignored) {
            boolean current;
            final int failureGeneration;
            final long failureToken;
            synchronized (lock) {
                current = isCurrentLocked(providerIndex, generation, openToken);
                if (current && sessions[providerIndex] == session) {
                    sessions[providerIndex] = null;
                    generations[providerIndex]++;
                    openTokens[providerIndex]++;
                }
                failureGeneration = generations[providerIndex];
                failureToken = openTokens[providerIndex];
            }
            // A provider may have allocated polling/network resources before
            // start() failed.  Release them immediately instead of waiting
            // for a later retry or dialog close.
            cancel(session);
            // cancel() may synchronously re-enter retry()/close(); do not
            // report an error for a generation that has already been
            // superseded by that cleanup.
            if (current) dispatchError(providerIndex, failureGeneration, failureToken, null);
        }
    }

    private Events<Q, A> events(int providerIndex, int generation, long openToken) {
        return new Events<>() {
            @Override
            public void onQrReady(@NonNull Q qr, @Nullable String status) {
                if (qr == null) {
                    dispatchError(providerIndex, generation, openToken, null);
                    return;
                }
                if (isCurrent(providerIndex, generation, openToken))
                    listener.onQrReady(providerIndex, qr, status);
            }

            @Override
            public void onScanned(@Nullable String status) {
                if (isCurrent(providerIndex, generation, openToken))
                    listener.onScanned(providerIndex, status);
            }

            @Override
            public void onAuthenticated(@NonNull A account) {
                if (account == null) {
                    dispatchError(providerIndex, generation, openToken, null);
                    return;
                }
                complete(providerIndex, generation, openToken, account);
            }

            @Override
            public void onExpired(@Nullable String status) {
                if (isCurrent(providerIndex, generation, openToken))
                    listener.onExpired(providerIndex, status);
            }

            @Override
            public void onError(@Nullable String status) {
                if (isCurrent(providerIndex, generation, openToken))
                    listener.onError(providerIndex, status);
            }
        };
    }

    private void complete(int providerIndex, int generation, long openToken, @NonNull A account) {
        List<Session<Q, A>> pending;
        synchronized (lock) {
            if (!isCurrentLocked(providerIndex, generation, openToken) || completed) return;
            completed = true;
            pending = takeSessionsLocked();
        }
        cancelAll(pending);
        listener.onAuthenticated(providerIndex, account);
    }

    private void dispatchError(int providerIndex, int generation, long openToken, @Nullable String status) {
        if (isCurrent(providerIndex, generation, openToken)) listener.onError(providerIndex, status);
    }

    private boolean isCurrent(int providerIndex, int generation, long openToken) {
        synchronized (lock) {
            return isCurrentLocked(providerIndex, generation, openToken);
        }
    }

    private boolean isCurrentLocked(int providerIndex, int generation, long openToken) {
        return started && !closed && !completed && generations[providerIndex] == generation
                && openTokens[providerIndex] == openToken;
    }

    private List<Session<Q, A>> takeSessionsLocked() {
        List<Session<Q, A>> pending = new ArrayList<>(PROVIDER_COUNT);
        for (int index = 0; index < PROVIDER_COUNT; index++) {
            if (sessions[index] != null) pending.add(sessions[index]);
            sessions[index] = null;
            generations[index]++;
            openTokens[index]++;
        }
        return pending;
    }

    private void reportClosed() {
        synchronized (lock) {
            if (closeReported) return;
            closeReported = true;
        }
        listener.onClosed();
    }

    private static <Q, A> void cancelAll(List<Session<Q, A>> sessions) {
        for (Session<Q, A> session : sessions) cancel(session);
    }

    private static <Q, A> void cancel(Session<Q, A> session) {
        if (session == null) return;
        try {
            session.cancel();
        } catch (Throwable ignored) {
            // Provider cleanup is best effort; the coordinator remains closed.
        }
    }
}
