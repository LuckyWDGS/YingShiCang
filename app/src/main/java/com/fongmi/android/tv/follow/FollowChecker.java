package com.fongmi.android.tv.follow;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.BackgroundThreads;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Looks for new episodes of favorites and recently watched series while Home is on screen. The sources
 * are the ones playback needs, so a round starts late, asks one title at a time and stops when Home is left.
 */
public final class FollowChecker {

    private static final String SAVED = "follow_list";
    private static final long START_DELAY_MS = TimeUnit.SECONDS.toMillis(45);
    // A title on a drive source takes over 20s here: its plugin checks each share in turn.
    private static final long DETAIL_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(35);
    private static final long RECENT_MS = TimeUnit.DAYS.toMillis(30);
    private static final int ROUND_TITLES = 10;

    private static final ExecutorService ROUND = Executors.newSingleThreadExecutor(BackgroundThreads.factory("follow-round"));
    private static final ExecutorService DETAIL = Executors.newCachedThreadPool(BackgroundThreads.factory("follow-detail"));
    private static final Runnable START = FollowChecker::start;

    private static volatile FollowList list;
    private static volatile Consumer<String> notice;
    private static volatile Future<Integer> asking;
    private static volatile boolean running;

    private FollowChecker() {
    }

    private static synchronized FollowList list() {
        if (list == null) list = new FollowList(Prefers.getString(SAVED));
        return list;
    }

    private static void save() {
        Prefers.put(SAVED, list().save());
    }

    public static boolean updated(String key) {
        return list().updated(key);
    }

    /** The user opened the title: what is there now counts as known. */
    public static void seen(String key, List<Flag> flags) {
        boolean updated = list().seen(key, episodes(flags));
        Task.execute(FollowChecker::save);
        if (!updated) return;
        RefreshEvent.history();
        RefreshEvent.keep();
    }

    /** Home is on screen. notice gets the text announcing new episodes. */
    public static void resume(Consumer<String> notice) {
        FollowChecker.notice = notice;
        if (Setting.isFollow()) App.post(START, START_DELAY_MS);
    }

    public static void pause() {
        notice = null;
        App.removeCallbacks(START);
        // Leaving Home is usually opening a title, and a plugin may serve one request at a time.
        Future<Integer> future = asking;
        if (future != null) future.cancel(true);
    }

    private static void start() {
        if (running || notice == null) return;
        running = true;
        ROUND.execute(() -> {
            try {
                round();
            } finally {
                running = false;
            }
        });
    }

    private static void round() {
        Map<String, Title> titles = titles();
        List<String> due = list().due(new ArrayList<>(titles.keySet()), System.currentTimeMillis(), ROUND_TITLES);
        int asked = 0, answered = 0;
        for (String key : due) {
            if (notice == null) break;
            asked++;
            int count;
            try {
                count = episodes(titles.get(key));
                if (count > 0) answered++;
            } catch (TimeoutException slow) {
                // The source may still be working on it: leave it alone, playback needs it.
                list().checked(key, 0, System.currentTimeMillis());
                break;
            }
            // Cancelled by leaving Home: this title has not been looked at.
            if (notice == null) break;
            list().checked(key, count, System.currentTimeMillis());
        }
        List<String> updates = list().untold();
        DiagnosticLog.write(App.get(), "follow_round", "titles=" + titles.size() + " due=" + due.size() + " asked=" + asked + " answered=" + answered + " updated=" + updates.size());
        if (asked == 0) return;
        save();
        if (updates.isEmpty()) return;
        String text = text(titles, updates);
        App.post(() -> {
            RefreshEvent.history();
            RefreshEvent.keep();
            Consumer<String> target = notice;
            if (target != null) target.accept(text);
        });
    }

    private record Title(String siteKey, String vodId, String name) {
    }

    /** Favorites first, then series watched lately, in the configuration in use. */
    private static Map<String, Title> titles() {
        Map<String, Title> titles = new LinkedHashMap<>();
        int cid = VodConfig.getCid();
        for (Keep item : Keep.getVod()) if (item.getCid() == cid) add(titles, item.getKey(), item.getSiteKey(), item.getVodId(), item.getVodName());
        long oldest = System.currentTimeMillis() - RECENT_MS;
        for (History item : History.get()) {
            if (item.getCreateTime() < oldest || item.getVodName().equals(item.getVodRemarks())) continue;
            add(titles, item.getKey(), item.getSiteKey(), item.getVodId(), item.getVodName());
        }
        return titles;
    }

    private static void add(Map<String, Title> titles, String key, String siteKey, String vodId, String name) {
        if (VodConfig.get().getSite(siteKey).isEmpty() || vodId.startsWith("msearch:") || vodId.startsWith("push://")) return;
        titles.putIfAbsent(key, new Title(siteKey, vodId, name));
    }

    private static int episodes(Title title) throws TimeoutException {
        Future<Integer> future = DETAIL.submit(() -> {
            Result result = SiteApi.detailContent(title.siteKey(), title.vodId());
            return result.getList().isEmpty() ? 0 : episodes(result.getVod().getFlags());
        });
        asking = future;
        try {
            return future.get(DETAIL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw error;
        } catch (Exception error) {
            return 0;
        } finally {
            asking = null;
        }
    }

    /** The longest line: lines of one title rarely agree. */
    private static int episodes(List<Flag> flags) {
        int count = 0;
        for (Flag flag : flags) count = Math.max(count, flag.getEpisodes().size());
        return count;
    }

    private static String text(Map<String, Title> titles, List<String> updates) {
        String name = titles.containsKey(updates.get(0)) ? titles.get(updates.get(0)).name() : "";
        return updates.size() == 1 ? "《" + name + "》有更新了" : "《" + name + "》等 " + updates.size() + " 部有更新";
    }
}
