package com.fongmi.android.tv.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.playback.PlaybackResult;
import com.fongmi.android.tv.playback.vod.VodDataSource;
import com.fongmi.android.tv.playback.vod.VodDetailResult;
import com.fongmi.android.tv.playback.vod.VodPlayRequest;
import com.fongmi.android.tv.playback.vod.VodPlaybackController;
import com.fongmi.android.tv.playback.vod.VodPlaybackHost;
import com.fongmi.android.tv.playback.vod.VodPlaybackState;
import com.fongmi.android.tv.resource.ResourceHealth;

public class VideoViewModel extends SiteViewModel implements VodDataSource {

    private final MutableLiveData<VodDetailResult> detail;
    private final MutableLiveData<PlaybackResult<VodPlayRequest>> preload;
    private final MutableLiveData<PlaybackResult<VodPlayRequest>> playback;
    private final ViewModelTaskRunner<TaskType> requestTasks;
    private final VodPlaybackState playbackState;
    private int detailGeneration, playbackGeneration, preloadGeneration;

    public VideoViewModel() {
        detail = new MutableLiveData<>();
        preload = new MutableLiveData<>();
        playback = new MutableLiveData<>();
        requestTasks = new ViewModelTaskRunner<>(TaskType.class);
        playbackState = new VodPlaybackState();
    }

    public LiveData<VodDetailResult> getDetail() {
        return detail;
    }

    public LiveData<PlaybackResult<VodPlayRequest>> getPreload() {
        return preload;
    }

    public LiveData<PlaybackResult<VodPlayRequest>> getPlayback() {
        return playback;
    }

    public VodPlaybackController createPlaybackController(VodPlaybackHost host) {
        return new VodPlaybackController(host, this, playbackState);
    }

    @Override
    public void detailContent(String key, String id) {
        requestTasks.cancelAll();
        int generation = ++detailGeneration;
        long revision = VodConfig.getDiscoveryRevision();
        requestTasks.execute(
                TaskType.DETAIL,
                Constant.TIMEOUT_VOD,
                () -> new VodDetailResult(key, id, loadDetail(key, id)),
                value -> App.post(() -> {
                    if (generation != detailGeneration || revision != VodConfig.getDiscoveryRevision()) return;
                    ResourceHealth.recordDetail(key, id, value.result(), revision);
                    detail.setValue(value);
                }),
                error -> App.post(() -> {
                    if (generation != detailGeneration || revision != VodConfig.getDiscoveryRevision()) return;
                    ResourceHealth.recordDetailFailure(key, id, error, revision);
                    detail.setValue(new VodDetailResult(key, id, Result.error(
                            ResourceHealth.failureStatus(error) == ResourceHealth.Status.INVALID
                                    ? "此资源暂时不可用" : "暂时无法连接此资源，请稍后再试")));
                }));
    }

    private Result loadDetail(String key, String id) throws Exception {
        boolean firstWasEmpty = false;
        try {
            Result result = SiteApi.detailContent(key, id);
            if (ResourceHealth.detailStatus(result) == ResourceHealth.Status.AVAILABLE) return result;
            firstWasEmpty = result != null && !result.hasMsg()
                    && ResourceHealth.detailStatus(result) == ResourceHealth.Status.INVALID;
            // Confirm a valid-but-empty response once before hiding its card.
        }
        catch (Exception error) {
            if (Thread.currentThread().isInterrupted() || ResourceHealth.failureStatus(error) != ResourceHealth.Status.TRANSIENT) throw error;
        }
        // One fresh attempt stays inside the original total request deadline.
        Thread.sleep(500);
        Result retry = SiteApi.detailContent(key, id);
        if (!firstWasEmpty && retry != null && !retry.hasMsg()
                && ResourceHealth.detailStatus(retry) == ResourceHealth.Status.INVALID)
            throw new java.io.IOException("Unconfirmed empty detail response");
        return retry;
    }

    @Override
    public void playerContent(VodPlayRequest request) {
        loadPlayback(request, TaskType.PLAYBACK, playback);
    }

    @Override
    public void preloadContent(VodPlayRequest request) {
        loadPlayback(request, TaskType.PRELOAD, preload);
    }

    private void loadPlayback(VodPlayRequest request, TaskType type, MutableLiveData<PlaybackResult<VodPlayRequest>> output) {
        int detailToken = detailGeneration;
        int token = type == TaskType.PLAYBACK ? ++playbackGeneration : ++preloadGeneration;
        long revision = VodConfig.getDiscoveryRevision();
        java.util.function.Consumer<PlaybackResult<VodPlayRequest>> publish = value -> App.post(() -> {
            if (detailToken != detailGeneration || revision != VodConfig.getDiscoveryRevision()) return;
            if (token != (type == TaskType.PLAYBACK ? playbackGeneration : preloadGeneration)) return;
            output.setValue(value);
        });
        // Waiting on the source's own QR/login dialog is the user scanning, not a slow line.
        requestTasks.execute(
                type,
                Constant.TIMEOUT_VOD,
                com.fongmi.android.tv.cloudlogin.LoginWait::pausedMillis,
                () -> new PlaybackResult<>(request, SiteApi.playerContent(request.getKey(), request.getFlag(), request.getId())),
                publish,
                error -> publish.accept(new PlaybackResult<>(request, handleError(error), error)));
    }

    private Result handleError(Throwable error) {
        error.printStackTrace();
        return error instanceof ExtractException ? Result.error(error.getMessage()) : Result.empty();
    }

    @Override
    protected void onCleared() {
        detailGeneration++;
        requestTasks.cancelAll();
        playbackState.reset();
        super.onCleared();
    }

    private enum TaskType {DETAIL, PLAYBACK, PRELOAD}
}
