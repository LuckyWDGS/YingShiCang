package com.fongmi.android.tv.follow;

import java.util.concurrent.Callable;

/** Identifies synchronous source calls made by the background update check. */
public final class FollowCheckScope {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private FollowCheckScope() {
    }

    public static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    static <T> T call(Callable<T> action) throws Exception {
        Boolean previous = ACTIVE.get();
        ACTIVE.set(true);
        try {
            return action.call();
        } finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }
}
