package com.fongmi.android.tv.diagnostics;

import android.os.Build;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Stops a start-up crash loop. Two Android 15 phones (2026-11-17, 2026-11-21) died about a second after
 * every launch inside a source plugin's own packed native library (SIGABRT, a JNI call on null), 14 times;
 * each launch loaded the same configuration and the same plugin again, and both users got out only by
 * clearing the app's data. The app cannot repair a plugin, but it can stop loading it by itself.
 *
 * A configuration load arms the guard; staying alive 15s after the load, a failed load, or the app going
 * to the background disarms it. A process that ended while armed counts once. After two in a row the
 * configuration is no longer loaded automatically; a load the user asks for starts the count again.
 * The state is a small file, written at once: a native crash runs no handler.
 */
public final class StartupGuard {

    static final int LIMIT = 2;
    static final long STABLE_MS = 15_000;

    /** An automatic load refused because the last launches did not survive it. */
    public static final class Paused extends IllegalStateException {
    }

    static final class State {
        int deaths;
        boolean armed;

        static State parse(String text) {
            State state = new State();
            String[] parts = text == null ? new String[0] : text.trim().split(" ");
            if (parts.length != 2) return state;
            try {
                state.deaths = Math.max(0, Math.min(99, Integer.parseInt(parts[0])));
                state.armed = "1".equals(parts[1]);
            } catch (NumberFormatException ignored) {
            }
            return state;
        }

        String encode() {
            return deaths + " " + (armed ? "1" : "0");
        }

        /** A new process: the one before it ended while armed, or it did not. */
        void started() {
            if (armed) deaths++;
            armed = false;
        }

        boolean paused() {
            return deaths >= LIMIT;
        }

        void loading(boolean automatic) {
            if (!automatic) deaths = 0;
            armed = true;
        }

        void disarm() {
            armed = false;
        }

        void stable() {
            deaths = 0;
            armed = false;
        }
    }

    private static State state = new State();
    private static File file;
    private static int generation;

    private StartupGuard() {
    }

    /** Once per main process, before anything loads a configuration. */
    public static synchronized void install(File target) {
        if (file != null || !mainProcess()) return;
        file = target;
        try {
            if (target.isFile()) state = State.parse(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        boolean counted = state.armed;
        state.started();
        store();
        if (counted) DiagnosticLog.write(App.get(), "startup_guard", "ended_while_loading count=" + state.deaths + (state.paused() ? " paused" : ""));
    }

    public static synchronized boolean paused() {
        return state.paused();
    }

    /** A configuration load begins; one the user asked for starts the count again. */
    public static synchronized void loading(boolean automatic) {
        generation++;
        state.loading(automatic);
        store();
    }

    /** The load failed in the ordinary way: nothing to hold against the configuration. */
    public static synchronized void failed() {
        generation++;
        state.disarm();
        store();
    }

    /** The load finished; still being here in 15s clears the count. */
    public static synchronized void loaded() {
        int expected = generation;
        App.post(() -> stable(expected), STABLE_MS);
    }

    /** No screen of the app is in front any more: an ending from here on is not a start-up crash. */
    public static synchronized void background() {
        if (!state.armed) return;
        state.disarm();
        store();
    }

    private static synchronized void stable(int expected) {
        if (expected != generation) return;
        boolean changed = state.armed || state.deaths > 0;
        state.stable();
        if (changed) store();
    }

    private static void store() {
        if (file == null) return;
        try {
            Files.write(file.toPath(), state.encode().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Diagnostics-grade: a full disk must not stop the app.
        }
    }

    /** The crash screen runs in its own process; its start is not a restart of the app. */
    private static boolean mainProcess() {
        try {
            String name = Build.VERSION.SDK_INT >= 28 ? android.app.Application.getProcessName()
                    : new String(Files.readAllBytes(new File("/proc/self/cmdline").toPath()), StandardCharsets.UTF_8).trim().replace("\0", "");
            return name.isEmpty() || BuildConfig.APPLICATION_ID.equals(name);
        } catch (Exception unknown) {
            return true;
        }
    }
}
