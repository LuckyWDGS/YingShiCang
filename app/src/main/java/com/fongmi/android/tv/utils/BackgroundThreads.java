package com.fongmi.android.tv.utils;

import android.os.Process;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Worker threads below the UI/RenderThread so loading cannot steal frames from scrolling. */
public final class BackgroundThreads {

    // Glide's choice: 9 stays out of the restricted background scheduling group that 10 enters on some devices.
    private static final int PRIORITY = Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE;

    private BackgroundThreads() {
    }

    public static ThreadFactory factory(String name) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(() -> {
                lower();
                runnable.run();
            }, name + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void lower() {
        try {
            Process.setThreadPriority(PRIORITY);
        } catch (Throwable ignored) {
            // JVM unit tests run against android.jar stubs.
        }
    }
}
