package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.KeyEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.discovery.DiscoveryCatalog;
import com.fongmi.android.tv.discovery.AiDramaCatalog;
import com.fongmi.android.tv.discovery.AiDramaRunner;
import com.fongmi.android.tv.discovery.SourceDiscoveryRunner;
import com.fongmi.android.tv.discovery.SourcePageCursor;
import com.fongmi.android.tv.discovery.DiscoveryScanBudget;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.net.OkHttp;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class HomeDiscovery {
    private static final int AI_DRAMA = 8;
    private static final int HISTORY = 9;
    private static final int MAX_AUTO_RETRIES = 2;
    private static final long AUTO_RETRY_DELAY_MS = 700L;
    private record Choice(String label, String value) {}
    private record FilterGroup(String key, String label, List<Choice> options) {}
    private final Activity activity;
    private final ActivityHomeBinding binding;
    private final Consumer<List<Vod>> render;
    private final Runnable showHistory;
    private final SharedPreferences preferences;
    private final OkHttpClient client;
    private final List<Call> requests = new ArrayList<>();
    private final List<Vod> items = new ArrayList<>();
    private final List<TextView> tabs = new ArrayList<>();
    private final Map<String, List<TextView>> options = new LinkedHashMap<>();
    private List<FilterGroup> displayedGroups = List.of();
    private final Map<Integer, DiscoveryCatalog.State> states = new HashMap<>();
    private final Map<Integer, List<FilterGroup>> catalogGroups = new HashMap<>();
    private List<AiDramaCatalog.Group> declaredAiGroups = List.of();
    private List<FilterGroup> aiGroups = List.of();
    private final SourceDiscoveryRunner.MetadataCache sourceCache = new SourceDiscoveryRunner.MetadataCache();
    private SourceDiscoveryRunner sourceRunner;
    private final AiDramaRunner.MetadataCache aiDramaCache = new AiDramaRunner.MetadataCache();
    private AiDramaRunner aiDramaRunner;
    private final Map<Integer, SourcePageCursor> providerCursors = new HashMap<>();
    private final DiscoveryScanBudget scanBudget = new DiscoveryScanBudget();
    private final String[] names = {"主页", "热播电影", "热播剧集", "热播综艺", "电影筛选", "电视筛选", "电影榜单", "电视剧榜单", "短剧漫剧"};
    // Filter controls are deliberately lazy.  Category switches happen often and the
    // panel is closed after the first click, so constructing every row there only adds
    // layout work before the user has asked to see it.
    private int filterViewTab = -1;
    private int active, generation, page;
    private boolean busy, failed, pendingRefresh, more = true;
    private boolean activeScreen = true;
    private Runnable autoRetry;
    private Runnable statusAction;
    private Runnable continueAction;

    public HomeDiscovery(Activity activity, ActivityHomeBinding binding, Consumer<List<Vod>> render, Runnable showHistory) {
        this.activity = activity;
        this.binding = binding;
        this.render = render;
        this.showHistory = showHistory;
        preferences = activity.getSharedPreferences("discovery_filters", Context.MODE_PRIVATE);
        client = OkHttp.client().newBuilder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build();
        binding.discoveryAction.setOnClickListener(v -> {
            Runnable action = statusAction;
            if (action != null && !busy) action.run();
        });
        binding.discoveryContinue.setOnClickListener(v -> {
            Runnable action = continueAction;
            if (action != null && !busy) action.run();
        });
        // Nine entries share one row; reserve the short Home label's spare width
        // for the longest ranking title and its inline filter indicator.
        LinearLayout.LayoutParams homeParams = (LinearLayout.LayoutParams) binding.home.getLayoutParams();
        homeParams.weight = 0.8f;
        binding.home.setLayoutParams(homeParams);
        tabs.add(binding.home);
        binding.home.setOnClickListener(v -> select(0));
        for (int i = 1; i < names.length; i++) {
            int index = i;
            TextView view = button(names[i]);
            view.setTextSize(16);
            view.setSingleLine(true);
            view.setBackgroundResource(R.drawable.home_category);
            view.setTextColor(androidx.core.content.ContextCompat.getColorStateList(activity, R.color.home_category_text));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ResUtil.dp2px(40), names[i].length() >= 5 ? 1.2f : 1f);
            params.setMarginEnd(i == names.length - 1 ? 0 : ResUtil.dp2px(8));
            view.setLayoutParams(params);
            int padding = ResUtil.dp2px(names[i].length() >= 5 ? 2 : 8);
            view.setPadding(padding, 0, padding, 0);
            tabs.add(view);
            binding.categories.addView(view);
            view.setOnClickListener(v -> select(index));
        }
        select(0);
    }

    public boolean isHistory() { return active == HISTORY; }
    public boolean isHome() { return active == 0; }
    public boolean isAiDrama() { return active == AI_DRAMA; }
    private boolean isSourceFilter() { return active == 4 || active == 5; }
    public boolean isCurrentSource(Vod item) {
        return active == AI_DRAMA ? aiDramaRunner != null && aiDramaRunner.owns(item)
                : isSourceFilter() && sourceRunner != null && sourceRunner.owns(item);
    }
    public void configChanged() {
        cancel(); sourceCache.clear(); aiDramaCache.clear();
        for (DiscoveryCatalog.State state : states.values()) state.select("content", "");
        items.clear(); render.accept(new ArrayList<>());
        pendingRefresh = false; more = false; failed = false;
        updateOptions(); status("");
    }
    public void configReady() {
        if (isHistory()) { showHistory.run(); status(""); return; }
        updateOptions(); load(0);
    }
    public void home() { select(0); }
    public void history() { select(HISTORY); }

    private List<FilterGroup> filterGroups() {
        if (active == AI_DRAMA) {
            List<AiDramaCatalog.Group> declared = aiDramaRunner == null ? List.of() : aiDramaRunner.groups();
            if (!declaredAiGroups.equals(declared)) {
                declaredAiGroups = declared;
                aiGroups = declared.stream().map(group -> new FilterGroup(group.key, group.label, group.options.stream()
                        .map(option -> new Choice(option.label, option.value)).toList())).toList();
            }
            return aiGroups;
        }
        return catalogGroups.computeIfAbsent(active, tab -> DiscoveryCatalog.groups(tab).stream().filter(group -> !group.key.equals("content"))
                .map(group -> new FilterGroup(group.key, group.label, group.options.stream()
                        .map(option -> new Choice(option.label, option.value)).toList())).toList());
    }

    private String selectedValue(String key) {
        if (active != AI_DRAMA) return state().value(key);
        AiDramaCatalog.State selected = aiDramaRunner == null ? null : aiDramaRunner.state();
        return selected == null ? "" : selected.value(key);
    }

    private void persistAiDrama() {
        AiDramaCatalog.State selected = aiDramaRunner == null ? null : aiDramaRunner.state();
        if (selected != null) preferences.edit().putString("ai_drama", selected.serialize()).apply();
    }

    private DiscoveryCatalog.State state() {
        return states.computeIfAbsent(active, tab -> {
            DiscoveryCatalog.State value = new DiscoveryCatalog.State(tab);
            value.restore(preferences.getString("tab_" + tab, ""));
            return value;
        });
    }

    private void persist() {
        preferences.edit().putString("tab_" + active, state().serialize()).apply();
    }

    private TextView button(String label) {
        TextView view = new TextView(activity);
        view.setId(View.generateViewId());
        view.setText(label);
        view.setTextColor(ResUtil.getColor(com.fongmi.android.tv.R.color.tv_theme_text));
        view.setTextSize(14);
        view.setGravity(android.view.Gravity.CENTER);
        view.setFocusable(true);
        view.setFocusableInTouchMode(false);
        view.setBackgroundResource(R.drawable.discovery_filter_control);
        view.setPadding(ResUtil.dp2px(12), 0, ResUtil.dp2px(12), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, ResUtil.dp2px(34));
        params.setMarginEnd(ResUtil.dp2px(6));
        view.setLayoutParams(params);
        return view;
    }

    private void select(int index) {
        if (active == index && index > 0 && index < names.length) {
            showFilters(binding.filterPanel.getVisibility() != View.VISIBLE);
            return;
        }
        cancel();
        if (isSourceFilter()) state().select("content", "");
        active = index;
        page = 0;
        scanBudget.reset();
        more = true;
        failed = false;
        pendingRefresh = false;
        boolean headerFocused = binding.actions.hasFocus() || binding.toolbar.hasFocus();
        if (headerFocused && !isHome()) focusCurrentTab();
        binding.topHeader.setExpanded(isHome(), activeScreen);
        binding.shortcutHeader.setExpanded(isHome(), activeScreen);
        for (int i = 0; i < binding.actions.getChildCount(); i++) {
            View action = binding.actions.getChildAt(i);
            action.setSelected(isHistory() && Integer.valueOf(R.string.home_recent).equals(action.getTag()));
        }
        binding.recycler.scrollToPosition(0);
        showFilters(false);
        status("");
        if (isHistory()) { showHistory.run(); return; }
        items.clear();
        // Provider/source loads clear their own first page; AI enters its runner directly.
        if (active == AI_DRAMA) render.accept(new ArrayList<>());
        load(0);
    }

    private void updateTabs() {
        boolean expanded = binding.filterPanel.getVisibility() == View.VISIBLE;
        for (int i = 0; i < tabs.size(); i++) {
            TextView tab = tabs.get(i);
            boolean filterable = i == active && (i == AI_DRAMA || !DiscoveryCatalog.groups(i).isEmpty());
            tab.setSelected(i == active);
            tab.setSingleLine(true);
            tab.setMaxLines(1);
            tab.setIncludeFontPadding(true);
            tab.setLineSpacing(0, 1);
            tab.setPadding(tab.getPaddingLeft(), tab.getPaddingTop(), tab.getPaddingRight(), 0);
            if (filterable) {
                String hint = expanded ? "点击收起筛选" : "点击展开筛选";
                SpannableString label = new SpannableString(names[i] + "\u2009\uFFFC");
                Drawable indicator = androidx.core.content.ContextCompat.getDrawable(activity,
                        expanded ? R.drawable.home_filter_chevron_up : R.drawable.home_filter_chevron_down).mutate();
                indicator.setTint(tab.getCurrentTextColor());
                int size = ResUtil.dp2px(12);
                indicator.setBounds(0, 0, size, size);
                label.setSpan(new ImageSpan(indicator, ImageSpan.ALIGN_BASELINE), label.length() - 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tab.setText(label);
                tab.setContentDescription(names[i] + "，" + hint);
            } else {
                tab.setText(names[i]);
                tab.setContentDescription(names[i]);
            }
        }
    }

    private void showFilters(boolean visible) {
        boolean filterable = visible && (active == AI_DRAMA || !filterGroups().isEmpty());
        if (filterable) { ensureFilters(); updateOptionSelections(); }
        binding.filterPanel.setVisibility(filterable ? View.VISIBLE : View.GONE);
        updateTabs();
        updateDownFocus();
        if (!visible) binding.recycler.post(this::loadNearEnd);
    }

    private void updateDownFocus() {
        if (active < tabs.size()) {
            int next = View.NO_ID;
            if (binding.filterPanel.getVisibility() == View.VISIBLE && !options.isEmpty()) {
                List<TextView> firstRow = options.values().iterator().next();
                for (TextView view : firstRow) if (view.isSelected()) next = view.getId();
            }
            if (next == View.NO_ID && binding.discoveryAction.getVisibility() == View.VISIBLE)
                next = binding.discoveryAction.getId();
            tabs.get(active).setNextFocusDownId(next);
            binding.discoveryAction.setNextFocusUpId(tabs.get(active).getId());
            binding.discoveryAction.setNextFocusDownId(binding.recycler.getId());
            binding.discoveryContinue.setNextFocusUpId(tabs.get(active).getId());
            binding.discoveryContinue.setNextFocusDownId(binding.recycler.getId());
            binding.discoveryAction.setNextFocusRightId(binding.discoveryContinue.getVisibility() == View.VISIBLE
                    ? binding.discoveryContinue.getId() : View.NO_ID);
            binding.discoveryContinue.setNextFocusLeftId(binding.discoveryAction.getId());
        }
    }

    public boolean closeFilters() {
        if (binding.filterPanel.getVisibility() != View.VISIBLE) return false;
        showFilters(false);
        if (!binding.filterPanel.isInTouchMode()) focusCurrentTab();
        return true;
    }

    private void filters() {
        binding.filters.removeAllViews();
        options.clear();
        displayedGroups = List.of();
        filterViewTab = active;
        if (isHome() || isHistory()) return;
        List<FilterGroup> groups = filterGroups();
        displayedGroups = groups;
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText(names[active]);
        title.setTextColor(ResUtil.getColor(com.fongmi.android.tv.R.color.tv_theme_text));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ResUtil.dp2px(38), 1));
        binding.filters.addView(header);
        for (FilterGroup group : groups) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView label = new TextView(activity);
            label.setText(group.label);
            label.setTextColor(ResUtil.getColor(com.fongmi.android.tv.R.color.tv_theme_text));
            label.setTextSize(13);
            label.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int labelWidth = ResUtil.dp2px(active == 5 ? 88 : 58);
            row.addView(label, new LinearLayout.LayoutParams(labelWidth, ResUtil.dp2px(40)));
            HorizontalScrollView scroll = new HorizontalScrollView(activity);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipToPadding(false);
            scroll.setFocusable(false);
            LinearLayout values = new LinearLayout(activity);
            values.setGravity(android.view.Gravity.CENTER_VERTICAL);
            scroll.addView(values);
            row.addView(scroll, new LinearLayout.LayoutParams(0, ResUtil.dp2px(40), 1));
            List<TextView> controls = new ArrayList<>();
            String selected = selectedValue(group.key);
            for (Choice option : group.options) {
                TextView view = button(option.label);
                view.setTag(option.value);
                view.setSelected(selected.equals(option.value));
                view.setContentDescription(group.label + "：" + option.label);
                view.setOnClickListener(v -> {
                    if (active == AI_DRAMA) {
                        if (aiDramaRunner == null || !aiDramaRunner.select(group.key, option.value)) return;
                        generation++;
                        cancelAutoRetry();
                        busy = false; failed = false; more = true; scanBudget.reset();
                        persistAiDrama();
                        if (displayedGroups.equals(filterGroups())) updateOptions();
                        else rebuildFiltersPreservingFocus();
                        binding.recycler.scrollToPosition(0);
                        loadAiDrama(false);
                        return;
                    }
                    if (!state().select(group.key, option.value)) return;
                    persist();
                    updateOptions();
                    load(0);
                });
                values.addView(view);
                controls.add(view);
            }
            // Keep horizontal D-pad navigation within a row, including its scroll viewport.
            for (int i = 0; i < controls.size(); i++) {
                TextView view = controls.get(i);
                view.setNextFocusLeftId(controls.get(Math.max(0, i - 1)).getId());
                view.setNextFocusRightId(controls.get(Math.min(controls.size() - 1, i + 1)).getId());
                if (options.isEmpty()) {
                    view.setNextFocusUpId(tabs.get(active).getId());
                    // A tall ScrollView searches for Up targets inside itself first.
                    // Route the first row explicitly back to the category above the panel.
                    view.setOnKeyListener((v, key, event) -> {
                        if (key != KeyEvent.KEYCODE_DPAD_UP) return false;
                        if (event.getAction() == KeyEvent.ACTION_DOWN) focusCurrentTab();
                        return true;
                    });
                }
            }
            options.put(group.key, controls);
            binding.filters.addView(row);
        }
        int maxHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.58f);
        binding.filterPanel.getLayoutParams().height = Math.min(ResUtil.dp2px(62 + groups.size() * 40), maxHeight);
        binding.filterPanel.requestLayout();
        binding.filterPanel.scrollTo(0, 0);
    }

    private void ensureFilters() {
        if (isHome() || isHistory()) return;
        // Native discovery groups are static.  AI groups can arrive later, so the
        // active-tab marker is also checked to avoid reusing a stale tab's controls.
        if (filterViewTab == active && displayedGroups.equals(filterGroups())) return;
        filters();
    }

    private void rebuildFiltersPreservingFocus() {
        boolean visible = binding.filterPanel.getVisibility() == View.VISIBLE;
        // Metadata may arrive while the panel is closed.  Defer its rows until the
        // next explicit expand instead of doing hidden UI work for every response.
        if (!visible) return;
        String focusKey = null;
        Object focusValue = null;
        for (var group : options.entrySet()) for (TextView view : group.getValue()) {
            if (!view.hasFocus() || view.isInTouchMode()) continue;
            focusKey = group.getKey(); focusValue = view.getTag();
        }
        filters(); showFilters(visible);
        if (focusKey == null || !visible) return;
        List<TextView> row = options.getOrDefault(focusKey, List.of());
        for (TextView view : row) if (Objects.equals(view.getTag(), focusValue)) {
            view.requestFocus(); return;
        }
        for (TextView view : row) if (view.isSelected()) { view.requestFocus(); return; }
        focusCurrentTab();
    }

    private void updateOptions() {
        if (binding.filterPanel.getVisibility() == View.VISIBLE) showFilters(true);
    }

    private void updateOptionSelections() {
        if (filterViewTab != active) return;
        for (Map.Entry<String, List<TextView>> group : options.entrySet()) {
            String selected = selectedValue(group.getKey());
            for (TextView view : group.getValue()) view.setSelected(selected.equals(view.getTag()));
        }
    }

    private void status(String text) {
        status(text, "", null);
    }

    private void status(String text, String label, Runnable action) {
        status(text, label, action, null);
    }

    private void status(String text, String label, Runnable action, Runnable resume) {
        boolean losingFocus = binding.discoveryAction.hasFocus() && action == null
                || binding.discoveryContinue.hasFocus() && resume == null;
        statusAction = action;
        continueAction = resume;
        binding.discoveryStatus.setText(text);
        binding.discoveryAction.setText(label);
        binding.discoveryAction.setVisibility(action == null ? View.GONE : View.VISIBLE);
        binding.discoveryContinue.setText("继续查找");
        binding.discoveryContinue.setVisibility(resume == null ? View.GONE : View.VISIBLE);
        binding.discoveryStatusRow.setVisibility(text.isEmpty() && action == null ? View.GONE : View.VISIBLE);
        if (losingFocus && !binding.discoveryAction.isInTouchMode()) focusCurrentTab();
        updateDownFocus();
    }

    private void outcome(String message, Runnable retry, Runnable resume) {
        if (failed) status(message, "重试", () -> {
            cancelAutoRetry(); scanBudget.reset(); retry.run();
        }, scanBudget.paused() && more ? () -> { cancelAutoRetry(); scanBudget.reset(); resume.run(); } : null);
        else if (scanBudget.paused()) status(items.isEmpty() ? "暂未找到符合条件的内容" : "暂未找到更多符合条件的内容",
                "继续查找", () -> { scanBudget.reset(); resume.run(); });
        else status(message);
    }

    private void load(int targetPage) {
        load(targetPage, 0);
    }

    private void load(int targetPage, int retryAttempt) {
        if (active == AI_DRAMA) { loadAiDrama(false, retryAttempt); return; }
        if (isSourceFilter()) { loadSource(targetPage == 0, false); return; }
        cancel();
        int token = generation;
        busy = true;
        failed = false;
        if (targetPage == 0) {
            scanBudget.reset();
            pendingRefresh = true;
            providerCursors.clear();
            items.clear(); render.accept(new ArrayList<>());
        }
        // Normal refresh/paging is intentionally silent; the cards themselves
        // provide progress and empty/error states remain available when needed.
        status("");
        List<DiscoveryCatalog.Request> specs = DiscoveryCatalog.requests(state(), targetPage);
        List<DiscoveryCatalog.Page> batches = new ArrayList<>(Collections.nCopies(specs.size(), null));
        requestProvider(specs, batches, targetPage, retryAttempt, token);
    }

    private void requestProvider(List<DiscoveryCatalog.Request> specs, List<DiscoveryCatalog.Page> batches,
                                 int targetPage, int retryAttempt, int token) {
        busy = true;
        status("");
        int before = items.size();
        int[] remaining = {(int) batches.stream().filter(Objects::isNull).count()};
        for (int i = 0; i < specs.size(); i++) {
            // Keep successful responses and only reissue the failed provider slots.
            if (batches.get(i) != null) continue;
            int slot = i;
            DiscoveryCatalog.Request spec = specs.get(i);
            Call call = client.newCall(new Request.Builder().url(spec.url).headers(Headers.of(spec.headers)).build());
            requests.add(call);
            call.enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) { complete(null); }
                @Override public void onResponse(Call call, Response response) {
                    try (response) {
                        if (!response.isSuccessful() || response.body() == null) throw new IOException("Unavailable");
                        complete(DiscoveryCatalog.parse(response.body().string()));
                    } catch (Exception error) { complete(null); }
                }
                private void complete(DiscoveryCatalog.Page result) {
                    App.post(() -> {
                        if (token != generation || activity.isDestroyed() || activity.isFinishing()) return;
                        batches.set(slot, result);
                        if (--remaining[0] != 0) return;
                        finish(specs, batches, targetPage, retryAttempt, token, before);
                    });
                }
            });
        }
    }

    private void loadAiDrama(boolean retry) {
        loadAiDrama(retry, 0);
    }

    private void loadAiDrama(boolean retry, int retryAttempt) {
        if (aiDramaRunner == null) {
            scanBudget.reset();
            aiDramaRunner = new AiDramaRunner(client, aiDramaCache, preferences.getString("ai_drama", ""));
            // A successful metadata cache can restore real choices synchronously.
            // If already expanded, show them before waiting for the page request.
            if (binding.filterPanel.getVisibility() == View.VISIBLE) showFilters(true);
        }
        int token = generation;
        busy = true; failed = false; pendingRefresh = false;
        status("");
        int before = items.size();
        aiDramaRunner.load(retry, batch -> {
            if (token != generation || active != AI_DRAMA || activity.isDestroyed() || activity.isFinishing()) return;
            if (aiDramaRunner == null || !aiDramaRunner.current()) { configChanged(); configReady(); return; }
            List<FilterGroup> groups = filterGroups();
            boolean rebuild = filterViewTab != active || !displayedGroups.equals(groups);
            if (rebuild) rebuildFiltersPreservingFocus();
            else updateOptions();
            persistAiDrama();
            items.clear(); items.addAll(batch.items());
            render.accept(new ArrayList<>(items));
            binding.recycler.setContentDescription("片单，共" + items.size() + "部");
            more = batch.more();
            if (!batch.finished()) return;
            busy = false; failed = batch.failed() || batch.metadataFailed();
            if (failed) scheduleAutoRetry(token, retryAttempt, () -> loadAiDrama(true, retryAttempt + 1));
            else cancelAutoRetry();
            scanBudget.accept(before, items.size(), more);
            boolean scanMore = more && !failed && !scanBudget.paused();
            String message = batch.failed() ? items.isEmpty() ? "短剧漫剧加载失败" : "加载更多失败，已保留当前片单"
                    : batch.metadataFailed() ? items.isEmpty() ? "题材筛选加载失败" : "题材筛选加载失败，已保留当前片单"
                    : items.isEmpty() ? more ? "暂未找到符合条件的短剧漫剧" : "暂无符合条件的短剧漫剧" : "";
            // A failure that will be retried automatically stays silent: showing it for the
            // ~0.7s backoff only flashed "加载失败" before the retry cleared it.
            if (failed && autoRetry != null) status("");
            else outcome(scanMore && items.isEmpty() ? "" : message,
                    () -> loadAiDrama(true, 0), () -> loadAiDrama(false, 0));
            if (scanMore) binding.recycler.post(() -> {
                if (token != generation || active != AI_DRAMA || busy || !activeScreen) return;
                if (items.isEmpty()) loadAiDrama(false);
                else loadNearEnd();
            });
        });
    }

    private void loadSource(boolean reset, boolean retry) {
        loadSource(reset, retry, false);
    }

    private void loadSource(boolean reset, boolean retry, boolean manual) {
        if (reset) {
            cancel();
            items.clear(); render.accept(new ArrayList<>());
            page = 0; more = true; scanBudget.reset();
            sourceRunner = new SourceDiscoveryRunner(state(), client, sourceCache);
        }
        if (sourceRunner == null || !sourceRunner.current()) { configChanged(); return; }
        int token = generation;
        busy = true; failed = false; pendingRefresh = false;
        // Load silently; reserve the status row for actionable failures.
        status("");
        int before = items.size();
        Consumer<SourceDiscoveryRunner.Batch> callback = batch -> {
            if (token != generation || activity.isDestroyed() || activity.isFinishing()) return;
            if (sourceRunner == null || !sourceRunner.current()) { configChanged(); configReady(); return; }
            items.clear(); items.addAll(batch.items());
            render.accept(new ArrayList<>(items));
            binding.recycler.setContentDescription("片单，共" + items.size() + "部");
            more = batch.more();
            if (!batch.finished()) return;
            busy = false; failed = batch.failed(); page++;
            if (failed) scheduleAutoRetry(token, sourceRunner.retryAttempt(), () -> loadSource(false, true));
            else cancelAutoRetry();
            boolean adult = !state().value("content").isEmpty();
            // Retrying an unchanged failed cursor did not scan a healthy page.
            // Only actual page rounds consume its empty-result budget; recovery
            // with new visible cards still resets that budget.
            if (!retry || items.size() > before) scanBudget.accept(before, items.size(), more);
            boolean scanMore = more && !scanBudget.paused();
            String message = items.isEmpty() ? "暂无符合条件的站源内容" : "";
            if (batch.unsupported() > 0) message = items.isEmpty() ? "站源未提供符合所选条件的分类或标签" : "部分站源不支持所选筛选条件";
            if (failed) message = "部分站源加载失败";
            if (adult && items.isEmpty()) {
                message = more ? "暂未找到符合条件的18+内容" : failed ? "已加载站源暂无符合条件的18+内容" : "当前站源暂无符合条件的18+内容";
                if (failed) message += "，部分站源加载失败";
                // Keep intermediate empty rounds silent, then let the user continue
                // without scanning hundreds of source pages in the background.
                if (scanMore) message = "";
            }
            if (failed && !items.isEmpty()) {
                // Partial failures stay silent while usable cards remain.
                // Keep the independent scan-pause recovery reachable.
                if (scanBudget.paused() && more) status("暂未找到更多符合条件的内容", "继续查找", () -> {
                    cancelAutoRetry(); scanBudget.reset(); loadSource(false, false);
                });
                else status("");
            } else outcome(scanMore && !failed && items.isEmpty() ? "" : message,
                    () -> loadSource(false, true, true), () -> loadSource(false, false));
            if (scanMore) binding.recycler.post(() -> {
                if (token != generation || busy || !activeScreen) return;
                if (items.isEmpty()) loadSource(false, false);
                else loadNearEnd();
            });
        };
        if (manual) sourceRunner.retryFailed(callback);
        else sourceRunner.next(retry, callback);
    }

    private void finish(List<DiscoveryCatalog.Request> specs, List<DiscoveryCatalog.Page> batches, int targetPage,
                        int retryAttempt, int token, int before) {
        requests.clear();
        busy = false;
        failed = batches.contains(null);
        if (failed) scheduleAutoRetry(token, retryAttempt, () -> requestProvider(specs, batches, targetPage, retryAttempt + 1, token));
        else cancelAutoRetry();
        List<Vod> next = targetPage == 0 ? new ArrayList<>() : new ArrayList<>(items);
        Set<String> ids = new HashSet<>();
        for (Vod item : next) ids.add(item.getId());
        int count = batches.stream().filter(Objects::nonNull).mapToInt(batch -> batch.entries.size()).max().orElse(0);
        for (int i = 0; i < count; i++) for (DiscoveryCatalog.Page batch : batches)
            if (batch != null && i < batch.entries.size()) {
                DiscoveryCatalog.Entry entry = batch.entries.get(i);
                if (ids.add(entry.id)) next.add(entry.toVod());
            }
        items.clear();
        items.addAll(next);
        if (!failed) page = targetPage;
        pendingRefresh = false;
        more = false;
        for (int i = 0; i < batches.size(); i++) if (!failed && batches.get(i) != null) {
            SourcePageCursor cursor = providerCursors.computeIfAbsent(i, slot -> new SourcePageCursor());
            cursor.accept(batches.get(i).rawIds, 0);
            if (!cursor.exhausted() && batches.get(i).hasMore(specs.get(i))) more = true;
        }
        // Visible emptiness after adult/promo filtering says nothing about raw provider pages.
        binding.recycler.setContentDescription("片单，共" + items.size() + "部");
        render.accept(new ArrayList<>(items));
        if (targetPage == 0) binding.recycler.scrollToPosition(0);
        String message = items.isEmpty() ? "暂无符合条件的片单" : "";
        if (failed) message = items.isEmpty() ? message + "，部分片单加载失败" : "部分片单加载失败";
        scanBudget.accept(before, items.size(), more);
        outcome(isHome() && !failed ? "" : message,
                () -> requestProvider(specs, batches, targetPage, 0, token), () -> load(page + 1));
        // Fill short viewports and keep auto-pagination usable after layout finishes.
        int nextToken = generation;
        if (more && !failed && !scanBudget.paused()) binding.recycler.post(() -> {
            if (busy || nextToken != generation || !activeScreen) return;
            if (items.isEmpty()) load(page + 1);
            else loadNearEnd();
        });
    }

    public void focusCurrentTab() {
        View target = isHistory() ? tabs.get(0) : tabs.get(active);
        if (target != null) target.requestFocus();
    }

    public void loadNearEnd() {
        if (!activeScreen || busy || scanBudget.paused() || (failed && !isSourceFilter()) || pendingRefresh || !more || isHistory() || items.isEmpty()
                || binding.filterPanel.getVisibility() == View.VISIBLE) return;
        var layout = (androidx.recyclerview.widget.GridLayoutManager) binding.recycler.getLayoutManager();
        if (layout != null && layout.findLastVisibleItemPosition() >= layout.getItemCount() - 10) load(page + 1);
    }

    public void cancel() {
        generation++;
        cancelAutoRetry();
        statusAction = null;
        continueAction = null;
        if (aiDramaRunner != null) aiDramaRunner.cancel();
        aiDramaRunner = null;
        if (sourceRunner != null) sourceRunner.cancel();
        sourceRunner = null;
        for (Call call : requests) call.cancel();
        requests.clear();
        busy = false;
    }

    private void scheduleAutoRetry(int token, int attempt, Runnable operation) {
        if (autoRetry != null || attempt >= MAX_AUTO_RETRIES || token != generation || activity.isDestroyed() || activity.isFinishing()) return;
        long delay = AUTO_RETRY_DELAY_MS * (attempt + 1L);
        final Runnable[] holder = new Runnable[1];
        holder[0] = () -> {
            if (autoRetry != holder[0] || token != generation || activity.isDestroyed() || activity.isFinishing()) return;
            if (!activeScreen) return;
            // Healthy source paging may finish during backoff. Never invalidate an
            // in-flight round just to retry a different source.
            if (busy) { App.post(holder[0], 200); return; }
            autoRetry = null;
            operation.run();
        };
        autoRetry = holder[0];
        if (activeScreen) App.post(holder[0], delay);
    }

    public void setActive(boolean value) {
        activeScreen = value;
        if (!value) {
            binding.topHeader.finishAnimation();
            binding.shortcutHeader.finishAnimation();
        }
        if (autoRetry != null) {
            App.removeCallbacks(autoRetry);
            if (value) App.post(autoRetry, AUTO_RETRY_DELAY_MS);
        }
    }

    private void cancelAutoRetry() {
        if (autoRetry != null) {
            App.removeCallbacks(autoRetry);
            autoRetry = null;
        }
    }
}
