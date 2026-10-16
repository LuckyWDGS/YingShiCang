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

    void manualSwitchSource() {
        fallbackToNextSource(true);
    }

    void search(String keyword, boolean autoFallback) {
        state.getSources().clear();
        state.setFallbackSearchStarted(autoFallback);
        state.setSearchKeyword(keyword);
        state.setAutoFallback(autoFallback);
        state.setSelectFirstSource(autoFallback);
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

    private void fallbackToNextLineOrSource() {
        if (!host.isSiteChangeable()) return;
        if (fallbackToNextLine()) return;
        fallbackToNextSource(false);
    }

    private boolean fallbackToNextLine() {
        int position = state.getFlagPosition() + 1;
        if (position >= state.getFlags().size()) return false;
        Flag flag = state.getFlags().get(position);
        host.showSwitchLine(flag);
        controller.selectFlag(flag);
        return true;
    }

    private void fallbackToNextSource(boolean force) {
        if (!force) state.addFailedId(host.getVodKey(), host.getVodId());
        state.getSources().removeIf(this::mismatch);
        if (state.hasSources()) nextSource();
        else if (!state.hasFallbackSearchStarted() || force) search(host.getVodName(), true);
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
