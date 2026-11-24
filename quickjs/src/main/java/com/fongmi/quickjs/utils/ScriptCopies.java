package com.fongmi.quickjs.utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/**
 * The last downloaded text of each JavaScript source and of the modules it imports, kept on disk.
 * Scripts were only held in memory: every launch downloaded them again, and a source whose script could
 * not be fetched just then did not start (one TV on a poor line, 2026-11-23: 739 of 821 failed JS source
 * starts were the download, not the script). A copy under a day old is used without asking; when the
 * address does not answer, a copy of any age is.
 */
public final class ScriptCopies {

    static final long FRESH_MS = 24 * 60 * 60 * 1000L;
    static final int MAX_FILES = 300;
    static final long MAX_BYTES = 48L * 1024 * 1024;
    private static final int PRUNE_EVERY = 20;

    private final File directory;
    private int saves;

    public ScriptCopies(File directory) {
        this.directory = directory;
    }

    /** The copy saved within the last day and after validAfter (the user's last reload), or null. */
    public String fresh(String address, long now, long validAfter) {
        File file = file(address);
        long saved = file.lastModified();
        if (saved <= 0 || saved < validAfter || now - saved >= FRESH_MS || now < saved) return null;
        return read(file);
    }

    /** The copy whatever its age, or null. */
    public String any(String address) {
        return read(file(address));
    }

    /** Written beside the old copy and renamed over it, so a failed write never leaves half a script. */
    public synchronized void save(String address, String content, long now) {
        if (content == null || content.trim().isEmpty()) return;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            File target = file(address);
            File staged = File.createTempFile("script-", ".tmp", directory);
            try {
                Files.write(staged.toPath(), content.getBytes(StandardCharsets.UTF_8));
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                target.setLastModified(now);
            } finally {
                Files.deleteIfExists(staged.toPath());
            }
            if (++saves % PRUNE_EVERY == 1) prune();
        } catch (IOException | RuntimeException ignored) {
            // A full disk only means the next launch downloads again.
        }
    }

    /** Oldest copies go first once there are too many or they are too large together. */
    void prune() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".js"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long bytes = 0;
        for (int i = 0; i < files.length; i++) {
            bytes += files[i].length();
            if (i >= MAX_FILES || bytes > MAX_BYTES) files[i].delete();
        }
    }

    private String read(File file) {
        try {
            if (!file.isFile()) return null;
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return content.trim().isEmpty() ? null : content;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private File file(String address) {
        return new File(directory, hash(address) + ".js");
    }

    private static String hash(String text) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
