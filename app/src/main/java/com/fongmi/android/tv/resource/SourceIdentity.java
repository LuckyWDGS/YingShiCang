package com.fongmi.android.tv.resource;

import com.fongmi.android.tv.bean.Site;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stable, anonymous identity for one source in one configuration. */
public final class SourceIdentity {
    private SourceIdentity() {}

    public static String of(String config, Site site) {
        if (site.getKey().isEmpty()) return "";
        String value = config + "\0" + site.getKey() + "\0" + site.getType() + "\0" + site.getApi()
                + "\0" + site.getExt() + "\0" + site.getJar() + "\0" + new java.util.TreeMap<>(site.getHeader());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("v2:");
            for (byte item : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
