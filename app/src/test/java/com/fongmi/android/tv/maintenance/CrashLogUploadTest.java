package com.fongmi.android.tv.maintenance;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class CrashLogUploadTest {
    @Test public void countsAttemptsThenGivesUp() throws Exception {
        File marker = File.createTempFile("crash", ".pending");
        Files.write(marker.toPath(), "0".getBytes(StandardCharsets.US_ASCII));
        for (int i = 1; i <= CrashLogUpload.MAX_ATTEMPTS; i++) assertEquals(i, CrashLogUpload.nextAttempt(marker));
        assertEquals(-1, CrashLogUpload.nextAttempt(marker));
        assertFalse(marker.exists());
    }

    @Test public void unreadableMarkerStartsAtFirstAttempt() throws Exception {
        File marker = File.createTempFile("crash", ".pending");
        Files.write(marker.toPath(), "garbage".getBytes(StandardCharsets.US_ASCII));
        assertEquals(1, CrashLogUpload.nextAttempt(marker));
        marker.delete();
    }
}
