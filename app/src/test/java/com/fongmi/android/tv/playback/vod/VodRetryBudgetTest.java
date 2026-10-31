package com.fongmi.android.tv.playback.vod;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;

import org.junit.Test;

public class VodRetryBudgetTest {

    @Test public void aFailingLineIsRetriedTwiceThenFallsBack() {
        VodRetryBudget budget = new VodRetryBudget();
        VodPlayRequest line = request("line", "ep1");
        assertTrue(budget.take(line, 0));
        assertTrue(budget.take(line, 5_000));
        assertFalse(budget.take(line, 10_000));
    }

    @Test public void aShortRecoveryDoesNotRefillTheRetries() {
        VodRetryBudget budget = new VodRetryBudget();
        VodPlayRequest line = request("line", "ep1");
        assertTrue(budget.take(line, 0));
        budget.ready(2_000);
        assertTrue(budget.take(line, 30_000));
        budget.ready(32_000);
        assertFalse(budget.take(line, 50_000));
    }

    @Test public void aMinuteOfPlaybackRefillsTheRetries() {
        VodRetryBudget budget = new VodRetryBudget();
        VodPlayRequest line = request("line", "ep1");
        assertTrue(budget.take(line, 0));
        assertTrue(budget.take(line, 5_000));
        budget.ready(7_000);
        budget.ready(40_000); // a later READY after a rebuffer does not restart the minute
        assertTrue(budget.take(line, 67_000));
        assertTrue(budget.take(line, 70_000));
        assertFalse(budget.take(line, 75_000));
    }

    @Test public void anotherLineOrEpisodeStartsWithFullRetries() {
        VodRetryBudget budget = new VodRetryBudget();
        assertTrue(budget.take(request("line", "ep1"), 0));
        assertTrue(budget.take(request("line", "ep1"), 1_000));
        assertTrue(budget.take(request("backup", "ep1"), 2_000));
        assertTrue(budget.take(request("backup", "ep2"), 3_000));
        assertFalse(budget.take(null, 4_000));
    }

    private static VodPlayRequest request(String flag, String url) {
        Episode episode = new Episode() { @Override public String getUrl() { return url; } };
        Flag line = new Flag() { @Override public String getFlag() { return flag; } };
        return VodPlayRequest.create("site", line, episode);
    }
}
