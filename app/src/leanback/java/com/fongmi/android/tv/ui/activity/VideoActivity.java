package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityVideoBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.model.VideoViewModel;
import com.fongmi.android.tv.playback.MediaSeekPolicy;
import com.fongmi.android.tv.playback.PlaybackAction;
import com.fongmi.android.tv.playback.PlaybackIntent;
import com.fongmi.android.tv.playback.PlaybackReset;
import com.fongmi.android.tv.playback.PlaybackResult;
import com.fongmi.android.tv.playback.vod.VodDetailResult;
import com.fongmi.android.tv.playback.vod.VodPlayRequest;
import com.fongmi.android.tv.playback.vod.VodQualityPreference;
import com.fongmi.android.tv.playback.vod.VodPlaybackController;
import com.fongmi.android.tv.playback.vod.VodPlaybackHost;
import com.fongmi.android.tv.playback.vod.VodPlaybackMedia;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.SpeedSetting;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.adapter.FlagAdapter;
import com.fongmi.android.tv.ui.adapter.QuickAdapter;
import com.fongmi.android.tv.ui.custom.CustomKeyDownVod;
import com.fongmi.android.tv.ui.custom.CustomMovement;
import com.fongmi.android.tv.ui.dialog.ChapterDialog;
import com.fongmi.android.tv.ui.dialog.ContentDialog;
import com.fongmi.android.tv.ui.dialog.DanmakuDialog;
import com.fongmi.android.tv.ui.dialog.EditionDialog;
import com.fongmi.android.tv.ui.dialog.ParseDialog;
import com.fongmi.android.tv.ui.dialog.PlayerEngineDialog;
import com.fongmi.android.tv.ui.dialog.SpeedSettingDialog;
import com.fongmi.android.tv.ui.dialog.TrackDialog;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.utils.Traffic;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.bassaer.library.MDColor;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class VideoActivity extends PlaybackActivity implements VodPlaybackHost, CustomKeyDownVod.Listener, ParseDialog.Listener, FlagAdapter.OnClickListener, EpisodeAdapter.OnClickListener, QuickAdapter.OnClickListener, Clock.Callback {

    private ActivityVideoBinding mBinding;
    private VideoViewModel mViewModel;
    private VodPlaybackController mVod;
    private FlagAdapter mFlagAdapter;
    private EpisodeAdapter mEpisodeAdapter;
    private QuickAdapter mQuickAdapter;
    private ViewGroup.LayoutParams mFrameParams;
    private CustomKeyDownVod mKeyDown;
    private Clock mClock;
    private View mFocus1;
    private boolean mTimeBarCenter;
    private static final long PAUSED_HIDE_MS = java.util.concurrent.TimeUnit.MINUTES.toMillis(5);
    private View mFocus2;
    private Runnable mR1;
    private Runnable mR2;
    private Runnable mR3;
    private Runnable mR4;
    private History mHistory;
    private boolean fullscreen;
    private boolean useParse;

    public static void push(FragmentActivity activity, String text) {
        Uri uri = UrlUtil.uri(text);
        if (FileChooser.isFileSource(uri)) FileChooser.getFileUri(uri, fileUri -> file(activity, fileUri));
        else start(activity, Sniffer.getUrl(text));
    }

    public static void file(FragmentActivity activity, Uri fileUri) {
        if (fileUri == null || activity.isFinishing() || activity.isDestroyed()) return;
        start(activity, SiteApi.PUSH, fileUri.toString(), FileUtil.getDisplayName(fileUri));
    }

    public static void cast(Activity activity, History history) {
        start(activity, history.getSiteKey(), history.getVodId(), history.getVodName(), history.getVodPic(), null, false, true);
    }

    public static void collect(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null, true, false);
    }

    public static void start(Activity activity, String url) {
        start(activity, SiteApi.PUSH, url, url);
    }

    public static void start(Activity activity, String key, String id, String name) {
        start(activity, key, id, name, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark) {
        start(activity, key, id, name, pic, mark, false, false);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect, boolean cast) {
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("collect", collect);
        intent.putExtra("cast", cast);
        intent.putExtra("mark", mark);
        intent.putExtra("name", name);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        putPic(intent, pic);
        activity.startActivity(intent);
    }

    private static void putPic(Intent intent, String pic) {
        intent.removeExtra("pic");
        pic = ImgUtil.cache(pic);
        if (!TextUtils.isEmpty(pic)) intent.putExtra("pic", pic);
    }

    private boolean isCast() {
        return getIntent().getBooleanExtra("cast", false);
    }

    private String getName() {
        return Objects.toString(getIntent().getStringExtra("name"), "");
    }

    private String getPic() {
        return Objects.toString(getIntent().getStringExtra("pic"), "");
    }

    private String getMark() {
        return Objects.toString(getIntent().getStringExtra("mark"), "");
    }

    private String getKey() {
        return Objects.toString(getIntent().getStringExtra("key"), "");
    }

    private String getId() {
        return Objects.toString(getIntent().getStringExtra("id"), "");
    }

    private boolean isSameVideo(Intent intent) {
        String key = Objects.toString(intent.getStringExtra("key"), "");
        String id = Objects.toString(intent.getStringExtra("id"), "");
        return TextUtils.equals(key, getKey()) && TextUtils.equals(id, getId());
    }

    @Override
    public String getHistoryKey() {
        return getKey().concat(AppDatabase.SYMBOL).concat(getId()).concat(AppDatabase.SYMBOL) + VodConfig.getCid();
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private Episode getEpisode() {
        return mEpisodeAdapter.getActivated();
    }

    private int getScale() {
        return mHistory != null && mHistory.getScale() != -1 ? mHistory.getScale() : PlayerSetting.getScale();
    }

    private void setScale(int scale) {
        mVod.setScale(scale);
        mBinding.player.setResizeMode(scale);
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
    }

    @Override
    public boolean isFromCollect() {
        return getIntent().getBooleanExtra("collect", false);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityVideoBinding.inflate(getLayoutInflater());
    }

    @Override
    protected PlaybackService.NavigationCallback getNavigationCallback() {
        return mNavigationCallback;
    }

    @Override
    protected PlayerView getPlayerView() {
        return mBinding.player;
    }

    @Override
    protected PlayerSeekView getSeekView() {
        return mBinding.control.seek;
    }

    @Override
    protected void onServiceConnected() {
        mVod.onPlaybackServiceReady();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (TextUtils.isEmpty(intent.getStringExtra("id")) || isSameVideo(intent)) return;
        saveHistory(true);
        mVod.reset();
        setIntent(intent);
        updateNavigationKey();
        checkId();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        mFrameParams = mBinding.video.getLayoutParams();
        mClock = Clock.create(mBinding.widget.clock);
        mKeyDown = CustomKeyDownVod.create(this);
        mR1 = this::hideControl;
        mR2 = this::updateFocus;
        mR3 = this::setTraffic;
        mR4 = this::showEmpty;
        setRecyclerView();
        setVideoView();
        setViewModel();
        checkCast();
        checkId();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.keep.setOnClickListener(view -> onKeep());
        mBinding.video.setOnClickListener(view -> onVideo());
        mBinding.change.setOnClickListener(view -> CollectActivity.start(this, mBinding.name.getText().toString()));
        mBinding.content.setOnClickListener(view -> onContent());
        mBinding.reverse.setOnClickListener(view -> onRevSort());
        mBinding.control.action.text.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.video.setOnClickListener(this::onTrack);
        mBinding.control.action.ending.setUpListener(this::onEndingAdd);
        mBinding.control.action.ending.setDownListener(this::onEndingSub);
        mBinding.control.action.opening.setUpListener(this::onOpeningAdd);
        mBinding.control.action.opening.setDownListener(this::onOpeningSub);
        mBinding.control.action.next.setOnClickListener(view -> checkNext());
        mBinding.control.action.prev.setOnClickListener(view -> checkPrev());
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.action.reset.setOnClickListener(view -> onReset());
        mBinding.control.action.replay.setOnClickListener(view -> onReplay());
        mBinding.control.action.parse.setOnClickListener(view -> onParse());
        mBinding.control.action.player.setOnClickListener(view -> onPlayer());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.ending.setOnClickListener(view -> onEnding());
        mBinding.control.action.repeat.setOnClickListener(view -> onRepeat());
        mBinding.control.action.danmaku.setOnClickListener(view -> onDanmaku());
        mBinding.control.action.edition.setOnClickListener(view -> onEdition());
        mBinding.control.action.chapter.setOnClickListener(view -> onChapter());
        mBinding.control.action.opening.setOnClickListener(view -> onOpening());
        mBinding.control.action.more.setOnClickListener(view -> toggleMore());
        mBinding.control.action.episodes.setOnClickListener(view -> {
            hideControl();
            exitFullscreen();
            mBinding.episode.requestFocus();
        });
        mBinding.control.action.render.setOnClickListener(view -> {
            int render = PlayerSetting.getRender() == 1 ? 0 : 1;
            PlayerSetting.putRender(render);
            mBinding.player.setRender(render);
            updateMoreLabels();
        });
        mBinding.control.action.screenInfo.setOnClickListener(view -> {
            getSharedPreferences("player_overlay", MODE_PRIVATE).edit().putBoolean("info", !isScreenInfoEnabled()).apply();
            updateMoreLabels();
            updateTopInfo();
        });
        mBinding.control.action.speed.setOnLongClickListener(view -> onSpeedLong());
        mBinding.control.action.ending.setOnLongClickListener(view -> onEndingReset());
        mBinding.control.action.opening.setOnLongClickListener(view -> onOpeningReset());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
        mBinding.flag.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (mFlagAdapter.getItemCount() > 0) onItemClick(mFlagAdapter.get(position));
            }
        });
        mBinding.episode.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                if (child != null && mBinding.video != mFocus1) mFocus1 = child.itemView;
            }
        });

    }

    private void setRecyclerView() {
        mBinding.flag.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.flag.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.flag.setAdapter(mFlagAdapter = new FlagAdapter(this));
        mBinding.episode.setAdapter(mEpisodeAdapter = new EpisodeAdapter(this));
        mBinding.quick.setHorizontalSpacing(ResUtil.dp2px(8));
        mBinding.quick.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.quick.setAdapter(mQuickAdapter = new QuickAdapter(this));
    }

    private void setVideoView() {
        PlayerEngineDialog.setText(mBinding.control.action.player);
        mBinding.control.action.danmaku.setVisibility(View.VISIBLE);
        updateMoreLabels();
        for (View action : new View[]{mBinding.control.action.next, mBinding.control.action.prev,
                mBinding.control.action.reset, mBinding.control.action.replay, mBinding.control.action.episodes,
                mBinding.control.action.speed, mBinding.control.action.text, mBinding.control.action.danmaku,
                mBinding.control.action.audio, mBinding.control.action.scale, mBinding.control.action.more}) action.setNextFocusUpId(action.getId());
    }

    private void setPlaybackMode() {
        PlaybackAction.setPlaybackMode(player(), mBinding.control.action.player, mBinding.control.action.decode);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(VideoViewModel.class);
        mVod = mViewModel.createPlaybackController(this);
        observeWhenServiceReady(mViewModel.getDetail(), this::onDetailObserved);
        observeWhenServiceReady(mViewModel.getSearch(), this::onSearchObserved);
        observeWhenServiceReady(mViewModel.getPreload(), this::onPreloadObserved);
        observeWhenServiceReady(mViewModel.getPlayback(), this::onPlaybackObserved);
    }

    private void onDetailObserved(VodDetailResult result) {
        mVod.onDetailResult(result);
    }

    private void onPlaybackObserved(PlaybackResult<VodPlayRequest> result) {
        mVod.onPlaybackResult(result);
    }

    private void onPreloadObserved(PlaybackResult<VodPlayRequest> result) {
        mVod.onPreloadResult(result);
    }

    private void onSearchObserved(Result result) {
        mVod.onSearchResult(result);
    }

    @Override
    public String getVodKey() {
        return getKey();
    }

    @Override
    public String getVodId() {
        return getId();
    }

    @Override
    public void setVodId(String id) {
        getIntent().putExtra("id", id);
    }

    @Override
    public String getVodName() {
        String name = mBinding.name.getText().toString();
        return name.isEmpty() ? getName() : name;
    }

    @Override
    public String getVodPic() {
        return getPic();
    }

    @Override
    public String getVodMark() {
        return getMark();
    }

    @Override
    public boolean isSiteChangeable() {
        return getSite().isChangeable();
    }

    @Override
    public boolean isHostFinishing() {
        return isFinishing() || isDestroyed();
    }

    @Override
    public boolean isPlayerEmpty() {
        return player().isEmpty();
    }

    @Override
    public boolean hasPlaybackSession() {
        return service() != null && isOwner() && player().hasPlaySpec();
    }

    @Override
    public boolean isFullscreenForPlayback() {
        return isFullscreen();
    }

    @Override
    public boolean isLivePlayback() {
        return service() != null && isOwner() && player().isLive();
    }

    @Override
    public boolean canTrackPlaybackProgress() {
        return service() != null && isOwner() && player().isVod();
    }

    @Override
    public boolean canPreloadNext() {
        return service() != null && isOwner() && player().canPreloadNext();
    }

    @Override
    public long getPlayerPosition() {
        return player().getPosition();
    }

    @Override
    public long getPlayerDuration() {
        return player().getDuration();
    }

    @Override
    public void usePushId(String id) {
        getIntent().putExtra("key", SiteApi.PUSH).putExtra("id", id);
    }

    @Override
    public void onPlaybackRequested() {
        showProgress();
    }

    @Override
    public void prepareSource(Vod item) {
        cancelPendingSeek();
        getIntent().putExtra("key", item.getSiteKey());
        getIntent().putExtra("id", item.getId());
        putPic(getIntent(), item.getPic());
        mBinding.scroll.scrollTo(0, 0);
        mClock.setCallback(null);
        updateNavigationKey();
        player().reset();
        player().stop();
    }

    @Override
    public void stopPlaybackForRefresh() {
        cancelPendingSeek();
        player().stop();
        player().clear();
        mClock.setCallback(null);
    }

    @Override
    public void resetPlaybackForError(String msg) {
        cancelPendingSeek();
        PlaybackReset.afterError(player(), () -> mClock.setCallback(null));
        showError(msg);
    }

    @Override
    public void replay(long position) {
        cancelPendingSeek();
        player().replay(position);
    }

    @Override
    public void startPlayback(Result result, boolean useParse, long startPositionMs, MediaMetadata metadata) {
        cancelPendingSeek();
        startPlayer(getHistoryKey(), result, useParse, getSite().getTimeout(), startPositionMs, metadata);
        player().observeCurrentPlayback(mVod.playbackObservation());
    }

    @Override
    public boolean preloadPlayback(Result result, long startPositionMs, MediaMetadata metadata) {
        return player().preload(PlaySpec.from(result, getHistoryKey(), metadata), startPositionMs);
    }

    @Override
    public void clearPreload() {
        if (service() != null && isOwner()) player().clearPreload();
    }

    @Override
    public void loadDanmaku(Result result, History history, Episode episode) {
        VodPlaybackMedia.searchDanmaku(result, history, episode, player().danmakuReceiver(false), player().danmakuReceiver(true));
    }

    @Override
    public void renderDetail(Vod item, History history) {
        for (TextView field : new TextView[]{mBinding.year, mBinding.area, mBinding.type,
                mBinding.site, mBinding.director, mBinding.actor}) field.setText("");
        mHistory = history;
        mBinding.progressLayout.showContent();
        mBinding.name.setText(item.getName());
        mBinding.video.requestFocus();
        App.removeCallbacks(mR4);
        setArtwork(item.getPic());
        checkKeepImg();
        setText(item);
        updateKeep();
    }

    @Override
    public void renderVodUpdate(Vod item) {
        if (!item.getId().isEmpty()) updateNavigationKey();
        renderVodMetadata(item.getName(), item.getPic());
        setText(item);
    }

    private void renderVodMetadata(String name, String pic) {
        if (name.isEmpty() && pic.isEmpty()) return;
        if (!name.isEmpty()) mBinding.name.setText(name);
        if (!pic.isEmpty()) setArtwork();
        updateKeep();
    }

    @Override
    public void renderEmptyDetail() {
        showEmpty();
    }

    @Override
    public void renderFallbackName(String name) {
        mBinding.name.setText(name);
    }

    @Override
    public void renderFlags(List<Flag> items) {
        int visibility = items.isEmpty() ? View.GONE : View.VISIBLE;
        mBinding.flagSection.setVisibility(visibility);
        mBinding.flag.setVisibility(visibility);
        mFlagAdapter.addAll(items);
        setR2Callback();
    }

    @Override
    public void renderEpisodes(List<Episode> items) {
        setEpisodeAdapter(items);
    }

    @Override
    public void renderFlagSelection(Flag item) {
        mBinding.flag.setSelectedPosition(mFlagAdapter.indexOf(item));
        notifyItemChanged(mBinding.flag, mFlagAdapter);
    }

    @Override
    public void renderEpisodeSelection(Episode item) {
        mBinding.episode.post(mEpisodeAdapter::refreshSelection);
        mBinding.episode.setSelectedPosition(mEpisodeAdapter.getPosition());
    }

    @Override
    public void renderReverseEpisodes(List<Episode> items, boolean scroll) {
        setEpisodeAdapter(items);
        if (scroll) mBinding.episode.setSelectedPosition(mEpisodeAdapter.getPosition());
    }

    @Override
    public int preferredQualityPosition(Result result, int previousPosition) {
        List<String> labels = new ArrayList<>();
        for (com.fongmi.android.tv.bean.Value value : result.getUrl().getValues()) {
            labels.add(value.getV().isEmpty() ? null : value.getN());
        }
        return VodQualityPreference.highest(labels, result.getUrl().getPosition());
    }

    @Override
    public void renderQuality(Result result, boolean visible) {
        // TV resolves the preferred quality before playback; no manual selector.
    }

    @Override
    public void renderQualityVisible(boolean visible) {
    }

    @Override
    public void renderSources(List<Vod> items) {
        mQuickAdapter.submitList(items);
        int visibility = mQuickAdapter.isEmpty() ? View.GONE : View.VISIBLE;
        mBinding.quickSection.setVisibility(visibility);
        mBinding.quick.setVisibility(visibility);
        setR2Callback();
    }

    @Override
    public void renderHistory(History history) {
        mHistory = history;
        updateSkipLabels();
        player().setSpeed(SpeedSetting.getPlayback());
        setScale(getScale());
    }

    @Override
    public void renderUseParse(boolean useParse) {
        setUseParse(useParse);
        mBinding.control.action.parse.setVisibility(isUseParse() ? View.VISIBLE : View.GONE);
        compactMoreGrid();
    }

    @Override
    public void renderArtwork(String url) {
        setArtwork(url);
    }

    @Override
    public void renderDescription(String desc) {
        mBinding.content.setTag(desc);
    }

    @Override
    public void renderPlaybackMetadata(MediaMetadata metadata) {
        if (service() != null && isOwner()) player().setMetadata(metadata);
        mBinding.widget.title.setText(metadata.displayTitle);
        mBinding.widget.title.setSelected(true);
    }

    @Override
    public void onDetailFallbackScheduled() {
        App.post(mR4, 10000);
    }

    @Override
    public void onDetailFallbackCancelled() {
        App.removeCallbacks(mR4);
    }

    @Override
    public void onSearchStarted(String keyword) {
        mQuickAdapter.clear();
        mBinding.quickSection.setVisibility(View.GONE);
        mBinding.quick.setVisibility(View.GONE);
        setR2Callback();
    }

    @Override
    public void onSearchResult() {
        App.removeCallbacks(mR4);
    }

    @Override
    public void showDetailMessage(String msg) {
        Notify.show(msg);
    }

    @Override
    public void showSwitchLine(Flag flag) {
        Notify.show(getString(R.string.play_switch_flag, flag.getFlag()));
    }

    @Override
    public void showSwitchSource(Vod item) {
        Notify.show(getString(R.string.play_switch_site, item.getSiteName()));
    }

    @Override
    public void showNoNext(boolean reversed) {
        Notify.show(reversed ? R.string.error_play_prev : R.string.error_play_next);
    }

    @Override
    public void showNoPrev(boolean reversed) {
        Notify.show(reversed ? R.string.error_play_next : R.string.error_play_prev);
    }

    @Override
    public void finishVod() {
        finish();
    }

    private void checkCast() {
        if (isCast() && !isFullscreen()) enterFullscreen();
        else mBinding.progressLayout.showProgress();
    }

    private void checkId() {
        mVod.checkId();
    }

    private void showEmpty() {
        mBinding.progressLayout.showEmpty();
    }

    private void setText(Vod item) {
        String description = android.text.Html.fromHtml(item.getContent(), android.text.Html.FROM_HTML_MODE_LEGACY)
                .toString().replace('\u00A0', ' ').trim();
        mBinding.content.setTag(description);
        String summary = description.replaceAll("[\\s\u3000]+", " ").trim();
        mBinding.summary.setText(summary.isEmpty() ? "暂无内容简介" : getString(R.string.detail_content, summary));
        setText(mBinding.year, R.string.detail_year, item.getYear());
        setText(mBinding.area, R.string.detail_area, item.getArea());
        setText(mBinding.type, R.string.detail_type, item.getTypeName());
        setText(mBinding.site, R.string.detail_site, getSite().getName());
        setText(mBinding.director, R.string.detail_director, item.getDirector());
        setText(mBinding.actor, R.string.detail_actor, item.getActor());
    }

    private void setText(TextView view, int resId, String text) {
        if (TextUtils.isEmpty(text) && !TextUtils.isEmpty(view.getText())) return;
        view.setText(Sniffer.buildClickable(resId > 0 ? getString(resId, text) : text, this::clickableSpan), TextView.BufferType.SPANNABLE);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        view.setLinkTextColor(ResUtil.getColor(R.color.tv_theme_text));
        CustomMovement.bind(view);
    }

    private ClickableSpan clickableSpan(Result result) {
        return new ClickableSpan() {
            @Override
            public void onClick(@NonNull View view) {
                VodActivity.start(getActivity(), getKey(), result);
                setRedirect(true);
            }
        };
    }

    @Override
    public void onItemClick(Flag item) {
        cancelPendingSeek();
        mVod.selectFlag(item);
    }

    private void setEpisodeAdapter(List<Episode> items) {
        int visibility = items.isEmpty() ? View.GONE : View.VISIBLE;
        mBinding.episodeSection.setVisibility(visibility);
        mBinding.episode.setVisibility(visibility);
        int columns = EpisodeAdapter.columnsFor(items);
        mEpisodeAdapter.setColumnCount(columns);
        mBinding.episode.setColumnCount(columns);
        mEpisodeAdapter.addAll(items);
        setR2Callback();
    }

    @Override
    public void onItemClick(Episode item) {
        if (shouldEnterFullscreen(item)) return;
        cancelPendingSeek();
        mVod.selectEpisode(item);
    }

    private int findFocusDown(int index) {
        List<Integer> orders = Arrays.asList(R.id.flag, R.id.episode, R.id.quick);
        for (int i = 0; i < orders.size(); i++) if (i > index) if (isVisible(findViewById(orders.get(i)))) return orders.get(i);
        return 0;
    }

    private int findFocusUp(int index) {
        List<Integer> orders = Arrays.asList(R.id.flag, R.id.episode, R.id.quick);
        for (int i = orders.size() - 1; i >= 0; i--) if (i < index) if (isVisible(findViewById(orders.get(i)))) return orders.get(i);
        return R.id.video;
    }

    private void updateFocus() {
        int first = findFocusDown(-1);
        for (View view : new View[]{mBinding.video, mBinding.change, mBinding.reverse, mBinding.keep, mBinding.content}) {
            view.setNextFocusDownId(first == 0 ? view.getId() : first);
        }
        mEpisodeAdapter.setNextFocusUp(findFocusUp(1));
        mFlagAdapter.setNextFocusDown(findFocusDown(0));
        mEpisodeAdapter.setNextFocusDown(findFocusDown(1));
        notifyItemChanged(mBinding.flag, mFlagAdapter);
    }

    private void onRevSort() {
        mVod.setRevSort(!mHistory.isRevSort());
        mVod.reverseEpisode(false);
    }

    private boolean shouldEnterFullscreen(Episode item) {
        boolean enter = !isFullscreen() && item.isSelected();
        if (enter) enterFullscreen();
        return enter;
    }

    private void enterFullscreen() {
        mFocus1 = getCurrentFocus();
        mBinding.detail.setVisibility(View.GONE);
        mBinding.scroll.setVisibility(View.GONE);
        mBinding.video.requestFocus();
        mBinding.video.setForeground(null);
        // Full screen is edge to edge: drop the preview's rounded clip and 1dp border.
        mBinding.video.setClipToOutline(false);
        mBinding.video.setBackgroundColor(Color.BLACK);
        mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        mBinding.flag.setSelectedPosition(mFlagAdapter.getPosition());
        mKeyDown.setFull(true);
        setFullscreen(true);
        mFocus2 = null;
    }

    private void exitFullscreen() {
        mBinding.video.setForeground(ResUtil.getDrawable(R.drawable.discovery_focus));
        mBinding.video.setBackgroundResource(R.drawable.detail_preview);
        mBinding.video.setClipToOutline(true);
        mBinding.video.setLayoutParams(mFrameParams);
        mBinding.detail.setVisibility(View.VISIBLE);
        mBinding.scroll.setVisibility(View.VISIBLE);
        getFocus1().requestFocus();
        mKeyDown.setFull(false);
        setFullscreen(false);
        mFocus2 = null;
        hideInfo();
    }

    private void onContent() {
        if (mBinding.content.getTag() == null) return;
        ContentDialog.create().content(mBinding.content.getTag().toString()).show(this);
    }

    private void onKeep() {
        Keep keep = Keep.find(getHistoryKey());
        Notify.show(keep != null ? R.string.keep_del : R.string.keep_add);
        if (keep != null) keep.delete();
        else createKeep();
        checkKeepImg();
    }

    private void onVideo() {
        if (!isFullscreen()) enterFullscreen();
    }

    private void onChange() {
        cancelPendingSeek();
        mVod.manualSwitchSource();
    }

    private void onRepeat() {
        player().setRepeatOne(!player().isRepeatOne());
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    private void checkNext() {
        cancelPendingSeek();
        checkNext(true);
    }

    private void checkNext(boolean notify) {
        mVod.nextEpisode(notify);
    }

    private void checkPrev() {
        cancelPendingSeek();
        mVod.prevEpisode(true);
    }

    private void onNext(boolean notify) {
        Episode item = mEpisodeAdapter.getNext();
        if (!item.isSelected()) onItemClick(item);
        else if (notify) Notify.show(mHistory.isRevPlay() ? R.string.error_play_prev : R.string.error_play_next);
    }

    private void onPrev(boolean notify) {
        Episode item = mEpisodeAdapter.getPrev();
        if (!item.isSelected()) onItemClick(item);
        else if (notify) Notify.show(mHistory.isRevPlay() ? R.string.error_play_next : R.string.error_play_prev);
    }

    private void onScale() {
        int index = getScale();
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        setScale(index == array.length - 1 ? 0 : ++index);
    }

    private void onSpeed() {
        SpeedSettingDialog.create().player(player()).save(true).show(this);
        hideControl();
    }

    private boolean onSpeedLong() {
        SpeedSetting.putPlayback(PlaybackAction.toggleSpeed(player(), mBinding.widget.message));
        return true;
    }

    private void onReset() {
        onRefresh();
    }

    private void onParse() {
        ParseDialog.create().show(this);
        hideControl();
    }

    private void cancelPendingSeek() {
        if (mKeyDown != null) mKeyDown.cancelPendingSeek();
    }

    private void onReplay() {
        cancelPendingSeek();
        mVod.replay();
    }

    private void onRefresh() {
        cancelPendingSeek();
        mVod.refresh();
    }

    private void onOpening() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetOpening(position, duration)) setOpening(position);
    }

    private void onOpeningAdd() {
        setOpening(Math.max(0, Math.max(0, mHistory.getOpening()) + 1000));
    }

    private void onOpeningSub() {
        setOpening(Math.max(0, Math.max(0, mHistory.getOpening()) - 1000));
    }

    private boolean onOpeningReset() {
        setOpening(0);
        return true;
    }

    private void setOpening(long opening) {
        mVod.setOpening(opening);
        updateSkipLabels();
    }

    private void onEnding() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetEnding(position, duration)) setEnding(duration - position);
    }

    private void onEndingAdd() {
        setEnding(Math.max(0, Math.max(0, mHistory.getEnding()) + 1000));
    }

    private void onEndingSub() {
        setEnding(Math.max(0, Math.max(0, mHistory.getEnding()) - 1000));
    }

    private boolean onEndingReset() {
        setEnding(0);
        return true;
    }

    private void setEnding(long ending) {
        mVod.setEnding(ending);
        updateSkipLabels();
    }

    private void updateSkipLabels() {
        if (mHistory == null) return;
        mBinding.control.action.opening.setText(getString(R.string.vod_opening_time, Util.timeMs(Math.max(0, mHistory.getOpening()))));
        mBinding.control.action.ending.setText(getString(R.string.vod_ending_time, Util.timeMs(Math.max(0, mHistory.getEnding()))));
    }

    private boolean isScreenInfoEnabled() {
        return getSharedPreferences("player_overlay", MODE_PRIVATE).getBoolean("info", true);
    }

    private void updateMoreLabels() {
        moveExtensionActionsToMore();
        mBinding.control.action.render.setText(PlayerSetting.getRender() == 1 ? R.string.vod_render_texture : R.string.vod_render_surface);
        mBinding.control.action.screenInfo.setText(isScreenInfoEnabled() ? R.string.vod_info_on : R.string.vod_info_off);
        if (service() != null) mBinding.control.action.player.setText(PlaybackAction.getEngineText(player()).split("[/·]")[0].trim());
        compactMoreGrid();
    }

    private void moveExtensionActionsToMore() {
        ViewGroup toolbar = (ViewGroup) mBinding.control.action.replay.getParent();
        List<View> common = Arrays.asList(mBinding.control.action.next, mBinding.control.action.prev,
                mBinding.control.action.reset, mBinding.control.action.replay, mBinding.control.action.episodes,
                mBinding.control.action.speed, mBinding.control.action.opening, mBinding.control.action.ending,
                mBinding.control.action.text, mBinding.control.action.danmaku, mBinding.control.action.audio,
                mBinding.control.action.scale);
        // Source plugins may append actions after inflation. Keep their handlers in the overflow menu.
        for (int i = 0; i < toolbar.getChildCount();) {
            View child = toolbar.getChildAt(i);
            if (common.contains(child)) {
                i++;
                continue;
            }
            toolbar.removeView(child);
            if (child.getId() == View.NO_ID) child.setId(View.generateViewId());
            child.setBackgroundResource(R.drawable.player_vod_action);
            // Keep the template in the grid: common actions now use LinearLayout parameters.
            TextView template = mBinding.control.action.player;
            child.setPadding(template.getPaddingLeft(), template.getPaddingTop(), template.getPaddingRight(), template.getPaddingBottom());
            if (child instanceof TextView text) {
                text.setSingleLine(false);
                text.setMaxLines(2);
                text.setGravity(android.view.Gravity.CENTER);
                text.setTextSize(15);
                text.setTextColor(template.getTextColors());
            }
            mBinding.control.action.moreGrid.addView(child, new android.widget.GridLayout.LayoutParams(
                    (android.widget.GridLayout.LayoutParams) template.getLayoutParams()));
        }
    }

    private void compactMoreGrid() {
        int visible = 0;
        android.widget.GridLayout grid = mBinding.control.action.moreGrid;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            android.widget.GridLayout.LayoutParams params = (android.widget.GridLayout.LayoutParams) child.getLayoutParams();
            if (child.getVisibility() == View.GONE) {
                params.rowSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED);
                params.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED);
            } else {
                params.rowSpec = android.widget.GridLayout.spec(visible / 2, android.widget.GridLayout.FILL);
                params.columnSpec = android.widget.GridLayout.spec(visible % 2, android.widget.GridLayout.FILL);
                visible++;
            }
            child.setLayoutParams(params);
        }
    }

    private void updateTopInfo() {
        mBinding.widget.top.setVisibility(isFullscreen() && isVisible(mBinding.control.getRoot()) && isScreenInfoEnabled() ? View.VISIBLE : View.GONE);
    }

    private void toggleMore() {
        if (isVisible(mBinding.control.action.morePanel)) {
            hideMore();
        } else {
            mBinding.control.action.morePanel.setVisibility(View.VISIBLE);
            updateMoreLabels();
            mBinding.control.action.player.requestFocusFromTouch();
            App.removeCallbacks(mR1);
        }
    }

    private void hideMore() {
        mBinding.control.action.morePanel.setVisibility(View.GONE);
        mBinding.control.action.more.requestFocusFromTouch();
        setR1Callback();
    }

    private boolean isMoreControl(View view) {
        while (view != null) {
            if (view == mBinding.control.action.morePanel) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    private boolean moveMoreFocus(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        List<View> visible = new ArrayList<>();
        ViewGroup grid = mBinding.control.action.moreGrid;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View control = grid.getChildAt(i);
            if (control.isShown() && control.isFocusable()) visible.add(control);
        }
        if (visible.isEmpty()) return true;
        int index = visible.indexOf(getCurrentFocus());
        if (index < 0) index = 0;
        int target = index;
        if (code == KeyEvent.KEYCODE_DPAD_UP) target = Math.max(0, index - 2);
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) target = Math.min(visible.size() - 1, index + 2);
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && index % 2 == 1) target--;
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && index % 2 == 0) target = Math.min(visible.size() - 1, index + 1);
        // The first D-pad event after a touch must leave touch mode before requesting focus.
        visible.get(target).requestFocusFromTouch();
        return true;
    }

    private void onPlayer() {
        PlayerEngineDialog.show(this, mBinding.control.action.player, player());
        hideControl();
    }

    private void onDecode() {
        mClock.setCallback(null);
        player().toggleDecode();
    }

    private void onTrack(View view) {
        TrackDialog.create().type(Integer.parseInt(view.getTag().toString())).player(player()).view(mBinding.player.getSubtitleView()).show(this);
        hideControl();
    }

    private void onEdition() {
        EditionDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onChapter() {
        ChapterDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onDanmaku() {
        DanmakuDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onToggle() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else showControl(getFocus2());
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        App.post(mR3, 0);
        hideCenter();
        hideError();
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR3);
        Traffic.reset();
    }

    private void showError(String text) {
        PlaybackAction.hideSpeedHint(mBinding.widget.message);
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.text.setText(text);
        hideProgress();
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.text.setText("");
    }

    private void showInfo() {
        updateTopInfo();
        mBinding.widget.action.setImageResource(R.drawable.ic_vod_resume);
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.widget.duration.setText(player().getDurationTime());
        mBinding.widget.position.setText(player().getPositionTime(0));
    }

    private void hideInfo() {
        mBinding.widget.top.setVisibility(View.GONE);
        mBinding.widget.center.setVisibility(View.GONE);
    }

    private void showControl(View view) {
        updateMoreLabels();
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        if (service() != null) mBinding.control.action.speed.setText(SpeedSetting.format(player().getSpeed()));
        updateTopInfo();
        view.requestFocusFromTouch();
        setR1Callback();
    }

    private void hideControl() {
        mBinding.control.action.morePanel.setVisibility(View.GONE);
        mBinding.control.getRoot().setVisibility(View.GONE);
        updateTopInfo();
        App.removeCallbacks(mR1);
    }

    private void hideCenter() {
        mBinding.widget.action.setImageResource(R.drawable.ic_vod_resume);
        mBinding.widget.center.setVisibility(View.GONE);
        updateTopInfo();
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.progress.traffic);
        App.post(mR3, 1000);
    }

    private void setR1Callback() {
        if (isScrubbing() || isVisible(mBinding.control.action.morePanel)) return;
        // Paused controls stay up (with Up/Down on the volume they are the remote's way back in), but not
        // for good: a pause left for hours must not burn the control bar into an OLED screen.
        boolean paused = controller() != null && !controller().getPlayWhenReady();
        App.post(mR1, paused ? PAUSED_HIDE_MS : Constant.INTERVAL_HIDE);
    }

    @Override
    protected void onScrubbingChanged(boolean scrubbing) {
        if (scrubbing) {
            cancelPendingSeek();
            hideCenter();
            App.removeCallbacks(mR1);
        }
        else if (isVisible(mBinding.control.getRoot())) setR1Callback();
    }

    private void setR2Callback() {
        App.post(mR2, 500);
    }

    private void setArtwork(String url) {
        mHistory.setVodPic(url);
        setArtwork();
    }

    private void setArtwork() {
        ImgUtil.load(this, mHistory.getVodPic(), new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                mBinding.player.setDefaultArtwork(resource);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                mBinding.player.setDefaultArtwork(errorDrawable);
            }
        });
    }

    private void saveHistory(boolean exit) {
        PlaybackService service = service();
        boolean owner = service != null && getPlaybackKey().equals(service.player().getKey());
        long position = owner ? service.player().getPosition() : C.TIME_UNSET;
        long duration = owner ? service.player().getDuration() : C.TIME_UNSET;
        if (mVod != null) mVod.saveHistory(exit, System.currentTimeMillis(), position, duration);
    }

    private void checkKeepImg() {
        boolean kept = Keep.find(getHistoryKey()) != null;
        mBinding.keep.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        mBinding.keep.setText(kept ? R.string.detail_remove_keep : R.string.detail_add_keep);
    }

    private void createKeep() {
        Keep keep = new Keep();
        keep.setKey(getHistoryKey());
        keep.setCid(VodConfig.getCid());
        keep.setVodPic(mHistory.getVodPic());
        keep.setVodName(mHistory.getVodName());
        keep.setSiteName(getSite().getName());
        keep.setCreateTime(System.currentTimeMillis());
        keep.save();
    }

    private void updateKeep() {
        Keep keep = Keep.find(getHistoryKey());
        if (keep != null) {
            keep.setVodName(mHistory.getVodName());
            keep.setVodPic(mHistory.getVodPic());
            keep.save();
        }
    }

    private final PlaybackService.NavigationCallback mNavigationCallback = new PlaybackService.NavigationCallback() {
        @Override
        public void onNext() {
            checkNext();
        }

        @Override
        public void onPrev() {
            checkPrev();
        }

        @Override
        public void onStop() {
            finish();
        }

        @Override
        public void onReplay() {
            VideoActivity.this.onReplay();
        }
    };

    @Override
    protected String getPlaybackKey() {
        return getHistoryKey();
    }

    @Override
    protected void onPrepare() {
        // A prepare can be triggered asynchronously by the playback service
        // after the user has released a directional seek key. Drop that
        // stale callback before the new player state is applied.
        cancelPendingSeek();
        setPlaybackMode();
    }

    @Override
    protected void onDecodeChanged() {
        setPlaybackMode();
    }

    @Override
    protected void onTracksChanged() {
        setTrackVisible();
    }

    @Override
    protected void onMediaOptionsChanged() {
        setMediaOptionVisible();
    }

    @Override
    protected void onError(String msg) {
        cancelPendingSeek();
        mVod.playbackError(msg);
    }

    @Override
    protected void onReclaim() {
        cancelPendingSeek();
        mVod.refresh();
    }

    @Override
    protected void onStateChanged(int state) {
        switch (state) {
            case Player.STATE_BUFFERING:
                showProgress();
                mClock.setCallback(null);
                break;
            case Player.STATE_READY:
                mVod.playbackReady();
                hideProgress();
                player().reset();
                mClock.setCallback(this);
                break;
            case Player.STATE_ENDED:
                // If there is no next episode, the delayed seek callback must
                // not apply to the ended item after playback transitions.
                cancelPendingSeek();
                hideProgress();
                mVod.playbackEnded();
                mClock.setCallback(null);
                break;
        }
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        if (isPlaying) {
            hideCenter();
        } else if (isPaused()) {
            if (isFullscreen()) showInfo();
            else hideInfo();
        }
    }

    @Override
    protected void onSizeChanged(VideoSize size) {
        mBinding.widget.size.setText(player().getSizeText());
    }

    @Override
    public void onTimeChanged(long time) {
        if (!isOwner() || !player().isVod()) return;
        long position = player().getPosition();
        long duration = player().getDuration();
        if (position < 0 || duration <= 0) return;
        mVod.onTimeChanged(time, position, duration);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (isRedirect()) return;
        if (event.getType() == RefreshEvent.Type.DETAIL) {
            cancelPendingSeek();
            mVod.requestDetail();
        } else if (event.getType() == RefreshEvent.Type.PLAYER) {
            cancelPendingSeek();
            mVod.refresh();
        } else if (event.getType() == RefreshEvent.Type.VOD) {
            cancelPendingSeek();
            mVod.updateVod(event.getVod());
        }
        else if (event.getType() == RefreshEvent.Type.SUBTITLE) player().setSub(Sub.from(event.getPath()));
        else if (event.getType() == RefreshEvent.Type.DANMAKU) player().setDanmaku(Danmaku.from(event.getPath()));
    }

    @Override
    protected long startPositionMs() {
        return mVod == null ? C.TIME_UNSET : mVod.startPositionMs();
    }

    private void setTrackVisible() {
        PlaybackAction.setTracks(player(), mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video);
        compactMoreGrid();
    }

    private void setMediaOptionVisible() {
        PlaybackAction.setMediaOptions(player(), mBinding.control.action.edition, mBinding.control.action.chapter);
        compactMoreGrid();
    }

    @Override
    public void onItemClick(Vod item) {
        cancelPendingSeek();
        mVod.selectSource(item);
    }

    @Override
    public void onParse(Parse item) {
        cancelPendingSeek();
        mVod.selectParse(item);
    }

    private void onPaused() {
        controller().pause();
    }

    private void onPlay() {
        if (mHistory != null && isEnded()) controller().seekTo(mHistory.getOpening());
        if (!player().isEmpty() && isIdle()) controller().prepare();
        controller().play();
    }

    private boolean isFullscreen() {
        return fullscreen;
    }

    private void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    public boolean isUseParse() {
        return useParse;
    }

    public void setUseParse(boolean useParse) {
        this.useParse = useParse;
    }

    private View getFocus1() {
        return mFocus1 == null || mFocus1.getVisibility() != View.VISIBLE ? mBinding.video : mFocus1;
    }

    private View getFocus2() {
        if (isMoreControl(mFocus2)) return mBinding.control.action.more;
        // The time bar is the default stop: a stray OK there pauses instead of skipping to the next episode.
        return !isControlFocus(mFocus2) || mFocus2.getVisibility() != View.VISIBLE || mFocus2 == mBinding.control.action.opening || mFocus2 == mBinding.control.action.ending ? timeBar() : mFocus2;
    }

    private View timeBar() {
        return mBinding.control.seek.findViewById(androidx.media3.ui.R.id.exo_progress);
    }

    /** OK on the time bar toggles playback; a press that commits a scrub stays the time bar's own. */
    private boolean handleTimeBarCenter(KeyEvent event) {
        if (!KeyUtil.isEnterKey(event) || !isVisible(mBinding.control.getRoot()) || getCurrentFocus() != timeBar()) {
            mTimeBarCenter = false;
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) mTimeBarCenter = !isScrubbing();
            return mTimeBarCenter;
        }
        if (event.getAction() != KeyEvent.ACTION_UP || !mTimeBarCenter) return false;
        mTimeBarCenter = false;
        onKeyCenter();
        return true;
    }

    private boolean isControlFocus(View view) {
        if (view == null || !view.isFocusable()) return false;
        View root = mBinding.control.getRoot();
        for (android.view.ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == root) return true;
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isVisible(mBinding.control.action.morePanel) && moveMoreFocus(event)) return true;
        if (isFullscreen() && KeyUtil.isMenuKey(event)) onToggle();
        if (isVisible(mBinding.control.getRoot())) setR1Callback();
        if (handleMediaEpisodeKey(event)) return true;
        if (handleTimeBarCenter(event)) return true;
        int code = event.getKeyCode();
        boolean direction = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (isFullscreen() && isVisible(mBinding.control.getRoot()) && event.getAction() == KeyEvent.ACTION_DOWN && direction && !isControlFocus(getCurrentFocus()) && !isScrubbing()) {
            View target = getFocus2();
            if (target.requestFocusFromTouch()) {
                mFocus2 = target;
                return true;
            }
        }
        if (isVisible(mBinding.control.getRoot()) && isControlFocus(getCurrentFocus())) mFocus2 = getCurrentFocus();
        if (isFullscreen() && isGone(mBinding.control.getRoot()) && mKeyDown.hasEvent(event) && service() != null) return mKeyDown.onKeyDown(event);
        if (handleMediaSeekKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean handleMediaEpisodeKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_MEDIA_PREVIOUS && code != KeyEvent.KEYCODE_MEDIA_NEXT) return false;
        // Consume both edges. Letting ACTION_DOWN reach MediaSession while
        // handling ACTION_UP here executes the same key twice on some TV
        // firmwares.
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) checkPrev();
            else checkNext();
        }
        return true;
    }

    private boolean handleMediaSeekKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_MEDIA_FAST_FORWARD && code != KeyEvent.KEYCODE_MEDIA_REWIND) return false;
        if (controller() == null || !hasPlaybackSource() || isScrubbing()) return true;
        // Consume the press edge so the service MediaSession cannot perform a
        // second seek; apply the one user action on release.
        if (event.getAction() == KeyEvent.ACTION_UP) {
            long intervalMs = mediaSeekInterval(event);
            mKeyDown.onMediaSeek(code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? intervalMs : -intervalMs);
        }
        return true;
    }

    private long mediaSeekInterval(KeyEvent event) {
        return MediaSeekPolicy.intervalMs(event.getEventTime() - event.getDownTime(), event.getRepeatCount());
    }

    @Override
    public void onSeeking(long time) {
        mBinding.widget.center.setVisibility(View.VISIBLE);
        mBinding.widget.duration.setText(player().getDurationTime());
        mBinding.widget.position.setText(player().getPositionTime(time));
        mBinding.widget.action.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        if (time == 0 || controller() == null || !hasPlaybackSource() || isScrubbing()) {
            mKeyDown.reset();
            hideCenter();
            return;
        }
        if (seekTo(time)) hideCenter();
        mKeyDown.reset();
    }

    @Override
    public void onSpeedUp() {
        if (!player().isPlaying()) return;
        PlaybackAction.startSpeedPress(player(), mBinding.widget.message);
    }

    @Override
    public void onSpeedEnd() {
        PlaybackAction.hideSpeedHint(mBinding.widget.message);
        player().setSpeed(SpeedSetting.getPlayback());
    }

    // With the controls hidden Up/Down change the volume; OK (pausing), Menu or a tap opens the controls.
    @Override
    public void onKeyUp() {
        adjustVolume(AudioManager.ADJUST_RAISE);
    }

    @Override
    public void onKeyDown() {
        adjustVolume(AudioManager.ADJUST_LOWER);
    }

    private void adjustVolume(int direction) {
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null) return;
        // HDMI passthrough boxes keep a fixed output level that only the TV can change.
        if (audio.isVolumeFixed()) Notify.show("此设备音量由电视控制，请用电视遥控器调节音量");
        else audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
    }

    @Override
    public void onKeyCenter() {
        if (controller() == null || service() == null) return;
        // Commit the coalesced directional seek before toggling playback. A
        // delayed callback must not resume a pause requested by this key.
        mKeyDown.commitPendingSeek();
        if (controller().getPlayWhenReady()) {
            // Pausing shows the controls, so remotes without a Menu key can still reach them.
            onPaused();
            showControl(getFocus2());
            return;
        }
        if (player().isEmpty()) onRefresh();
        else onPlay();
        hideControl();
    }

    @Override
    public void onSingleTap() {
        if (isFullscreen()) onToggle();
    }

    @Override
    public void onDoubleTap() {
        if (isFullscreen()) onKeyCenter();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == 1001) PlaybackIntent.onExternalResult(data, service()::dispatchNext, controller()::seekTo);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mClock.stop().start();
    }

    @Override
    protected void onPause() {
        // Home/another activity can animate for longer than the seek debounce
        // before onStop. Revoke queued input as soon as this screen loses focus.
        cancelPendingSeek();
        hideCenter();
        super.onPause();
    }

    @Override
    protected void onStop() {
        // The activity may stop without being destroyed (home/PiP/another
        // surface). Do not let a queued key seek mutate a hidden player.
        cancelPendingSeek();
        super.onStop();
        saveHistory(false);
        if (PlayerSetting.isBackgroundOff()) mClock.stop();
    }

    @Override
    protected void onBackInvoked() {
        cancelPendingSeek();
        if (isVisible(mBinding.control.action.morePanel)) {
            hideMore();
        } else if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (isVisible(mBinding.widget.center)) {
            hideCenter();
        } else if (isFullscreen()) {
            exitFullscreen();
        } else {
            mViewModel.stopSearch();
            if (isTaskRoot()) startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        cancelPendingSeek();
        mClock.release();
        saveHistory(true);
        DanmakuApi.cancel();
        RefreshEvent.keep();
        App.removeCallbacks(mR1, mR2, mR3, mR4);
        super.onDestroy();
    }
}
