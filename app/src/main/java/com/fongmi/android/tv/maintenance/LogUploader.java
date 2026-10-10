package com.fongmi.android.tv.maintenance;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.diagnostics.RingLog;
import com.google.gson.Gson;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.*;
import okio.BufferedSink;

public final class LogUploader {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private volatile Call call;
    private volatile boolean canceled;
    public void cancel() { canceled = true; Call request = call; if (request != null) request.cancel(); }
    private static final class Receipt { String batchId, reportId, sha256; long bytes; boolean persisted; }

    public String upload(RingLog store, String endpoint) throws IOException {
        if (!BUSY.compareAndSet(false, true)) throw new IOException("已有日志上传正在进行");
        try {
            HttpUrl url = MaintenanceHttp.validate(endpoint);
            if (canceled) throw new InterruptedIOException("已取消");
            RingLog.Batch batch = store.snapshot();
            if (batch.bytes() == 0) return "没有待上传日志";
            java.security.MessageDigest hash;
            try { hash = java.security.MessageDigest.getInstance("SHA-256"); }
            catch (Exception e) { throw new IOException(e); }
            store.stream(batch, new java.security.DigestOutputStream(new OutputStream() {
                @Override public void write(int value) {}
                @Override public void write(byte[] bytes, int offset, int count) {}
            }, hash));
            StringBuilder digest = new StringBuilder();
            for (byte b : hash.digest()) digest.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            RequestBody body = new RequestBody() {
                @Override public MediaType contentType() { return MediaType.get("text/plain; charset=utf-8"); }
                @Override public long contentLength() { return batch.bytes(); }
                @Override public boolean isOneShot() { return true; }
                @Override public void writeTo(BufferedSink sink) throws IOException { store.stream(batch, sink.outputStream()); }
            };
            call = MaintenanceHttp.CLIENT.newCall(new Request.Builder().url(url).post(body)
                    .header("X-Batch-Id", batch.id()).header("X-Content-SHA256", digest.toString())
                    .header("X-App-Version", BuildConfig.VERSION_NAME).build());
            if (canceled) { call.cancel(); throw new InterruptedIOException("已取消"); }
            Receipt receipt;
            try (Response response = call.execute()) {
                receipt = new Gson().fromJson(MaintenanceHttp.text(response, 8192), Receipt.class);
            } catch (RuntimeException error) { throw new IOException("服务器未返回有效上传回执", error); }
            if (receipt == null || !receipt.persisted || !batch.id().equals(receipt.batchId)
                    || receipt.bytes != batch.bytes() || !digest.toString().equals(receipt.sha256)
                    || receipt.reportId == null || !receipt.reportId.matches("[a-zA-Z0-9-]{1,80}"))
                throw new IOException("上传尚未确认保存，日志已保留");
            store.acknowledge(batch, receipt.batchId);
            return "上传成功，已清理该批日志。报告：" + receipt.reportId;
        } finally { BUSY.set(false); call = null; }
    }
}
