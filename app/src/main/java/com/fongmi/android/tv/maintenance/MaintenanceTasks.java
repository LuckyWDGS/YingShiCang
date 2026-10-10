package com.fongmi.android.tv.maintenance;

import java.util.concurrent.*;

/** External source/plugin jobs must not starve update and support operations. */
public final class MaintenanceTasks {
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> new Thread(r,"maintenance"));
    private MaintenanceTasks() {}
    public static Future<?> submit(Runnable runnable) { POOL.purge(); return POOL.submit(runnable); }
    public static void execute(Runnable runnable) { submit(runnable); }
}
