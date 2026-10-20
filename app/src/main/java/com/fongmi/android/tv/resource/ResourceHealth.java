package com.fongmi.android.tv.resource;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.playback.vod.VodPlayRequest;

import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

/** Local observations, never a permanent or shared blacklist. */
public final class ResourceHealth {
    public enum Status { AVAILABLE, INVALID, TRANSIENT }
    public static final long INVALID_TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);
    public static final long TEMPORARY_TTL_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final int MAX_ENTRIES = 2048;
    private static final Map<Key, Long> invalid = new ConcurrentHashMap<>();
    private static final Map<PlaybackKey, Long> failedEpisodes = new ConcurrentHashMap<>();
    private static final Map<Key, Long> detailFailures = new ConcurrentHashMap<>();
    private static final Map<Key, Long> playbackFailures = new ConcurrentHashMap<>();

    private ResourceHealth() {}

    public static boolean isFiltered(long revision, String siteKey, String resourceId) {
        return active(invalid, new Key(revision, clean(siteKey), clean(resourceId)));
    }

    public static int searchPenalty(long revision, String site, String id) {
        Key key = new Key(revision, clean(site), clean(id));
        return active(detailFailures, key) || active(playbackFailures, key) ? 1 : 0;
    }

    public static boolean isSearchCard(Vod item) {
        return item != null && item.getId() != null && !item.getId().trim().isEmpty()
                && item.getName() != null && !item.getName().trim().isEmpty()
                && !item.isFolder() && !item.isAction();
    }

    public static Status recordDetail(String site, String id, Result result, long revision) {
        Status status = detailStatus(result);
        record(new Key(revision, clean(site), clean(id)), status);
        Key key = new Key(revision, clean(site), clean(id));
        if (status == Status.AVAILABLE) detailFailures.remove(key);
        else if (status == Status.TRANSIENT) temporary(detailFailures, key);
        return status;
    }

    public static Status recordDetailFailure(String site, String id, Throwable error, long revision) {
        Status status = failureStatus(error);
        if (isCancellation(error)) return status;
        Key key = new Key(revision, clean(site), clean(id));
        record(key, status);
        if (status == Status.TRANSIENT) temporary(detailFailures, key);
        return status;
    }

    public static Status detailStatus(Result result) {
        if (result == null) return Status.TRANSIENT;
        if (result.hasMsg()) return failureStatus(new IllegalStateException(result.getMsg()));
        if (result.getList().isEmpty()) return Status.INVALID;
        return hasPlayableEntry(result.getVod()) ? Status.AVAILABLE : Status.INVALID;
    }

    public static boolean hasPlayableEntry(Vod vod) {
        if (vod == null) return false;
        for (Flag flag : vod.getFlags()) {
            if (flag == null || flag.getEpisodes() == null) continue;
            for (Episode episode : flag.getEpisodes())
                if (episode != null && !episode.getUrl().trim().isEmpty()) return true;
        }
        return false;
    }

    public static Status playbackStatus(Result result, Throwable error) {
        if (error != null) return failureStatus(error);
        if (result == null) return Status.TRANSIENT;
        if (result.hasMsg()) return failureStatus(new IllegalStateException(result.getMsg()));
        return result.getUrl().isEmpty() ? Status.INVALID : Status.AVAILABLE;
    }

    /** A bad episode/line is not evidence that other episodes or lines are bad. */
    public static void recordPlayback(String site, String id, VodPlayRequest request, List<Flag> flags,
                                      Status status, long revision) {
        if (request == null || !request.getKey().equals(site) || flags == null) return;
        Key resource = new Key(revision, clean(site), clean(id));
        PlaybackKey episode = new PlaybackKey(resource, request.getFlag(), request.getId());
        if (status == Status.AVAILABLE) {
            failedEpisodes.remove(episode);
            detailFailures.remove(resource);
            playbackFailures.remove(resource);
            record(resource, Status.AVAILABLE);
        } else if (status == Status.INVALID) {
            temporary(playbackFailures, resource);
            trim(failedEpisodes);
            failedEpisodes.put(episode, now() + INVALID_TTL_MILLIS);
            boolean any = false;
            long until = Long.MAX_VALUE;
            for (Flag flag : flags) {
                if (flag == null || flag.getEpisodes() == null) continue;
                for (Episode item : flag.getEpisodes()) {
                    if (item == null || item.getUrl().trim().isEmpty()) continue;
                    any = true;
                    PlaybackKey candidate = new PlaybackKey(resource, flag.getFlag(), item.getUrl());
                    if (!active(failedEpisodes, candidate)) return;
                    Long expiry = failedEpisodes.get(candidate);
                    if (expiry == null) return;
                    until = Math.min(until, expiry);
                }
            }
            if (any && !resource.site.isEmpty() && !resource.id.isEmpty()) {
                trim(invalid);
                invalid.put(resource, until);
            }
        } else if (status == Status.TRANSIENT) temporary(playbackFailures, resource);
    }

    public static boolean isCancellation(Throwable error) {
        for (int i = 0; error != null && i < 8; i++, error = error.getCause()) {
            if (error instanceof CancellationException || error instanceof InterruptedException) return true;
            String message = error.getMessage();
            if (message != null && containsAny(message.toLowerCase(Locale.ROOT), "canceled", "cancelled", "interrupted")) return true;
        }
        return false;
    }

    private static void temporary(Map<Key, Long> entries, Key key) {
        if (key.site.isEmpty() || key.id.isEmpty()) return;
        trim(entries);
        entries.put(key, now() + TEMPORARY_TTL_MILLIS);
    }

    public static Status failureStatus(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getCause()) {
            if (current instanceof InterruptedIOException || current instanceof SocketException
                    || current instanceof UnknownHostException || current instanceof SSLException
                    || current instanceof CancellationException || current instanceof InterruptedException)
                return Status.TRANSIENT;
            if (current.getMessage() != null) text.append(' ').append(current.getMessage());
        }
        String message = text.toString().toLowerCase(Locale.ROOT);
        if (containsAny(message, "timeout", "timed out", "socket", "connection", "unknown host", "dns",
                "network", "tls", "ssl", "proxy", "cancel", "interrupted", "captcha", "challenge", "forbidden", "unauthorized", "permission", "login", "sign in", "验证码", "验证失败",
                "网络", "超时", "连接", "登录", "登陆", "权限", "频繁", "限流")
                || message.matches("(?s).*\\b(?:401|403|429|5\\d\\d)\\b.*")) return Status.TRANSIENT;
        // An API route or signed media URL returning 404/410 is not proof that
        // the resource itself disappeared. Only an explicit resource error is.
        if (containsAny(message, "resource not found", "video not found", "resource expired",
                "资源不存在", "资源失效", "资源已失效", "资源无效", "资源已过期", "资源已删除", "资源已下架", "影片不存在", "视频不存在",
                "内容不存在", "分享已取消", "分享已失效", "分享不存在", "链接已失效", "文件已删除")) return Status.INVALID;
        return Status.TRANSIENT;
    }

    public static void clear() { invalid.clear(); failedEpisodes.clear(); detailFailures.clear(); playbackFailures.clear(); }

    static void recordTemporaryForTest(long revision, String site, String id, long until) {
        playbackFailures.put(new Key(revision, site, id), until);
    }

    static void recordInvalidForTest(long revision, String site, String id, long until) {
        invalid.put(new Key(revision, site, id), until);
    }

    private static boolean containsAny(String value, String... tokens) {
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    private static void record(Key key, Status status) {
        if (key.site.isEmpty() || key.id.isEmpty()) return;
        if (status == Status.INVALID) {
            trim(invalid);
            invalid.put(key, now() + INVALID_TTL_MILLIS);
        } else if (status == Status.AVAILABLE) invalid.remove(key);
    }

    private static <T> boolean active(Map<T, Long> entries, T key) {
        Long until = entries.get(key);
        if (until == null) return false;
        if (until > now()) return true;
        entries.remove(key, until);
        return false;
    }

    private static <T> void trim(Map<T, Long> entries) {
        if (entries.size() < MAX_ENTRIES) return;
        long now = now();
        entries.entrySet().removeIf(entry -> entry.getValue() <= now);
        entries.keySet().stream().limit(Math.max(0, entries.size() - MAX_ENTRIES + 1)).forEach(entries::remove);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
    private record Key(long revision, String site, String id) {}
    private record PlaybackKey(Key resource, String flag, String episode) {}
}
