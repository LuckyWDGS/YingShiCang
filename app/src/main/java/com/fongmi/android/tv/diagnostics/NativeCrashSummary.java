package com.fongmi.android.tv.diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the crashing thread's top frames from a native crash tombstone (Android 12+ hands it out as
 * {@code ApplicationExitInfo.getTraceInputStream()}, protobuf per AOSP debuggerd tombstone.proto).
 * Only the signal, library file names and function names are kept: no paths, addresses or memory.
 */
final class NativeCrashSummary {

    private static final int MAX_BYTES = 2 << 20;
    private static final int MAX_FRAMES = 8;

    private NativeCrashSummary() {
    }

    /** e.g. {@code sig=SIGSEGV/SEGV_MAPERR frames=libmpv.so!render;libc.so!memcpy}; empty when unreadable. */
    static String summarize(InputStream stream) throws IOException {
        if (stream == null) return "";
        byte[] data = read(stream);
        Reader tombstone = new Reader(data, 0, data.length);
        long tid = -1;
        String signal = "", code = "", abort = "";
        List<byte[]> threads = new ArrayList<>();
        while (tombstone.more()) {
            int key = (int) tombstone.varint();
            int field = key >>> 3, type = key & 7;
            if (field == 6 && type == 0) tid = tombstone.varint();
            else if (field == 10 && type == 2) {
                Reader info = tombstone.message();
                while (info.more()) {
                    int k = (int) info.varint();
                    if ((k >>> 3) == 2 && (k & 7) == 2) signal = info.string();
                    else if ((k >>> 3) == 4 && (k & 7) == 2) code = info.string();
                    else info.skip(k & 7);
                }
            } else if (field == 14 && type == 2) abort = tombstone.string();
            else if (field == 16 && type == 2) threads.add(tombstone.bytes());
            else tombstone.skip(type);
        }
        List<String> frames = new ArrayList<>();
        for (byte[] entry : threads) {
            Reader map = new Reader(entry, 0, entry.length);
            long id = -1;
            byte[] thread = null;
            while (map.more()) {
                int k = (int) map.varint();
                if ((k >>> 3) == 1 && (k & 7) == 0) id = map.varint();
                else if ((k >>> 3) == 2 && (k & 7) == 2) thread = map.bytes();
                else map.skip(k & 7);
            }
            if (id != tid || thread == null) continue;
            Reader body = new Reader(thread, 0, thread.length);
            while (body.more() && frames.size() < MAX_FRAMES) {
                int k = (int) body.varint();
                if ((k >>> 3) == 4 && (k & 7) == 2) frames.add(frame(body.message()));
                else body.skip(k & 7);
            }
        }
        StringBuilder text = new StringBuilder("sig=").append(signal.isEmpty() ? "?" : signal);
        if (!code.isEmpty()) text.append('/').append(code);
        if (!frames.isEmpty()) text.append(" frames=").append(String.join(";", frames));
        if (!abort.isEmpty()) text.append(" abort=").append(abort.length() > 100 ? abort.substring(0, 100) : abort);
        return text.toString();
    }

    private static String frame(Reader frame) throws IOException {
        String function = "", file = "";
        while (frame.more()) {
            int k = (int) frame.varint();
            if ((k >>> 3) == 4 && (k & 7) == 2) function = frame.string();
            else if ((k >>> 3) == 6 && (k & 7) == 2) file = frame.string();
            else frame.skip(k & 7);
        }
        String name = file.substring(file.lastIndexOf('/') + 1);
        if (function.length() > 60) function = function.substring(0, 60);
        return (name.isEmpty() ? "?" : name) + (function.isEmpty() ? "" : "!" + function);
    }

    private static byte[] read(InputStream stream) throws IOException {
        try (stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = stream.read(buffer)) != -1 && out.size() < MAX_BYTES) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    /** Minimal protobuf wire-format reader: varints and length-delimited fields. */
    private static final class Reader {
        private final byte[] data;
        private int position;
        private final int end;

        Reader(byte[] data, int start, int end) {
            this.data = data;
            this.position = start;
            this.end = end;
        }

        boolean more() {
            return position < end;
        }

        long varint() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (position >= end) throw new IOException("truncated");
                byte b = data[position++];
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
            }
            throw new IOException("bad varint");
        }

        int length() throws IOException {
            long length = varint();
            if (length < 0 || length > end - position) throw new IOException("bad length");
            return (int) length;
        }

        byte[] bytes() throws IOException {
            int length = length();
            byte[] value = new byte[length];
            System.arraycopy(data, position, value, 0, length);
            position += length;
            return value;
        }

        Reader message() throws IOException {
            int length = length();
            Reader child = new Reader(data, position, position + length);
            position += length;
            return child;
        }

        String string() throws IOException {
            return new String(bytes(), StandardCharsets.UTF_8);
        }

        void skip(int type) throws IOException {
            switch (type) {
                case 0 -> varint();
                case 1 -> position += 8;
                case 2 -> {
                    // Read the length first: "position += length()" would add it to the position before the length varint.
                    int length = length();
                    position += length;
                }
                case 5 -> position += 4;
                default -> throw new IOException("unsupported wire type " + type);
            }
            if (position > end) throw new IOException("truncated");
        }
    }
}
