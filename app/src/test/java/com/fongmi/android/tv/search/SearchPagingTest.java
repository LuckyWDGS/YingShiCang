package com.fongmi.android.tv.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

public class SearchPagingTest {

    private final List<Runnable> posted = new ArrayList<>();
    private final List<Delayed> delayed = new ArrayList<>();
    private final List<Request> requests = new ArrayList<>();
    private String selected = "a";
    private boolean canPage = true;
    private boolean nearEnd = true;
    private boolean newCards = true;
    private boolean shownGrew = true;
    private boolean reject;
    private int empties;
    private int cancelled;
    private SearchPaging paging;

    private record Delayed(Runnable task, long delayMs) {
    }

    private record Request(int page, Consumer<List<Vod>> done, Consumer<Throwable> failed) {
    }

    @Before
    public void setUp() {
        paging = new SearchPaging(new SearchPaging.Host() {
            @Override public String selected() { return selected; }
            @Override public boolean canPage() { return canPage; }
            @Override public boolean nearEnd() { return nearEnd; }
            @Override public Site source(String key) { return new Site(); }
            @Override public String query() { return "爱"; }
            @Override public boolean alive() { return true; }
            @Override public SearchPaging.Appended append(String key, List<Vod> page) { return new SearchPaging.Appended(newCards, shownGrew); }
            @Override public void updateEmpty() { empties++; }
            @Override public void post(Runnable task) { posted.add(task); }
            @Override public void postDelayed(Runnable task, long delayMs) { delayed.add(new Delayed(task, delayMs)); }
            @Override public void remove(Runnable task) { delayed.removeIf(item -> item.task() == task); }
        }, (source, query, page, done, failed) -> {
            if (reject) throw new RejectedExecutionException();
            requests.add(new Request(page, done, failed));
            return () -> cancelled++;
        });
    }

    private void runPosted() {
        while (!posted.isEmpty()) posted.remove(0).run();
    }

    private void answer(int index) {
        requests.get(index).done().accept(List.of());
        runPosted();
    }

    private void fail(int index) {
        requests.get(index).failed().accept(new RuntimeException());
        runPosted();
    }

    @Test
    public void pagesAdvanceOnePageAtATime() {
        paging.next(false, false, false);
        paging.next(false, false, false);
        assertEquals(1, requests.size());
        assertEquals(2, requests.get(0).page());
        assertTrue(paging.isLoading());
        answer(0);
        paging.next(false, false, false);
        assertEquals(3, requests.get(1).page());
    }

    @Test
    public void allSourcesLettersAndFarFromEndDoNotPage() {
        selected = "";
        paging.next(false, false, false);
        selected = "a";
        canPage = false;
        paging.next(false, false, false);
        canPage = true;
        nearEnd = false;
        paging.next(false, false, false);
        assertTrue(requests.isEmpty());
        paging.next(false, false, true);
        assertEquals(1, requests.size());
    }

    @Test
    public void aPageWithNothingNewEndsTheSource() {
        newCards = false;
        paging.next(false, false, false);
        answer(0);
        assertTrue(paging.isExhausted("a"));
        paging.next(false, false, true);
        assertEquals(1, requests.size());
    }

    @Test
    public void aFailedPageIsRetriedTwiceThenLeftToTheUser() {
        paging.next(false, false, false);
        fail(0);
        assertEquals(1, delayed.size());
        assertEquals(SearchPaging.RETRY_DELAY_MS, delayed.get(0).delayMs());
        paging.next(false, false, false);
        assertEquals("a failed page waits for its retry", 1, requests.size());
        delayed.remove(0).task().run();
        assertEquals(2, requests.size());
        assertEquals(2, requests.get(1).page());
        fail(1);
        assertEquals(2 * SearchPaging.RETRY_DELAY_MS, delayed.get(0).delayMs());
        delayed.remove(0).task().run();
        fail(2);
        assertTrue(delayed.isEmpty());
        assertEquals(1, empties);
        paging.select("a", true);
        paging.next(false, false, false);
        assertEquals("picking the source again asks once more", 4, requests.size());
    }

    @Test
    public void aRejectedStartIsRetried() {
        reject = true;
        paging.next(false, false, false);
        assertFalse(paging.isLoading());
        assertEquals(1, delayed.size());
        reject = false;
        delayed.remove(0).task().run();
        assertEquals(1, requests.size());
    }

    @Test
    public void aCancelledPageIsIgnored() {
        paging.next(false, false, false);
        paging.cancel();
        assertEquals(1, cancelled);
        answer(0);
        assertFalse(paging.isExhausted("a"));
        paging.next(false, false, false);
        assertEquals(2, requests.size());
        assertEquals("the cancelled page is asked again", 2, requests.get(1).page());
    }

    @Test
    public void scanningStopsAfterThreePagesShowingNothing() {
        shownGrew = false;
        paging.next(false, false, false);
        for (int i = 0; i < 5; i++) {
            if (i < requests.size()) answer(i);
        }
        assertEquals(3, requests.size());
        assertEquals(3, paging.emptyScans());
        paging.select("a", false);
        assertEquals(0, paging.emptyScans());
    }

    @Test
    public void aRetryForAnotherSourceIsDropped() {
        paging.next(false, false, false);
        fail(0);
        selected = "b";
        delayed.remove(0).task().run();
        assertEquals(1, requests.size());
    }
}
