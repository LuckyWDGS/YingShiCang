package com.fongmi.android.tv.diagnostics;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class NativeCrashSummaryTest {

    @Test
    public void keepsSignalAndCrashingThreadFramesOnly() throws Exception {
        byte[] signal = message(field(2, "SIGSEGV"), field(4, "SEGV_MAPERR"));
        byte[] crashing = message(varintField(1, 42), field(2, message(
                field(4, message(varintField(1, 4096), field(4, "mpv_render"), field(6, "/data/app/~~x/com.yingshicang.player-y/lib/arm64/libmpv.so"), field(8, "0123abcd"))),
                field(4, message(field(6, "/apex/com.android.runtime/lib64/bionic/libc.so"))))));
        byte[] other = message(varintField(1, 7), field(2, message(field(4, message(field(4, "idle"), field(6, "libother.so"))))));
        // Skipped length-delimited fields at every level (fingerprint, command line, build id) as in real tombstones.
        byte[] tombstone = message(varintField(1, 3), field(2, "OnePlus/device:14/user/release-keys"), varintField(6, 42),
                field(9, "com.yingshicang.player"), field(10, signal), field(14, "check failed"), field(16, other), field(16, crashing),
                field(17, message(field(1, "/system/lib64/libc.so"))));
        assertEquals("sig=SIGSEGV/SEGV_MAPERR frames=libmpv.so!mpv_render;libc.so abort=check failed",
                NativeCrashSummary.summarize(new ByteArrayInputStream(tombstone)));
    }

    @Test
    public void unreadableOrMissingTombstonesSayLittle() throws Exception {
        assertEquals("", NativeCrashSummary.summarize(null));
        assertEquals("sig=?", NativeCrashSummary.summarize(new ByteArrayInputStream(new byte[0])));
    }

    private static byte[] message(byte[]... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] field : fields) out.writeBytes(field);
        return out.toByteArray();
    }

    private static byte[] field(int number, String value) {
        return field(number, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] field(int number, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varint(out, (number << 3) | 2);
        varint(out, value.length);
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static byte[] varintField(int number, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varint(out, number << 3);
        varint(out, value);
        return out.toByteArray();
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }
}
