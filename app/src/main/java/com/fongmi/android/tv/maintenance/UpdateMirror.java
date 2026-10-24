package com.fongmi.android.tv.maintenance;

import com.fongmi.android.tv.BuildConfig;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;

import javax.net.ssl.X509TrustManager;

import okhttp3.Dns;
import okhttp3.HttpUrl;

/**
 * Optional copy of the latest GitHub Release on the publisher's own server (service/update_mirror).
 * GitHub stays the source of truth and the fallback: the mirror serves GitHub's own release answer and files,
 * which are checked against GitHub's declared sizes and SHA-256 digests as before. The connection trusts only
 * the pinned server key, so it needs no domain or certificate authority and ignores a wrong device clock.
 * The URL host may be a private name that only selects the mirror's site on a shared server (TLS SNI);
 * UPDATE_MIRROR_ADDRESS then supplies the server's IP instead of public DNS.
 */
public final class UpdateMirror {
    private static final String RELEASE_DOWNLOAD = "https://github.com/LuckyWDGS/YingShiCang/releases/download/";
    private static final byte[] PIN = pin(BuildConfig.UPDATE_MIRROR_PIN);
    private static final HttpUrl BASE = PIN == null ? null : base(BuildConfig.UPDATE_MIRROR_URL);

    private static final String ADDRESS = BuildConfig.UPDATE_MIRROR_ADDRESS.trim();

    private UpdateMirror() {}

    public static boolean enabled() {
        return BASE != null;
    }

    public static String latestUrl() {
        return BASE == null ? null : BASE + "latest.json";
    }

    /** The mirror copy of a published GitHub asset, or null when there is no mirror or the URL is not one. */
    public static String assetUrl(String githubUrl) {
        return assetUrl(BASE, githubUrl);
    }

    static String assetUrl(HttpUrl base, String githubUrl) {
        if (base == null || githubUrl == null || !githubUrl.startsWith(RELEASE_DOWNLOAD)) return null;
        String path = githubUrl.substring(RELEASE_DOWNLOAD.length());
        // tag/file, both already validated by GitHubRelease; refuse anything else.
        if (!path.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+/[A-Za-z0-9][A-Za-z0-9._-]{0,199}") || path.contains("..")) return null;
        return base + "download/" + path;
    }

    static boolean owns(String url) {
        return owns(BASE, url == null ? null : HttpUrl.parse(url));
    }

    static boolean owns(HttpUrl base, HttpUrl url) {
        return base != null && url != null && url.isHttps() && url.host().equals(base.host()) && url.port() == base.port()
                && url.encodedPath().startsWith(base.encodedPath()) && url.username().isEmpty() && url.password().isEmpty();
    }

    static HttpUrl base(String value) {
        HttpUrl url = value == null ? null : HttpUrl.parse(value.trim());
        if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null || !url.encodedPath().endsWith("/")) return null;
        return url;
    }

    /** SHA-256 of the server's SubjectPublicKeyInfo, as 64 hex characters. */
    static byte[] pin(String value) {
        String hex = value == null ? "" : value.trim();
        if (!hex.matches("[0-9a-fA-F]{64}")) return null;
        byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    /** Resolves the mirror's host to the configured address; every other name uses the system resolver. */
    static Dns dns() {
        return dns(BASE == null ? null : BASE.host(), ADDRESS);
    }

    static Dns dns(String host, String address) {
        return hostname -> {
            if (host == null || address.isEmpty() || !hostname.equals(host)) return Dns.SYSTEM.lookup(hostname);
            if (!address.matches("[0-9.]+|[0-9A-Fa-f:]+")) throw new UnknownHostException("更新镜像地址无效");
            // A literal IP address never triggers a DNS query.
            return Collections.singletonList(InetAddress.getByName(address));
        };
    }

    static X509TrustManager trustManager() {
        return trustManager(PIN);
    }

    /** Accepts exactly the server key whose SubjectPublicKeyInfo SHA-256 is pinned. */
    static X509TrustManager trustManager(byte[] pin) {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("client certificates are not used");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (pin == null || chain == null || chain.length == 0) throw new CertificateException("更新镜像证书无效");
                try {
                    byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getPublicKey().getEncoded());
                    if (!MessageDigest.isEqual(actual, pin)) throw new CertificateException("更新镜像证书不匹配");
                } catch (NoSuchAlgorithmException error) {
                    throw new CertificateException(error);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
