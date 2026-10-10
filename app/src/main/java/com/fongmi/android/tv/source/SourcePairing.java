package com.fongmi.android.tv.source;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Short lived pairing for configuration push only. No cloud account or stored password. */
public final class SourcePairing {
    public static final SourcePairing INSTANCE = new SourcePairing(System::currentTimeMillis);
    private final LongSupplier clock;
    private String token = "", code = "";
    private long expires, blockedUntil;
    private int attempts;
    public SourcePairing(LongSupplier clock) { this.clock = clock; }
    public synchronized void open() {
        if (clock.getAsLong() < expires) return;
        token = UUID.randomUUID().toString();
        code = String.format(java.util.Locale.ROOT, "%06d", new SecureRandom().nextInt(1000000));
        expires = clock.getAsLong() + 15 * 60_000;
        blockedUntil = 0; attempts = 0;
    }
    public synchronized String token() { return token; }
    public synchronized String code() { return code; }
    public synchronized boolean valid(String supplied) {
        return supplied != null && !token.isEmpty() && clock.getAsLong() < expires && token.equals(supplied);
    }
    public synchronized String login(String supplied) {
        long now = clock.getAsLong();
        if (now >= expires || now < blockedUntil) return null;
        if (!code.equals(supplied)) {
            if (++attempts >= 5) { blockedUntil = now + 60_000; attempts = 0; }
            return null;
        }
        attempts = 0;
        return token;
    }
}
