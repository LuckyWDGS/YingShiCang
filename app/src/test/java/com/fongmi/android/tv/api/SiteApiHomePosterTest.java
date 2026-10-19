package com.fongmi.android.tv.api;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** Home poster lookup is optional and must not take down an otherwise valid feed. */
public class SiteApiHomePosterTest {
    @Test public void notFoundPosterEndpointKeepsHomeRows() throws Exception {
        try (Fixture fixture = new Fixture(404, "not found")) {
            Result home = home();
            Vod original = home.getList().get(0);
            assertSame(home, SiteApi.fetchPic(fixture.site(), home));
            assertSame(original, home.getList().get(0));
            assertEquals("title", home.getList().get(0).getName());
        }
    }

    @Test public void emptyPosterEndpointKeepsHomeRows() throws Exception {
        try (Fixture fixture = new Fixture(200, "  \n\t")) {
            Result home = home();
            assertSame(home, SiteApi.fetchPic(fixture.site(), home));
            assertEquals(1, home.getList().size());
            assertEquals("id", home.getList().get(0).getId());
        }
    }

    @Test public void malformedPosterEndpointKeepsHomeRows() throws Exception {
        try (Fixture fixture = new Fixture(200, "{}")) {
            Result home = home();
            SiteApi.fetchPic(fixture.site(), home);
            assertEquals(1, home.getList().size());
            assertEquals("id", home.getList().get(0).getId());
        }
    }

    @Test public void nullHomeRowsDoNotCrashOrClearTheFeed() throws Exception {
        Vod original = home().getList().get(0);
        Result home = Result.list(Arrays.asList(null, original));
        Site site = new Site() { @Override public Integer getType() { return 1; } };
        assertSame(home, SiteApi.fetchPic(site, home));
        assertEquals(2, home.getList().size());
        assertSame(original, home.getList().get(1));
    }

    @Test public void searchPosterLookupDoesNotClearRowsWithoutQueryableIds() throws Exception {
        Vod original = home().getList().get(0);
        Result search = Result.list(Arrays.asList(original));
        Site site = new Site() {
            @Override public Integer getType() { return 1; }
            @Override public List<String> getCategories() { return List.of("Other"); }
        };
        try (SearchRequestScope scope = new SearchRequestScope(2000)) {
            assertSame(search, SiteApi.fetchSearchPic(site, search, scope));
        }
        assertEquals(1, search.getList().size());
        assertSame(original, search.getList().get(0));
    }

    @Test public void searchPosterLookupSkipsNullRows() throws Exception {
        Vod original = home().getList().get(0);
        Result search = Result.list(Arrays.asList(null, original));
        Site site = new Site() { @Override public Integer getType() { return 1; } };
        try (SearchRequestScope scope = new SearchRequestScope(2000)) {
            assertSame(search, SiteApi.fetchSearchPic(site, search, scope));
        }
        assertEquals(2, search.getList().size());
        assertSame(original, search.getList().get(1));
    }

    private static Result home() {
        Vod item = new Vod() {
            @Override public String getId() { return "id"; }
            @Override public String getName() { return "title"; }
            @Override public String getPic() { return ""; }
            @Override public String getTypeName() { return ""; }
        };
        return Result.list(List.of(item));
    }

    private static final class Fixture implements AutoCloseable {
        private final ServerSocket server;
        private final Thread worker;
        private final int status;
        private final byte[] body;

        Fixture(int status, String body) throws IOException {
            this.status = status;
            this.body = body.getBytes(StandardCharsets.UTF_8);
            server = new ServerSocket();
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            worker = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    InputStream in = socket.getInputStream();
                    int previous = -1, current, end = 0;
                    while ((current = in.read()) != -1) {
                        if (previous == '\r' && current == '\n') end++;
                        else if (previous != '\r') end = 0;
                        if (end == 2) break;
                        previous = current;
                    }
                    OutputStream out = socket.getOutputStream();
                    String reason = status == 200 ? "OK" : "Not Found";
                    out.write(("HTTP/1.1 " + status + " " + reason + "\r\n"
                            + "Content-Length: " + this.body.length + "\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(this.body);
                    out.flush();
                } catch (IOException ignored) {
                    // Closing the fixture while a client is still connecting is fine.
                }
            }, "site-api-home-poster-fixture");
            worker.setDaemon(true);
            worker.start();
        }

        Site site() {
            String api = "http://127.0.0.1:" + server.getLocalPort() + "/api";
            return new Site() {
                @Override public Integer getType() { return 1; }
                @Override public String getApi() { return api; }
                @Override public long getTimeout() { return 2000; }
            };
        }

        @Override public void close() throws IOException {
            server.close();
            try { worker.join(1000); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
