package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.resource.ResourceHealth;

import java.util.ArrayList;
import java.util.List;

class VodFallbackPolicy {

    private final VodPlaybackController controller;
    private final VodPlaybackState state;
    private final VodPlaybackHost host;
    private final VodDataSource dataSource;

    VodFallbackPolicy(VodPlaybackController controller, VodPlaybackState state, VodPlaybackHost host, VodDataSource dataSource) {
        this.controller = controller;
        this.state = state;
        this.host = host;
        this.dataSource = dataSource;
    }

    void playbackError() {
        fallbackToNextLineOrSource();
    }

    void emptyFlag() {
        fallbackToNextLineOrSource();
    }

    void emptyDetail() {
        fallbackToNextSource(false);
    }

    /** This source has no line with the episode wanted (user 2026-11-14: find 第20集 elsewhere, not 第1集 here). */
    void episodeMissing() {
        fallbackToNextSource(false);
    }

    void manualSwitchSource() {
        fallbackToNextSource(true);
    }

    void search(String keyword, boolean autoFallback) {
        state.getSources().clear();
        state.setFallbackSearchStarted(autoFallback);
        state.setSearchKeyword(keyword);
        state.setAutoFallback(autoFallback);
        state.setSelectFirstSource(autoFallback);
        state.setSearchFinished(false);
        host.onSearchStarted(keyword);
        dataSource.searchContent(getSearchableSites(), keyword, true);
    }

    void onSearchResult(Result result) {
        if (result == null) return;
        List<Vod> items = new ArrayList<>(result.getList());
        items.removeIf(this::mismatch);
        state.getSources().removeIf(this::mismatch);
        state.appendSources(items);
        if (state.isSelectFirstSource() && state.hasSources()) nextSource();
        else host.renderSources(state.getSources());
        if (items.isEmpty()) return;
        host.onSearchResult();
    }

    /** Every source has answered. Still waiting for one to try: there is none. */
    void searchFinished() {
        state.setSearchFinished(true);
        if (state.isSelectFirstSource() && !state.hasSources()) exhausted();
    }

    private void exhausted() {
        state.setSelectFirstSource(false);
        controller.sourcesExhausted();
    }

    private void fallbackToNextLineOrSource() {
        if (!host.isSiteChangeable()) return;
        if (fallbackToNextLine()) return;
        fallbackToNextSource(false);
    }

    private boolean fallbackToNextLine() {
        List<Flag> flags = state.getFlags();
        if (flags.isEmpty()) return false;
        int position = state.getFlagPosition();
        state.addFailedLine(flags.get(position));
        String episode = state.getHistory() == null ? "" : state.getHistory().getVodRemarks();
        // The line opened need not be the first, so go round: every line gets its turn once. A line that
        // clearly lacks the episode being watched is passed over; another source may have it (user
        // 2026-11-14: rather 第20集 from another source than another episode here).
        for (int i = 1; i < flags.size(); i++) {
            Flag flag = flags.get((position + i) % flags.size());
            if (state.hasFailedLine(flag) || state.needsLogin(flag) || flag.lacks(episode)) continue;
            host.showSwitchLine(flag);
            controller.fallbackFlag(flag);
            return true;
        }
        return false;
    }

    private void fallbackToNextSource(boolean force) {
        if (!force) state.addFailedId(host.getVodKey(), host.getVodId());
        state.getSources().removeIf(this::mismatch);
        if (state.hasSources()) nextSource();
        else if (!state.hasFallbackSearchStarted() || force) search(host.getVodName(), true);
        else if (state.isSearchFinished()) exhausted();
        else state.setSelectFirstSource(true); // Wait for later batches; never start a search loop.
    }

    private void nextSource() {
        if (!state.hasSources()) return;
        Vod item = state.removeFirstSource();
        host.renderSources(state.getSources());
        host.showSwitchSource(item);
        state.setSelectFirstSource(false);
        controller.fallbackSource(item);
    }

    private List<Site> getSearchableSites() {
        List<Site> sites = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) if (isPass(site)) sites.add(site);
        return sites;
    }

    private boolean isPass(Site item) {
        if (state.isAutoFallback() && !item.isChangeable()) return false;
        return item.isSearchable();
    }

    private boolean mismatch(Vod item) {
        if (!ResourceHealth.isSearchCard(item)) return true;
        if (host.getVodKey().equals(item.getSiteKey()) && host.getVodId().equals(item.getId())) return true;
        if (state.hasFailedId(item.getSiteKey(), item.getId())) return true;
        if (ResourceHealth.isFiltered(VodConfig.getDiscoveryRevision(), item.getSiteKey(), item.getId())) return true;
        if (state.isAutoFallback()) return !item.getName().equals(state.getSearchKeyword());
        return !item.getName().contains(state.getSearchKeyword());
    }
}
