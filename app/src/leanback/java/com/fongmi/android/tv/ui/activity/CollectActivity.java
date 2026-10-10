package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityCollectBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.GridSpacing;
import com.fongmi.android.tv.ui.presenter.DiscoveryPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.ResUtil;
import java.util.*;

public class CollectActivity extends BaseActivity implements VodPresenter.OnClickListener {
    static final String EXTRA_RETURN_HOME = "return_home_from_search";
    private ActivityCollectBinding binding;
    private SiteViewModel model;
    private ArrayObjectAdapter cards;
    private final List<Vod> items = new ArrayList<>();
    private final Set<String> identities = new HashSet<>();
    private final Map<String, Site> sources = new LinkedHashMap<>();
    private final Map<String, Integer> pages = new HashMap<>();
    private final Set<String> exhausted = new HashSet<>();
    private String selected = "", loadingKey;
    private java.util.concurrent.Future<?> pageJob;
    private com.fongmi.android.tv.api.SiteApi.SearchTask pageTask;
    private int pageEpoch;
    private List<Site> eligible;

    public static void start(Activity activity, String keyword) {
        start(activity, keyword, activity instanceof HomeActivity);
    }
    static void start(Activity activity, String keyword, boolean returnHome) {
        activity.startActivity(new Intent(activity, CollectActivity.class)
                .putExtra("keyword", keyword).putExtra(EXTRA_RETURN_HOME, returnHome));
    }
    private String keyword() { return Objects.toString(getIntent().getStringExtra("keyword"), ""); }
    @Override protected ViewBinding getBinding() { return binding = ActivityCollectBinding.inflate(getLayoutInflater()); }
    @Override protected void initView(Bundle savedInstanceState) {
        model = new ViewModelProvider(this).get(SiteViewModel.class);
        binding.results.setLayoutManager(new GridLayoutManager(this, 5));
        binding.results.addItemDecoration(new GridSpacing(5, 10));
        binding.results.setItemAnimator(null);
        binding.results.setAdapter(new ItemBridgeAdapter(cards = new ArrayObjectAdapter(new DiscoveryPresenter(this, 1.65f))));
        model.getSearch().observe(this, result -> {
            if (result == null) return;
            append(result.getList());
        });
        model.getSearchStatus().observe(this, status -> {
            java.util.regex.Matcher progress = java.util.regex.Pattern.compile("(\\d+)/(\\d+)").matcher(status);
            binding.progress.setText(progress.find() ? "搜索(" + progress.group(1) + "/" + progress.group(2) + ")" : "搜索(0/0)");
            if (status.startsWith("搜索完成") && items.isEmpty()) binding.empty.setText("未找到结果，可返回修改关键词或搜索源");
        });
        search();
    }
    @Override protected void initEvent() {
        binding.results.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override public void onScrolled(androidx.recyclerview.widget.RecyclerView view, int dx, int dy) {
                if (dy > 0) view.post(() -> loadNextPage(false));
            }
            @Override public void onScrollStateChanged(androidx.recyclerview.widget.RecyclerView view, int state) {
                if (state == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) view.post(() -> loadNextPage(false));
            }
        });
        binding.results.setOnGenericMotionListener((view, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_SCROLL && event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL) < 0)
                view.post(() -> loadNextPage(false));
            return false;
        });
    }

    private void loadNextPage(boolean retry) {
        if (selected.isEmpty() || loadingKey != null || exhausted.contains(selected) || (!retry && failedPages.contains(selected))) return;
        GridLayoutManager layout = (GridLayoutManager) binding.results.getLayoutManager();
        if (layout.findLastVisibleItemPosition() < cards.size() - 5) return;
        String key = selected;
        Site source = sources.get(key);
        if (source == null) return;
        int page = pages.getOrDefault(key, 1) + 1;
        String query = com.github.catvod.utils.Trans.t2s(keyword());
        int token = ++pageEpoch;
        loadingKey = key; failedPages.remove(key);
        var task = com.fongmi.android.tv.api.SiteApi.searchTask(source, query, false, String.valueOf(page));
        pageTask = task;
        com.google.common.util.concurrent.FluentFuture<Result> future;
        try {
            com.fongmi.android.tv.utils.Task.purgeSearchTasks();
            future = com.google.common.util.concurrent.FluentFuture.from(com.fongmi.android.tv.utils.Task.searchExecutor(task.isCrawler()).submit(task))
                    .withTimeout(com.fongmi.android.tv.Constant.TIMEOUT_SEARCH, java.util.concurrent.TimeUnit.MILLISECONDS, com.fongmi.android.tv.utils.Task.scheduler());
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            task.close(); pageTask = null; loadingKey = null; failedPages.add(key);
            showPageFailure(key);
            return;
        }
        pageJob = future;
        future.addCallback(com.fongmi.android.tv.utils.Task.callback(result -> {
            task.close();
            App.post(() -> {
            if (token != pageEpoch || isDestroyed() || isFinishing()) return;
            pageTask = null; pageJob = null; loadingKey = null;
            int before = identities.size();
            append(result.getList());
            if (identities.size() == before) exhausted.add(key);
            else pages.put(key, page);
        });
        }, error -> {
            task.close();
            App.post(() -> {
            if (token != pageEpoch || isDestroyed() || isFinishing()) return;
            pageTask = null; pageJob = null; loadingKey = null; failedPages.add(key);
            showPageFailure(key);
        });
        }), com.google.common.util.concurrent.MoreExecutors.directExecutor());
    }
    private final Set<String> failedPages = new HashSet<>();
    private void showPageFailure(String key) {
        if (selected.equals(key)) com.google.android.material.snackbar.Snackbar.make(binding.getRoot(), "此源加载失败", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("重试", view -> { if (selected.equals(key)) loadNextPage(true); }).show();
    }
    private void cancelPage() {
        pageEpoch++;
        if (pageTask != null) pageTask.close();
        if (pageJob != null) pageJob.cancel(true);
        pageTask = null; pageJob = null; loadingKey = null;
        com.fongmi.android.tv.utils.Task.purgeSearchTasks();
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); search(); }
    private void search() {
        cancelPage(); failedPages.clear();
        model.stopSearch(); items.clear(); identities.clear(); sources.clear(); pages.clear(); exhausted.clear(); selected = ""; loadingKey = null;
        eligible = VodConfig.get().getSites().stream().filter(Site::isSearchable).toList();
        com.fongmi.android.tv.setting.Setting.putKeyword(saveKeyword());
        binding.progress.setText("搜索(0/" + eligible.size() + ")");
        binding.results.setContentDescription(keyword() + "，影片列表");
        display(true); rebuildSources();
        model.searchContent(eligible, keyword(), false);
    }
    private String saveKeyword() {
        List<String> history;
        try { history = App.gson().fromJson(com.fongmi.android.tv.setting.Setting.getKeyword(), com.google.gson.reflect.TypeToken.getParameterized(List.class, String.class).getType()); }
        catch (Exception ignored) { history = null; }
        if (history == null) history = new ArrayList<>();
        history.remove(keyword()); history.add(0, keyword());
        return App.gson().toJson(history.subList(0, Math.min(9, history.size())));
    }
    private void append(List<Vod> batch) {
        boolean sourceChanged = false;
        for (Vod item : batch) {
            if (item.getSite() == null || item.getId().isEmpty()) continue;
            if (identities.add(item.getSiteKey() + "\u0000" + item.getId())) items.add(item);
            if (!sources.containsKey(item.getSiteKey())) { sources.put(item.getSiteKey(), item.getSite()); sourceChanged = true; }
        }
        if (sourceChanged) rebuildSources();
        display(false);
    }
    private void rebuildSources() {
        // Existing rows are retained while asynchronous sources arrive, preserving focus.
        if (binding.sources.getChildCount() == 0 || sources.isEmpty()) {
            binding.sources.removeAllViews(); addSource("", "全部来源");
        }
        for (var source : sources.entrySet()) {
            if (binding.sources.findViewWithTag(source.getKey()) == null) addSource(source.getKey(), source.getValue().getName());
        }
        updateSourceSelection();
    }
    private void addSource(String key, String label) {
        android.widget.TextView button = new android.widget.TextView(this);
        button.setText(label); button.setTag(key); button.setTextColor(0xFFF3F6F8); button.setTextSize(14);
        button.setGravity(android.view.Gravity.CENTER_VERTICAL); button.setPadding(ResUtil.dp2px(10), 0, ResUtil.dp2px(10), 0);
        button.setSingleLine(true); button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setFocusable(true); button.setFocusableInTouchMode(false); button.setBackgroundResource(R.drawable.source_sidebar_selector);
        var params = new android.widget.LinearLayout.LayoutParams(-1, ResUtil.dp2px(40)); params.bottomMargin = ResUtil.dp2px(4);
        binding.sources.addView(button, params);
        button.setOnClickListener(v -> { selected = key; updateSourceSelection(); display(true); });
    }
    private void updateSourceSelection() {
        for (int i = 0; i < binding.sources.getChildCount(); i++) binding.sources.getChildAt(i).setSelected(selected.equals(binding.sources.getChildAt(i).getTag()));
    }
    private void display(boolean top) {
        List<Vod> visible = new ArrayList<>();
        for (Vod item : items) if (selected.isEmpty() || selected.equals(item.getSiteKey())) visible.add(item);
        binding.results.setContentDescription(keyword() + "，影片列表，共" + visible.size() + "项");
        cards.setItems(visible, new androidx.leanback.widget.DiffCallback<Vod>() {
            @Override public boolean areItemsTheSame(Vod oldItem, Vod newItem) {
                return oldItem.getSiteKey().equals(newItem.getSiteKey()) && oldItem.getId().equals(newItem.getId());
            }
            @Override public boolean areContentsTheSame(Vod oldItem, Vod newItem) { return oldItem.isSameContent(newItem); }
        });
        binding.empty.setVisibility(visible.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
        binding.empty.setText(eligible != null && eligible.isEmpty() ? "没有启用的搜索源，请先选择搜索源" : "正在等待搜索结果…");
        if (top) binding.results.scrollToPosition(0);
    }
    @Override public void onItemClick(Vod item) {
        setResult(Activity.RESULT_OK);
        if (item.isFolder()) VodActivity.start(this, item.getSiteKey(), Result.folder(item));
        else VideoActivity.collect(this, item.getSiteKey(), item.getId(), item.getName(), item.getPic());
    }
    @Override public boolean onLongClick(Vod item) { return false; }
    @Override protected void onBackInvoked() {
        cancelPage(); model.stopSearch();
        if (getIntent().getBooleanExtra(EXTRA_RETURN_HOME, false)) {
            // Reuse the original home, preserving its category/scroll and removing intermediate screens.
            startActivity(new Intent(this, HomeActivity.class).setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
        super.onBackInvoked();
    }
    @Override protected void onDestroy() { cancelPage(); model.stopSearch(); super.onDestroy(); }
}
