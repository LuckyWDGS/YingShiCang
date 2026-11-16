package com.fongmi.android.tv.player.exo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class SourceRefusalTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test public void thePluginsLocalServerAnsweringAnErrorIsTheSourcesFailure() {
        // Phone 2026-11-16: 127.0.0.1:6677/proxy answered 500 with this body to every 短剧漫剧 episode.
        SourceRefusal refusal = SourceRefusal.of("127.0.0.1", 6677, 500, bytes("End of input at character 0 of \n"));
        assertEquals(500, refusal.status());
        assertEquals("source=local6677 status=500 text=End_of_input_at_character_0_of", refusal.log());
    }

    @Test public void theAppsOwnProxyCountsToo() {
        assertEquals("source=local9978 status=404", SourceRefusal.of("localhost", 9978, 404, null).log());
        assertEquals("source=local status=502", SourceRefusal.of("127.0.0.1", -1, 502, new byte[0]).log());
    }

    @Test public void aServerOnTheNetworkIsNotTheSourcesLocalServer() {
        assertNull(SourceRefusal.of("cdn.example.com", 443, 500, bytes("oops")));
        assertNull(SourceRefusal.of("192.168.1.8", 8080, 500, bytes("oops")));
        assertNull(SourceRefusal.of(null, 80, 500, null));
    }

    @Test public void anAnswerThatIsNotAnErrorIsNotARefusal() {
        assertNull(SourceRefusal.of("127.0.0.1", 6677, 302, null));
    }

    @Test public void whatTheServerSaidIsLoggedWithoutAddressesOrIds() {
        String log = SourceRefusal.of("127.0.0.1", 6677, 500,
                bytes("请求失败 https://api.example.com/play?vid=7480012345678901234&token=abcdef0123456789 vid=7480012345678901234")).log();
        assertEquals("source=local6677 status=500 text=请求失败_…_…", log);
    }

    @Test public void aBodyWithNothingReadableLeavesNoText() {
        assertEquals("source=local6677 status=500", SourceRefusal.of("127.0.0.1", 6677, 500, bytes("7480012345678901234")).log());
        assertEquals("source=local6677 status=500", SourceRefusal.of("127.0.0.1", 6677, 500, bytes("   ")).log());
    }
}
