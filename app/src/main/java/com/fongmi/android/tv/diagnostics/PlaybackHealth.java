package com.fongmi.android.tv.diagnostics;

import android.os.SystemClock;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.media.PlaySpec;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Passive state timing. Never downloads additional content during playback. */
public final class PlaybackHealth {
    public record Stream(String url, Map<String, String> headers) {}
    public record Summary(long startupMs, long playedMs, long bufferedMs, int buffers, String error) {}
    private static volatile Stream lastStream;
    private static volatile Summary lastSummary;
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private String id;
    private long started, mark, played, buffered, ready = -1;
    private int state, buffers;
    private boolean active, playRequested = true, seeking;
    private String error = "";
    public static Stream lastStream() { return lastStream; }
    public static Summary lastSummary() { return lastSummary; }
    public static boolean active() { return ACTIVE.get() > 0; }
    public void start(PlaySpec spec) {
        finish();
        if (spec == null || spec.getUrl() == null || spec.getUrl().trim().isEmpty()) return;
        lastStream = new Stream(spec.getUrl(), Collections.unmodifiableMap(spec.getHeaders() == null ? Map.of() : new HashMap<>(spec.getHeaders())));
        id = UUID.randomUUID().toString(); started = mark = SystemClock.elapsedRealtime();
        played = buffered = 0; ready = -1; buffers = 0; error = ""; seeking = false; state = 0;
        playRequested = true; active = true; ACTIVE.incrementAndGet();
        log("start", "");
    }
    private void account() {
        long now = SystemClock.elapsedRealtime(), delta = Math.max(0, now - mark); mark = now;
        if (playRequested && ready >= 0 && !seeking) {
            if (state == 3) played += delta;
            if (state == 2) buffered += delta;
        }
    }
    public void state(int next) {
        if (!active) return;
        account();
        if (next == 3 && ready < 0) ready = mark - started;
        if (next == 2 && state != 2 && ready >= 0 && playRequested && !seeking) buffers++;
        state = next;
        if (next == 3) seeking = false;
        lastSummary = new Summary(ready, played, buffered, buffers, error);
        log("state", "state=" + next + " readyMs=" + ready + " playedMs=" + played + " rebufferMs=" + buffered);
        if (next == 4) finish();
    }
    public void playWhenReady(boolean value) { if (active) account(); playRequested = value; }
    public void seek() {
        if (active) {
            account();
            // A buffered seek can stay READY and never emit another state transition.
            seeking = state != 3;
        }
    }
    public void error(String value) { error = value; log("error", value); finish(); }
    public void finish() {
        if (!active) return;
        account(); lastSummary = new Summary(ready, played, buffered, buffers, error);
        log("end", "playedMs=" + played + " rebufferMs=" + buffered + " buffers=" + buffers);
        active = false; ACTIVE.decrementAndGet();
    }
    private void log(String event, String detail) {
        // JVM diagnostics and a few early lifecycle callbacks can run before
        // Application.onCreate().  Timing state remains useful without a log
        // sink; avoid turning that benign race into a playback failure.
        if (App.get() != null) {
            DiagnosticLog.write(App.get(), "playback_" + event, "session=" + id + " " + detail);
        }
    }
}
