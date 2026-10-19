package com.fongmi.android.tv.search;

import com.fongmi.android.tv.bean.Site;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Main-thread aggregate-search startup recovery, without changing source preferences. */
public final class SearchSourceGate {
    private final Supplier<List<Site>> configuredSites;
    private Consumer<List<Site>> pending;

    public SearchSourceGate(Supplier<List<Site>> configuredSites) {
        this.configuredSites = configuredSites;
    }

    public void search(Consumer<List<Site>> start) {
        List<Site> sites = configuredSites.get();
        // A real config with every search switch disabled is a final empty list.
        pending = sites.isEmpty() ? start : null;
        start.accept(searchable(sites));
    }

    public void configReady() {
        Consumer<List<Site>> start = pending;
        if (start == null) return;
        cancel();
        // Consume before invoking the search so duplicate/reentrant events cannot
        // restart a completed search or replace its progressively arriving cards.
        start.accept(searchable(configuredSites.get()));
    }

    public void cancel() {
        pending = null;
    }

    public boolean isWaitingForConfig() {
        return pending != null;
    }

    private static List<Site> searchable(List<Site> sites) {
        return sites.stream().filter(Site::isSearchable).toList();
    }
}
