package com.fongmi.android.tv.search;

import com.fongmi.android.tv.bean.Site;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchSourceGateTest {
    @Test public void coldSearchUsesConfiguredSourcesOnceTheyBecomeReady() {
        AtomicReference<List<Site>> config = new AtomicReference<>(List.of());
        SearchSourceGate gate = new SearchSourceGate(config::get);
        List<List<Site>> searches = new ArrayList<>();
        Site enabled = new Site(), disabled = new Site().setSearchable(false);

        gate.search(searches::add);
        assertTrue(gate.isWaitingForConfig());
        assertEquals(List.of(List.of()), searches);
        config.set(List.of(enabled, disabled));
        gate.configReady();
        gate.configReady();

        assertFalse(gate.isWaitingForConfig());

        assertEquals(List.of(List.of(), List.of(enabled)), searches);
        assertFalse(disabled.isSearchable());
    }

    @Test public void disabledSourcesAndWarmSearchAreNotRestartedByConfigEvents() {
        AtomicReference<List<Site>> config = new AtomicReference<>(List.of(new Site().setSearchable(false)));
        SearchSourceGate gate = new SearchSourceGate(config::get);
        List<List<Site>> searches = new ArrayList<>();

        gate.search(searches::add);
        config.set(List.of(new Site()));
        gate.configReady();
        assertEquals(List.of(List.of()), searches);

        gate.search(searches::add);
        gate.configReady();
        assertEquals(2, searches.size());
        assertEquals(1, searches.get(1).size());
    }

    @Test public void latestKeywordReplacesPendingSearchAndLeavingCancelsIt() {
        AtomicReference<List<Site>> config = new AtomicReference<>(List.of());
        SearchSourceGate gate = new SearchSourceGate(config::get);
        List<String> searches = new ArrayList<>();
        gate.search(sites -> searches.add("old:" + sites.size()));
        gate.search(sites -> searches.add("latest:" + sites.size()));
        config.set(List.of(new Site()));
        gate.configReady();
        assertEquals(List.of("old:0", "latest:0", "latest:1"), searches);

        config.set(List.of());
        gate.search(sites -> searches.add("closed:" + sites.size()));
        gate.cancel();
        config.set(List.of(new Site()));
        gate.configReady();
        assertEquals(List.of("old:0", "latest:0", "latest:1", "closed:0"), searches);
    }

    @Test public void failedConfigCompletionIsConsumedWithoutRetryLoop() {
        SearchSourceGate gate = new SearchSourceGate(List::of);
        List<List<Site>> searches = new ArrayList<>();
        gate.search(searches::add);
        gate.configReady();
        gate.configReady();
        assertEquals(List.of(List.of(), List.of()), searches);
    }
}
