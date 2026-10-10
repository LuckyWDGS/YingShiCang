package com.fongmi.android.tv.discovery;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
public class SourcePageCursorTest {
    @Test public void rejectedRowsStillAdvanceUsingRawIdsAndPageCount() {
        SourcePageCursor cursor = new SourcePageCursor();
        cursor.accept(List.of("adult-rejected-1", "adult-rejected-2"), 3);
        assertEquals(2, cursor.next()); assertFalse(cursor.exhausted());
        cursor.accept(List.of("ordinary-3"), 3);
        assertFalse(cursor.exhausted());
        cursor.accept(List.of("ordinary-4"), 3);
        assertTrue(cursor.exhausted());
    }
    @Test public void repeatedPageCannotCauseUnboundedPagination() {
        SourcePageCursor cursor = new SourcePageCursor();
        cursor.accept(List.of("same-id"), 0);
        assertEquals(2, cursor.next()); assertFalse(cursor.exhausted());
        cursor.accept(List.of("same-id"), 0);
        assertTrue(cursor.exhausted());
    }
    @Test public void emptyAndMissingPagesRemainHonest() {
        SourcePageCursor cursor = new SourcePageCursor();
        assertEquals(1, cursor.next()); // A failed request does not call accept.
        cursor.accept(List.of(), 0); assertTrue(cursor.exhausted());
    }
}
