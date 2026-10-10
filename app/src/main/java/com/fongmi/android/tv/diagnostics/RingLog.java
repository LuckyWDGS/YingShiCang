package com.fongmi.android.tv.diagnostics;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** JVM-testable disk ring. Sealed snapshot files are never appended again. */
public final class RingLog {
    private final File directory;
    private final long limit;
    private final long segmentLimit;
    private File current;
    private long lastSegment;

    public record Entry(String name, long bytes, String sha256) {}
    public record Batch(String id, List<Entry> entries) {
        public long bytes() { return entries.stream().mapToLong(Entry::bytes).sum(); }
    }

    public RingLog(File directory, long limit) throws IOException {
        if (limit < 1024) throw new IllegalArgumentException("Log budget too small");
        this.directory = directory.getCanonicalFile();
        this.limit = limit;
        this.segmentLimit = Math.min(500_000L, limit / 4);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create diagnostics directory");
        ensureRoom(0);
        for (File file : files()) {
            try { lastSegment = Math.max(lastSegment, Long.parseLong(file.getName().substring(7, 26))); }
            catch (RuntimeException ignored) {}
        }
    }

    public synchronized long size() {
        File[] all = directory.listFiles();
        return all == null ? 0 : Arrays.stream(all).filter(File::isFile).mapToLong(File::length).sum();
    }

    public synchronized void append(String record) throws IOException {
        // Bound characters before UTF-8 encoding; include final newline in hard cap.
        int chars = (int) Math.min(4096, (segmentLimit - 1) / 4);
        byte[] bytes = (record.substring(0, Math.min(record.length(), chars)).replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8);
        if (current != null && current.length() + bytes.length > segmentLimit) current = null;
        ensureRoom(bytes.length);
        if (current == null || !current.exists()) {
            lastSegment = Math.max(System.currentTimeMillis(), lastSegment + 1);
            current = new File(directory, String.format(Locale.ROOT, "events-%019d-%s.log", lastSegment, UUID.randomUUID()));
        }
        try (FileOutputStream out = new FileOutputStream(current, true)) { out.write(bytes); }
    }

    private List<File> files() {
        File[] files = directory.listFiles(f -> f.isFile() && f.getName().matches("events-[0-9]+-[a-f0-9-]+\\.log"));
        List<File> result = new ArrayList<>(files == null ? List.of() : Arrays.asList(files));
        result.sort(Comparator.comparing(File::getName));
        return result;
    }

    private void ensureRoom(long incoming) throws IOException {
        long total = size();
        for (File file : files()) {
            if (total + incoming <= limit) break;
            long old = file.length();
            if (!file.delete()) throw new IOException("Cannot rotate log");
            if (file.equals(current)) current = null;
            total -= old;
        }
        if (total + incoming > limit) throw new IOException("Diagnostic budget exhausted");
    }

    public synchronized Batch snapshot() throws IOException {
        current = null;
        List<Entry> entries = new ArrayList<>();
        for (File file : files()) entries.add(new Entry(file.getName(), file.length(), digest(file)));
        return new Batch(UUID.randomUUID().toString(), List.copyOf(entries));
    }

    private File resolve(Entry entry) throws IOException {
        if (!entry.name.matches("events-[0-9]+-[a-f0-9-]+\\.log")) throw new IOException("Invalid segment");
        File file = new File(directory, entry.name).getCanonicalFile();
        if (!directory.equals(file.getParentFile())) throw new IOException("Invalid segment path");
        return file;
    }

    /** Bounded read; aborts if rotation removed any segment. HTTP uploader must not retry partial bodies. */
    public void stream(Batch batch, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        for (Entry entry : batch.entries) {
            File file = resolve(entry);
            MessageDigest hash = sha();
            long count = 0;
            try (InputStream in = new FileInputStream(file)) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Canceled");
                    count += n;
                    if (count > entry.bytes) throw new IOException("Snapshot changed");
                    hash.update(buffer, 0, n);
                    output.write(buffer, 0, n);
                }
            }
            if (count != entry.bytes || !hex(hash.digest()).equals(entry.sha256)) throw new IOException("Snapshot expired; retry with current logs");
        }
    }

    public synchronized void acknowledge(Batch batch, String acknowledgedId) throws IOException {
        if (!batch.id.equals(acknowledgedId)) throw new IOException("Wrong batch receipt");
        for (Entry entry : batch.entries) {
            File file = resolve(entry);
            if (!file.exists()) continue;
            if (file.length() != entry.bytes || !digest(file).equals(entry.sha256)) throw new IOException("Snapshot changed");
            if (!file.delete()) throw new IOException("Uploaded; local cleanup must be retried");
        }
    }

    public static String digest(File file) throws IOException {
        MessageDigest hash = sha();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) hash.update(buffer, 0, n);
        }
        return hex(hash.digest());
    }
    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder();
        for (byte b : data) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
}
