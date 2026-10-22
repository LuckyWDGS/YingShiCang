package com.github.catvod.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Protocol;
import okhttp3.Response;

/**
 * Opt-in timing of the shared client's calls for uploaded diagnostics. While a {@link Recording} is
 * open, every call made through the shared client (from any thread, plugins included) is summarized
 * as a host label, durations and an outcome. Hosts are reduced to well-known cloud-drive labels or
 * "other", and messages to fixed keywords, so no URL, query, cookie or source address is recorded.
 */
public final class NetTrace extends EventListener {

    private static final int MAX_ENTRIES = 64;
    private static final int SLOWEST = 4;
    private static final List<Recording> ACTIVE = new CopyOnWriteArrayList<>();
    private static final String[][] HOSTS = {
            {"quark.cn", "quark"}, {"uc.cn", "uc"}, {"baidu.com", "baidu"}, {"baidupcs.com", "baidu"}, {"bdstatic.com", "baidu"},
            {"aliyundrive.com", "ali"}, {"alipan.com", "ali"}, {"aliyuncs.com", "ali"}, {"115.com", "115"}, {"115cdn.net", "115"},
            {"123pan.com", "123"}, {"123pan.cn", "123"}, {"123684.com", "123"}, {"xunlei.com", "xunlei"}, {"189.cn", "tianyi"},
            {"139.com", "yidong"}, {"10086.cn", "yidong"}, {"github.com", "github"}, {"githubusercontent.com", "github"}};

    /** Every shared-client call; costs nothing while no recording is open. */
    public static final EventListener.Factory FACTORY = call -> ACTIVE.isEmpty() ? EventListener.NONE : new NetTrace(call, new ArrayList<>(ACTIVE));

    private final List<Recording> recordings;
    private final String host;
    private final long start = System.nanoTime();
    private long dnsStart, dnsMs = -1, connectStart, connectMs = -1;
    private int code = -1;
    private String protocol = "";

    private NetTrace(Call call, List<Recording> recordings) {
        this.recordings = recordings;
        this.host = label(call.request().url().host());
    }

    public static Recording begin() {
        Recording recording = new Recording();
        ACTIVE.add(recording);
        return recording;
    }

    /** Coarse host label: a known cloud-drive family, "ip" for a literal address, otherwise "other". */
    static String label(String host) {
        if (host == null || host.isEmpty()) return "other";
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("127.") || value.equals("localhost")) return "local";
        if (value.matches("[0-9.]+") || value.contains(":")) return "ip";
        for (String[] item : HOSTS) if (value.equals(item[0]) || value.endsWith("." + item[0])) return item[1];
        return "other";
    }

    /** Exception class plus a fixed keyword for its message; the message itself may hold a URL. */
    public static String reason(Throwable error) {
        if (error == null) return "none";
        String name = error.getClass().getSimpleName();
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        String keyword = message.contains("unexpected end of stream") ? "eos"
                : message.contains("timeout") || message.contains("timed out") ? "timeout"
                : message.contains("canceled") || message.contains("cancelled") ? "canceled"
                : message.contains("reset") ? "reset"
                : message.contains("refused") ? "refused"
                : message.contains("abort") ? "abort"
                : message.contains("unable to resolve") || message.contains("no address") ? "dns"
                : message.contains("closed") ? "closed"
                : message.contains("handshake") || message.contains("certificate") ? "tls"
                : message.isEmpty() ? "" : readable(error.getMessage());
        return keyword.isEmpty() ? name : name + "(" + keyword + ")";
    }

    /**
     * A plugin's own error text (such as why a drive refused a file), in any language, with everything that
     * could identify a person or a request replaced by an ellipsis: URLs, bracketed titles, any 7+ character
     * run that contains a digit (tokens, phone numbers, ids) and anything with @, = or / (emails, key=value,
     * paths). A message that is nothing but such parts is "other".
     */
    static String readable(String message) {
        String value = message.trim();
        value = value.replaceAll("(?i)https?://\\S+", "…");
        value = value.replaceAll("《[^》]*》|「[^」]*」|“[^”]*”|\"[^\"]*\"", "…");
        value = value.replaceAll("[A-Za-z0-9._~+%-]*[0-9][A-Za-z0-9._~+%-]*", "\u0000$0\u0000");
        StringBuilder kept = new StringBuilder();
        for (String part : value.split("\u0000", -1)) kept.append(part.length() >= 7 && part.matches(".*[0-9].*") ? "…" : part);
        value = kept.toString().replaceAll("\\S*[@=/\\\\]\\S*", "…");
        value = value.replaceAll("\\s+", "_");
        if (value.replace("…", "").replace("_", "").trim().isEmpty()) return "other";
        return value.length() > 60 ? value.substring(0, 60) + "…" : value;
    }

    @Override
    public void dnsStart(Call call, String domainName) {
        dnsStart = System.nanoTime();
    }

    @Override
    public void dnsEnd(Call call, String domainName, List<InetAddress> list) {
        dnsMs = ms(dnsStart);
    }

    @Override
    public void connectStart(Call call, InetSocketAddress address, Proxy proxy) {
        connectStart = System.nanoTime();
    }

    @Override
    public void connectEnd(Call call, InetSocketAddress address, Proxy proxy, Protocol protocol) {
        connectMs = ms(connectStart);
        if (protocol != null) this.protocol = protocol.toString();
    }

    @Override
    public void connectFailed(Call call, InetSocketAddress address, Proxy proxy, Protocol protocol, IOException ioe) {
        connectMs = ms(connectStart);
    }

    @Override
    public void responseHeadersEnd(Call call, Response response) {
        code = response.code();
    }

    @Override
    public void callEnd(Call call) {
        finish(code < 0 ? "ok" : String.valueOf(code), false);
    }

    @Override
    public void callFailed(Call call, IOException ioe) {
        finish(reason(ioe), true);
    }

    private void finish(String outcome, boolean failed) {
        Entry entry = new Entry(host, ms(start), dnsMs, connectMs, protocol, outcome, failed);
        for (Recording recording : recordings) recording.add(entry);
    }

    private static long ms(long since) {
        return (System.nanoTime() - since) / 1_000_000;
    }

    private record Entry(String host, long ms, long dns, long connect, String protocol, String outcome, boolean failed) {
        String text() {
            StringBuilder builder = new StringBuilder(host).append(':').append(ms).append("ms:").append(outcome);
            if (dns >= 0) builder.append(":dns").append(dns);
            if (connect >= 0) builder.append(":conn").append(connect);
            if (!protocol.isEmpty()) builder.append(':').append(protocol);
            return builder.toString();
        }
    }

    /** Calls finished while this recording was open. Close it with {@link #end()}. */
    public static final class Recording {
        private final List<Entry> entries = new ArrayList<>();
        private int count, failures;

        private synchronized void add(Entry entry) {
            count++;
            if (entry.failed) failures++;
            if (entries.size() < MAX_ENTRIES) entries.add(entry);
        }

        /** Stops recording and returns e.g. {@code net=5 fail=1 slow=quark:15020ms:SocketTimeoutException(timeout):dns3:conn15002}. */
        public synchronized String end() {
            ACTIVE.remove(this);
            List<Entry> slowest = new ArrayList<>(entries);
            slowest.sort((a, b) -> Long.compare(b.ms, a.ms));
            StringBuilder builder = new StringBuilder("net=").append(count).append(" fail=").append(failures);
            for (int i = 0; i < Math.min(SLOWEST, slowest.size()); i++) builder.append(i == 0 ? " slow=" : ",").append(slowest.get(i).text());
            return builder.toString();
        }
    }
}
