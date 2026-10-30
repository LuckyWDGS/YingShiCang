package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Word;
import com.fongmi.android.tv.databinding.ActivitySearchBinding;
import com.fongmi.android.tv.discovery.HotRank;
import com.fongmi.android.tv.model.SearchSuggestionController;
import com.fongmi.android.tv.ui.adapter.RecordAdapter;
import com.fongmi.android.tv.ui.adapter.WordAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomKeyboard;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.utils.ZhuToPin;
import com.github.catvod.net.OkHttp;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexboxLayoutManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class SearchActivity extends BaseActivity implements WordAdapter.OnClickListener, RecordAdapter.OnClickListener, CustomKeyboard.Callback {
    private ActivitySearchBinding mBinding;
    private RecordAdapter mRecordAdapter;
    private WordAdapter mWordAdapter;
    // Typed suggestions also list titles from the 短剧漫剧 (AiDj) source (user 2026-10-24: iQIYI's
    // suggestions only cover TV, film and variety). Its plugin search runs beside the iQIYI request.
    private static final long DRAMA_DEBOUNCE_MS = 400;
    private static final int DRAMA_WORDS = 8, DRAMA_AFTER = 6;
    private final java.util.concurrent.ExecutorService dramaExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(
            com.fongmi.android.tv.utils.BackgroundThreads.factory("drama-suggest"));
    private final Map<String, List<Word.Data>> dramaCache = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Word.Data>> eldest) { return size() > 16; }
    };
    private final Runnable dramaLookup = this::lookupDrama;
    private com.fongmi.android.tv.bean.Site dramaSite;
    private com.fongmi.android.tv.api.SiteApi.SearchTask dramaTask;
    private java.util.concurrent.Future<?> dramaFuture;
    private String wordsFor = "", dramaFor = "";
    private List<Word.Data> baseWords = List.of(), dramaWords = List.of();
    private final List<Call> rankCalls = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SearchSuggestionController<Word.Data> suggestions;
    private String inputQuery;
    private long suggestionUiVersion;
    private boolean resumed;
    private int rankPending;
    private boolean rankFailed;
    private boolean destroyed;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SearchActivity.class)
                .putExtra(CollectActivity.EXTRA_RETURN_HOME, activity instanceof HomeActivity));
    }
    public static void start(Activity activity, String keyword) {
        if (activity instanceof HomeActivity) { CollectActivity.startEntered(activity, keyword); return; }
        activity.startActivity(new Intent(activity, SearchActivity.class).putExtra("keyword", keyword));
    }
    private String keyword() { return mBinding.keyword.getText().toString().trim(); }
    @Override protected ViewBinding getBinding() { return mBinding = ActivitySearchBinding.inflate(getLayoutInflater()); }

    @Override protected void initView(Bundle savedInstanceState) {
        CustomKeyboard.init(this, mBinding);
        mWordAdapter = setupWords(mBinding.wordRecycler, true);
        suggestions = new SearchSuggestionController<>(new SearchSuggestionController.Scheduler() {
            @Override public SearchSuggestionController.Cancelable schedule(long delay, Runnable task) {
                handler.postDelayed(task, delay);
                return () -> handler.removeCallbacks(task);
            }
            @Override public void post(Runnable task) { handler.post(task); }
        }, this::requestSuggestions, SystemClock::elapsedRealtime, this::showSuggestions);
        suggestions.suspend();
        mBinding.recordRecycler.setItemAnimator(null);
        mBinding.recordRecycler.setLayoutManager(new FlexboxLayoutManager(this, FlexDirection.ROW));
        mBinding.recordRecycler.setAdapter(mRecordAdapter = new RecordAdapter(this));
        mBinding.recordRecycler.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left - v.getPaddingLeft() - v.getPaddingRight();
            if (width > 0 && right - left != oldRight - oldLeft) v.post(() -> mRecordAdapter.setArranger(items -> packRows(items, width)));
        });
        warmSources();
        loadRank(mBinding.tvRecycler, "tv_real_time_hotest", 3, 2);
        loadRank(mBinding.movieRecycler, "movie_real_time_hotest", 2, 1);
        loadRank(mBinding.varietyRecycler, "show_hot", 4, 3);
        String initial = getIntent().getStringExtra("keyword");
        setKeyword(initial == null ? "" : initial);
        getWord(keyword());
        if (savedInstanceState == null && !keyword().isEmpty()) onSearch();
        else if (savedInstanceState == null) focusFirstKey();
    }

    private void focusFirstKey() {
        mBinding.keyboard.post(() -> {
            RecyclerView.LayoutManager manager = mBinding.keyboard.getLayoutManager();
            View first = manager == null ? null : manager.findViewByPosition(0);
            if (first != null) first.requestFocus();
        });
    }

    private void setStatus(String text) {
        mBinding.searchStatus.setText(text);
        mBinding.searchStatus.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void updateRankStatus() {
        if (!keyword().isEmpty()) return;
        boolean empty = mBinding.tvRecycler.getAdapter() == null || mBinding.tvRecycler.getAdapter().getItemCount() == 0;
        if (rankPending > 0 && empty) setStatus("榜单加载中…");
        else setStatus(rankFailed ? "部分榜单暂不可用 · 保留上次内容" : "");
    }

    private WordAdapter setupWords(RecyclerView view) {
        return setupWords(view, false);
    }

    private WordAdapter setupWords(RecyclerView view, boolean immediate) {
        view.setItemAnimator(null);
        view.setLayoutManager(new FlexboxLayoutManager(this, FlexDirection.ROW));
        WordAdapter adapter = new WordAdapter(this, immediate);
        view.setAdapter(adapter);
        return adapter;
    }

    @Override protected void initEvent() {
        mBinding.source.setOnClickListener(v -> showDialog());
        mBinding.search.setOnClickListener(v -> onSearch());
        mBinding.clearInput.setOnClickListener(v -> setKeyword(""));
        mBinding.remote.setOnClickListener(v -> onRemote());
        mBinding.backspace.setOnClickListener(v -> CustomKeyboard.backspace(mBinding.keyword));
        mBinding.backspace.setOnLongClickListener(v -> { setKeyword(""); return true; });
        mBinding.clearHistory.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("清空搜索历史？")
                .setNegativeButton("取消", null).setPositiveButton("清空", (d, which) -> mRecordAdapter.clear()).show());
        mBinding.keyword.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) { onSearch(); return true; }
            return false;
        });
        mBinding.keyword.addTextChangedListener(new CustomTextListener() {
            @Override public void afterTextChanged(Editable text) { getWord(text.toString()); }
        });
        mBinding.mic.setOnClickListener(v -> mBinding.mic.start());
        mBinding.mic.setListener(this, new CustomTextListener() {
            @Override public void onResults(String result) { if (!result.isEmpty()) setKeyword(result); }
        });
        int end = mBinding.mic.getVisibility() == View.VISIBLE ? ResUtil.dp2px(44) : mBinding.keyword.getPaddingStart();
        mBinding.keyword.setPadding(mBinding.keyword.getPaddingStart(), 0, end, 0);
        mBinding.keyword.setNextFocusRightId(mBinding.mic.getVisibility() == View.VISIBLE ? R.id.mic : R.id.keyword);
    }

    private void setKeyword(String text) { mBinding.keyword.setText(text); mBinding.keyword.setSelection(mBinding.keyword.length()); }

    private void getWord(String text) {
        String query = text.trim();
        if (Objects.equals(inputQuery, query)) return;
        inputQuery = query;
        suggestionUiVersion++;
        boolean hot = query.isEmpty();
        if (hot) showHotWords();
        else setStatus(""); // Keep current words available while the new query is pending.
        suggestions.update(query);
        requestDrama(query);
    }

    /**
     * Letters (pinyin initials from the TV keyboard) match the local title index at once. Chinese text
     * also asks the source itself, which knows every title, and its answers join the local ones.
     */
    /**
     * Starts the 短剧漫剧 plugin first (its title index waits for it), then the search plugins. Opened
     * right after launch the configuration may still be loading with no sites yet, so typing and
     * resuming try again until it is there.
     */
    private void warmSources() {
        var config = com.fongmi.android.tv.api.config.VodConfig.get();
        if (dramaSite == null || config.getSite(dramaSite.getKey()) != dramaSite) {
            dramaSite = com.fongmi.android.tv.discovery.AiDramaRunner.site(config);
            com.fongmi.android.tv.api.SiteApi.prewarm(dramaSite);
            com.fongmi.android.tv.discovery.ShortDramaTitles.prepare(dramaSite);
        }
        com.fongmi.android.tv.api.SiteApi.prewarmSearch(config.getSites());
    }

    private void requestDrama(String query) {
        cancelDrama();
        warmSources();
        if (query.isEmpty() || dramaSite == null) return;
        boolean chinese = query.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
        List<Word.Data> cached = dramaCache.get(query);
        showDrama(query, withLocal(query, cached == null ? List.of() : cached));
        if (chinese && cached == null) handler.postDelayed(dramaLookup, DRAMA_DEBOUNCE_MS);
    }

    private List<Word.Data> withLocal(String query, List<Word.Data> found) {
        List<Word.Data> words = new ArrayList<>(found);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Word.Data word : found) seen.add(word.getTitle());
        for (String title : com.fongmi.android.tv.discovery.ShortDramaTitles.match(query, DRAMA_WORDS))
            if (words.size() < DRAMA_WORDS && seen.add(title)) words.add(Word.Data.of(title));
        return words;
    }

    private void lookupDrama() {
        String query = inputQuery;
        if (destroyed || !resumed || query == null || query.isEmpty() || dramaSite == null) return;
        var task = com.fongmi.android.tv.api.SiteApi.searchTask(dramaSite, query, false, "1");
        dramaTask = task;
        dramaFuture = dramaExecutor.submit(() -> {
            List<Word.Data> words = new ArrayList<>();
            try (task) {
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (com.fongmi.android.tv.bean.Vod vod : task.call().getList()) {
                    String name = vod == null ? "" : vod.getName().trim();
                    if (!name.isEmpty() && seen.add(name)) words.add(Word.Data.of(name));
                    if (words.size() == DRAMA_WORDS) break;
                }
            } catch (Exception ignored) {
                return; // Failures leave the iQIYI words alone; the source logs its own error.
            }
            App.post(() -> {
                if (destroyed || !query.equals(inputQuery)) return;
                dramaCache.put(query, words);
                showDrama(query, withLocal(query, words));
            });
        });
    }

    private void showDrama(String query, List<Word.Data> words) {
        dramaFor = query;
        dramaWords = words;
        renderWords(query);
    }

    private void cancelDrama() {
        handler.removeCallbacks(dramaLookup);
        if (dramaTask != null) dramaTask.close();
        if (dramaFuture != null) dramaFuture.cancel(true);
        dramaTask = null; dramaFuture = null;
    }

    private void showHotWords() {
        if (mBinding.wordRecycler.hasFocus()) mBinding.search.requestFocus();
        mBinding.word.setText("热门搜索");
        mBinding.wordRecycler.setVisibility(View.GONE);
        mBinding.hotGroups.setVisibility(View.VISIBLE);
        updateRankStatus();
    }

    private void showSuggestions(String query, List<Word.Data> words) {
        if (destroyed || !resumed || !query.equals(keyword())) return;
        if (words.isEmpty()) { if (query.isEmpty()) showHotWords(); return; }
        wordsFor = query;
        baseWords = words;
        renderWords(query);
    }

    /** iQIYI's first words, then the short-drama titles, then the rest; either part may be missing. */
    private void renderWords(String query) {
        if (destroyed || !resumed || !query.equals(keyword())) return;
        List<Word.Data> base = query.equals(wordsFor) ? baseWords : List.of();
        List<Word.Data> drama = query.equals(dramaFor) ? dramaWords : List.of();
        if (base.isEmpty() && drama.isEmpty()) return;
        List<Word.Data> words = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Word.Data word : base.subList(0, Math.min(DRAMA_AFTER, base.size()))) if (seen.add(word.getTitle())) words.add(word);
        for (Word.Data word : drama) if (seen.add(word.getTitle())) words.add(word);
        for (Word.Data word : base) if (seen.add(word.getTitle())) words.add(word);
        long version = suggestionUiVersion;
        // Diff directly into the visible list; never blank it between typed characters.
        mWordAdapter.setItems(words, () -> {
            if (!destroyed && resumed && version == suggestionUiVersion && query.equals(keyword())) {
                mBinding.word.setText("相关关键词");
                if (mBinding.hotGroups.hasFocus()) mBinding.search.requestFocus();
                mBinding.hotGroups.setVisibility(View.GONE);
                mBinding.wordRecycler.setVisibility(View.VISIBLE);
            }
        });
    }

    /**
     * Each hot list shows exactly two rows of chips (user 2026-10-23: three or four rows depending on
     * title lengths). Only the titles that fit two rows are submitted, measured with the chip itself;
     * a Flexbox line limit squeezed the rest into the second row as clipped and empty chips. Every
     * shown title is complete, and shorter titles go first (user 2026-10-24: longer ones have lower
     * priority); whatever does not fit the two rows is left out.
     */
    private void showRank(RecyclerView view, WordAdapter adapter, List<Word.Data> words) {
        int width = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
        if (width <= 0) { view.post(() -> { if (!destroyed && view.getAdapter() == adapter) showRank(view, adapter, words); }); return; }
        adapter.setItems(firstRows(view, words, width, 2));
    }

    private List<Word.Data> firstRows(RecyclerView view, List<Word.Data> words, int width, int rows) {
        var chip = com.fongmi.android.tv.databinding.AdapterSearchWordBinding.inflate(getLayoutInflater(), view, false).text;
        var params = (android.view.ViewGroup.MarginLayoutParams) chip.getLayoutParams();
        int unspecified = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED);
        int limit = Math.min(chip.getMaxWidth(), width - params.leftMargin - params.rightMargin);
        chip.setMaxWidth(Integer.MAX_VALUE);
        List<Word.Data> sorted = new java.util.ArrayList<>(words);
        java.util.Map<Word.Data, Integer> natural = new java.util.IdentityHashMap<>();
        for (Word.Data word : sorted) {
            chip.setText(word.getTitle());
            chip.measure(unspecified, unspecified);
            natural.put(word, chip.getMeasuredWidth());
        }
        // Stable: equal widths keep the source's rank order.
        sorted.sort(java.util.Comparator.comparingInt(natural::get));
        List<Word.Data> shown = new java.util.ArrayList<>();
        int line = 1, used = 0;
        for (Word.Data word : sorted) {
            int chipWidth = natural.get(word);
            if (chipWidth > limit) break; // would be cut off; every later one is at least as wide
            int need = chipWidth + params.leftMargin + params.rightMargin;
            if (used > 0 && used + need > width) { if (++line > rows) break; used = 0; }
            used += need;
            shown.add(word);
        }
        return shown;
    }

    /**
     * Search history fills its narrow column row by row: a record that does not fit the rest of a row
     * lets a later, shorter one take the gap instead of leaving it empty (user 2026-10-24: wide gaps
     * before a line break). Each row still starts with the most recent record not yet placed.
     */
    private List<String> packRows(List<String> items, int width) {
        var chip = com.fongmi.android.tv.databinding.AdapterSearchRecordBinding.inflate(getLayoutInflater(), mBinding.recordRecycler, false).text;
        var params = (android.view.ViewGroup.MarginLayoutParams) chip.getLayoutParams();
        int unspecified = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED);
        java.util.Map<String, Integer> need = new java.util.HashMap<>();
        for (String item : items) {
            chip.setText(item);
            chip.measure(unspecified, unspecified);
            need.put(item, Math.min(chip.getMeasuredWidth(), width) + params.leftMargin + params.rightMargin);
        }
        List<String> left = new java.util.ArrayList<>(items), packed = new java.util.ArrayList<>();
        while (!left.isEmpty()) {
            int used = 0;
            for (var it = left.iterator(); it.hasNext(); ) {
                String item = it.next();
                if (used > 0 && used + need.get(item) > width) continue;
                used += need.get(item);
                packed.add(item);
                it.remove();
            }
        }
        return packed;
    }

    /** Douban's real-time collection first; 360kan's slower rank only when Douban fails. */
    private void loadRank(RecyclerView view, String collection, int requestCategory, int actualCategory) {
        WordAdapter adapter = setupWords(view);
        var cache = getSharedPreferences("search_ranks", MODE_PRIVATE);
        String key = "rank" + actualCategory;
        showRank(view, adapter, Word.objectFrom(cache.getString(key, "")).getData());
        rankPending++;
        Runnable fallback = () -> fetchRank(OkHttp.newCall("https://api.web.360kan.com/v1/rank?cat=" + requestCategory,
                Map.of("Referer", "https://www.360kan.com/rank/general")), body -> HotRank.from360(body, actualCategory), view, key, null);
        fetchRank(OkHttp.newCall(HotRank.doubanUrl(collection), HotRank.DOUBAN_HEADERS), HotRank::fromDouban, view, key, fallback);
    }

    private void fetchRank(Call call, Function<String, String> parse, RecyclerView view, String key, Runnable fallback) {
        App.post(() -> { if (!destroyed) rankCalls.add(call); else call.cancel(); });
        call.enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException error) {
                App.post(() -> {
                    if (destroyed) return;
                    if (fallback != null) { fallback.run(); return; }
                    rankPending--;
                    rankFailed = true;
                    updateRankStatus();
                });
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("Rank unavailable");
                    String json = parse.apply(response.body().string());
                    if (json.isEmpty()) throw new IOException("Empty rank");
                    List<Word.Data> words = Word.objectFrom(json).getData();
                    App.post(() -> {
                        if (destroyed) return;
                        // A diff from the cached words kept the list at its old height (Flexbox), hiding the
                        // first row when the new words need more rows; lay the new words out from scratch.
                        showRank(view, setupWords(view, true), words);
                        getSharedPreferences("search_ranks", MODE_PRIVATE).edit().putString(key, json).apply();
                        rankPending--;
                        updateRankStatus();
                    });
                } catch (Exception error) { onFailure(call, new IOException(error)); }
            }
        });
    }

    private SearchSuggestionController.Cancelable requestSuggestions(String text, SearchSuggestionController.Callback<Word.Data> callback) {
        Call request = OkHttp.newCall("https://suggest.video.iqiyi.com/?if=mobile&rltnum=24&key=" + Uri.encode(ZhuToPin.get(text)));
        request.timeout().timeout(3, TimeUnit.SECONDS);
        request.enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException error) {
                callback.onFailure();
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("Suggestion unavailable");
                    // Parsing and text conversion stay on OkHttp's background callback.
                    Map<String, Word.Data> unique = new LinkedHashMap<>();
                    for (Word.Data word : Word.objectFrom(response.body().string()).getData()) {
                        if (call.isCanceled()) return;
                        if (word != null && !word.getTitle().trim().isEmpty()) unique.putIfAbsent(word.getTitle(), word);
                        if (unique.size() == 24) break;
                    }
                    callback.onResult(new ArrayList<>(unique.values()));
                } catch (Exception error) { onFailure(call, new IOException(error)); }
            }
        });
        return request::cancel;
    }

    @Override public void onItemClick(String text) { setKeyword(text); onSearch(); }
    @Override public void onDataChanged(int size) {
        mBinding.emptyHistory.setVisibility(size == 0 ? View.VISIBLE : View.GONE);
        mBinding.historyTip.setVisibility(size == 0 ? View.GONE : View.VISIBLE);
        if (size == 0 && mBinding.recordLayout.hasFocus()) focusFirstKey();
        mBinding.clearHistory.setEnabled(size > 0);
    }
    @Override public void onSearch() {
        String text = keyword();
        if (text.isEmpty()) { Notify.show("请输入要搜索的内容"); return; }
        suggestions.suspend();
        suggestionUiVersion++;
        mRecordAdapter.remember(text);
        Util.hideKeyboard(mBinding.keyword);
        boolean returnHome = getIntent().getBooleanExtra(CollectActivity.EXTRA_RETURN_HOME, false);
        CollectActivity.start(this, text, returnHome);
        if (returnHome) finish();
    }
    @Override public void showDialog() { SiteDialog.create().search().show(this); }
    @Override public void onRemote() { PushActivity.start(this, 1); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isMenuKey(event)) { showDialog(); return true; }
        return super.dispatchKeyEvent(event);
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (suggestions != null) suggestions.resume(keyword());
        if (inputQuery != null) requestDrama(inputQuery);
    }
    @Override protected void onPause() {
        resumed = false;
        cancelDrama();
        suggestionUiVersion++;
        if (suggestions != null) suggestions.suspend();
        super.onPause();
    }
    @Override protected void onStop() {
        super.onStop();
        mRecordAdapter.show();
    }
    @Override protected void onDestroy() {
        destroyed = true;
        if (suggestions != null) suggestions.close();
        cancelDrama();
        dramaExecutor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        for (Call call : rankCalls) call.cancel();
        mBinding.mic.destroy();
        super.onDestroy();
    }
}
