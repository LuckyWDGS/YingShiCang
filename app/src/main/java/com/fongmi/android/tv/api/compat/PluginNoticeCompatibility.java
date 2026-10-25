package com.fongmi.android.tv.api.compat;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

import dalvik.system.DexClassLoader;

/**
 * Suppresses only the audited source plugin's selected informational notices.
 *
 * The provider ships a new build almost daily and re-obfuscates class names (the ordinary notice callback was
 * merge.tO on 2026-10-16 and merge.Nl on 2026-10-22), so the plugin is recognised by its exact structure, not
 * by file hash or class name: InitOrigin's seven fields and public API, the repeating notice InitOrigin$1, and
 * an ordinary notice callback with exactly the audited synthetic fields SN (Object) and yq (int, 3 = show).
 * Anything that differs keeps the plugin's own behaviour; only the exact texts below are dropped.
 */
public final class PluginNoticeCompatibility {
    private static final String INIT = "com.github.catvod.spider.Init";
    private static final String ORIGIN = "com.github.catvod.spider.InitOrigin";
    private static final String REPEATING_NOTICE = ORIGIN + "$1";
    static final long REPEAT_GAP_FACTOR = 3;
    private static final Pattern AD_COUNT = Pattern.compile("^已过滤视频中广告\\([0-9]+条\\)$");
    private static final String BILI_GUEST = "哔哩游客模式，可前往【哔哔】主页扫码登录解锁高分辨率";
    // The danmaku itself shows that it loaded; the toast covered the picture (user 1.7.4 screenshot).
    private static final Pattern DANMAKU_LOADED = Pattern.compile("^弹幕【[^】\\n]{1,80}】加载成功$");

    public enum Result { INSTALLED, ALREADY_INSTALLED, UNSUPPORTED, ACTIVE_REPEAT, FAILED }

    private PluginNoticeCompatibility() {}

    /**
     * Call after initializing a fresh plugin loader and before publishing it to source callers.
     * Existing ordinary posts stay on their original queue; no tasks are removed or reposted.
     * An existing repeat task cannot safely change Handler targets, so leave that plugin alone.
     */
    public static synchronized Result install(ClassLoader outer) {
        if (outer == null) return Result.UNSUPPORTED;
        try {
            Class<?> init = Class.forName(INIT, false, outer);
            require(init.getClassLoader() == outer);
            Method loaderMethod = method(init, "loader", DexClassLoader.class, Modifier.PUBLIC | Modifier.STATIC);
            Object loaded = loaderMethod.invoke(null);
            if (!(loaded instanceof DexClassLoader loader)) return Result.UNSUPPORTED;

            Class<?> origin = Class.forName(ORIGIN, false, loader);
            require(origin.getClassLoader() == loader && origin.getSuperclass() == Object.class);
            require(origin.getModifiers() == Modifier.PUBLIC && origin.getInterfaces().length == 0);
            require(origin.getDeclaredFields().length == 7);
            field(origin, "i", Class.class, Modifier.PUBLIC | Modifier.STATIC);
            field(origin, "short", short[].class, Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL);
            Field repeatTask = field(origin, "tF", Runnable.class, Modifier.PRIVATE | Modifier.STATIC);
            Field repeatMode = field(origin, "zH", boolean.class, Modifier.PUBLIC | Modifier.STATIC);
            field(origin, "N", Application.class, Modifier.PRIVATE);
            Field handlerField = field(origin, "SN", Handler.class, Modifier.PRIVATE | Modifier.FINAL);
            field(origin, "yq", ExecutorService.class, Modifier.PRIVATE | Modifier.FINAL);
            Method get = method(origin, "get", origin, Modifier.PUBLIC | Modifier.STATIC);
            method(origin, "show", void.class, Modifier.PUBLIC | Modifier.STATIC, String.class);
            method(origin, "run", void.class, Modifier.PUBLIC | Modifier.STATIC, Runnable.class);
            method(origin, "run", void.class, Modifier.PUBLIC | Modifier.STATIC, Runnable.class, int.class);
            method(origin, "stopShowingToast", void.class, Modifier.PUBLIC | Modifier.STATIC);

            NoticeShape notices = NoticeShape.read(loader);
            Object instance = get.invoke(null);
            require(instance != null && instance.getClass() == origin);
            Object current = handlerField.get(instance);
            if (current instanceof NoticeHandler installed && installed.origin == origin) return Result.ALREADY_INSTALLED;
            require(current != null && current.getClass() == Handler.class);
            Handler original = (Handler) current;
            require(original.getLooper() == Looper.getMainLooper());
            NoticeHandler replacement = new NoticeHandler(original, origin, notices);
            // Check immediately before replacing SN. Do not attempt to migrate an old repeat loop.
            if (repeatTask.get(null) != null || repeatMode.getBoolean(null)) return Result.ACTIVE_REPEAT;
            handlerField.set(instance, replacement);
            return handlerField.get(instance) == replacement ? Result.INSTALLED : Result.FAILED;
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException | UnsupportedShape ignored) {
            return Result.UNSUPPORTED;
        } catch (Throwable ignored) {
            // Compatibility failure must not fail plugin initialization or reveal plugin data.
            return Result.FAILED;
        }
    }

    static boolean isSilentNotice(String text) {
        return text != null && (BILI_GUEST.equals(text) || AD_COUNT.matcher(text).matches() || DANMAKU_LOADED.matcher(text).matches());
    }

    private static Field field(Class<?> owner, String name, Class<?> type, int modifiers)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        require(field.getType() == type && field.getModifiers() == modifiers);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> owner, String name, Class<?> result, int modifiers, Class<?>... arguments)
            throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, arguments);
        require(method.getReturnType() == result && method.getModifiers() == modifiers);
        method.setAccessible(true);
        return method;
    }

    private static void require(boolean matches) throws UnsupportedShape {
        if (!matches) throw new UnsupportedShape();
    }

    private static final class UnsupportedShape extends ReflectiveOperationException {}

    private static final class NoticeShape {
        private static final int SYNTHETIC_FINAL = Modifier.PUBLIC | Modifier.FINAL | 0x1000;
        private final ClassLoader loader;
        private final Class<?> repeating;
        private final Field repeatingText;
        // Other Runnables the plugin posts; checked once each (dispatch runs on the main thread only).
        private final Set<Class<?>> others = new HashSet<>();
        private Class<?> ordinary;
        private Field ordinaryText;
        private Field ordinaryKind;

        private NoticeShape(ClassLoader loader, Class<?> repeating, Field repeatingText) {
            this.loader = loader;
            this.repeating = repeating;
            this.repeatingText = repeatingText;
        }

        private static NoticeShape read(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> repeating = Class.forName(REPEATING_NOTICE, false, loader);
            require(repeating.getClassLoader() == loader && repeating.getSuperclass() == Object.class);
            require(repeating.getModifiers() == 0);
            require(repeating.getInterfaces().length == 1 && repeating.getInterfaces()[0] == Runnable.class);
            require(repeating.getDeclaredFields().length == 1);
            Field repeatText = field(repeating, "yq", String.class, Modifier.FINAL);
            require(repeating.getDeclaredConstructor(String.class).getModifiers() == 0);
            method(repeating, "run", void.class, Modifier.PUBLIC);
            return new NoticeShape(loader, repeating, repeatText);
        }

        /** The ordinary notice callback, matched once by its exact shape; its obfuscated name changes per build. */
        private boolean isOrdinary(Class<?> type) {
            if (type == ordinary) return true;
            if (ordinary != null || type.getClassLoader() != loader || others.contains(type)) return false;
            try {
                require(type.getSuperclass() == Object.class && type.getModifiers() == (SYNTHETIC_FINAL) && type.isSynthetic());
                require(type.getInterfaces().length == 1 && type.getInterfaces()[0] == Runnable.class);
                require(type.getDeclaredFields().length == 2);
                Field text = field(type, "SN", Object.class, SYNTHETIC_FINAL);
                Field kind = field(type, "yq", int.class, SYNTHETIC_FINAL);
                Constructor<?> constructor = type.getDeclaredConstructor(Object.class, int.class);
                require(constructor.getModifiers() == (Modifier.PUBLIC | 0x1000) && constructor.isSynthetic());
                method(type, "run", void.class, Modifier.PUBLIC | Modifier.FINAL);
                ordinaryText = text;
                ordinaryKind = kind;
                ordinary = type;
                return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                others.add(type);
                return false;
            }
        }

        boolean isRepeating(Runnable callback) {
            return callback != null && callback.getClass() == repeating;
        }

        private boolean suppress(Runnable callback) {
            if (callback == null) return false;
            try {
                Object text;
                if (callback.getClass() == repeating) {
                    text = repeatingText.get(callback);
                } else if (isOrdinary(callback.getClass())) {
                    if (ordinaryKind.getInt(callback) != 3) return false;
                    text = ordinaryText.get(callback);
                } else return false;
                return text instanceof String && isSilentNotice((String) text);
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static final class NoticeHandler extends Handler {
        private final Handler original;
        private final Class<?> origin;
        private final NoticeShape notices;

        private NoticeHandler(Handler original, Class<?> origin, NoticeShape notices) {
            super(original.getLooper());
            this.original = original;
            this.origin = origin;
            this.notices = notices;
        }

        @Override public void dispatchMessage(Message message) {
            if (!notices.suppress(message.getCallback())) original.dispatchMessage(message);
        }

        /**
         * The repeating notice under the plugin's QR code reposts itself on a delay; it came back too
         * often, so its gap is doubled (user 2026-10-23) and then lengthened 1.5 times more (user
         * 2026-10-24): three times the plugin's own. The first show and other messages keep theirs.
         */
        @Override public boolean sendMessageAtTime(Message message, long uptimeMillis) {
            if (notices.isRepeating(message.getCallback())) {
                long delay = uptimeMillis - SystemClock.uptimeMillis();
                if (delay > 0) uptimeMillis += delay * (REPEAT_GAP_FACTOR - 1);
            }
            return super.sendMessageAtTime(message, uptimeMillis);
        }
    }
}
