package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.discovery.DiscoveryCatalog;
import com.fongmi.android.tv.diagnostics.DiagnosticLog;
import com.fongmi.android.tv.discovery.AiDramaCatalog;
import com.fongmi.android.tv.discovery.AiDramaRunner;
import com.fongmi.android.tv.discovery.SourceDiscoveryRunner;
import com.fongmi.android.tv.discovery.SourcePageCursor;
import com.fongmi.android.tv.discovery.DiscoveryScanBudget;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.net.NetTrace;
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
        // Its own dispatcher: queued plugin/background requests on the shared one (5 per host,
        // 64 in all) must never hold the category lists back after a few tab switches.
        okhttp3.Dispatcher dispatcher = new okhttp3.Dispatcher();
        dispatcher.setMaxRequestsPerHost(8);
        client = OkHttp.client().newBuilder().dispatcher(dispatcher).connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build();
        binding.discoveryAction.setOnClickListener(v -> {
            Runnable action = statusAction;
            if (action != null && !busy) action.run();
        });
        binding.discoveryContinue.setOnClickListener(v -> {
            Runnable action = continueAction;
            if (action != null && !busy) action.run();
        });
        // Nine 20sp entries share one row (user 2026-10-23: 16sp was small on a TV). Each keeps
        // its own label width and the spare width is spread evenly; equal shares clipped 4-char
        // labels with the filter indicator. The row's 24dp gutter plus 6dp padding lines the
        // labels up with the 32dp card gutter.
        tabs.add(binding.home);
        binding.home.setOnClickListener(v -> select(0));
        for (int i = 1; i < names.length; i++) {
            int index = i;
            TextView view = button(names[i]);
            view.setTextSize(TAB_TEXT_SP);
            view.setSingleLine(true);
            view.setBackgroundResource(R.drawable.home_category);
            view.setTextColor(androidx.core.content.ContextCompat.getColorStateList(activity, R.color.home_category_text));
            view.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ResUtil.dp2px(44), 1f));
            view.setPadding(ResUtil.dp2px(6), 0, ResUtil.dp2px(6), 0);
            tabs.add(view);
            binding.categories.addView(view);
            view.setOnClickListener(v -> select(index));
        }
        // The filter indicator is a fixed end drawable, hidden unless the tab is open: as an inline text
        // span it changed the tab's width each time it appeared or flipped, so the row jumped on OK and
        // a full row clipped it. Every tab, 主页 included, reserves the same end space, so the labels
        // sit at equal gaps (user 2026-10-24: 主页 was closer to its neighbour than the others).
        for (TextView tab : tabs) {
            tab.setCompoundDrawablePadding(ResUtil.dp2px(3));
            tab.setCompoundDrawableTintList(androidx.core.content.ContextCompat.getColorStateList(activity, R.color.home_category_text));
        }
        binding.categories.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) v.post(this::fitTabs);
        });
        LinearLayout.LayoutParams homeParams = (LinearLayout.LayoutParams) binding.home.getLayoutParams();
        homeParams.weight = 1f;
        binding.home.setLayoutParams(homeParams);
        // Remote focus resting on a tab opens it, without pressing OK (user 2026-10-23). Focus
        // arriving from elsewhere (cards, shortcuts, title) stays on the tab it reached: moving it
        // on to the open tab afterwards flashed the reached tab first (user 2026-10-24, Up from 设置
        // lit 短剧漫剧 and then jumped to 主页). OK on the open tab still toggles filters.
        binding.categories.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            binding.categories.removeCallbacks(focusSelect);
            binding.categories.removeCallbacks(optionSelect);
            if (newFocus == null || oldFocus == null || newFocus.isInTouchMode()) return;
            if (!tabs.contains(newFocus)) { followOptionFocus(oldFocus, newFocus); return; }
            if (tabs.indexOf(newFocus) != active) binding.categories.postDelayed(focusSelect, FOCUS_SELECT_MS);
        });
        select(0);
    }

    private static final int FILTER_INDICATOR_DP = 10;
    private static final float TAB_TEXT_SP = 20;

    private static boolean canFilter(int index) {
        return index == AI_DRAMA || !DiscoveryCatalog.groups(index).isEmpty();
    }

    /**
     * Nine tabs must fit one row whatever the device font: a TV with wider system glyphs or a larger
     * font scale overflowed the 20sp row, and LinearLayout then squeezed the tabs and clipped the
     * indicator. Shrink every label by the same factor only as far as needed.
     */
    private void fitTabs() {
        int available = binding.categories.getWidth() - binding.categories.getPaddingLeft() - binding.categories.getPaddingRight();
        if (available <= 0) return;
        float base = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, TAB_TEXT_SP,
                activity.getResources().getDisplayMetrics());
        android.text.TextPaint paint = new android.text.TextPaint(tabs.get(0).getPaint());
        paint.setTextSize(base);
        float text = 0, fixed = 0;
        for (int i = 0; i < tabs.size(); i++) {
            TextView tab = tabs.get(i);
            text += paint.measureText(names[i]);
            fixed += tab.getPaddingLeft() + tab.getPaddingRight();
            fixed += ResUtil.dp2px(FILTER_INDICATOR_DP) + tab.getCompoundDrawablePadding();
        }
        // A little slack for glyph rounding, so the row is never exactly full.
        float scale = Math.min(1f, (available - fixed - ResUtil.dp2px(8)) / text);
        float size = base * Math.max(.75f, scale);
        for (TextView tab : tabs) if (Math.abs(tab.getTextSize() - size) > .5f) tab.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size);
    }
    // Skips tabs passed over while the remote key is held, so only the resting tab loads.
    private static final long FOCUS_SELECT_MS = 250;
    private final Runnable focusSelect = this::selectFocusedTab;

    private final Runnable optionSelect = this::selectFocusedOption;

    private List<TextView> optionRow(View view) {
        for (List<TextView> row : options.values()) if (row.contains(view)) return row;
        return null;
    }

    /**
     * Filter options follow focus like the tabs (user 2026-10-23): Left/Right within a row applies
     * the option after the same pause; moving into a row from another row or the tab lands on that
     * row's selected option, so passing through never changes a filter. Up/Down between rows go
     * straight to that option (rowStep); the redirect here only covers other ways in, e.g. a mouse
     * wheel, and would flash the first-reached option.
     */
    private void followOptionFocus(View oldFocus, View newFocus) {
        List<TextView> row = optionRow(newFocus);
        if (row == null || newFocus.isSelected()) return;
        if (row.contains(oldFocus)) { binding.categories.postDelayed(optionSelect, FOCUS_SELECT_MS); return; }
        for (TextView view : row) if (view.isSelected()) {
            binding.categories.post(() -> { if (newFocus.isFocused()) view.requestFocus(); });
            return;
        }
    }

    /** Up/Down from an option row lands on the selected option of the neighbouring row directly. */
    private boolean rowStep(View view, int key, KeyEvent event) {
        if (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        List<List<TextView>> rows = new ArrayList<>(options.values());
        int index = rows.indexOf(optionRow(view));
        if (index < 0) return false;
        boolean up = key == KeyEvent.KEYCODE_DPAD_UP;
        if (up && index == 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                focusCurrentTab();
                view.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_UP);
            }
            return true;
        }
        if (!up && index == rows.size() - 1) return false;
        TextView target = null;
        for (TextView option : rows.get(index + (up ? -1 : 1))) if (option.isSelected()) target = option;
        if (target == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            target.requestFocus();
            view.playSoundEffect(up ? android.view.SoundEffectConstants.NAVIGATION_UP : android.view.SoundEffectConstants.NAVIGATION_DOWN);
        }
        return true;
    }

    private void selectFocusedOption() {
        View focused = binding.filterPanel.findFocus();
        if (optionRow(focused) != null && !focused.isSelected() && activeScreen) focused.performClick();
    }

    private void selectFocusedTab() {
        int index = tabs.indexOf(binding.categories.findFocus());
        if (index >= 0 && index != active && activeScreen) select(index);
    }

    /**
     * Up from the cards to the tab row lands on the open tab. Landing on the tab above a card opened that
     * tab and dropped the list being browsed (found 2026-10-24 after 2.1.0 let focus stay where it
     * landed). Decided before focus moves, so nothing flashes; the Home shortcuts and the title still
     * land on the tab above them.
     */
    public boolean upFromCards(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getKeyCode() != KeyEvent.KEYCODE_DPAD_UP) return false;
        View focused = activity.getCurrentFocus();
        if (focused == null || focused.isInTouchMode() || binding.recycler.canScrollVertically(-1)) return false;
        boolean inCards = false;
        for (android.view.ViewParent parent = focused.getParent(); parent != null; parent = parent.getParent()) if (parent == binding.recycler) inCards = true;
        if (!inCards) return false;
        View next = android.view.FocusFinder.getInstance().findNextFocus((android.view.ViewGroup) binding.getRoot(), focused, View.FOCUS_UP);
        View open = tabs.get(isHistory() ? 0 : active);
        if (!tabs.contains(next) || next == open) return false;
        open.requestFocus();
        focused.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_UP);
        return true;
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

    private long roundStart;
    private String roundTab, roundKind;
    // Last provider failure of the current round (class + keyword or HTTP status), for diagnostics only.
    private volatile String roundError;

    private String selectedValue(String key) {
        if (active != AI_DRAMA) return state().value(key);
        AiDramaCatalog.State selected = aiDramaRunner == null ? null : aiDramaRunner.state();
        return selected == null ? "" : selected.value(key);
    }

    // Each app start opens 短剧漫剧 on 全部/默认; only the AI漫剧/AI短剧 type is remembered across launches.
    private static boolean aiDramaFiltersReset;

    private String savedAiDrama() {
        String saved = preferences.getString("ai_drama", "");
        if (aiDramaFiltersReset) return saved;
        aiDramaFiltersReset = true;
        saved = AiDramaCatalog.typeOnly(saved);
        preferences.edit().putString("ai_drama", saved).apply();
        return saved;
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
        setHeadersExpanded(isHome(), activeScreen);
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

    private static final long HEADER_SLIDE_MS = 220;

    /**
     * Shows the Home-only header rows (title/weather/clock and the shortcuts) on 主页 and hides them on the
     * other tabs. The rows used to grow or shrink frame by frame, which laid the whole page out, cards
     * included, on every frame of the 280ms change; on the user's TV that stuttered each time the remote
     * left or reached 主页 (2026-10-25). Now the page is laid out once at its final size and the rows
     * below slide from where they were to where they are, a translation the render thread draws cheaply.
     */
    private void setHeadersExpanded(boolean expanded, boolean animate) {
        ViewGroup column = (ViewGroup) binding.topHeader.getParent();
        boolean changed = binding.topHeader.getVisibility() != (expanded ? View.VISIBLE : View.GONE)
                || binding.shortcutHeader.getVisibility() != (expanded ? View.VISIBLE : View.GONE);
        endHeaderSlide();
        int[] before = new int[column.getChildCount()];
        for (int i = 0; i < before.length; i++) before[i] = column.getChildAt(i).getTop();
        binding.topHeader.setExpanded(expanded, false);
        binding.shortcutHeader.setExpanded(expanded, false);
        if (!changed || !animate || !column.isLaidOut()) return;
        column.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                column.getViewTreeObserver().removeOnPreDrawListener(this);
                android.view.animation.Interpolator ease = new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
                for (int i = 0; i < before.length && i < column.getChildCount(); i++) {
                    View child = column.getChildAt(i);
                    if (child.getVisibility() != View.VISIBLE) continue;
                    if (child == binding.topHeader || child == binding.shortcutHeader) {
                        child.setAlpha(0f);
                        child.animate().alpha(1f).setDuration(HEADER_SLIDE_MS).setInterpolator(ease).start();
                    }
                    int offset = before[i] - child.getTop();
                    if (offset == 0) continue;
                    child.setTranslationY(offset);
                    child.animate().translationY(0f).setDuration(HEADER_SLIDE_MS).setInterpolator(ease).start();
                }
                return true;
            }
        });
    }

    private void endHeaderSlide() {
        ViewGroup column = (ViewGroup) binding.topHeader.getParent();
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            child.animate().cancel();
            child.setTranslationY(0f);
            if (child == binding.topHeader || child == binding.shortcutHeader) child.setAlpha(1f);
        }
    }

    private void updateTabs() {
        boolean expanded = binding.filterPanel.getVisibility() == View.VISIBLE;
        for (int i = 0; i < tabs.size(); i++) {
            TextView tab = tabs.get(i);
            boolean filterable = i == active && canFilter(i);
            tab.setSelected(i == active);
            // Every change below relays the tab out; on a slow TV re-applying all of it to nine tabs on
            // each switch was part of the lag (2026-10-25), so only a changed state is applied.
            String state = filterable + "/" + expanded;
            if (state.equals(tab.getTag(R.id.home_category_state))) continue;
            boolean first = tab.getTag(R.id.home_category_state) == null;
            tab.setTag(R.id.home_category_state, state);
            if (first) {
                tab.setSingleLine(true);
                tab.setMaxLines(1);
                tab.setIncludeFontPadding(true);
                tab.setLineSpacing(0, 1);
                tab.setPadding(tab.getPaddingLeft(), tab.getPaddingTop(), tab.getPaddingRight(), 0);
                tab.setText(names[i]);
            }
            // Same size open or closed, up or down, filterable or not; only visibility and direction change.
            Drawable indicator = androidx.core.content.ContextCompat.getDrawable(activity,
                    filterable && expanded ? R.drawable.home_filter_chevron_up : R.drawable.home_filter_chevron_down).mutate();
            int size = ResUtil.dp2px(FILTER_INDICATOR_DP);
            indicator.setBounds(0, 0, size, size);
            indicator.setAlpha(filterable ? 255 : 0);
            tab.setCompoundDrawablesRelative(null, null, indicator, null);
            // The arrow fades in on the tab that just opened instead of popping up (user 2026-10-25).
            if (filterable && !first && !Boolean.TRUE.equals(tab.getTag(R.id.home_category_arrow))) fadeIn(tab, indicator);
            tab.setTag(R.id.home_category_arrow, filterable);
            int background = filterable ? R.drawable.home_category : R.drawable.home_category_plain;
            if (!Integer.valueOf(background).equals(tab.getTag(R.id.home_category_background))) {
                // An inset background would hand its inset to the padding; keep the tab's own.
                int left = tab.getPaddingLeft(), top = tab.getPaddingTop(), right = tab.getPaddingRight(), bottom = tab.getPaddingBottom();
                tab.setBackgroundResource(background);
                tab.setPadding(left, top, right, bottom);
                tab.setTag(R.id.home_category_background, background);
            }
            tab.setContentDescription(filterable ? names[i] + "，" + (expanded ? "点击收起筛选" : "点击展开筛选") : names[i]);
        }
    }

    private static void fadeIn(TextView tab, Drawable indicator) {
        android.animation.ValueAnimator fade = android.animation.ValueAnimator.ofInt(0, 255);
        fade.setDuration(180);
        fade.addUpdateListener(animation -> {
            indicator.setAlpha((int) animation.getAnimatedValue());
            tab.invalidate();
        });
        fade.start();
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
                // A tall ScrollView searches for Up targets inside itself first, so the first row
                // routes Up explicitly back to the category above the panel (rowStep).
                if (options.isEmpty()) view.setNextFocusUpId(tabs.get(active).getId());
                view.setOnKeyListener(this::rowStep);
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
        roundError = null;
        beginRound("douban");
        int[] remaining = {(int) batches.stream().filter(Objects::isNull).count()};
        for (int i = 0; i < specs.size(); i++) {
            // Keep successful responses and only reissue the failed provider slots.
            if (batches.get(i) != null) continue;
            int slot = i;
            fetch(specs.get(i).effective(), result -> App.post(() -> {
                if (token != generation || activity.isDestroyed() || activity.isFinishing()) return;
                batches.set(slot, result);
                if (--remaining[0] != 0) return;
                finish(specs, batches, targetPage, retryAttempt, token, before);
            }));
        }
    }

    /** One provider page; a Douban refusal of the web endpoint retries once from the mobile API. */
    private void fetch(DiscoveryCatalog.Request spec, Consumer<DiscoveryCatalog.Page> complete) {
        Call call = client.newCall(new Request.Builder().url(spec.url).headers(Headers.of(spec.headers)).build());
        requests.add(call);
        call.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                roundError = NetTrace.reason(error);
                complete.accept(null);
            }
            @Override public void onResponse(Call call, Response response) {
                try (response) {
                    if (DiscoveryCatalog.refused(response.code()) && spec.fallback != null) {
                        DiscoveryCatalog.webRefused();
                        App.post(() -> { if (!call.isCanceled()) fetch(spec.fallback, complete); });
                        return;
                    }
                    if (!response.isSuccessful() || response.body() == null) {
                        roundError = "HTTP" + response.code();
                        throw new IOException("Unavailable");
                    }
                    complete.accept(DiscoveryCatalog.parse(response.body().string()));
                } catch (Exception error) {
                    if (roundError == null) roundError = NetTrace.reason(error);
                    complete.accept(null);
                }
            }
        });
    }

    private void loadAiDrama(boolean retry) {
        loadAiDrama(retry, 0);
    }

    private void loadAiDrama(boolean retry, int retryAttempt) {
        if (aiDramaRunner == null) {
            scanBudget.reset();
            aiDramaRunner = new AiDramaRunner(client, aiDramaCache, savedAiDrama());
            // A successful metadata cache can restore real choices synchronously.
            // If already expanded, show them before waiting for the page request.
            if (binding.filterPanel.getVisibility() == View.VISIBLE) showFilters(true);
        }
        int token = generation;
        busy = true; failed = false; pendingRefresh = false;
        status("");
        int before = items.size();
        beginRound("ai");
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
            String cause = aiDramaRunner.failure();
            logRound(before, batch.failed() ? "failed" + (cause.isEmpty() ? "" : ":" + cause) : batch.metadataFailed() ? "metadata_failed" : null);
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
        beginRound("source");
        Consumer<SourceDiscoveryRunner.Batch> callback = batch -> {
            if (token != generation || activity.isDestroyed() || activity.isFinishing()) return;
            if (sourceRunner == null || !sourceRunner.current()) { configChanged(); configReady(); return; }
            items.clear(); items.addAll(batch.items());
            render.accept(new ArrayList<>(items));
            binding.recycler.setContentDescription("片单，共" + items.size() + "部");
            more = batch.more();
            if (!batch.finished()) return;
            logRound(before, batch.failed() ? "partial_failed" : null);
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
        logRound(targetPage == 0 ? 0 : before, failed ? "failed:" + roundError : null);
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

    private void beginRound(String kind) {
        roundStart = SystemClock.elapsedRealtime();
        roundTab = names[active];
        roundKind = kind;
    }

    /** One line per load round: which tab, how long, how many cards; never titles or URLs. */
    private void logRound(int before, String failure) {
        int added = Math.max(0, items.size() - before);
        writeRound(failure != null ? failure : items.isEmpty() ? "empty" : "ok", added);
    }

    private void writeRound(String outcome, int added) {
        if (roundTab == null) return;
        DiagnosticLog.write(activity, "category_load", "tab=" + roundTab + " kind=" + roundKind + " ms=" + (SystemClock.elapsedRealtime() - roundStart)
                + " items=" + items.size() + " added=" + added + " outcome=" + outcome);
        roundTab = null;
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
        // A round left unfinished (tab switched, screen left) is what "keeps spinning" looks like.
        if (busy) writeRound("cancelled", 0);
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
            endHeaderSlide();
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
