package com.fongmi.android.tv.maintenance;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;
import okhttp3.HttpUrl;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateMirrorTest {
    private static final String RELEASE = "https://github.com/LuckyWDGS/YingShiCang/releases/download/";
    // Self-signed test-only certificate (CN=ysc-mirror-test) and its SubjectPublicKeyInfo SHA-256.
    private static final String CERT = "-----BEGIN CERTIFICATE-----\n"
            + "MIIBjDCCATGgAwIBAgIUC01RxPKf3rN3hwS6xwwnP7Nm8UkwCgYIKoZIzj0EAwIw\n"
            + "GjEYMBYGA1UEAwwPeXNjLW1pcnJvci10ZXN0MCAXDTI2MTAyNDE0MjIwM1oYDzIx\n"
            + "MjYwOTMwMTQyMjAzWjAaMRgwFgYDVQQDDA95c2MtbWlycm9yLXRlc3QwWTATBgcq\n"
            + "hkjOPQIBBggqhkjOPQMBBwNCAAQjp6WbGfS+dYEu69cvZEG+90SKWaMn/qiW/LEJ\n"
            + "7lm7ReIxi/6d7jyq35HYqVj39TuP/b6dRkzobDK176zmeqpyo1MwUTAdBgNVHQ4E\n"
            + "FgQUz9/yVThKHikyPddcvV7xnw2m5vgwHwYDVR0jBBgwFoAUz9/yVThKHikyPddc\n"
            + "vV7xnw2m5vgwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNJADBGAiEAtnY3\n"
            + "D06OCB0s4mTdbXrdqJCoy7tF3XbYu4H/ZJqMTgECIQDOh7x37ZUy5n3Q21K3tuzD\n"
            + "hV41OWVIKfb2Fk+mqIPCQw==\n"
            + "-----END CERTIFICATE-----\n";
    private static final String PIN = "093a8e137696bca4e37554f9364348a754ce5fa7e9286f0f7b994cfc9a1174c4";

    @Test public void acceptsOnlyHttpsBaseDirectories() {
        assertNotNull(UpdateMirror.base("https://203.0.113.5:18443/ysc/"));
        assertNotNull(UpdateMirror.base("https://updates.example/"));
        for (String url : new String[]{null, "", "http://203.0.113.5:18443/ysc/", "https://203.0.113.5:18443/ysc",
                "https://user:pass@updates.example/ysc/", "https://updates.example/ysc/?a=1", "not a url"})
            assertNull(url, UpdateMirror.base(url));
    }

    @Test public void mapsOnlyPublishedReleaseAssets() {
        HttpUrl base = UpdateMirror.base("https://203.0.113.5:18443/ysc/");
        assertEquals("https://203.0.113.5:18443/ysc/download/v1.9.0/YingShiCang-1.9.0-tv-arm64-v8a.apk",
                UpdateMirror.assetUrl(base, RELEASE + "v1.9.0/YingShiCang-1.9.0-tv-arm64-v8a.apk"));
        assertEquals("https://203.0.113.5:18443/ysc/download/v1.9.0/release-manifest.json",
                UpdateMirror.assetUrl(base, RELEASE + "v1.9.0/release-manifest.json"));
        for (String url : new String[]{null, "https://evil.example/v1.9.0/a.apk", RELEASE + "v1.9.0/../x.apk",
                RELEASE + "latest/a.apk", RELEASE + "v1.9.0/sub/a.apk", RELEASE + "v1.9.0/a.apk?x=1",
                "https://github.com/Other/Repo/releases/download/v1.9.0/a.apk"})
            assertNull(url, UpdateMirror.assetUrl(base, url));
        assertNull(UpdateMirror.assetUrl(null, RELEASE + "v1.9.0/release-manifest.json"));
    }

    @Test public void ownsOnlyItsOwnHostPortAndPath() {
        HttpUrl base = UpdateMirror.base("https://203.0.113.5:18443/ysc/");
        assertTrue(UpdateMirror.owns(base, HttpUrl.parse("https://203.0.113.5:18443/ysc/latest.json")));
        for (String url : new String[]{"https://203.0.113.5:8443/ysc/latest.json", "https://203.0.113.6:18443/ysc/latest.json",
                "https://203.0.113.5:18443/other/latest.json", "https://api.github.com/repos/x/y/releases/latest"})
            assertFalse(url, UpdateMirror.owns(base, HttpUrl.parse(url)));
        assertFalse(UpdateMirror.owns(null, HttpUrl.parse("https://203.0.113.5:18443/ysc/latest.json")));
    }

    @Test public void resolvesOnlyTheMirrorNameToTheConfiguredAddress() throws Exception {
        assertEquals(List.of(InetAddress.getByName("203.0.113.5")),
                UpdateMirror.dns("ysc-update.invalid", "203.0.113.5").lookup("ysc-update.invalid"));
        assertThrows(UnknownHostException.class, () -> UpdateMirror.dns("ysc-update.invalid", "evil.example").lookup("ysc-update.invalid"));
        // Other names go to the system resolver (a literal address resolves offline to itself).
        assertEquals(List.of(InetAddress.getByName("198.51.100.7")),
                UpdateMirror.dns("ysc-update.invalid", "203.0.113.5").lookup("198.51.100.7"));
        assertEquals(List.of(InetAddress.getByName("198.51.100.7")), UpdateMirror.dns(null, "203.0.113.5").lookup("198.51.100.7"));
    }

    @Test public void parsesHexPins() {
        assertEquals(32, UpdateMirror.pin(PIN).length);
        assertEquals(32, UpdateMirror.pin(" " + PIN.toUpperCase() + " ").length);
        for (String pin : new String[]{null, "", PIN.substring(2), PIN + "00", "z" + PIN.substring(1)})
            assertNull(UpdateMirror.pin(pin));
    }

    @Test public void trustsExactlyThePinnedKey() throws Exception {
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(CERT.getBytes(StandardCharsets.US_ASCII)));
        UpdateMirror.trustManager(UpdateMirror.pin(PIN)).checkServerTrusted(new X509Certificate[]{cert}, "ECDHE_ECDSA");
        X509TrustManager other = UpdateMirror.trustManager(UpdateMirror.pin(PIN.replace('0', '1')));
        assertThrows(CertificateException.class, () -> other.checkServerTrusted(new X509Certificate[]{cert}, "ECDHE_ECDSA"));
        X509TrustManager none = UpdateMirror.trustManager(null);
        assertThrows(CertificateException.class, () -> none.checkServerTrusted(new X509Certificate[]{cert}, "ECDHE_ECDSA"));
        assertThrows(CertificateException.class,
                () -> UpdateMirror.trustManager(UpdateMirror.pin(PIN)).checkServerTrusted(new X509Certificate[0], "ECDHE_ECDSA"));
    }
}
