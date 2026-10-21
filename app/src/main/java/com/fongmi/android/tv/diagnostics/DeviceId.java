package com.fongmi.android.tv.diagnostics;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import com.github.catvod.utils.Prefers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Anonymous per-install ID (e.g. "K7Q2-9MZD") that groups one device's uploaded logs together.
 * On Android 8+ it is a salted hash of ANDROID_ID, which the system already scopes to this app's
 * signing key, so it survives reinstall but cannot be matched with other apps. Older systems,
 * where ANDROID_ID may be shared by cloned box images, get a random ID instead.
 */
public final class DeviceId {
    private static final String KEY = "ysc_device_id";
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    public static final String PATTERN = "[0-9A-Z]{4}-[0-9A-Z]{4}";

    private DeviceId() {}

    public static synchronized String get(Context context) {
        String saved = Prefers.getString(KEY);
        if (saved.matches(PATTERN)) return saved;
        String id = fromSeed(seed(context));
        Prefers.put(KEY, id);
        return id;
    }

    @SuppressLint("HardwareIds")
    private static byte[] seed(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            String android = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (android != null && !android.isEmpty() && !android.matches("0+") && !"9774d56d682e549c".equals(android))
                return ("ysc-log-device:" + android).getBytes(StandardCharsets.UTF_8);
        }
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        return random;
    }

    /** First 40 bits of SHA-256(seed) as eight Crockford base32 characters, split 4-4. */
    static String fromSeed(byte[] seed) {
        byte[] hash;
        try { hash = MessageDigest.getInstance("SHA-256").digest(seed); }
        catch (Exception e) { throw new IllegalStateException(e); }
        long bits = 0;
        for (int i = 0; i < 5; i++) bits = (bits << 8) | (hash[i] & 255);
        StringBuilder id = new StringBuilder(9);
        for (int i = 7; i >= 0; i--) {
            id.append(ALPHABET.charAt((int) (bits >>> (i * 5)) & 31));
            if (i == 4) id.append('-');
        }
        return id.toString();
    }
}
