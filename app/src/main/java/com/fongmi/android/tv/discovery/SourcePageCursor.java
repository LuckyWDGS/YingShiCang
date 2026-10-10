package com.fongmi.android.tv.discovery;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Tracks raw pages: a filtered empty page does not mean that the source is exhausted. */
public final class SourcePageCursor {
    private final Set<String> identities = new HashSet<>();
    private int next = 1;
    private boolean exhausted;
    public int next() { return next; }
    public boolean exhausted() { return exhausted; }
    public void accept(Collection<String> rawIds, int pageCount) {
        if (exhausted) return;
        boolean added = false;
        for (String id : rawIds) if (id != null && !id.isEmpty() && identities.add(id)) added = true;
        exhausted = rawIds.isEmpty() || !added || pageCount > 0 && next >= pageCount || next >= 500;
        next++;
    }
    // Failure does not advance: the same raw page must be retried.
}
