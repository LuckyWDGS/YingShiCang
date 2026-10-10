package com.fongmi.android.tv.api.compat;

import com.fongmi.android.tv.api.SearchRequestScope;
import com.github.catvod.crawler.Spider;
import com.github.catvod.spider.BaseSpiderGuard;
import com.github.catvod.spider.SixV;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutionException;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

import static org.junit.Assert.*;

public class SixVSearchCompatibilityTest {
    private final HttpUrl base = HttpUrl.get("https://fixture.invalid/");

    private String fixture(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test public void derivesCurrentPostFieldsAndRejectsUntrustedForms() throws Exception {
        var form = SixVSearchCompatibility.form(base, fixture("sixv-home.html"));
        assertEquals("https://fixture.invalid/e/search/so.php", form.action.toString());
        assertEquals("title", form.fields.get("show"));
        assertEquals("article", form.fields.get("tbname"));
        assertEquals("1", form.fields.get("tempid"));
        assertFalse(form.fields.containsKey("keyboard"));
        String[] invalid = {
                fixture("sixv-home.html").replace("/e/search/so.php", "https://elsewhere.invalid/e/search/so.php"),
                fixture("sixv-home.html").replace("/e/search/so.php", "javascript:alert(1)"),
                fixture("sixv-home.html").replace("/e/search/so.php", "/login"),
                fixture("sixv-home.html").replace("value=\"title\"", "value=\"other\""), "<html>broken</html>"};
        for (String html : invalid) {
            try { SixVSearchCompatibility.form(base, html); fail("Unsafe or incompatible form accepted"); }
            catch (IOException expected) { assertFalse(expected.getMessage().contains("elsewhere")); }
        }
    }

    @Test public void currentThreeKeywordStructuresPreserveDetailIdentityAndTitle() throws Exception {
        String[] files = {"sixv-search-three.html", "sixv-search-earth.html", "sixv-search-anime.html"};
        int[] counts = {3, 3, 20};
        for (int i=0;i<files.length;i++) {
            var result=SixVSearchCompatibility.results(base,fixture(files[i]));
            var rows=result.getAsJsonArray("list");
            assertEquals(counts[i],rows.size());
            var first=rows.get(0).getAsJsonObject();
            assertEquals("/dianshiju/guoju/10000.html",first.get("vod_id").getAsString());
            assertEquals("Fixture 1[全集]",first.get("vod_name").getAsString());
            assertEquals("https://covers.fixture.invalid/1.jpg",first.get("vod_pic").getAsString());
            assertEquals("电视剧",first.get("type_name").getAsString());
        }
    }

    @Test public void doesNotConvertChallengeOrMalformedCardsIntoNoMatch() throws Exception {
        String[] invalid = {"<html>captcha</html>","<div class='mainleft'><ul class='masonry'><li class='post'>broken</li></ul></div>",
                fixture("sixv-search-three.html").replace("/dianshiju/guoju/", "https://elsewhere.invalid/dianshiju/guoju/")};
        for(String html : invalid) {
            try { SixVSearchCompatibility.results(base,html); fail("Invalid schema treated as successful empty result"); }
            catch(IOException expected) { assertNull(expected.getCause()); }
        }
        assertEquals(0,SixVSearchCompatibility.results(base,"<div class='mainleft'><ul class='masonry'></ul></div>").getAsJsonArray("list").size());
    }

    @Test public void fallbackDoesNotOverwriteHitsErrorsOrOtherPages() {
        assertTrue(SixVSearchCompatibility.emptyFirstPage("{\"list\":[]}","1"));
        assertTrue(SixVSearchCompatibility.emptyFirstPage("", "1"));
        assertFalse(SixVSearchCompatibility.emptyFirstPage("{\"list\":[{}]}","1"));
        assertFalse(SixVSearchCompatibility.emptyFirstPage("{\"error\":500}","1"));
        assertFalse(SixVSearchCompatibility.emptyFirstPage("{\"error\":500,\"list\":[]}","1"));
        assertFalse(SixVSearchCompatibility.emptyFirstPage("{\"code\":500,\"list\":[]}","1"));
        assertFalse(SixVSearchCompatibility.emptyFirstPage("{\"status\":\"403\",\"list\":[]}","1"));
        assertTrue(SixVSearchCompatibility.emptyFirstPage("{\"code\":1,\"list\":[]}","1"));
        assertFalse(SixVSearchCompatibility.emptyFirstPage("<html>failure</html>","1"));
        assertFalse(SixVSearchCompatibility.emptyFirstPage("{\"list\":[]}","2"));
        assertFalse(SixVSearchCompatibility.supports(null,"https://fixture.invalid/"));
        assertNull(SixVSearchCompatibility.base("https://user:secret@fixture.invalid/"));
        assertNull(SixVSearchCompatibility.base("https://fixture.invalid/?token=secret"));

    }

    @Test public void protocolGateAcceptsOnlySixVAndProvenTransparentGuards() {
        String origin = "https://fixture.invalid/";
        assertTrue(SixVSearchCompatibility.supports(new SixV(), origin));
        assertTrue(SixVSearchCompatibility.supports(new BaseSpiderGuard(new SixV()), origin));
        assertTrue(SixVSearchCompatibility.supports(new BaseSpiderGuard(new BaseSpiderGuard(new SixV())), origin));
        assertFalse(SixVSearchCompatibility.supports(new BaseSpiderGuard(new Spider() {}), origin));

        class UnknownWrapper extends Spider {
            private final Spider unrelated = new SixV();
            @Override public String searchContent(String key, boolean quick) { return ""; }
        }
        class OverridingGuard extends BaseSpiderGuard {
            OverridingGuard() { super(new SixV()); }
            @Override public String searchContent(String key, boolean quick) { return ""; }
        }
        assertFalse(SixVSearchCompatibility.supports(new UnknownWrapper(), origin));
        assertFalse(SixVSearchCompatibility.supports(new OverridingGuard(), origin));
    }

    @Test public void ambiguousUnrelatedAndCyclicDelegatesCannotSelectProtocol() {
        String origin = "https://fixture.invalid/";
        class AmbiguousGuard extends BaseSpiderGuard {
            private final Spider another = new SixV();
            AmbiguousGuard() { super(new SixV()); }
        }
        class UnrelatedFieldGuard extends BaseSpiderGuard {
            private final Spider unused = new SixV();
            UnrelatedFieldGuard() { super(new Spider() {}); }
        }
        assertFalse(SixVSearchCompatibility.supports(new AmbiguousGuard(), origin));
        assertFalse(SixVSearchCompatibility.supports(new UnrelatedFieldGuard(), origin));
        assertFalse(SixVSearchCompatibility.supports(new BaseSpiderGuard(null), origin));
        BaseSpiderGuard cycle = new BaseSpiderGuard(null);
        cycle.setDelegate(cycle);
        assertFalse(SixVSearchCompatibility.supports(cycle, origin));
        Spider deep = new SixV();
        for (int i = 0; i < 8; i++) deep = new BaseSpiderGuard(deep);
        assertFalse(SixVSearchCompatibility.supports(deep, origin));
    }

    private static final class StaticFieldGuard extends BaseSpiderGuard {
        private static final Spider unused = new Spider() {};
        StaticFieldGuard() { super(new SixV()); }
    }

    @Test public void unrelatedStaticFieldsDoNotChangeProvenGuardIdentity() {
        assertTrue(SixVSearchCompatibility.supports(new StaticFieldGuard(), "https://fixture.invalid/"));
    }

    @Test public void performsFormSearchAndSharesCancellation() throws Exception {
        try(ServerSocket server=new ServerSocket(0)) {
            ExecutorService executor=Executors.newCachedThreadPool();
            CountDownLatch postReceived=new CountDownLatch(1);
            AtomicReference<String> actualBody=new AtomicReference<>();
            String home=fixture("sixv-home.html"), results=fixture("sixv-search-three.html");
            Future<?> fixtureServer=executor.submit(()->{
                try {
                    for(int i=0;i<2;i++) try(Socket socket=server.accept()) {
                        socket.setSoTimeout(2000);
                        InputStream input=socket.getInputStream();
                        StringBuilder head=new StringBuilder();
                        while(!head.toString().endsWith("\r\n\r\n")) { int b=input.read();if(b<0)break;head.append((char)b); }
                        if(i==1) {
                            int length=0;
                            for(String line:head.toString().split("\r\n")) if(line.toLowerCase().startsWith("content-length:")) length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                            actualBody.set(new String(input.readNBytes(length),StandardCharsets.UTF_8));postReceived.countDown();
                        }
                        byte[] bytes=(i==0?home:results).getBytes(StandardCharsets.UTF_8);
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "+bytes.length+"\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(bytes);
                    }
                }catch(Exception error){throw new RuntimeException(error);}
            });
            try(SearchRequestScope scope=new SearchRequestScope(new OkHttpClient(),5000)) {
                String body=SixVSearchCompatibility.search("http://127.0.0.1:"+server.getLocalPort()+"/", "三体 & A",scope);
                assertEquals(3,JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("list").size());
                assertTrue(postReceived.await(1,TimeUnit.SECONDS));
                assertTrue(actualBody.get().contains("keyboard="+URLEncoder.encode("三体 & A",StandardCharsets.UTF_8)));
            } finally {executor.shutdownNow();}
            fixtureServer.get(2,TimeUnit.SECONDS);
        }
        try(SearchRequestScope scope=new SearchRequestScope(new OkHttpClient(),5000)) {
            scope.close();
            try {SixVSearchCompatibility.search("https://fixture.invalid/","三体",scope);fail("Cancelled repair ran network request");}
            catch(java.io.InterruptedIOException expected) {assertEquals("Search cancelled",expected.getMessage());}
        }
    }

    @Test public void cancellationAbortsInFlightSearchHttp() throws Exception {
        try(ServerSocket server=new ServerSocket(0)) {
            CountDownLatch received=new CountDownLatch(1), release=new CountDownLatch(1);
            ExecutorService executor=Executors.newCachedThreadPool();
            Future<?> fixtureServer=executor.submit(()->{
                try(Socket socket=server.accept()) {
                    InputStream input=socket.getInputStream();StringBuilder head=new StringBuilder();
                    while(!head.toString().endsWith("\r\n\r\n")) {int b=input.read();if(b<0)break;head.append((char)b);}
                    received.countDown();release.await(2,TimeUnit.SECONDS);
                }catch(Exception error){throw new RuntimeException(error);}
            });
            String origin="http://127.0.0.1:"+server.getLocalPort()+"/";
            try(SearchRequestScope scope=new SearchRequestScope(new OkHttpClient(),5000)) {
                Future<String> pending=executor.submit(()->SixVSearchCompatibility.search(origin,"三体",scope));
                assertTrue(received.await(1,TimeUnit.SECONDS));scope.close();
                try{pending.get(1,TimeUnit.SECONDS);fail("Cancelled request did not abort");}
                catch(ExecutionException expected){assertTrue(expected.getCause() instanceof IOException);}
            } finally {release.countDown();fixtureServer.get(2,TimeUnit.SECONDS);executor.shutdownNow();}
        }
    }
}
