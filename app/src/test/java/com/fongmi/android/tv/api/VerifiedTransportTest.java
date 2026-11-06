package com.fongmi.android.tv.api;

import com.github.catvod.net.OkHttp;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.BeforeClass;
import org.junit.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import static org.junit.Assert.*;

/** Real local TLS handshakes: no external network or user's credentials. */
public class VerifiedTransportTest {
    private static SSLContext serverContext, trustedContext;
    private static X509TrustManager fixtureTrust;

    @BeforeClass public static void certificate() throws Exception {
        Path dir = Files.createTempDirectory("ysc-tls-test-");
        Path store = dir.resolve("test.p12");
        try {
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                    "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
                    "-keystore", store.toString(), "-storepass", "test-password", "-keypass", "test-password")
                    .redirectErrorStream(true).redirectOutput(dir.resolve("keytool.log").toFile()).start();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(store)) { keys.load(input, "test-password".toCharArray()); }
            KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(keys, "test-password".toCharArray());
            TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tm.init(keys);
            fixtureTrust = (X509TrustManager) tm.getTrustManagers()[0];
            serverContext = SSLContext.getInstance("TLS");
            serverContext.init(km.getKeyManagers(), null, null);
            trustedContext = SSLContext.getInstance("TLS");
            trustedContext.init(null, new TrustManager[]{fixtureTrust}, null);
        } finally { Files.deleteIfExists(store); Files.deleteIfExists(dir.resolve("keytool.log")); Files.deleteIfExists(dir); }
    }

    private static void request(OkHttpClient client, String host, String response) throws Exception {
        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket(0)) {
            Thread thread = new Thread(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.setSoTimeout(3000);
                    socket.startHandshake();
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                    for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {}
                    socket.getOutputStream().write(response.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                } catch (IOException expected) {}
            });
            thread.setDaemon(true); thread.start();
            try (var result = client.newBuilder().proxy(java.net.Proxy.NO_PROXY)
                    .dns(hostname -> java.util.List.of(java.net.InetAddress.getByName("127.0.0.1")))
                    .fastFallback(false).retryOnConnectionFailure(false)
                    .callTimeout(10, TimeUnit.SECONDS).build().newCall(new Request.Builder()
                            .url("https://" + host + ":" + server.getLocalPort() + "/config").build()).execute()) {
                assertEquals(200, result.code());
            }
        }
    }

    @Test public void rejectsUntrustedCertificate() {
        assertThrows(SSLHandshakeException.class, () -> request(OkHttp.verified(), "localhost", "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"));
    }

    @Test public void validatesHostnameEvenForTrustedCertificate() {
        var client = OkHttp.verified().newBuilder().sslSocketFactory(trustedContext.getSocketFactory(), fixtureTrust).build();
        assertThrows(SSLPeerUnverifiedException.class, () -> request(client, "127.0.0.1", "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"));
    }

    @Test public void validCertificateAndHostnameWork() throws Exception {
        var client = OkHttp.verified().newBuilder().sslSocketFactory(trustedContext.getSocketFactory(), fixtureTrust).build();
        request(client, "localhost", "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
    }

    @Test public void httpsRedirectCannotSendRequestOverPlainHttp() throws Exception {
        var client = OkHttp.verified().newBuilder().sslSocketFactory(trustedContext.getSocketFactory(), fixtureTrust).build();
        var received = new java.util.concurrent.atomic.AtomicInteger(-1);
        try (var clear = new java.net.ServerSocket(0)) {
            clear.setSoTimeout(3000);
            Thread listener = new Thread(() -> {
                try (var socket = clear.accept()) {
                    socket.setSoTimeout(3000);
                    received.set(socket.getInputStream().read());
                } catch (IOException expected) {}
            });
            listener.setDaemon(true);
            listener.start();
            assertThrows(SSLPeerUnverifiedException.class, () -> request(client, "localhost",
                    "HTTP/1.1 302 Found\r\nLocation: http://localhost:" + clear.getLocalPort() + "/plugin\r\nContent-Length: 0\r\n\r\n"));
            listener.join(4000);
            assertEquals(-1, received.get());
        }
    }
}
