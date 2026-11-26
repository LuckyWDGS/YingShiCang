package com.fongmi.android.tv.maintenance;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.diagnostics.DeviceId;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;
import com.fongmi.android.tv.diagnostics.RingLog;
import com.google.gson.Gson;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;

/**
 * Sends one batch of the diagnostic log, gzip-compressed, and clears it once the receiver confirms it.
 *
 * The receiver's gateway hands a text/plain body to the function as text: a 7-10 MB log made the 256 MB
 * function fail about one time in three (HTTP 502, measured 2026-11-25), and any byte that is not valid
 * UTF-8 changes on the way, so the checksum no longer matches (HTTP 400). A binary body arrives byte for
 * byte and is about a tenth of the size. The checksum, the size in the receipt and the stored object are
 * those of the compressed bytes; scripts/fetch_logs.py unpacks it on download.
 */
public final class LogUploader {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final MediaType GZIP = MediaType.get("application/gzip");
    private volatile Call call;
    private volatile boolean canceled;
    public void cancel() { canceled = true; Call request = call; if (request != null) request.cancel(); }
    private static final class Receipt { String batchId, reportId, sha256; long bytes; boolean persisted; }

    /** The receiver answered, but did not take the batch. */
    public static final class Rejected extends IOException {
        public final int status;
        public final String reason;
        Rejected(int status, String reason) {
            super(message(status, reason));
            this.status = status;
            this.reason = reason;
        }
        private static String message(int status, String reason) {
            String detail = "HTTP " + status + (reason.isEmpty() ? "" : " " + reason);
            if (status >= 500) return "日志服务器暂时不可用（" + detail + "），日志已保留，请稍后再试";
            return "日志服务器未接受这批日志（" + detail + "），日志已保留";
        }
    }

    public String upload(RingLog store, String endpoint) throws IOException {
        return upload(store, endpoint, "");
    }

    public String upload(RingLog store, String endpoint, String deviceInfo) throws IOException {
        return upload(store, endpoint, deviceInfo, "");
    }

    /**
     * @param deviceInfo base64url device facts for X-Device-Info, or empty to omit the header.
     * @param deviceId   anonymous {@link DeviceId} the server files this device's logs under, or empty.
     */
    public String upload(RingLog store, String endpoint, String deviceInfo, String deviceId) throws IOException {
        if (!BUSY.compareAndSet(false, true)) throw new IOException("已有日志上传正在进行");
        File packed = null;
        long raw = 0, sent = 0, started = System.nanoTime();
        try {
            HttpUrl url = MaintenanceHttp.validate(endpoint);
            if (canceled) throw new InterruptedIOException("已取消");
            RingLog.Batch batch = store.snapshot();
            raw = batch.bytes();
            if (raw == 0) return "没有待上传日志";
            packed = File.createTempFile("ysc-log-upload", ".gz");
            String digest = pack(store, batch, packed);
            sent = packed.length();
            Request.Builder request = new Request.Builder().url(url).post(RequestBody.create(packed, GZIP))
                    .header("X-Batch-Id", batch.id()).header("X-Content-SHA256", digest)
                    .header("X-App-Version", BuildConfig.VERSION_NAME)
                    .header("X-Log-Encoding", "gzip").header("X-Log-Bytes", String.valueOf(raw));
            if (deviceInfo != null && deviceInfo.matches("[A-Za-z0-9_-]{1,8000}")) request.header("X-Device-Info", deviceInfo);
            boolean identified = deviceId != null && deviceId.matches(DeviceId.PATTERN);
            if (identified) request.header("X-Device-Id", deviceId);
            call = MaintenanceHttp.CLIENT.newCall(request.build());
            if (canceled) { call.cancel(); throw new InterruptedIOException("已取消"); }
            Receipt receipt;
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) throw new Rejected(response.code(), reason(response));
                receipt = new Gson().fromJson(MaintenanceHttp.text(response, 8192), Receipt.class);
            } catch (RuntimeException error) { throw new IOException("服务器未返回有效上传回执", error); }
            if (receipt == null || !receipt.persisted || !batch.id().equals(receipt.batchId)
                    || receipt.bytes != sent || !digest.equals(receipt.sha256)
                    || receipt.reportId == null || !receipt.reportId.matches("[a-zA-Z0-9-]{1,80}"))
                throw new IOException("上传尚未确认保存，日志已保留");
            store.acknowledge(batch, receipt.batchId);
            log("ok", raw, sent, started);
            return "上传成功，已清理该批日志。\n请把设备 ID 和报告编号发给开发者：" + (identified ? deviceId + " / " : "") + receipt.reportId;
        } catch (IOException error) {
            log(outcome(error), raw, sent, started);
            throw error;
        } finally {
            if (packed != null && !packed.delete()) packed.deleteOnExit();
            BUSY.set(false);
            call = null;
        }
    }

    /** Writes the batch gzip-compressed into {@code target}; returns the SHA-256 of the compressed bytes. */
    private static String pack(RingLog store, RingLog.Batch batch, File target) throws IOException {
        java.security.MessageDigest hash;
        try { hash = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IOException(e); }
        try (OutputStream out = new java.util.zip.GZIPOutputStream(new java.security.DigestOutputStream(new FileOutputStream(target), hash), 8192)) {
            store.stream(batch, out);
        }
        StringBuilder digest = new StringBuilder();
        for (byte b : hash.digest()) digest.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return digest.toString();
    }

    /** The receiver's own word for a refusal ({"error": "Checksum mismatch"}), letters and spaces only. */
    static String reason(Response response) {
        try {
            ResponseBody body = response.body();
            if (body == null) return "";
            okio.BufferedSource source = body.source();
            source.request(512);
            String text = source.getBuffer().clone().readUtf8(Math.min(512, source.getBuffer().size()));
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"error\"\\s*:\\s*\"([A-Za-z ]{1,40})\"").matcher(text);
            return matcher.find() ? matcher.group(1).trim() : "";
        } catch (Exception unreadable) {
            return "";
        }
    }

    private static String outcome(IOException error) {
        if (error instanceof Rejected rejected) return "http_" + rejected.status + (rejected.reason.isEmpty() ? "" : " reason=" + rejected.reason.replace(' ', '_'));
        return error.getClass().getSimpleName();
    }

    /** Sizes and the receiver's answer only; a failure stays in the log for the next upload that succeeds. */
    private static void log(String outcome, long raw, long sent, long started) {
        DiagnosticLog.write(null, "log_upload", "outcome=" + outcome + " bytes=" + raw + " sent=" + sent
                + " ms=" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
}
