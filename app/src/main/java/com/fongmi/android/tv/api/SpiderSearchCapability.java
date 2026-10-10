package com.fongmi.android.tv.api;

import com.github.catvod.crawler.Spider;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;

/** Proves an optional search method is absent; uncertainty never hides a query failure. */
public final class SpiderSearchCapability {
    private static final String TRANSPARENT_GUARD = "com.github.catvod.spider.BaseSpiderGuard";
    private SpiderSearchCapability() {}

    public static boolean hasDefaultPagination(Spider spider) {
        IdentityHashMap<Spider, Boolean> visited = new IdentityHashMap<>();
        for (int depth = 0; spider != null && depth < 8; depth++) {
            if (visited.put(spider, Boolean.TRUE) != null) return false;
            try {
                Class<?> declaring = spider.getClass()
                        .getMethod("searchContent", String.class, boolean.class, String.class)
                        .getDeclaringClass();
                if (declaring == Spider.class) return true;
                // This guard is verified to forward the overload directly. Do not infer
                // forwarding behavior from arbitrary wrapper fields or naming patterns.
                if (!TRANSPARENT_GUARD.equals(declaring.getName())) return false;
                Spider delegate = transparentDelegate(spider);
                if (delegate == null) return false;
                spider = delegate;
            } catch (Throwable error) {
                return false;
            }
        }
        return false;
    }

    private static Spider transparentDelegate(Spider wrapper) throws IllegalAccessException {
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
