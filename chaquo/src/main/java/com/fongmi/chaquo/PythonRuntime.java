package com.fongmi.chaquo;

import android.os.Looper;

import com.chaquo.python.Python;

import java.util.concurrent.CancellationException;

/** Starts both direct Python sources and Java bridges with the packaged Android platform. */
public final class PythonRuntime {
    private PythonRuntime() {}

    public static void prepare() {
        checkCancellation();
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Python 源必须在后台加载");
        // Chaquopy's own entry points also lock this class. Keep the check/start pair
        // atomic with direct bridge calls to getInstance(), which otherwise auto-start
        // GenericPlatform and permanently poison this process after startup failure.
        synchronized (Python.class) {
            checkCancellation();
            if (!Python.isStarted()) Python.start(Platform.create());
            checkCancellation();
        }
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted())
            throw new CancellationException("Python runtime preparation cancelled");
    }
}
