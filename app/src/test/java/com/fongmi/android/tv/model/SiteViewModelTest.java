package com.fongmi.android.tv.model;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.resource.ResourceHealth;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Regression coverage for source-local search filtering. */
public class SiteViewModelTest {

    @Before
    @After
    public void clearHealth() {
        ResourceHealth.clear();
    }

    @Test
    public void singleSourceSearchKeepsTransientCardsButHidesInvalidAndMalformedEntries() {
        Vod playable = card("playable", "Playable");
        Vod transientCard = card("temporary", "Temporary");
        Vod invalid = card("gone", "Gone");
        Vod malformed = card("", "No id");

        Result result = Result.list(List.of(playable, transientCard, invalid, malformed));

        // filterSearchResult uses the active discovery revision. An explicit
        // resource error hides a card; a network timeout must keep it visible.
        long revision = com.fongmi.android.tv.api.config.VodConfig.getDiscoveryRevision();
        ResourceHealth.recordDetailFailure("source-a", "gone", new IOException("resource not found"), revision);
        ResourceHealth.recordDetailFailure("source-a", "temporary", new SocketTimeoutException(), revision);

        Result filtered = SiteViewModel.filterSearchResult(result);
        assertEquals("Raw source rows must remain available for paging/diagnostics", 4,
                filtered.getSearchRawList().size());
        assertEquals(2, filtered.getList().size());
        assertTrue(filtered.getList().contains(playable));
        assertTrue(filtered.getList().contains(transientCard));
    }

    private Vod card(String id, String name) {
        return new TestVod(id, name);
    }

    /** Avoid Android TextUtils stubs in local JVM tests while exercising real filtering. */
    private static final class TestVod extends Vod {
        private final String id;
        private final String name;

        private TestVod(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override public String getId() { return id; }
        @Override public String getName() { return name; }
        @Override public boolean isFolder() { return false; }
        @Override public boolean isAction() { return false; }
        @Override public String getSiteKey() { return "source-a"; }
    }
}
