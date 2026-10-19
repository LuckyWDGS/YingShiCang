package com.fongmi.android.tv.discovery;

/** Pauses automatic paging after consecutive rounds without new visible cards. */
public final class DiscoveryScanBudget {
    private static final int MAX_EMPTY_ROUNDS = 3;
    private int emptyRounds;
    private boolean paused;

    public void reset() { emptyRounds = 0; paused = false; }

    public void accept(int before, int after, boolean more) {
        emptyRounds = more && after <= before ? emptyRounds + 1 : 0;
        paused = more && emptyRounds >= MAX_EMPTY_ROUNDS;
    }

    public boolean paused() { return paused; }
}
