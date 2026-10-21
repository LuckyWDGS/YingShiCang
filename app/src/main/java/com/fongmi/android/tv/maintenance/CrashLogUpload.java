package com.fongmi.android.tv.maintenance;

import android.content.Context;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.diagnostics.DeviceId;
import com.fongmi.android.tv.diagnostics.DeviceReport;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * After a crash, uploads the diagnostic log shortly after the next launch. Every other upload
 * stays manual. Gives up after {@link #MAX_ATTEMPTS} launches so a broken receiver cannot keep
 * costing the user traffic; the log itself stays on the device either way.
 */
public final class CrashLogUpload {
    static final int MAX_ATTEMPTS = 3;
    private static final long DELAY_MS = 15_000;

    private CrashLogUpload() {}

    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        App.post(() -> MaintenanceTasks.execute(() -> run(app)), DELAY_MS);
    }

    private static void run(Context context) {
        File marker = DiagnosticLog.crashMarker();
        String endpoint = ServiceSettings.logsUrl().trim();
        if (marker == null || !marker.exists() || endpoint.isEmpty()) return;
        int attempt = nextAttempt(marker);
        if (attempt < 0) {
            DiagnosticLog.write(context, "crash_upload", "gave up after " + MAX_ATTEMPTS + " attempts");
            return;
        }
        try {
            new LogUploader().upload(DiagnosticLog.store(), endpoint, DeviceReport.encode(context), DeviceId.get(context));
            marker.delete();
            DiagnosticLog.write(context, "crash_upload", "ok attempt=" + attempt);
        } catch (Exception e) {
            DiagnosticLog.write(context, "crash_upload", "attempt=" + attempt + " failed: " + e.getClass().getSimpleName());
        }
    }

    /** Records and returns this launch's attempt number, or -1 (and drops the marker) when exhausted. */
    static int nextAttempt(File marker) {
        int done;
        try { done = Integer.parseInt(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.US_ASCII).trim()); }
        catch (Exception e) { done = 0; }
        if (done >= MAX_ATTEMPTS) {
            marker.delete();
            return -1;
        }
        try (FileOutputStream out = new FileOutputStream(marker)) { out.write(String.valueOf(done + 1).getBytes(StandardCharsets.US_ASCII)); }
        catch (Exception ignored) {}
        return done + 1;
    }
}
