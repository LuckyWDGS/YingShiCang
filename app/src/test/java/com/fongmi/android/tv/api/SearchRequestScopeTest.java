package com.fongmi.android.tv.api;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class SearchRequestScopeTest {
    private static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        final ExecutorService workers = Executors.newCachedThreadPool();
        final CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger requests = new AtomicInteger();
        final int blockedRequest;
        final long firstDelay;

        Fixture(int blockedRequest, long firstDelay) throws IOException {
            this.blockedRequest = blockedRequest; this.firstDelay = firstDelay;
            workers.submit(() -> {
                while (!server.isClosed()) try { Socket socket = server.accept(); workers.submit(() -> handle(socket)); }
                catch (IOException ignored) { break; }
            });
        }

        private void handle(Socket socket) {
            try (socket) {
                var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while ((line = input.readLine()) != null && !line.isEmpty()) { }
                int number = requests.incrementAndGet();
                requested.countDown();
                if (number == 1 && firstDelay > 0) Thread.sleep(firstDelay);
                if (number == blockedRequest) release.await();
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            } catch (Exception ignored) { }
        }

        Request request() { return new Request.Builder().url("http://127.0.0.1:" + server.getLocalPort() + "/search").build(); }
        @Override public void close() throws IOException { release.countDown(); server.close(); workers.shutdownNow(); }
    }

    @Test public void closeCancelsActiveSocketAndPreventsTheNextSearchStage() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (var fixture = new Fixture(1, 0); var scope = new SearchRequestScope(new OkHttpClient(), 3000)) {
            var active = worker.submit(() -> scope.execute(fixture.request()));
            assertTrue(fixture.requested.await(1, TimeUnit.SECONDS));
            scope.close();
            try { active.get(1, TimeUnit.SECONDS); fail("Cancelled socket continued waiting for response"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof IOException); }
            assertThrows(IOException.class, () -> scope.execute(fixture.request()));
            assertEquals("Cancellation started another HTTP stage", 1, fixture.requests.get());
        } finally { worker.shutdownNow(); }
    }

    @Test public void sequentialStagesShareOneDeadlineInsteadOfResettingIt() throws Exception {
        List<Long> deadlines = new CopyOnWriteArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder().eventListener(new EventListener() {
            @Override public void callStart(Call call) { deadlines.add(TimeUnit.NANOSECONDS.toMillis(call.timeout().timeoutNanos())); }
        }).build();
        try (var fixture = new Fixture(2, 100); var scope = new SearchRequestScope(client, 1000)) {
            assertEquals("ok", scope.execute(fixture.request()));
            assertThrows(IOException.class, () -> scope.execute(fixture.request(), 80));
            assertEquals(2, deadlines.size());
            assertTrue("The second stage exceeded its optional enrichment allowance", deadlines.get(1) <= 80);
        }
        deadlines.clear();
        try (var fixture = new Fixture(2, 100); var scope = new SearchRequestScope(client, 350)) {
            assertEquals("ok", scope.execute(fixture.request()));
            assertThrows(IOException.class, () -> scope.execute(fixture.request()));
            assertEquals(2, deadlines.size());
            assertTrue("Extension and main search each received a fresh full timeout", deadlines.get(1) <= deadlines.get(0) - 70);
        }
    }

    @Test public void interruptedCallerFailsBeforeOpeningAnotherHttpCall() throws Exception {
        try (var fixture = new Fixture(1, 0); var scope = new SearchRequestScope(new OkHttpClient(), 3000)) {
            Thread.currentThread().interrupt();
            try {
                scope.execute(fixture.request());
                fail("Interrupted search started an HTTP call");
            } catch (InterruptedIOException expected) {
                assertEquals("Search cancelled", expected.getMessage());
                assertEquals(0, fixture.requests.get());
            } finally {
                Thread.interrupted();
            }
        }
    }
}
