package com.fongmi.android.tv.diagnostics;

import org.junit.Test;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class RingLogTest {
    @Test public void everyWriteStaysWithinBudgetIncludingLargeUnicodeRecords() throws Exception {
        RingLog ring = new RingLog(Files.createTempDirectory("ring").toFile(), 4096);
        for (int i = 0; i < 100; i++) { ring.append("中文".repeat(1000)); assertTrue(ring.size() <= 4096); }
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ring.stream(ring.snapshot(), out);
        assertTrue(out.toString("UTF-8").endsWith("\n"));
    }
    @Test public void acknowledgementNeverDeletesConcurrentNewLogs() throws Exception {
        RingLog ring = new RingLog(Files.createTempDirectory("receipt").toFile(), 4096);
        ring.append("before"); RingLog.Batch old = ring.snapshot(); ring.append("after");
        assertThrows(IOException.class, () -> ring.acknowledge(old, "wrong"));
        ring.acknowledge(old, old.id()); ring.acknowledge(old, old.id());
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ring.stream(ring.snapshot(), out);
        assertEquals("after\n", out.toString("UTF-8"));
    }
    @Test public void rotationInvalidatesUploadInsteadOfSilentlyTruncating() throws Exception {
        RingLog ring = new RingLog(Files.createTempDirectory("expiry").toFile(), 4096);
        ring.append("old"); RingLog.Batch old = ring.snapshot();
        for (int i=0; i<100; i++) ring.append("x".repeat(800));
        assertThrows(IOException.class, () -> ring.stream(old, new ByteArrayOutputStream()));
    }
    @Test public void credentialsAreRemovedBeforePersistence() {
        String out = LogSanitizer.clean("Cookie: secret=abc; session=xyz\nAuthorization: Bearer access-secret\nhttps://cdn/a?auth=abc token=123");
        for (String secret : new String[]{"abc", "xyz", "access-secret", "123"}) assertFalse(out.contains(secret));
    }
}
