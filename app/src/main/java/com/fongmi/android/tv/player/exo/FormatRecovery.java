package com.fongmi.android.tv.player.exo;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Each alternative may be tried once per explicit media start, never MIME ping-pong. */
public final class FormatRecovery {
    private final Set<String> attempted = new HashSet<>();
    public void reset() { attempted.clear(); }
    public boolean allow(String current, String candidate) {
        if (candidate == null || Objects.equals(current, candidate) || attempted.size() >= 2) return false;
        return attempted.add(candidate);
    }
}
