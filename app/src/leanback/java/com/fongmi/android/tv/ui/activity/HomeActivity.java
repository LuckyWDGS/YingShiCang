package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.SearchManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.splashscreen.SplashScreen;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.GridLayoutManager;
import android.graphics.Rect;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.db.BackupManager;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.adapter.BaseDiffCallback;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.custom.CustomTitleView;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.presenter.FuncPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.ui.presenter.DiscoveryPresenter;
import com.fongmi.android.tv.ui.custom.HomeWeather;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import okhttp3.Call;
import okhttp3.Response;
import java.io.IOException;
import java.util.Map;

public class HomeActivity extends BaseActivity implements CustomTitleView.Listener, VodPresenter.OnClickListener, FuncPresenter.OnClickListener, HistoryPresenter.OnClickListener {

    private ActivityHomeBinding mBinding;
    private ArrayObjectAdapter mHistoryAdapter;
    private ArrayObjectAdapter mAdapter;
    private HistoryPresenter mPresenter;
    private SiteViewModel mViewModel;
    private Result mResult;
    private Clock mClock;
    private HomeWeather mWeather;
    private com.fongmi.android.tv.ui.custom.HomeDiscovery discovery;
    private boolean mInitialFocus = true;

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        checkAction(intent);
        if (intent.getAction() == null) returnHome();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mResult = Result.empty();
        mClock = Clock.create(mBinding.clock).format("yyyy年M月d日 HH:mm");
        mWeather = new HomeWeather(this, mBinding.weather, mBinding.weatherInfo, mBinding.weatherIcon);
        mBinding.progressLayout.showProgress();
        PermissionUtil.requestNotify(this);
        DLNARendererService.start(this);
        Updater.create().start(this);
        setRecyclerView();
        setViewModel();
        setAdapter();
        setFunc();
        initConfig();
        setTitle();
        setLogo();
        discovery = new com.fongmi.android.tv.ui.custom.HomeDiscovery(this, mBinding, this::renderDiscovery, () -> getHistory(true));
    }

    @Override
    protected void initEvent() {
        mBinding.title.setListener(this);
        mBinding.recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recycler, int dx, int dy) {
                if (dy > 0 && discovery != null) recycler.post(() -> discovery.loadNearEnd());
            }
        });
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            FileChooser.getUri(intent, uri -> loadLive(UrlUtil.toLocalUrl(uri)));
        } else {
            FileChooser.getUri(intent, uri -> VideoActivity.file(this, uri));
        }
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Integer.class, new HeaderPresenter());
        selector.addPresenter(String.class, new ProgressPresenter());
        selector.addPresenter(Vod.class, new DiscoveryPresenter(this));
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16, FocusHighlight.ZOOM_FACTOR_SMALL, HorizontalGridView.FOCUS_SCROLL_ALIGNED), HistoryPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        GridLayoutManager layout = new GridLayoutManager(this, 5);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position < mAdapter.size() && mAdapter.get(position) instanceof Vod ? 1 : 5;
            }
        });
        mBinding.recycler.setLayoutManager(layout);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(view);
                if (position < 0 || position >= mAdapter.size()) return;
                int gap = ResUtil.dp2px(12);
                out.bottom = gap;
                if (mAdapter.get(position) instanceof Vod) {
                    int column = layout.getSpanSizeLookup().getSpanIndex(position, 5);
                    out.left = column * gap / 5;
                    out.right = gap - (column + 1) * gap / 5;
                }
            }
        });
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, result -> {
            mResult = result;
            Cache.clear().put(result);
        });
    }

    private void setAdapter() {
        mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));

    }

    private void renderDiscovery(List<Vod> items) {
        int offset = 0;
        // Append pages without removing existing view holders: focus and scroll stay put.
        int existing = Math.max(0, mAdapter.size() - offset);
        boolean prefix = !items.isEmpty() && existing > 0 && items.size() >= existing;
        for (int i = 0; prefix && i < existing; i++) prefix = mAdapter.get(offset + i) == items.get(i);
        if (prefix) {
            if (items.size() > existing) mAdapter.addAll(mAdapter.size(), items.subList(existing, items.size()));
        } else {
            mAdapter.clear();
            mAdapter.addAll(mAdapter.size(), items);
        }
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.title.setText(s));
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void start() {
                if (discovery != null) discovery.configChanged();
            }

            @Override
            public void success() {
                showContent();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                showContent();
            }
        };
    }

    private void showContent() {
        mBinding.progressLayout.showContent();
        checkAction(getIntent());
        setFocus();
    }

    private void loadLive(String url) {
        if (isFinishing() || isDestroyed()) return;
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                LiveActivity.start(getActivity());
            }
        });
    }

    private void setFocus() {
        mBinding.title.setSelected(true);
        App.post(() -> mBinding.title.setFocusable(true), 500);
        if (mInitialFocus && !mBinding.recycler.isInTouchMode()) mBinding.recycler.requestFocus();
        mInitialFocus = false;
    }

    private void getVideo() {
        mResult = Result.empty();
        mViewModel.homeContent();
    }

    private void setFunc() {
        List<Func> items = new ArrayList<>();
        items.add(Func.create(R.string.home_recent));
        if (LiveConfig.hasUrl()) items.add(Func.create(R.string.home_live));
        items.add(Func.create(R.string.home_search));
        items.add(Func.create(R.string.home_config));
        items.add(Func.create(R.string.home_lines));
        items.add(Func.create(R.string.home_keep));
        items.add(Func.create(R.string.home_push));
        items.add(Func.create(R.string.home_setting));
        View focused = mBinding.actions.findFocus();
        Object focusTag = focused == null ? null : focused.getTag();
        mBinding.actions.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            Func item = items.get(i);
            var button = com.fongmi.android.tv.databinding.AdapterFuncBinding.inflate(getLayoutInflater(), mBinding.actions, false);
            var params = new android.widget.LinearLayout.LayoutParams(0, ResUtil.dp2px(40), 1);
            button.getRoot().setBackgroundResource(R.drawable.home_shortcut);
            button.text.setTextSize(14);
            button.icon.getLayoutParams().width = ResUtil.dp2px(22);
            button.icon.getLayoutParams().height = ResUtil.dp2px(22);
            params.setMarginEnd(i == items.size() - 1 ? 0 : ResUtil.dp2px(12));
            button.getRoot().setLayoutParams(params); button.getRoot().setTag(item.getResId());
            button.text.setText(item.getText());
            button.icon.setImageResource(item.getResId() == R.string.home_setting ? R.drawable.ic_home_settings_outline
                    : item.getResId() == R.string.home_live ? R.drawable.ic_home_tv_outline : item.getDrawable());
            button.getRoot().setSelected(discovery != null && discovery.isHistory() && item.getResId() == R.string.home_recent);
            button.getRoot().setOnClickListener(view -> onItemClick(item));
            mBinding.actions.addView(button.getRoot());
            if (Integer.valueOf(item.getResId()).equals(focusTag)) button.getRoot().requestFocus();
        }
    }

    private void getHistory() {
        getHistory(false);
    }

    private void getHistory(boolean renew) {
        if (discovery != null && !discovery.isHistory()) return;
        mAdapter.clear();
        mAdapter.add(R.string.home_history);
        mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        List<History> items = History.get();
        if (!items.isEmpty()) mAdapter.add(new ListRow(mHistoryAdapter));
        mHistoryAdapter.setItems(items, new BaseDiffCallback<History>());
    }

    private void setHistoryDelete(boolean delete) {
        mPresenter.setDelete(delete);
        mHistoryAdapter.notifyArrayItemRangeChanged(0, mHistoryAdapter.size());
    }

    private void clearHistory() {
        mAdapter.removeItems(getHistoryIndex(), 1);
        History.clear(VodConfig.getCid());
        mPresenter.setDelete(false);
        mHistoryAdapter.clear();
    }

    private int getHistoryIndex() {
        return mAdapter.indexOf(R.string.home_history) + 1;
    }

    private int getDoubanIndex() {
        return mAdapter.indexOf(R.string.home_douban) + 1;
    }

    private void returnHome() {
        mBinding.recycler.stopScroll();
        if (discovery != null && !discovery.isHome()) discovery.home();
        mBinding.recycler.scrollToPosition(0);
        if (mPresenter.isDelete()) setHistoryDelete(false);
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD_LOADING:
                if (discovery != null) discovery.configChanged();
                break;
            case VOD:
                RefreshEvent.history();
                RefreshEvent.home();
                setLogo();
                break;
            case COMMON:
                setFunc();
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                if (discovery != null) discovery.configChanged();
                getVideo();
                if (discovery != null) discovery.configReady();
                setTitle();
                break;
            case HISTORY:
                getHistory();
                break;
            case SIZE:
                getVideo();
                getHistory(true);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        switch (event.type()) {
            case SEARCH:
                SearchActivity.start(this, event.text());
                break;
            case PUSH:
                VideoActivity.push(this, event.text());
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (VodConfig.get().getConfig().equals(event.config())) {
            VideoActivity.cast(this, event.history());
        } else {
            VodConfig.load(event.config(), getCallback(event));
        }
    }

    private Callback getCallback(CastEvent event) {
        return new Callback() {
            @Override
            public void success() {
                onCastEvent(event);
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    @Override
    public void onItemClick(Func item) {
        if (item.getResId() == R.string.home_recent) { if (discovery != null) discovery.history(); }
        else if (item.getResId() == R.string.home_vod) VodActivity.start(this, mResult);
        else if (item.getResId() == R.string.home_live) LiveActivity.start(this);
        else if (item.getResId() == R.string.home_keep) KeepActivity.start(this);
        else if (item.getResId() == R.string.home_push) PushActivity.start(this);
        else if (item.getResId() == R.string.home_search) SearchActivity.start(this);
        else if (item.getResId() == R.string.home_setting) SettingActivity.start(this);
        else if (item.getResId() == R.string.home_config) com.fongmi.android.tv.ui.dialog.SourceConfigDialog.showOn(this);
        else if (item.getResId() == R.string.home_lines) com.fongmi.android.tv.ui.dialog.SourceLineDialog.showOn(this);
    }

    @Override
    public void onItemClick(Vod item) {
        Site origin = item.getSite();
        if (origin != null) {
            // Site.equals uses its key; an identical key in a replacement config is a different origin.
            boolean current = VodConfig.get().getSites().stream().anyMatch(site -> site == origin)
                    && discovery != null && discovery.isCurrentSource(item);
            if (!current) {
                Notify.show("站源已变更，请重新筛选");
                return;
            }
            if (item.isAction()) mViewModel.action(origin.getKey(), item.getAction());
            else if (origin.isIndex()) CollectActivity.start(this, item.getName());
            else VideoActivity.start(this, origin.getKey(), item.getId(), item.getName(), item.getPic());
        } else if (item.getAction().startsWith("search:")) CollectActivity.start(this, item.getAction().substring("search:".length()));
        else if (item.isAction()) mViewModel.action(getHome().getKey(), item.getAction());
        else if (getHome().isIndex()) CollectActivity.start(this, item.getName());
        else VideoActivity.start(this, getHome().getKey(), item.getId(), item.getName(), item.getPic());
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction()) return false;
        CollectActivity.start(this, item.getName());
        return true;
    }

    @Override
    public void onItemClick(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(History item) {
        mHistoryAdapter.remove(item.delete());
        if (mHistoryAdapter.size() > 0) return;
        mAdapter.removeItems(getHistoryIndex(), 1);
        mPresenter.setDelete(false);
    }

    @Override
    public boolean onLongClick() {
        if (mPresenter.isDelete()) clearHistory();
        else setHistoryDelete(true);
        return true;
    }

    @Override
    public void showDialog() {
        SiteDialog.create().show(this);
    }

    @Override
    public void onRefresh() {
        getVideo();
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isMenuKey(event)) showDialog();
        if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event) && getCurrentFocus() == mBinding.title && mBinding.recycler.getChildCount() > 0) return mBinding.recycler.getChildAt(0).requestFocus();
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mClock.start();
        mWeather.refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mClock.stop();
        mWeather.stop();
    }

    @Override
    protected void onBackInvoked() {
        if (discovery != null && discovery.closeFilters()) {
            return;
        } else if (mBinding.progressLayout.isProgress()) {
            showContent();
        } else if (mPresenter.isDelete()) {
            setHistoryDelete(false);
        } else if (discovery != null && discovery.isHistory()) {
            discovery.home();
        } else if (mBinding.recycler.canScrollVertically(-1)) {
            returnHome();
        } else {
            if (PlaybackService.isRunning()) Util.moveToBackground(this);
            else super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        if (discovery != null) discovery.cancel();
        DLNARendererService.stop(this);
        LiveConfig.get().clear();
        VodConfig.get().clear();
        BackupManager.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }
}
