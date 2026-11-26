package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.DiffCallback;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityCollectBinding;
import com.fongmi.android.tv.discovery.AiDramaRunner;
import com.fongmi.android.tv.discovery.ShortDramaTitles;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.resource.ResourceHealth;
import com.fongmi.android.tv.search.SearchPaging;
import com.fongmi.android.tv.search.SearchResultRanker;
import com.fongmi.android.tv.search.SearchSourceGate;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.GridSpacing;
import com.fongmi.android.tv.ui.presenter.DiscoveryPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Trans;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.gson.reflect.TypeToken;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import java.util.*;

public class CollectActivity extends BaseActivity implements VodPresenter.OnClickListener {
    // Progressive search batches are merged into at most one diff per interval.
    private static final long DISPLAY_INTERVAL_MS = 150L;
    static final String EXTRA_RETURN_HOME = "return_home_from_search";
    // Only a keyword the user entered goes into search history; titles searched for a card, 快速搜索 or a
    // favorite do not (user 2026-10-30). The search page saves its own typed keywords.
    static final String EXTRA_RECORD = "record";
    // Titles typed pinyin initials may stand for (SearchActivity.initialsTerms), searched beside them.
    static final String EXTRA_TERMS = "terms";
    static final int MAX_TERMS = 7;
    static final String EXTRA_EXPECTED_YEAR = "expected_year";
    static final String EXTRA_TARGET_TITLE = "target_title";
    private ActivityCollectBinding binding;
    private SiteViewModel model;
    private ArrayObjectAdapter cards;
    private final List<Vod> items = new ArrayList<>();
    private final Set<String> identities = new HashSet<>();
    private final Map<Vod, Integer> ranks = new IdentityHashMap<>();
    private final Map<String, Long> cardIds = new HashMap<>();
    private final Map<Vod, Long> objectIds = new IdentityHashMap<>();
    private final Runnable queuedDisplay = this::flushDisplay;
    private boolean displayQueued;
    private long lastDisplay;
    private final Map<String, Site> sources = new LinkedHashMap<>();
    // Sources with at least one card; only these get a sidebar row (user 2026-10-24: sources that
    // found nothing or failed were listed for nothing).
    private final Set<String> listed = new HashSet<>();
    private String selected = "";
    private long nextCardId;
    private boolean searchComplete;
    private boolean restoringFocus;
    private SearchResultRanker.Target target;
    private SearchResultRanker.Query queryRanker;
    private List<Site> eligible;
    private final SearchSourceGate searchSources = new SearchSourceGate(() -> VodConfig.get().getSites());

    public static void start(Activity activity, String keyword) {
        start(activity, keyword, activity instanceof HomeActivity);
    }
    static void start(Activity activity, String keyword, boolean returnHome) {
        start(activity, keyword, returnHome, List.of());
    }
    static void start(Activity activity, String keyword, boolean returnHome, List<String> terms) {
        activity.startActivity(new Intent(activity, CollectActivity.class)
                .putExtra("keyword", keyword).putExtra(EXTRA_RETURN_HOME, returnHome)
                .putStringArrayListExtra(EXTRA_TERMS, new ArrayList<>(terms)));
    }
    /** A keyword entered outside the search page (phone push, voice), saved to search history. */
    static void startEntered(Activity activity, String keyword) {
        activity.startActivity(new Intent(activity, CollectActivity.class)
                .putExtra("keyword", keyword).putExtra(EXTRA_RETURN_HOME, activity instanceof HomeActivity).putExtra(EXTRA_RECORD, true));
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
        // A held Down used to outrun the smooth scroll: the next row was not laid out yet, so focus
        // left the cards for the source list, which then showed that source instead (LDPlayer
        // 2026-11-15, 482 cards). CardGridView keeps Down in the cards; rows scroll in at once while held.
        binding.results.setLayoutManager(new GridLayoutManager(this, 5) {
            @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child, Rect rect, boolean immediate, boolean focusedChildVisible) {
                return super.requestChildRectangleOnScreen(parent, child, rect, immediate || binding.results.isHoldingDirection(), focusedChildVisible);
            }
        });
        binding.results.addItemDecoration(new GridSpacing(5, 10));
        // A batch merged under a held key could cost a 100-200ms frame (LDPlayer, at the end of the list).
        binding.results.setOnHoldEnd(() -> { if (displayQueued) display(false); });
        binding.results.setItemAnimator(null);
        cards = new ArrayObjectAdapter(new DiscoveryPresenter(this, true)) {
            @Override public long getId(int position) {
                return objectIds.computeIfAbsent((Vod) get(position), item -> cardIds.computeIfAbsent(identity(item), key -> nextCardId++));
            }
        };
        cards.setHasStableIds(true);
        binding.results.setAdapter(new ItemBridgeAdapter(cards));
        model.getSearch().observe(this, result -> {
            if (result == null) return;
            append(result.getSearchRawList(), false);
        });
        model.getSearchStatus().observe(this, status -> {
            Matcher progress = Pattern.compile("(\\d+)/(\\d+)").matcher(status);
            binding.progress.setText(progress.find() ? "搜索(" + progress.group(1) + "/" + progress.group(2) + ")" : "搜索(0/0)");
            searchComplete = status.startsWith("搜索完成") || status.equals("没有启用的搜索源");
            if (displayQueued) flushDisplay();
            updateEmpty();
        });
        search();
    }
    @Override protected void initEvent() {
        binding.getRoot().getViewTreeObserver().addOnGlobalFocusChangeListener(this::followSourceFocus);
        binding.results.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) {
                if (dy > 0 && !restoringFocus) view.post(() -> { if (!restoringFocus) loadNextPage(false); });
            }
            @Override public void onScrollStateChanged(RecyclerView view, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE && !restoringFocus)
                    view.post(() -> { if (!restoringFocus) loadNextPage(false); });
            }
        });
        binding.results.setOnGenericMotionListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_SCROLL && event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0)
                view.post(() -> loadNextPage(false));
            return false;
        });
    }

    /**
     * A detail or the player on top: sources not yet asked wait until the results show again. A 2 GB TV
     * kept the search going under playback and lost its process again and again (MiTV-ASTP0, 2026-11-23).
     */
    @Override protected void onStop() {
        String paused = model.pauseSearch();
        if (!paused.isEmpty()) com.fongmi.android.tv.diagnostics.DiagnosticLog.write(this, "search_paused", paused);
        super.onStop();
    }

    @Override protected void onStart() {
        super.onStart();
        String resumed = model.resumeSearch();
        if (!resumed.isEmpty()) com.fongmi.android.tv.diagnostics.DiagnosticLog.write(this, "search_resumed", resumed);
    }

    @Override protected void onResume() {
        super.onResume();
        // A detail screen can confirm a card is invalid and finish itself. Recheck
        // when the aggregate search becomes visible so the failed card disappears
        // without asking the user to retry the whole search.
        if (cards != null) pruneFilteredCards();
    }

    private void loadNextPage(boolean retry) {
        paging.next(retry, false, false);
    }

    private final SearchPaging paging = new SearchPaging(new SearchPaging.Host() {
        @Override public String selected() { return selected; }
        @Override public boolean canPage() { return terms.isEmpty(); }
        @Override public boolean nearEnd() {
            GridLayoutManager layout = (GridLayoutManager) binding.results.getLayoutManager();
            return layout.findLastVisibleItemPosition() >= cards.size() - 5;
        }
        @Override public Site source(String key) { return sources.get(key); }
        @Override public String query() { return Trans.t2s(keyword()); }
        @Override public boolean alive() { return !isDestroyed() && !isFinishing(); }
        @Override public SearchPaging.Appended append(String key, List<Vod> page) {
            int before = identities.size();
            int shownBefore = cards.size();
            CollectActivity.this.append(page, true);
            return new SearchPaging.Appended(identities.size() > before, cards.size() != shownBefore);
        }
        @Override public void updateEmpty() { CollectActivity.this.updateEmpty(); }
        @Override public void post(Runnable task) { App.post(task); }
        @Override public void postDelayed(Runnable task, long delayMs) { binding.results.postDelayed(task, delayMs); }
        @Override public void remove(Runnable task) { binding.results.removeCallbacks(task); }
    }, CollectActivity::fetchPage);

    /** One page of one source, on the search executor with the usual deadline. */
    private static SearchPaging.Job fetchPage(Site source, String query, int page, Consumer<List<Vod>> done, Consumer<Throwable> failed) {
        SiteApi.SearchTask task = SiteApi.searchTask(source, query, false, String.valueOf(page));
        FluentFuture<Result> future;
        try {
            Task.purgeSearchTasks();
            future = FluentFuture.from(Task.searchExecutor(task.isCrawler()).submit(task))
                    .withTimeout(Constant.TIMEOUT_SEARCH, TimeUnit.MILLISECONDS, Task.scheduler());
        } catch (RejectedExecutionException rejected) {
            task.close();
            throw rejected;
        }
        future.addCallback(Task.callback(result -> {
            task.close();
            done.accept(result.getList());
        }, error -> {
            task.close();
            failed.accept(error);
        }), MoreExecutors.directExecutor());
        return () -> {
            task.close();
            future.cancel(true);
        };
    }

    private void cancelPage() {
        paging.cancel();
        Task.purgeSearchTasks();
    }

    /**
     * Opened in a process where nothing has loaded the configuration (Android brings this screen back on
     * its own after the app was killed in the background), the search waited for a load that never came
     * and stayed on 正在加载搜索源… (LDPlayer 2026-11-16). It starts the load itself, as details do.
     */
    private void awaitConfig() {
        VodConfig.get().whenReady(45_000).whenComplete((ignored, error) -> App.post(() -> {
            if (!isDestroyed() && !isFinishing()) searchSources.configReady();
        }));
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); search(); }
    private void search() {
        paging.reset();
        Task.purgeSearchTasks();
        model.resetSearch(); items.clear(); identities.clear(); ranks.clear(); sources.clear(); listed.clear(); selected = "";
        searchComplete = false;
        String title = Objects.toString(getIntent().getStringExtra(EXTRA_TARGET_TITLE), "");
        target = title.isEmpty() ? null : SearchResultRanker.target(title, expectedYear());
        queryRanker = SearchResultRanker.query(keyword());
        letters = keyword().matches("[A-Za-z]{2,12}") && ShortDramaTitles.canMatchLetters() ? keyword() : null;
        List<String> extra = getIntent().getStringArrayListExtra(EXTRA_TERMS);
        terms = letters == null || extra == null ? List.of() : extra.subList(0, Math.min(MAX_TERMS, extra.size()));
        termRankers = terms.stream().map(SearchResultRanker::query).toList();
        if (getIntent().getBooleanExtra(EXTRA_RECORD, false)) Setting.putKeyword(saveKeyword());
        searchSources.search(this::startSearch);
        if (searchSources.isWaitingForConfig()) awaitConfig();
    }
    private void startSearch(List<Site> sites) {
        eligible = sites;
        // Letters may stand for a 短剧漫剧 title too (user 2026-11-01), so that source joins their search.
        Site drama = letters == null ? null : AiDramaRunner.site(VodConfig.get());
        if (drama != null && !sites.contains(drama)) { eligible = new ArrayList<>(sites); eligible.add(drama); }
        searchComplete = false;
        binding.progress.setText("搜索(0/" + eligible.size() + ")");
        binding.results.setContentDescription(keyword() + "，影片列表");
        display(true); rebuildSources();
        if (searchSources.isWaitingForConfig()) return;
        if (terms.isEmpty()) { model.searchContent(eligible, keyword(), false); return; }
        // The candidate titles first, in the order they were shown, then the letters as typed.
        List<String> queries = new ArrayList<>(terms);
        queries.add(keyword());
        model.searchContent(eligible, queries, false);
    }
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.isVod() && !isFinishing() && !isDestroyed()) searchSources.configReady();
    }
    private String saveKeyword() {
        List<String> history;
        try { history = App.gson().fromJson(Setting.getKeyword(), TypeToken.getParameterized(List.class, String.class).getType()); }
        catch (Exception ignored) { history = null; }
        if (history == null) history = new ArrayList<>();
        history.remove(keyword()); history.add(0, keyword());
        return App.gson().toJson(history.subList(0, Math.min(9, history.size())));
    }
    // Typed letters (xcjc) that reached the results: sources match letters loosely and returned unrelated
    // titles (user 2026-11-01), so only titles those letters spell, or that are one of the titles they may
    // stand for, are shown.
    private String letters;
    private List<String> terms = List.of();
    private List<SearchResultRanker.Query> termRankers = List.of();

    // Sources match letters loosely and return unrelated titles for initials and English words alike.
    private boolean lettersMatch(String name) {
        return letters == null || letterScore(name) < NOT_RELATED;
    }

    /**
     * Typed letters: titles of the candidates in the order they were suggested, then titles the letters
     * spell (pinyin, English text or word initials); unrelated last.
     */
    private int letterScore(String name) {
        for (int i = 0; i < termRankers.size(); i++) {
            SearchResultRanker.Query term = termRankers.get(i);
            if (term.related(name)) return i * TERM_STEP + term.score(name);
        }
        // The letters as text (an English title, a bilibili upload named xcjc), never a two-letter overlap.
        if (queryRanker.related(name)) return termRankers.size() * TERM_STEP + queryRanker.score(name);
        if (ShortDramaTitles.contains(name, letters)) return termRankers.size() * TERM_STEP + 50_000;
        return NOT_RELATED;
    }

    // Above every candidate's score: from the fifth candidate on it passes SearchResultRanker's UNRELATED.
    private static final int TERM_STEP = 60_000, NOT_RELATED = 10_000_000;

    /** By relevance alone, whatever the source (bilibili is no longer put last, user 2026-11-03). */
    private int rank(Vod item) {
        return letters != null ? letterScore(item.getName()) : queryRanker.score(item.getName());
    }

    private void append(List<Vod> batch, boolean immediate) {
        boolean sourceChanged = false;
        for (Vod item : batch) {
            if (item == null || item.getSite() == null || item.getId().isEmpty()) continue;
            // Raw identities and source presence also own pagination, including
            // successful batches which contain no match for the clicked card.
            if (identities.add(identity(item))) {
                int rank = target != null ? target.score(item.getName(), item.getYear()) : rank(item);
                if (ResourceHealth.isSearchCard(item) && (target == null || target.accepts(rank)) && lettersMatch(item.getName())) {
                    items.add(item);
                    ranks.put(item, rank);
                    if (listed.add(item.getSiteKey())) sourceChanged = true;
                }
            }
            sources.putIfAbsent(item.getSiteKey(), item.getSite());
        }
        // Stable sorting lets slow exact hits rise above unrelated fast-source
        // rows while retaining arrival order for equally relevant resources.
        items.sort(Comparator.comparingInt(ranks::get));
        if (sourceChanged) rebuildSources();
        // Paging compares the visible count right after appending, so it cannot wait.
        if (immediate) display(false);
        else scheduleDisplay();
    }

    private void scheduleDisplay() {
        if (displayQueued) return;
        if (binding.results.isHoldingDirection()) {
            displayQueued = true;
            return;
        }
        long wait = lastDisplay + DISPLAY_INTERVAL_MS - SystemClock.uptimeMillis();
        if (wait <= 0) {
            display(false);
            return;
        }
        displayQueued = true;
        binding.results.postDelayed(queuedDisplay, wait);
    }

    /** A queued batch waits while a key is held on the cards; the end of the hold delivers it. */
    private void flushDisplay() {
        if (!displayQueued || binding.results.isHoldingDirection()) return;
        display(false);
    }

    private void pruneFilteredCards() {
        // Keep raw rows and ranks so expiry/recovery can reveal them again.
        display(false);
    }
    private void rebuildSources() {
        // Existing rows are retained while asynchronous sources arrive, preserving focus.
        // 全部来源 stays pinned above the list; only the individual sources scroll.
        if (sources.isEmpty()) binding.sources.removeAllViews();
        if (binding.allSource.getChildCount() == 0) addSource(binding.allSource, "", "全部来源");
        for (var source : sources.entrySet()) {
            if (!listed.contains(source.getKey())) continue;
            if (binding.sources.findViewWithTag(source.getKey()) == null) addSource(binding.sources, source.getKey(), source.getValue().getName());
        }
        updateSourceSelection();
    }
    private void addSource(ViewGroup parent, String key, String label) {
        TextView button = new TextView(this);
        button.setText(label); button.setTag(key); button.setTextColor(ResUtil.getColor(R.color.tv_theme_text)); button.setTextSize(14);
        button.setGravity(Gravity.CENTER_VERTICAL); button.setPadding(ResUtil.dp2px(10), 0, ResUtil.dp2px(10), 0);
        button.setSingleLine(true); button.setEllipsize(TextUtils.TruncateAt.END);
        button.setFocusable(true); button.setFocusableInTouchMode(false); button.setBackgroundResource(R.drawable.source_sidebar_selector);
        var params = new LinearLayout.LayoutParams(-1, ResUtil.dp2px(40)); params.bottomMargin = ResUtil.dp2px(4);
        parent.addView(button, params);
        button.setOnClickListener(v -> {
            // Returning to a source starts a new user-driven paging attempt.
            boolean changed = !selected.equals(key);
            if (changed) Task.purgeSearchTasks();
            paging.select(key, changed);
            selected = key;
            updateSourceSelection(); display(true);
            binding.results.post(() -> loadNextPage(false));
        });
    }
    private static final long FOCUS_SELECT_MS = 250;
    private final Runnable focusSelect = this::selectFocusedSource;

    private boolean isSource(View view) {
        return view != null && (view.getParent() == binding.allSource || view.getParent() == binding.sources);
    }

    private boolean focusPending;

    private void selectFocusedSource() {
        focusPending = false;
        View focused = getCurrentFocus();
        if (isSource(focused) && !selected.equals(focused.getTag())) focused.performClick();
    }

    private void markSelected(View source) {
        for (ViewGroup group : new ViewGroup[]{binding.allSource, binding.sources})
            for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).setSelected(group.getChildAt(i) == source);
    }

    /**
     * Remote focus moving between sources shows that source without pressing OK (user 2026-10-23);
     * keys held across several load only the resting one. The selected mark moves with focus at
     * once (user 2026-10-24: the previous source stayed marked until the new one loaded); leaving
     * for the results before the pause shows the source that was marked. Focus arriving from the
     * results lands on the selected source rather than the nearest row.
     */
    private void followSourceFocus(View oldFocus, View newFocus) {
        boolean pending = focusPending;
        focusPending = false;
        binding.results.removeCallbacks(focusSelect);
        if (pending && isSource(oldFocus) && !isSource(newFocus) && !selected.equals(oldFocus.getTag())) oldFocus.performClick();
        if (oldFocus == null || !isSource(newFocus) || newFocus.isInTouchMode()) return;
        if (isSource(oldFocus)) {
            if (!selected.equals(newFocus.getTag())) {
                markSelected(newFocus);
                focusPending = true;
                binding.results.postDelayed(focusSelect, FOCUS_SELECT_MS);
            } else updateSourceSelection();
            return;
        }
        View current = binding.allSource.findViewWithTag(selected);
        if (current == null) current = binding.sources.findViewWithTag(selected);
        View target = current;
        if (target != null && target != newFocus) binding.results.post(() -> { if (newFocus.isFocused()) target.requestFocus(); });
    }

    private void updateSourceSelection() {
        if (focusPending && isSource(getCurrentFocus())) { markSelected(getCurrentFocus()); return; }
        for (ViewGroup group : new ViewGroup[]{binding.allSource, binding.sources})
            for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).setSelected(selected.equals(group.getChildAt(i).getTag()));
    }
    private void display(boolean top) {
        // Any direct display also delivers a queued batch.
        if (displayQueued) binding.results.removeCallbacks(queuedDisplay);
        displayQueued = false;
        lastDisplay = SystemClock.uptimeMillis();
        RecyclerView.ViewHolder focused = binding.results.findFocus() == null ? null
                : binding.results.findContainingViewHolder(binding.results.findFocus());
        long focusedId = !top && focused != null ? focused.getItemId() : RecyclerView.NO_ID;
        int focusedPosition = focused == null ? RecyclerView.NO_POSITION : focused.getBindingAdapterPosition();
        long revision = VodConfig.getDiscoveryRevision();
        // The penalty is 0 or 1: a stable partition equals the former stable sort
        // and keeps relevance/arrival order, with one health lookup per card.
        List<Vod> visible = new ArrayList<>();
        List<Vod> penalized = new ArrayList<>();
        for (Vod item : items) {
            if (!selected.isEmpty() && !selected.equals(item.getSiteKey())) continue;
            if (ResourceHealth.isFiltered(revision, item.getSiteKey(), item.getId())) continue;
            if (ResourceHealth.searchPenalty(revision, item.getSiteKey(), item.getId()) > 0) penalized.add(item);
            else visible.add(item);
        }
        visible.addAll(penalized);
        binding.results.setContentDescription(keyword() + "，影片列表，共" + visible.size() + "项");
        cards.setItems(visible, new DiffCallback<Vod>() {
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
            binding.results.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
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
        binding.empty.setVisibility(cards.size() == 0 ? View.VISIBLE : View.GONE);
        if (searchSources.isWaitingForConfig()) binding.empty.setText("正在加载搜索源…");
        else if (eligible != null && eligible.isEmpty()) binding.empty.setText("没有启用的搜索源，请先选择搜索源");
        else if (target != null && !selected.isEmpty() && paging.emptyScans() >= 3 && !paging.isExhausted(selected))
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
        searchSources.cancel();
        cancelPage(); model.stopSearch();
        if (getIntent().getBooleanExtra(EXTRA_RETURN_HOME, false)) {
            // Reuse the original home, preserving its category/scroll and removing intermediate screens.
            startActivity(new Intent(this, HomeActivity.class).setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
        super.onBackInvoked();
    }
    /**
     * Left from the results goes straight to the selected source. Moving focus there after it had
     * reached the nearest row first flashed that row (the same flash the home tabs had).
     */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT && event.getAction() == KeyEvent.ACTION_DOWN) {
            View focused = getCurrentFocus();
            if (focused != null && !isSource(focused)) {
                View next = leftOf(focused);
                View current = binding.allSource.findViewWithTag(selected);
                if (current == null) current = binding.sources.findViewWithTag(selected);
                if (isSource(next) && current != null && current != next) {
                    current.requestFocus();
                    focused.playSoundEffect(SoundEffectConstants.NAVIGATION_LEFT);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }
    /**
     * The view Left would reach. A card removed by an arriving batch can still hold the focus for a frame;
     * FocusFinder then throws "parameter must be a descendant of this view" (a Hisense TV, 2026-11-25).
     */
    private View leftOf(View focused) {
        try { return FocusFinder.getInstance().findNextFocus((ViewGroup) binding.getRoot(), focused, View.FOCUS_LEFT); }
        catch (IllegalArgumentException detached) { return null; }
    }
    @Override protected void onDestroy() { binding.results.removeCallbacks(queuedDisplay); binding.results.removeCallbacks(focusSelect); searchSources.cancel(); cancelPage(); model.stopSearch(); super.onDestroy(); }
}
