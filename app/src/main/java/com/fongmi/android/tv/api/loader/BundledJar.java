package com.fongmi.android.tv.api.loader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Exact source binding: the package never supplies this plugin to an unrelated URL. */
public final class BundledJar {
    public static final String ASSET = "plugins/source-20261016.jar";
    public static final String SHA256 = "798d86aba4698a1135c260880f0fe4e37952f53b197138a851c34fe0085fca02";
    public static final String MD5 = "aa8d37525617af470b3b8dfc91db8669";
    private static final String URL_SHA256 = "c1d87213bf0323f9cfed254e04506793f466fe142b734e2fe2d47e633d63e5e9";

    private BundledJar() {}

    public static boolean matches(String url, String expectedMd5) {
        if (url == null || expectedMd5 == null) return false;
        // This provider declares MD5. A removed/changed checksum requires a fresh online plugin.
        if (!MD5.equalsIgnoreCase(expectedMd5)) return false;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (byte b : digest) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            return URL_SHA256.contentEquals(hash);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
