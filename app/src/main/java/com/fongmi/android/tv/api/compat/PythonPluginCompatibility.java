package com.fongmi.android.tv.api.compat;

import android.content.Context;
import android.content.ContextWrapper;

import com.github.catvod.crawler.Spider;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;

/** Identifies the audited Java-to-Python bridge, including its guarded delegate. */
public final class PythonPluginCompatibility {
    private static final String TRANSPARENT_GUARD = "com.github.catvod.spider.BaseSpiderGuard";
    private PythonPluginCompatibility() {}

    /** The audited bridge accepts the original runtime host name before locating its App class. */
    public static Context hostContext(Context context) {
        return new ContextWrapper(context) {
            @Override public String getPackageName() { return "com.fongmi.android.tv"; }
        };
    }

    public static boolean requiresRuntime(Spider spider) {
        if (spider == null) return false;
        IdentityHashMap<Spider, Boolean> seen = new IdentityHashMap<>();
        for (int depth = 0; spider != null && depth < 8; depth++) {
            if (seen.put(spider, Boolean.TRUE) != null) return false;
            try {
                String declaring = spider.getClass().getMethod("init", Context.class, String.class)
                        .getDeclaringClass().getName();
                if ("com.github.catvod.spider.AiDj".equals(declaring)
                        || "com.github.catvod.spider.merge.oE".equals(declaring)) return true;
                if (!TRANSPARENT_GUARD.equals(declaring)) return false;
                spider = delegate(spider);
            } catch (Throwable error) {
                // Unrelated plugins must keep their existing initialization path.
                return false;
            }
        }
        return false;
    }

    private static Spider delegate(Spider wrapper) throws IllegalAccessException {
        Field delegate = null;
        for (Class<?> type = wrapper.getClass(); type != null && type != Spider.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Spider.class.isAssignableFrom(field.getType())) continue;
                if (delegate != null) return null;
                delegate = field;
            }
        }
        if (delegate == null) return null;
        delegate.setAccessible(true);
        return (Spider) delegate.get(wrapper);
    }
}
