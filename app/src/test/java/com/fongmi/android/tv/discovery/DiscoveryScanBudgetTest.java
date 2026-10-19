package com.fongmi.android.tv.discovery;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class DiscoveryScanBudgetTest {
    @Test public void filteredPagesPauseWithoutExhaustingRawCursorAndContinueAtNextPage() {
        DiscoveryScanBudget budget = new DiscoveryScanBudget();
        SourcePageCursor cursor = new SourcePageCursor();
        for (int page = 1; page <= 3; page++) {
            cursor.accept(List.of("filtered-" + page), 500);
            budget.accept(0, 0, !cursor.exhausted());
            assertEquals(page == 3, budget.paused());
        }
        assertFalse(cursor.exhausted());
        budget.reset();
        assertEquals(4, cursor.next());
        cursor.accept(List.of("matching-4"), 500);
        budget.accept(0, 1, !cursor.exhausted());
        assertFalse(budget.paused());
    }

    @Test public void existingResultsDoNotAllowUnlimitedEmptyPagingAndProgressResetsBudget() {
        DiscoveryScanBudget budget = new DiscoveryScanBudget();
        budget.accept(0, 4, true);
        budget.accept(4, 4, true);
        budget.accept(4, 4, true);
        assertFalse(budget.paused());
        budget.accept(4, 5, true);
        budget.accept(5, 5, true);
        budget.accept(5, 5, true);
        assertFalse(budget.paused());
        budget.accept(5, 5, true);
        assertTrue(budget.paused());
        budget.reset();
        assertFalse(budget.paused());
    }

    @Test public void exhaustedCatalogDoesNotOfferContinuation() {
        DiscoveryScanBudget budget = new DiscoveryScanBudget();
        for (int i = 0; i < 3; i++) budget.accept(0, 0, true);
        budget.accept(0, 0, false);
        assertFalse(budget.paused());
    }
}
