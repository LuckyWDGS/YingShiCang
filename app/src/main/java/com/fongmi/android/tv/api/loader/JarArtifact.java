package com.fongmi.android.tv.api.loader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipFile;

/** A failed refresh must never truncate the previously initialized plugin. */
public final class JarArtifact {
    public interface Fetcher { void fetch(File destination) throws IOException; }
    public interface Loader { void load(File file); }
    public record Bundle(String sha256, Fetcher copy) {}
    private static final long TTL_MS = 24 * 60 * 60 * 1000L;

    private JarArtifact() {}

    public static void load(File target, String identity, String expectedMd5, long now, Fetcher fetcher, Loader loader) throws IOException {
        load(target, identity, expectedMd5, now, fetcher, loader, null);
    }

    public static void load(File target, String identity, String expectedMd5, long now, Fetcher fetcher, Loader loader, Bundle bundle) throws IOException {
        checkCanceled();
        File marker = new File(target + ".verified");
        if (!expectedMd5.isEmpty() && target.isFile() && expectedMd5.equalsIgnoreCase(hash(target, "MD5"))) {
            loader.load(target);
            if (bundle == null || !bundle.sha256().equals(hash(target, "SHA-256"))) mark(marker, target, identity, now);
            return;
        }
        IOException failure = null;
        File staged = File.createTempFile("plugin-", ".jar", target.getParentFile());
        try {
            boolean downloaded = false;
            for (int attempt = 0; attempt < 2; attempt++) {
                checkCanceled();
                try {
                    fetcher.fetch(staged);
                    checkCanceled();
                    validate(staged, expectedMd5);
                    downloaded = true;
                    break;
                } catch (IOException e) {
                    checkCanceled();
                    if ("Canceled".equals(e.getMessage())) throw e;
                    failure = e;
                }
            }
            if (!downloaded) {
                if (verified(marker, target, identity, now)
                        && (expectedMd5.isEmpty() || expectedMd5.equalsIgnoreCase(hash(target, "MD5")))) {
                    loader.load(target);
                    return; // A fallback never renews its own age.
                }
                if (bundle == null || replacesOnlineVersion(marker, identity, bundle.sha256())) throw failure;
                bundle.copy().fetch(staged);
                checkCanceled();
                if (!bundle.sha256().equals(hash(staged, "SHA-256"))) throw new IOException("内置源插件校验失败");
                validate(staged, expectedMd5);
            }
            File backup = File.createTempFile("plugin-previous-", ".jar", target.getParentFile());
            boolean previous = target.isFile();
            if (previous) Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try {
                checkCanceled();
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                loader.load(target);
                // A packaged baseline is not a freshly downloaded/verified online update.
                if (downloaded) mark(marker, target, identity, now);
            } catch (RuntimeException | Error | IOException e) {
                if (previous) Files.move(backup.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                else Files.deleteIfExists(target.toPath());
                throw e;
            } finally {
                Files.deleteIfExists(backup.toPath());
            }
        } finally {
            Files.deleteIfExists(staged.toPath());
        }
    }

    private static boolean replacesOnlineVersion(File marker, String identity, String bundledHash) {
        try {
            String[] record = Files.readString(marker.toPath()).split("\n", 3);
            return record.length == 3 && identity.equals(record[2]) && !bundledHash.equals(record[1]);
        } catch (IOException e) { return false; }
    }

    private static void validate(File file, String md5) throws IOException {
        boolean dex;
        try (var input = Files.newInputStream(file.toPath())) {
            byte[] header = new byte[8];
            dex = input.read(header) == 8 && header[0] == 'd' && header[1] == 'e' && header[2] == 'x'
                    && header[3] == '\n' && header[7] == 0;
        }
        if (!dex) {
            try (ZipFile zip = new ZipFile(file)) {
                if (zip.getEntry("classes.dex") == null) throw new IOException("源插件缺少 classes.dex");
            }
        }
        if (!md5.isEmpty() && !md5.equalsIgnoreCase(hash(file, "MD5"))) throw new IOException("源插件校验失败");
    }

    private static String hash(File file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (var input = Files.newInputStream(file.toPath())) {
                byte[] bytes = new byte[16 * 1024];
                int count;
                while ((count = input.read(bytes)) != -1) { checkCanceled(); digest.update(bytes, 0, count); }
            }
            StringBuilder result = new StringBuilder();
            for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    private static void mark(File marker, File target, String identity, long now) {
        try { Files.writeString(marker.toPath(), now + "\n" + hash(target, "SHA-256") + "\n" + identity); }
        catch (IOException ignored) { marker.delete(); } // Cache bookkeeping cannot invalidate a loaded plugin.
    }

    private static boolean verified(File marker, File target, String identity, long now) {
        try {
            String[] record = Files.readString(marker.toPath()).split("\n", 3);
            long age = now - Long.parseLong(record[0]);
            return age >= 0 && age < TTL_MS && identity.equals(record[2]) && record[1].equals(hash(target, "SHA-256"));
        } catch (CancellationException e) { throw e; }
        catch (IOException | RuntimeException e) { return false; }
    }

    private static void checkCanceled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
