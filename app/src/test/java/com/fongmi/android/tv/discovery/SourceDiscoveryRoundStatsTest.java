package com.fongmi.android.tv.discovery;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutionException;
import org.junit.Test;
import static org.junit.Assert.*;

public class SourceDiscoveryRoundStatsTest {
    private static JsonObject summary(SourceDiscoveryRoundStats stats) {
        return stats.summary(5, true, 3, false, 4, 3, 1, 1, 12, 2, 3, 6, 1, 7, true);
    }

    @Test public void distinguishesRawAdultEvidenceFromAcceptanceAndUnknownKindFiltering() {
        SourceDiscoveryRoundStats stats = new SourceDiscoveryRoundStats();
        stats.item(false, false, false);
        stats.item(true, false, true);
        stats.item(true, true, false);
        stats.item(true, false, false);
        JsonObject value = summary(stats);
        assertEquals(4, value.get("round_raw_items").getAsInt());
        assertEquals(3, value.get("round_raw_adult_item_evidence").getAsInt());
        assertEquals(1, value.get("round_accepted_items").getAsInt());
        assertEquals(3, value.get("round_rejected_items").getAsInt());
        assertEquals(1, value.get("round_unknown_kind_rejected").getAsInt());
        assertEquals(3, value.get("explicit_queries").getAsInt());
        assertEquals(6, value.get("fallback_queries").getAsInt());
        assertEquals(12, value.get("declared_categories").getAsInt());
        assertEquals(2, value.get("adult_categories").getAsInt());
    }

    @Test public void failureHistogramContainsOnlyFixedFamiliesAndNeverExceptionTextOrCustomClass() {
        SourceDiscoveryRoundStats stats = new SourceDiscoveryRoundStats();
        class PrivateSourceFailure extends IOException { PrivateSourceFailure() { super("https://private.invalid/token?title=private"); } }
        stats.error(SourceDiscoveryRoundStats.errorType(new PrivateSourceFailure()));
        stats.error(SourceDiscoveryRoundStats.errorType(new ExecutionException(new SocketTimeoutException("secret source name"))));
        stats.error(SourceDiscoveryRoundStats.errorType(new IllegalArgumentException("secret tag")));
        JsonObject value = summary(stats);
        JsonObject errors = value.getAsJsonObject("round_errors");
        assertEquals(1, errors.get("IOException").getAsInt());
        assertEquals(1, errors.get("SocketTimeoutException").getAsInt());
        assertEquals(1, errors.get("RuntimeException").getAsInt());
        for (String privateText : new String[]{"https", "private", "token", "secret", "PrivateSourceFailure"})
            assertFalse(privateText, value.toString().contains(privateText));
        for (var field : value.entrySet()) if (!field.getKey().equals("round_errors")) {
            assertTrue(field.getValue().isJsonPrimitive());
            assertFalse(field.getValue().getAsJsonPrimitive().isString());
        }
    }

    @Test public void separateRoundsDoNotCarryPriorItemsOrErrors() {
        SourceDiscoveryRoundStats before = new SourceDiscoveryRoundStats();
        before.item(true, true, false); before.error(SourceDiscoveryRoundStats.ErrorType.TimeoutException);
        JsonObject after = summary(new SourceDiscoveryRoundStats());
        assertEquals(0, after.get("round_raw_items").getAsInt());
        assertEquals(0, after.get("round_raw_adult_item_evidence").getAsInt());
        assertEquals(0, after.getAsJsonObject("round_errors").size());
        assertEquals(7, after.get("visible_items").getAsInt());
        assertTrue(after.get("more").getAsBoolean());
    }
}
