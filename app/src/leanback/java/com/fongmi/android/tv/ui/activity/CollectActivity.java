package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityCollectBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.resource.ResourceHealth;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.GridSpacing;
import com.fongmi.android.tv.ui.presenter.DiscoveryPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.search.SearchResultRanker;
import java.util.*;

public class CollectActivity extends BaseActivity implements VodPresenter.OnClickListener {
    private static final int MAX_PAGE_AUTO_RETRIES = 2;
    private static final long PAGE_AUTO_RETRY_DELAY_MS = 700L;
    static final String EXTRA_RETURN_HOME = "return_home_from_search";
    static final String EXTRA_EXPECTED_YEAR = "expected_year";
    static final String EXTRA_TARGET_TITLE = "target_title";
    private ActivityCollectBinding binding;
    private SiteViewModel model;
    private ArrayObjectAdapter cards;
    private final List<Vod> items = new ArrayList<>();
    private final Set<String> identities = new HashSet<>();
    private final Map<String, Integer> ranks = new HashMap<>();
    private final Map<String, Long> cardIds = new HashMap<>();
    private final Map<String, Site> sources = new LinkedHashMap<>();
    private final Map<String, Integer> pages = new HashMap<>();
    private final Set<String> exhausted = new HashSet<>();
    private final Map<String, Integer> pageRetryAttempts = new HashMap<>();
    private final Map<String, Runnable> pendingPageRetries = new HashMap<>();
    private String selected = "", loadingKey;
    private java.util.concurrent.Future<?> pageJob;
    private com.fongmi.android.tv.api.SiteApi.SearchTask pageTask;
    private int pageEpoch;
    private int emptyPageScans;
    private long nextCardId;
    private boolean searchComplete;
    private boolean restoringFocus;
    private SearchResultRanker.Target target;
    private SearchResultRanker.Query queryRanker;
    private List<Site> eligible;

    public static void start(Activity activity, String keyword) {
        start(activity, keyword, activity instanceof HomeActivity);
    }
    static void start(Activity activity, String keyword, boolean returnHome) {
        activity.startActivity(new Intent(activity, CollectActivity.class)
                .putExtra("keyword", keyword).putExtra(EXTRA_RETURN_HOME, returnHome));
    }
    public static void startForCard(Activity activity, String keyword, String expectedYear, boolean returnHome) {
        activity.startActivity(new Intent(activity, CollectActivity.class)
                .putExtra("keyword", keyword)
                .putExtra(EXTRA_TARGET_TITLE, keyword)
                .putExtra(EXTRA_EXPECTED_YEAR, expectedYear == null ? "" : expectedYear)
                .putExtra(EXTRA_RETURN_HOME, returnHome));
    }
    private String keyword() { return Objects.toString(getIntent().getStringExtra("keyword"), ""); }
    private String expectedYear() { return Objects.toString(getIntent().getStringExtra(EXTRA_EXPECTED_YEAR), ""); }
    @Override protected ViewBinding getBinding() { return binding = ActivityCollectBinding.inflate(getLayoutInflater()); }
    @Override protected void initView(Bundle savedInstanceState) {
        model = new ViewModelProvider(this).get(SiteViewModel.class);
        binding.results.setLayoutManager(new GridLayoutManager(this, 5));
        binding.results.addItemDecoration(new GridSpacing(5, 10));
        binding.results.setItemAnimator(null);
        cards = new ArrayObjectAdapter(new DiscoveryPresenter(this, true)) {
            @Override public long getId(int position) {
                return cardIds.computeIfAbsent(identity((Vod) get(position)), key -> nextCardId++);
            }
        };
        cards.setHasStableIds(true);
        binding.results.setAdapter(new ItemBridgeAdapter(cards));
        model.getSearch().observe(this, result -> {
            if (result == null) return;
            append(result.getSearchRawList());
        });
        model.getSearchStatus().observe(this, status -> {
            java.util.regex.Matcher progress = java.util.regex.Pattern.compile("(\\d+)/(\\d+)").matcher(status);
            binding.progress.setText(progress.find() ? "搜索(" + progress.group(1) + "/" + progress.group(2) + ")" : "搜索(0/0)");
            searchComplete = status.startsWith("搜索完成") || status.equals("没有启用的搜索源");
            updateEmpty();
        });
        search();
    }
    @Override protected void initEvent() {
        binding.results.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override public void onScrolled(androidx.recyclerview.widget.RecyclerView view, int dx, int dy) {
                if (dy > 0 && !restoringFocus) view.post(() -> { if (!restoringFocus) loadNextPage(false); });
            }
            @Override public void onScrollStateChanged(androidx.recyclerview.widget.RecyclerView view, int state) {
                if (state == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE && !restoringFocus)
                    view.post(() -> { if (!restoringFocus) loadNextPage(false); });
            }
        });
        binding.results.setOnGenericMotionListener((view, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_SCROLL && event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL) < 0)
                view.post(() -> loadNextPage(false));
            return false;
        });
    }

    @Override protected void onResume() {
        super.onResume();
        // A detail screen can confirm a card is invalid and finish itself. Recheck
        // when the aggregate search becomes visible so the failed card disappears
        // without asking the user to retry the whole search.
        if (cards != null) pruneFilteredCards();
    }

    private void loadNextPage(boolean retry) {
        loadNextPage(retry, false, false);
    }
    private void loadNextPage(boolean retry, boolean scanning) {
        loadNextPage(retry, scanning, false);
    }
    private void loadNextPage(boolean retry, boolean scanning, boolean force) {
        if (selected.isEmpty() || loadingKey != null || exhausted.contains(selected) || (!retry && failedPages.contains(selected))) return;
        if (scanning && emptyPageScans >= 3) return;
        GridLayoutManager layout = (GridLayoutManager) binding.results.getLayoutManager();
        if (!force && layout.findLastVisibleItemPosition() < cards.size() - 5) return;
        String key = selected;
        Site source = sources.get(key);
        if (source == null) return;
        int page = pages.getOrDefault(key, 1) + 1;
        String retryKey = pageRetryKey(key, page);
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
            schedulePageRetry(key, page, token);
            return;
        }
        pageJob = future;
        future.addCallback(com.fongmi.android.tv.utils.Task.callback(result -> {
            task.close();
            App.post(() -> {
            if (token != pageEpoch || isDestroyed() || isFinishing()) return;
            pageTask = null; pageJob = null; loadingKey = null;
            int before = identities.size();
            int visibleBefore = selected.equals(key) ? cards.size() : -1;
            append(result.getList());
            if (identities.size() == before) exhausted.add(key);
            else pages.put(key, page);
            pageRetryAttempts.remove(retryKey);
            // A filtered-out page still advances the source cursor.  Scan a few
            // pages when no matching card is visible, then let a source click continue.
            if (selected.equals(key) && cards.size() == visibleBefore && identities.size() > before) {
                emptyPageScans++;
                updateEmpty();
                binding.results.post(() -> loadNextPage(false, true));
            } else if (selected.equals(key)) emptyPageScans = 0;
        });
        }, error -> {
            task.close();
            App.post(() -> {
            if (token != pageEpoch || isDestroyed() || isFinishing()) return;
            pageTask = null; pageJob = null; loadingKey = null; failedPages.add(key);
            schedulePageRetry(key, page, token);
        });
        }), com.google.common.util.concurrent.MoreExecutors.directExecutor());
    }
    private final Set<String> failedPages = new HashSet<>();
    private String pageRetryKey(String key, int page) { return key + "\u0000" + page; }
    private void schedulePageRetry(String key, int page, int token) {
        if (!selected.equals(key) || isDestroyed() || isFinishing()) return;
        String retryKey = pageRetryKey(key, page);
        int attempt = pageRetryAttempts.getOrDefault(retryKey, 0);
        if (attempt >= MAX_PAGE_AUTO_RETRIES) { updateEmpty(); return; }
        pageRetryAttempts.put(retryKey, attempt + 1);
        Runnable retry = () -> {
            pendingPageRetries.remove(retryKey);
            if (token != pageEpoch || !selected.equals(key) || isDestroyed() || isFinishing()) return;
            loadNextPage(true, false, true);
        };
        Runnable old = pendingPageRetries.put(retryKey, retry);
        if (old != null) binding.results.removeCallbacks(old);
        binding.results.postDelayed(retry, PAGE_AUTO_RETRY_DELAY_MS * (attempt + 1L));
    }
    private void cancelPage() {
        pageEpoch++;
        if (pageTask != null) pageTask.close();
        if (pageJob != null) pageJob.cancel(true);
        pageTask = null; pageJob = null; loadingKey = null;
        for (Runnable retry : pendingPageRetries.values()) binding.results.removeCallbacks(retry);
        pendingPageRetries.clear();
        com.fongmi.android.tv.utils.Task.purgeSearchTasks();
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); search(); }
    private void search() {
        cancelPage(); failedPages.clear(); pageRetryAttempts.clear();
        model.resetSearch(); items.clear(); identities.clear(); ranks.clear(); sources.clear(); pages.clear(); exhausted.clear(); selected = ""; loadingKey = null;
        emptyPageScans = 0; searchComplete = false;
        String title = Objects.toString(getIntent().getStringExtra(EXTRA_TARGET_TITLE), "");
        target = title.isEmpty() ? null : SearchResultRanker.target(title, expectedYear());
        queryRanker = SearchResultRanker.query(keyword());
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
            if (item == null || item.getSite() == null || item.getId().isEmpty()) continue;
            // Raw identities and source presence also own pagination, including
            // successful batches which contain no match for the clicked card.
            if (identities.add(identity(item))) {
                int rank = target == null ? queryRanker.score(item.getName()) : target.score(item.getName(), item.getYear());
                if (ResourceHealth.isSearchCard(item) && (target == null || target.accepts(rank))) {
                    items.add(item);
                    ranks.put(identity(item), rank);
                }
            }
            if (!sources.containsKey(item.getSiteKey())) { sources.put(item.getSiteKey(), item.getSite()); sourceChanged = true; }
        }
        // Stable sorting lets slow exact hits rise above unrelated fast-source
        // rows while retaining arrival order for equally relevant resources.
        items.sort(Comparator.comparingInt(item -> ranks.get(identity(item))));
        if (sourceChanged) rebuildSources();
        display(false);
    }

    private void pruneFilteredCards() {
        // Keep raw rows and ranks so expiry/recovery can reveal them again.
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
        button.setText(label); button.setTag(key); button.setTextColor(ResUtil.getColor(com.fongmi.android.tv.R.color.tv_theme_text)); button.setTextSize(14);
        button.setGravity(android.view.Gravity.CENTER_VERTICAL); button.setPadding(ResUtil.dp2px(10), 0, ResUtil.dp2px(10), 0);
        button.setSingleLine(true); button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setFocusable(true); button.setFocusableInTouchMode(false); button.setBackgroundResource(R.drawable.source_sidebar_selector);
        var params = new android.widget.LinearLayout.LayoutParams(-1, ResUtil.dp2px(40)); params.bottomMargin = ResUtil.dp2px(4);
        binding.sources.addView(button, params);
        button.setOnClickListener(v -> {
            if (!selected.equals(key)) {
                cancelPage();
                // Returning to a source starts a new user-driven paging attempt.
                failedPages.remove(key);
                pageRetryAttempts.keySet().removeIf(value -> value.startsWith(key + "\u0000"));
            }
            selected = key; emptyPageScans = 0;
            updateSourceSelection(); display(true);
            binding.results.post(() -> loadNextPage(false));
        });
    }
    private void updateSourceSelection() {
        for (int i = 0; i < binding.sources.getChildCount(); i++) binding.sources.getChildAt(i).setSelected(selected.equals(binding.sources.getChildAt(i).getTag()));
    }
    private void display(boolean top) {
        RecyclerView.ViewHolder focused = binding.results.findFocus() == null ? null
                : binding.results.findContainingViewHolder(binding.results.findFocus());
        long focusedId = !top && focused != null ? focused.getItemId() : RecyclerView.NO_ID;
        int focusedPosition = focused == null ? RecyclerView.NO_POSITION : focused.getBindingAdapterPosition();
        List<Vod> visible = new ArrayList<>();
        for (Vod item : items) if ((selected.isEmpty() || selected.equals(item.getSiteKey()))
                && !ResourceHealth.isFiltered(VodConfig.getDiscoveryRevision(), item.getSiteKey(), item.getId())) visible.add(item);
        // Sort a copy so recovery restores original relevance and arrival order.
        long revision = VodConfig.getDiscoveryRevision();
        visible.sort(Comparator.comparingInt(item -> ResourceHealth.searchPenalty(revision, item.getSiteKey(), item.getId())));
        binding.results.setContentDescription(keyword() + "，影片列表，共" + visible.size() + "项");
        cards.setItems(visible, new androidx.leanback.widget.DiffCallback<Vod>() {
            @Override public boolean areItemsTheSame(Vod oldItem, Vod newItem) {
                return oldItem.getSiteKey().equals(newItem.getSiteKey()) && oldItem.getId().equals(newItem.getId());
            }
            @Override public boolean areContentsTheSame(Vod oldItem, Vod newItem) { return oldItem.isSameContent(newItem); }
        });
        updateEmpty();
        if (top) binding.results.scrollToPosition(0);
        else if (focusedId != RecyclerView.NO_ID) {
            for (int i = 0; i < cards.size(); i++) if (cards.getId(i) == focusedId) {
                // Stable IDs retain the actual focused Vod after a diff. Keep it
                // laid out too, so an insertion cannot refocus a different card.
                if (i != focusedPosition) restoreFocusedPosition(i);
                break;
            }
        }
    }
    private void restoreFocusedPosition(int position) {
        if (!restoringFocus) {
            restoringFocus = true;
            binding.results.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    binding.results.getViewTreeObserver().removeOnPreDrawListener(this);
                    restoringFocus = false;
                    return true;
                }
            });
        }
        // This layout scroll preserves identity; it is not a request to fetch
        // another page. Clear the guard only after RecyclerView has laid it out.
        binding.results.scrollToPosition(position);
    }
    private static String identity(Vod item) { return item.getSiteKey() + "\u0000" + item.getId(); }
    private void updateEmpty() {
        binding.empty.setVisibility(cards.size() == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        if (eligible != null && eligible.isEmpty()) binding.empty.setText("没有启用的搜索源，请先选择搜索源");
        else if (target != null && !selected.isEmpty() && emptyPageScans >= 3 && !exhausted.contains(selected))
            binding.empty.setText("此来源暂未找到匹配内容，可点击来源继续查找");
        else if (target != null && searchComplete) binding.empty.setText("未找到与所选影片匹配的内容，可切换来源继续查找");
        else if (searchComplete) binding.empty.setText("未找到结果，可返回修改关键词或搜索源");
        else binding.empty.setText("正在等待搜索结果…");
    }
    @Override public void onItemClick(Vod item) {
        if (item == null || ResourceHealth.isFiltered(VodConfig.getDiscoveryRevision(), item.getSiteKey(), item.getId())) {
            pruneFilteredCards();
            return;
        }
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
