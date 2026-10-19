package com.fongmi.android.tv.diagnostics;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded numerical load observations. Never retains requests, media items or exception messages. */
public final class PlaybackLoadDiagnostics {

    static final int MAX_EVENTS = 240;
    static final int RESERVED_ERROR_EVENTS = 32;
    static final int MAX_TRACKED_TASKS = 32;

    public enum Event {
        STARTED("playback_load_started", 1_000),
        COMPLETED("playback_load_completed", 1_000),
        CANCELED("playback_load_canceled", 1_000),
        ERROR("playback_load_error", 500),
        BANDWIDTH("playback_bandwidth", 5_000);

        private final String logName;
        private final long intervalMs;

        Event(String logName, long intervalMs) {
            this.logName = logName;
            this.intervalMs = intervalMs;
        }

        public String logName() { return logName; }
    }

    private final long[] lastLoggedMs = new long[Event.values().length];
    private final Map<Long, Integer> retries = new LinkedHashMap<>();
    private int logged, ordinaryLogged;
    private long suppressed;
    private boolean active;

    public void reset() {
        Arrays.fill(lastLoggedMs, -1);
        retries.clear();
        logged = ordinaryLogged = 0;
        suppressed = 0;
        active = true;
    }

    public void stop() {
        active = false;
        retries.clear();
    }

    /** Unknown retry counts remain -1 when a start predates this bounded observation window. */
    public String load(Event event, long nowMs, long taskId, long bytesLoaded, long durationMs,
                       int dataType, int trackType, int retryCount, boolean wasCanceled,
                       int httpStatus, Throwable error) {
        if (!active || event == null || event == Event.BANDWIDTH) return null;
        if (event == Event.STARTED && taskId >= 0 && retryCount >= 0) {
            retries.put(taskId, retryCount);
            if (retries.size() > MAX_TRACKED_TASKS) retries.remove(retries.keySet().iterator().next());
        }
        int observedRetry = retryCount >= 0 ? retryCount : retries.getOrDefault(taskId, -1);
        if (event == Event.COMPLETED || event == Event.CANCELED || (event == Event.ERROR && wasCanceled)) {
            retries.remove(taskId);
        }
        if (!allow(event, nowMs)) return null;
        // Class names are the only textual error metadata; never call toString/getMessage.
        String errorType = error == null ? "none" : error.getClass().getSimpleName();
        if (errorType.isEmpty() || errorType.length() > 80 || !errorType.matches("[A-Za-z0-9_$]+")) errorType = "unknown";
        return "taskId=" + (taskId >= 0 ? taskId : -1)
                + " bytes=" + Math.max(0, bytesLoaded) + " durationMs=" + Math.max(0, durationMs)
                + " retryCount=" + observedRetry + " dataType=" + dataType + " trackType=" + trackType
                + " wasCanceled=" + wasCanceled + " httpStatus=" + (httpStatus >= 100 && httpStatus <= 599 ? httpStatus : -1)
                + " errorType=" + errorType + counters();
    }

    /** Bandwidth-meter samples are aggregate network observations, not individual HTTP requests. */
    public String bandwidth(long nowMs, int totalLoadTimeMs, long totalBytesLoaded, long bitrateEstimate) {
        if (!allow(Event.BANDWIDTH, nowMs)) return null;
        return "sampleMs=" + Math.max(0, totalLoadTimeMs) + " bytes=" + Math.max(0, totalBytesLoaded)
                + " bitrateEstimate=" + (bitrateEstimate >= 0 ? bitrateEstimate : -1) + counters();
    }

    private boolean allow(Event event, long nowMs) {
        if (!active) return false;
        long now = Math.max(0, nowMs);
        long previous = lastLoggedMs[event.ordinal()];
        if (logged >= MAX_EVENTS || (event != Event.ERROR && ordinaryLogged >= MAX_EVENTS - RESERVED_ERROR_EVENTS)
                || (previous >= 0 && (now < previous || now - previous < event.intervalMs))) {
            if (suppressed < Long.MAX_VALUE) suppressed++;
            return false;
        }
        lastLoggedMs[event.ordinal()] = now;
        logged++;
        if (event != Event.ERROR) ordinaryLogged++;
        return true;
    }

    private String counters() { return " observation=" + logged + " suppressed=" + suppressed; }
}
