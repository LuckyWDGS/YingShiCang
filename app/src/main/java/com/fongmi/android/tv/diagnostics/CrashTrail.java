package com.fongmi.android.tv.diagnostics;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the app was doing when its process died: the screen in front and the source-plugin calls still
 * running. A native crash runs no Java handler and, below Android 12, leaves no tombstone the app may
 * read (TCL, Android 11, 2026-11-10: two SIGSEGV exits with nothing before them in the log), and the log
 * names a plugin call only once it has returned.
 *
 * The state sits in a small memory-mapped file: a change is a memory write, and the pages outlive the
 * process. The next launch reads it before starting its own.
 */
public final class CrashTrail {

    static final int SIZE = 1024;
    private static final int MAX_CALLS = 8;
    private static final Pattern NAME = Pattern.compile("[^A-Za-z0-9_.$-]");
    private static final Pattern ENCODED = Pattern.compile("at=\\d+ screen=\\S* calls=\\d+( \\S+)?");

    private static final State STATE = new State();
    private static MappedByteBuffer map;
    private static volatile String left = "";

    private CrashTrail() {
    }

    /** Reads what the previous process left, then starts this one's. */
    static synchronized void install(File file) {
        if (map != null) return;
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            if (raf.length() != SIZE) raf.setLength(SIZE);
            MappedByteBuffer mapped = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, SIZE);
            byte[] bytes = new byte[SIZE];
            mapped.get(bytes);
            left = decode(bytes);
            map = mapped;
            store();
        } catch (Throwable ignored) {
            // Diagnostics only: a full or read-only disk must not stop the app.
        }
    }

    /** A source-plugin call starts; pass the result to {@link #end}. */
    public static long begin(String what) {
        long token = STATE.begin(what, System.currentTimeMillis());
        store();
        return token;
    }

    public static void end(long token) {
        if (STATE.end(token)) store();
    }

    public static void screen(String name) {
        if (STATE.screen(name)) store();
    }

    /** What the previous process was doing, as ages at the time it died; empty if nothing was left. */
    static String left(long exitTime) {
        return describe(left, exitTime);
    }

    /** Calls still running when the previous process ended, for systems that keep no exit records. */
    static boolean leftCalls() {
        return ENCODED.matcher(left).matches() && !left.contains(" calls=0");
    }

    private static synchronized void store() {
        MappedByteBuffer target = map;
        if (target == null) return;
        try {
            byte[] bytes = STATE.encode(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
            int length = Math.min(bytes.length, SIZE - 2);
            target.put(0, (byte) (length >> 8));
            target.put(1, (byte) length);
            for (int i = 0; i < length; i++) target.put(2 + i, bytes[i]);
        } catch (Throwable ignored) {
        }
    }

    static String decode(byte[] bytes) {
        if (bytes.length < 2) return "";
        int length = ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff);
        if (length <= 0 || length > bytes.length - 2) return "";
        String text = new String(bytes, 2, length, StandardCharsets.UTF_8);
        // A write cut short by the crash itself is not worth reporting.
        return ENCODED.matcher(text).matches() ? text : "";
    }

    static String describe(String encoded, long exitTime) {
        if (!ENCODED.matcher(encoded).matches()) return "";
        String[] parts = encoded.split(" ");
        long at = Long.parseLong(parts[0].substring(3));
        if (exitTime < 0) exitTime = at;
        StringBuilder text = new StringBuilder("idle=").append(Math.max(0, exitTime - at)).append("ms ")
                .append(parts[1]).append(' ').append(parts[2]);
        if (parts.length < 4) return text.toString();
        List<String> calls = new ArrayList<>();
        for (String call : parts[3].split(",")) {
            int split = call.lastIndexOf('@');
            if (split <= 0) continue;
            try {
                long age = Math.max(0, exitTime - Long.parseLong(call.substring(split + 1)));
                calls.add(call.substring(0, split) + ":" + age + "ms");
            } catch (NumberFormatException ignored) {
            }
        }
        return text.append(' ').append(String.join(",", calls)).toString();
    }

    /** The trail itself, apart from where it is kept. */
    static final class State {
        private final Map<Long, String> calls = new LinkedHashMap<>();
        private String screen = "";
        private long next;

        synchronized long begin(String what, long now) {
            long token = ++next;
            calls.put(token, name(what) + "@" + now);
            return token;
        }

        synchronized boolean end(long token) {
            return calls.remove(token) != null;
        }

        synchronized boolean screen(String name) {
            String value = name(name);
            if (value.equals(screen)) return false;
            screen = value;
            return true;
        }

        /** The newest calls are listed; the count says how many ran in all. */
        synchronized String encode(long now) {
            StringBuilder text = new StringBuilder("at=").append(now).append(" screen=").append(screen).append(" calls=").append(calls.size());
            if (calls.isEmpty()) return text.toString();
            List<String> newest = new ArrayList<>(calls.values());
            if (newest.size() > MAX_CALLS) newest = newest.subList(newest.size() - MAX_CALLS, newest.size());
            return text.append(' ').append(String.join(",", newest)).toString();
        }

        private static String name(String value) {
            String name = NAME.matcher(value == null ? "" : value).replaceAll("");
            return name.length() > 40 ? name.substring(0, 40) : name;
        }
    }
}
