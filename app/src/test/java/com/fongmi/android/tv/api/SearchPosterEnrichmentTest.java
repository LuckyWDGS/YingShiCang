package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

public class SearchPosterEnrichmentTest {
    private static Vod hit(String id, String name, String pic) {
        return new Vod() {
            @Override public String getId() { return id; }
            @Override public String getName() { return name; }
            @Override public String getPic() { return pic; }
        };
    }
    private static Result result(Vod... items) { Result result = new Result(); result.setList(List.of(items)); return result; }

    @Test public void optionalPosterFailurePreservesAlreadyValidHits() {
        Vod hit = hit("real-id", "real-name", "");
        Result initial = result(hit);
        assertSame(initial, SearchPosterEnrichment.apply(initial, () -> { throw new IOException("Poster timeout"); }));
        assertEquals(1, initial.getList().size());
        assertSame(hit, initial.getList().get(0));
    }

    @Test public void partialEnrichmentKeepsOtherHitsAndRejectsUnrequestedIdentities() {
        Vod a = hit("a", "A", ""), b = hit("b", "B", "");
        Vod enrichedA = hit("a", "A", "poster");
        Result initial = result(a, b);
        SearchPosterEnrichment.apply(initial, () -> result(enrichedA, hit("extra", "Other", "poster")));
        assertEquals(2, initial.getList().size());
        assertSame(enrichedA, initial.getList().get(0));
        assertSame(b, initial.getList().get(1));
    }

    @Test public void emptyOrInvalidPosterRowsCannotEraseValidSearchResults() {
        Vod hit = hit("a", "A", "");
        Result initial = result(hit);
        SearchPosterEnrichment.apply(initial, () -> result(hit("a", "", "poster")));
        assertSame(hit, initial.getList().get(0));
        SearchPosterEnrichment.apply(initial, () -> result());
        assertSame(hit, initial.getList().get(0));
    }
}
