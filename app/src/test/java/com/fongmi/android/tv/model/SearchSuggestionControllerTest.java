package com.fongmi.android.tv.model;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class SearchSuggestionControllerTest {
    private static final class Timer implements SearchSuggestionController.Scheduler, SearchSuggestionController.Clock {
        long now;
        boolean inlinePosts;
        final List<Work> scheduled = new ArrayList<>();
        final List<Runnable> posted = new ArrayList<>();
        @Override public long nowMillis() { return now; }
        @Override public SearchSuggestionController.Cancelable schedule(long delay, Runnable action) {
            Work work = new Work(now + delay, action);
            scheduled.add(work);
            return work;
        }
        @Override public void post(Runnable action) { if (inlinePosts) action.run(); else posted.add(action); }
        void advance(long millis) {
            now += millis;
            for (Work work : new ArrayList<>(scheduled)) {
                if (!work.ran && !work.cancelled && work.at <= now) { work.ran = true; work.action.run(); }
            }
        }
        void drain() {
            while (!posted.isEmpty()) posted.remove(0).run();
        }
    }

    private static final class Work implements SearchSuggestionController.Cancelable {
        final long at;
        final Runnable action;
        boolean cancelled, ran;
        Work(long at, Runnable action) { this.at = at; this.action = action; }
        @Override public void cancel() { cancelled = true; }
    }

    private static final class Request implements SearchSuggestionController.Cancelable {
        final String query;
        final SearchSuggestionController.Callback<String> callback;
        int cancellations;
        Runnable onCancel;
        Request(String query, SearchSuggestionController.Callback<String> callback) { this.query = query; this.callback = callback; }
        @Override public void cancel() { cancellations++; if (onCancel != null) onCancel.run(); }
        void result(String... values) { callback.onResult(Arrays.asList(values)); }
    }

    private static final class Event {
        final String query;
        final List<String> values;
        Event(String query, List<String> values) { this.query = query; this.values = values; }
    }

    private static final class Fixture {
        final Timer timer = new Timer();
        final List<Request> requests = new ArrayList<>();
        final List<Event> events = new ArrayList<>();
        final SearchSuggestionController<String> controller = new SearchSuggestionController<>(timer, (query, callback) -> {
            Request request = new Request(query, callback);
            requests.add(request);
            return request;
        }, timer, (query, values) -> events.add(new Event(query, values)));
        Request latest() { return requests.get(requests.size() - 1); }
        Event lastEvent() { return events.get(events.size() - 1); }
        void request(String query) { controller.update(query); timer.advance(SearchSuggestionController.DEBOUNCE_MILLIS); }
        void populate(String query) { request(query); latest().result(query + " suggestion"); timer.drain(); }
    }

    @Test public void rapidTypingOnlyRequestsLatestQueryAfterDebounce() {
        Fixture f = new Fixture();
        f.controller.update("L");
        f.timer.advance(100);
        f.controller.update("LX");
        f.timer.advance(100);
        f.controller.update("LXR");
        f.timer.advance(149);
        assertTrue(f.requests.isEmpty());
        f.timer.advance(1);
        assertEquals(1, f.requests.size());
        assertEquals("LXR", f.latest().query);
        assertTrue("Typing should leave existing hot/candidate content displayed", f.events.isEmpty());
        assertTrue(f.timer.scheduled.get(0).cancelled);
        assertTrue(f.timer.scheduled.get(1).cancelled);
    }

    @Test public void trimmedRepeatDoesNotRestartDebounceOrRequest() {
        Fixture f = new Fixture();
        f.controller.update("  字  ");
        f.timer.advance(100);
        f.controller.update("字");
        f.timer.advance(50);
        assertEquals(1, f.requests.size());
        f.controller.update(" 字 ");
        f.timer.advance(150);
        assertEquals(1, f.requests.size());
        assertEquals(0, f.latest().cancellations);
        assertTrue(f.events.isEmpty());
        assertEquals("字", f.latest().query);
    }

    @Test public void singleLetterAndSingleCharacterBothRequestSuggestions() {
        Fixture f = new Fixture();
        f.request("a");
        assertEquals("a", f.latest().query);
        f.request("影");
        assertEquals("影", f.latest().query);
        assertEquals(2, f.requests.size());
    }

    @Test public void staleCallbackAndEvenCancelledDebounceCannotReplaceLatestOrEnterCache() {
        Fixture f = new Fixture();
        f.request("old");
        Request old = f.latest();
        f.request("new");
        old.result("stale");
        f.latest().result("fresh");
        f.timer.drain();
        assertEquals(Arrays.asList("fresh"), f.lastEvent().values);
        assertEquals("new", f.lastEvent().query);
        assertEquals(1, old.cancellations);
        int eventCount = f.events.size();
        f.controller.update("old");
        assertEquals("A stale response was admitted to the cache", eventCount, f.events.size());
        f.timer.advance(150);
        assertEquals("old", f.latest().query);
        f.controller.update("cancelled");
        f.controller.update("newer");
        int requestCount = f.requests.size();
        f.timer.scheduled.get(f.timer.scheduled.size() - 2).action.run();
        assertEquals("A cancelled debounce still dispatched", requestCount, f.requests.size());
    }

    @Test public void queuedResultIsRejectedOnSuspendAndResumeStartsFreshGeneration() {
        Fixture f = new Fixture();
        f.request("x");
        Request old = f.latest();
        old.result("queued old");
        f.controller.suspend();
        f.timer.drain();
        assertTrue(f.events.isEmpty());
        assertEquals(1, old.cancellations);
        f.controller.resume("x");
        f.timer.advance(150);
        assertEquals(2, f.requests.size());
        f.latest().result("fresh");
        f.timer.drain();
        assertEquals(Arrays.asList("fresh"), f.lastEvent().values);
        f.controller.resume("x");
        f.timer.advance(150);
        assertEquals("Active resume should not repeat the request", 2, f.requests.size());
    }

    @Test public void queuedDeliveryCannotNotifyAfterCloseAndCloseCannotResume() {
        Fixture f = new Fixture();
        f.request("q");
        f.latest().result("queued");
        f.controller.close();
        f.controller.close();
        f.timer.drain();
        f.controller.update("other");
        f.controller.resume("q");
        f.timer.advance(150);
        assertTrue(f.events.isEmpty());
        assertEquals(1, f.requests.size());
        assertEquals(1, f.latest().cancellations);
    }

    @Test public void generationAdvancesBeforeSynchronousCancellationCallback() {
        Fixture f = new Fixture();
        f.timer.inlinePosts = true;
        f.request("old");
        Request old = f.latest();
        old.onCancel = () -> old.result("from cancel");
        f.controller.update("next");
        assertTrue("Cancelling an old query must not publish its synchronous callback", f.events.isEmpty());
        f.timer.advance(150);
        f.latest().result("next result");
        assertEquals(Arrays.asList("next result"), f.lastEvent().values);
    }

    @Test public void submitSuspensionImmediatelyCancelsRequestAndCancelsPendingDebounce() {
        Fixture f = new Fixture();
        f.controller.update("a");
        f.controller.suspend();
        assertTrue(f.timer.scheduled.get(0).cancelled);
        f.timer.advance(150);
        assertTrue(f.requests.isEmpty());
        f.controller.update("b");
        f.timer.advance(150);
        assertTrue(f.requests.isEmpty());
        f.controller.resume("b");
        f.timer.advance(150);
        Request request = f.latest();
        f.controller.suspend();
        assertEquals("Submit must release the in-flight request immediately", 1, request.cancellations);
        request.result("late");
        f.timer.drain();
        assertTrue(f.events.isEmpty());
    }

    @Test public void cacheHitReturnsImmediateImmutableSnapshotWithoutNetwork() {
        Fixture f = new Fixture();
        f.request("x");
        List<String> mutable = new ArrayList<>(Arrays.asList("one", "two"));
        f.latest().callback.onResult(mutable);
        mutable.clear();
        f.timer.drain();
        assertEquals(Arrays.asList("one", "two"), f.lastEvent().values);
        try { f.lastEvent().values.add("mutate"); fail("Snapshot was mutable"); }
        catch (UnsupportedOperationException expected) { }
        f.controller.update("");
        f.controller.update("x");
        assertEquals(Arrays.asList("one", "two"), f.lastEvent().values);
        f.timer.advance(150);
        assertEquals(1, f.requests.size());
    }

    @Test public void cacheExpiresAtFiveMinutesAndReadDoesNotExtendTtl() {
        Fixture f = new Fixture();
        f.populate("q");
        long created = f.timer.now;
        f.controller.update("");
        f.timer.now = created + SearchSuggestionController.CACHE_TTL_MILLIS - 1;
        f.controller.update("q");
        assertFalse(f.lastEvent().values.isEmpty());
        f.controller.update("");
        f.timer.now++;
        f.controller.update("q");
        assertTrue(f.lastEvent().values.isEmpty());
        f.timer.advance(150);
        assertEquals(2, f.requests.size());
    }

    @Test public void cacheIsBoundedAndEvictsLeastRecentlyUsedEntry() {
        Fixture f = new Fixture();
        for (int i = 0; i < SearchSuggestionController.CACHE_LIMIT; i++) f.populate("q" + i);
        f.controller.update("q0");
        assertFalse(f.lastEvent().values.isEmpty());
        f.populate("q32");
        f.controller.update("q0");
        assertFalse("Recently read entry should stay cached", f.lastEvent().values.isEmpty());
        int requestCount = f.requests.size();
        int eventCount = f.events.size();
        f.controller.update("q1");
        assertEquals("Least recently used entry should be evicted", eventCount, f.events.size());
        assertEquals("An uncached query should retain the previous visible candidate", "q0", f.lastEvent().query);
        f.timer.advance(150);
        assertEquals(requestCount + 1, f.requests.size());
    }

    @Test public void whitespaceClearsAndCancelsWithoutDispatchingAnEmptyQuery() {
        Fixture f = new Fixture();
        f.populate("x");
        f.controller.update(" \t\n ");
        assertEquals("", f.lastEvent().query);
        assertTrue(f.lastEvent().values.isEmpty());
        f.controller.update(null);
        f.timer.advance(150);
        assertEquals(1, f.requests.size());
        f.request("y");
        f.controller.update(" ");
        assertEquals(1, f.latest().cancellations);
        f.latest().result("late");
        f.timer.drain();
        assertEquals("", f.lastEvent().query);
    }

    @Test public void emptySuccessAndFailureStaySilentAndAreBothRetriedOnReturn() {
        Fixture f = new Fixture();
        f.request("empty");
        f.latest().result();
        f.timer.drain();
        assertTrue("An empty response should leave the displayed hot/candidate content", f.events.isEmpty());
        f.controller.update("");
        f.controller.update("empty");
        f.timer.advance(150);
        assertEquals("An empty response should not be cached", 2, f.requests.size());
        f.request("failure");
        int eventCount = f.events.size();
        f.latest().callback.onFailure();
        f.timer.drain();
        assertEquals("Failure should not show a loading/error state", eventCount, f.events.size());
        f.controller.update("");
        f.request("failure");
        assertEquals("Transient failure should not be cached", 4, f.requests.size());
    }

    @Test public void duplicateCallbacksOnlyDeliverOnce() {
        Fixture f = new Fixture();
        f.request("q");
        f.latest().result("first");
        f.latest().callback.onFailure();
        f.latest().result("second");
        f.timer.drain();
        assertEquals(1, f.events.size());
        assertEquals(Arrays.asList("first"), f.lastEvent().values);
    }

    @Test public void synchronousTransportCompletionDoesNotLeaveCancelableRequestAttached() {
        Timer timer = new Timer();
        timer.inlinePosts = true;
        int[] cancelled = {0};
        List<Event> events = new ArrayList<>();
        SearchSuggestionController<String> controller = new SearchSuggestionController<>(timer, (query, callback) -> {
            callback.onResult(Arrays.asList("immediate"));
            return () -> cancelled[0]++;
        }, timer, (query, values) -> events.add(new Event(query, values)));
        controller.update("x");
        timer.advance(150);
        assertEquals(Arrays.asList("immediate"), events.get(0).values);
        assertEquals(1, cancelled[0]);
        controller.suspend();
        assertEquals("Completed transport handle should not be cancelled again", 1, cancelled[0]);
    }

    @Test public void throwingTransportFailsSilently() {
        Timer timer = new Timer();
        List<Event> events = new ArrayList<>();
        SearchSuggestionController<String> controller = new SearchSuggestionController<>(timer, (query, callback) -> {
            throw new IllegalStateException("unavailable");
        }, timer, (query, values) -> events.add(new Event(query, values)));
        controller.update("x");
        timer.advance(150);
        timer.drain();
        assertTrue(events.isEmpty());
    }

    @Test public void pendingFailureAndEmptyReplacementKeepLastNonemptyCandidatesVisible() {
        Fixture f = new Fixture();
        f.populate("previous");
        List<String> previous = f.lastEvent().values;
        f.controller.update("pending");
        assertEquals(1, f.events.size());
        assertSame(previous, f.lastEvent().values);
        f.timer.advance(150);
        f.latest().callback.onFailure();
        f.timer.drain();
        assertEquals("A failed replacement cleared the candidates", 1, f.events.size());
        f.request("empty");
        f.latest().result();
        f.timer.drain();
        assertEquals("An empty replacement cleared the candidates", 1, f.events.size());
        assertSame(previous, f.lastEvent().values);
        f.request("ready");
        f.latest().result("replacement one", "replacement two");
        assertSame("Candidates changed before the scheduler delivered the replacement", previous, f.lastEvent().values);
        f.timer.drain();
        assertEquals(2, f.events.size());
        assertEquals(Arrays.asList("replacement one", "replacement two"), f.lastEvent().values);
    }

    @Test public void rapidReplacementOnlyCommitsLatestNonemptyResultWithoutIntermediateClear() {
        Fixture f = new Fixture();
        f.populate("shown");
        f.request("intermediate");
        Request intermediate = f.latest();
        intermediate.result("queued intermediate");
        f.request("latest");
        f.latest().result("latest one", "latest two");
        assertEquals(1, f.events.size());
        assertEquals(Arrays.asList("shown suggestion"), f.lastEvent().values);
        f.timer.drain();
        assertEquals("Only the latest replacement should commit", 2, f.events.size());
        assertEquals("latest", f.lastEvent().query);
        assertEquals(Arrays.asList("latest one", "latest two"), f.lastEvent().values);
    }

    @Test public void explicitClearRemovesCandidatesImmediatelyAndRejectsQueuedNonemptyReplacement() {
        Fixture f = new Fixture();
        f.populate("shown");
        f.request("pending");
        Request pending = f.latest();
        pending.result("queued pending");
        f.controller.update("");
        assertEquals(2, f.events.size());
        assertEquals("", f.lastEvent().query);
        assertTrue(f.lastEvent().values.isEmpty());
        assertEquals(1, pending.cancellations);
        f.timer.drain();
        pending.result("even later");
        f.timer.drain();
        assertEquals("A late result restored cleared candidates", 2, f.events.size());
        assertTrue(f.lastEvent().values.isEmpty());
    }
}
