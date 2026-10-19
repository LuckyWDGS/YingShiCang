package com.fongmi.android.tv.api.compat;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.fongmi.android.tv.api.loader.BundledJar;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

import dalvik.system.DexClassLoader;

/** Suppresses only the audited source plugin's selected informational notices. */
public final class PluginNoticeCompatibility {
    private static final String INIT = "com.github.catvod.spider.Init";
    private static final String ORIGIN = "com.github.catvod.spider.InitOrigin";
    private static final String NOTICE = "com.github.catvod.spider.merge.tO";
    private static final String REPEATING_NOTICE = ORIGIN + "$1";
    private static final Pattern AD_COUNT = Pattern.compile("^已过滤视频中广告\\([0-9]+条\\)$");
    private static final String BILI_GUEST = "哔哩游客模式，可前往【哔哔】主页扫码登录解锁高分辨率";

    public enum Result { INSTALLED, ALREADY_INSTALLED, UNSUPPORTED, ACTIVE_REPEAT, FAILED }

    private PluginNoticeCompatibility() {}

    /**
     * Call after initializing a fresh plugin loader and before publishing it to source callers.
     * Existing ordinary posts stay on their original queue; no tasks are removed or reposted.
     * An existing repeat task cannot safely change Handler targets, so leave that plugin alone.
     */
    public static synchronized Result install(ClassLoader outer, File artifact) {
        if (outer == null) return Result.UNSUPPORTED;
        try {
            // Obfuscated callback identities belong to this audited artifact only.
            if (!matchesArtifact(artifact)) return Result.UNSUPPORTED;
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
        return text != null && (BILI_GUEST.equals(text) || AD_COUNT.matcher(text).matches());
    }

    private static boolean matchesArtifact(File artifact) throws Exception {
        if (artifact == null || artifact.length() != 1_281_099) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(artifact)) {
            byte[] bytes = new byte[16 * 1024];
            int count;
            while ((count = input.read(bytes)) != -1) {
                if (Thread.currentThread().isInterrupted()) return false;
                digest.update(bytes, 0, count);
            }
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
        return BundledJar.SHA256.contentEquals(hash);
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
        private final Class<?> ordinary;
        private final Class<?> repeating;
        private final Field ordinaryText;
        private final Field ordinaryKind;
        private final Field repeatingText;

        private NoticeShape(Class<?> ordinary, Class<?> repeating, Field ordinaryText, Field ordinaryKind, Field repeatingText) {
            this.ordinary = ordinary;
            this.repeating = repeating;
            this.ordinaryText = ordinaryText;
            this.ordinaryKind = ordinaryKind;
            this.repeatingText = repeatingText;
        }

        private static NoticeShape read(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> ordinary = Class.forName(NOTICE, false, loader);
            Class<?> repeating = Class.forName(REPEATING_NOTICE, false, loader);
            require(ordinary.getClassLoader() == loader && repeating.getClassLoader() == loader);
            require(ordinary.getSuperclass() == Object.class && repeating.getSuperclass() == Object.class);
            require(ordinary.getModifiers() == (Modifier.PUBLIC | Modifier.FINAL | 0x1000) && ordinary.isSynthetic());
            require(repeating.getModifiers() == 0);
            require(ordinary.getInterfaces().length == 1 && ordinary.getInterfaces()[0] == Runnable.class);
            require(repeating.getInterfaces().length == 1 && repeating.getInterfaces()[0] == Runnable.class);
            require(ordinary.getDeclaredFields().length == 2 && repeating.getDeclaredFields().length == 1);
            int syntheticFinal = Modifier.PUBLIC | Modifier.FINAL | 0x1000;
            Field text = field(ordinary, "SN", Object.class, syntheticFinal);
            Field kind = field(ordinary, "yq", int.class, syntheticFinal);
            Field repeatText = field(repeating, "yq", String.class, Modifier.FINAL);
            Constructor<?> constructor = ordinary.getDeclaredConstructor(Object.class, int.class);
            require(constructor.getModifiers() == (Modifier.PUBLIC | 0x1000) && constructor.isSynthetic());
            require(repeating.getDeclaredConstructor(String.class).getModifiers() == 0);
            method(ordinary, "run", void.class, Modifier.PUBLIC | Modifier.FINAL);
            method(repeating, "run", void.class, Modifier.PUBLIC);
            return new NoticeShape(ordinary, repeating, text, kind, repeatText);
        }

        private boolean suppress(Runnable callback) {
            if (callback == null) return false;
            try {
                Object text;
                if (callback.getClass() == ordinary) {
                    if (ordinaryKind.getInt(callback) != 3) return false;
                    text = ordinaryText.get(callback);
                } else if (callback.getClass() == repeating) {
                    text = repeatingText.get(callback);
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
    }
}
