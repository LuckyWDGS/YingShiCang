package com.fongmi.android.tv.api.loader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Exact source binding: the package never supplies this plugin to an unrelated URL.
 * 2026-10-22 build of the provider's plugin at its new address (the 2026-10-16 address is no longer configured).
 */
public final class BundledJar {
    public static final String ASSET = "plugins/source-20261022.jar";
    public static final String SHA256 = "7783d0326547d9db37d6a1ff3c6db7262085051d9024af21f71b9efafffb61ca";
    public static final String MD5 = "654fb7dcb0aeaaea7dba0e662395f38e";
    private static final String URL_SHA256 = "02d129c7dc0e673e11c7596e91e50e4a35653b402a42a6f7e0567272856a9999";

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
