package com.fongmi.android.tv.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.resource.ResourceHealth;
import com.github.catvod.utils.Trans;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public class SiteViewModel extends ViewModel {

    private final MutableLiveData<Result> result;
    private final MutableLiveData<Result> search;
    private final MutableLiveData<Result> action;
    private final MutableLiveData<String> searchStatus = new MutableLiveData<>();
    private int searchGeneration;

    public LiveData<String> getSearchStatus() { return searchStatus; }

    private final ViewModelTaskRunner<TaskType> tasks;
    private final ViewModelSearchRunner searches;

    public SiteViewModel() {
        result = new MutableLiveData<>();
        search = new MutableLiveData<>();
        action = new MutableLiveData<>();
        tasks = new ViewModelTaskRunner<>(TaskType.class);
        searches = new ViewModelSearchRunner();
    }

    public LiveData<Result> getResult() {
        return result;
    }

    public LiveData<Result> getSearch() {
        return search;
    }

    public LiveData<Result> getAction() {
        return action;
    }

    public SiteViewModel init() {
        search.setValue(null);
        result.setValue(null);
        action.setValue(null);
        return this;
    }

    public void homeContent() {
        execute(TaskType.RESULT, result, () -> SiteApi.homeContent(VodConfig.get().getHome()));
    }

    public void categoryContent(String key, String tid, String page, boolean filter, HashMap<String, String> extend) {
        execute(TaskType.RESULT, result, () -> SiteApi.categoryContent(key, tid, page, filter, extend));
    }

    public void action(String key, String act) {
        execute(TaskType.ACTION, action, () -> SiteApi.action(key, act));
    }

    public void detailContent(String key, String id) {
        execute(TaskType.RESULT, result, () -> SiteApi.detailContent(key, id));
    }

    public void searchContent(Site site, String keyword, boolean quick, String page) {
        execute(TaskType.RESULT, result, SearchTask.create(site, keyword, quick, page),
                value -> result.postValue(filterSearchResult(value)));
    }

    public void searchContent(List<Site> sites, String keyword, boolean quick) {
        int generation = ++searchGeneration;
        searches.start(sites, site -> SearchTask.create(site, keyword, quick),
                value -> com.fongmi.android.tv.App.post(() -> { if (generation == searchGeneration) search.setValue(value); }),
                status -> com.fongmi.android.tv.App.post(() -> { if (generation == searchGeneration) searchStatus.setValue(status); }));
    }

    private void execute(TaskType type, MutableLiveData<Result> liveData, Callable<Result> callable) {
        execute(type, liveData, callable, liveData::postValue);
    }

    private void execute(TaskType type, MutableLiveData<Result> liveData, Callable<Result> callable,
                         Consumer<Result> onSuccess) {
        tasks.execute(type, callable instanceof SiteApi.SearchTask ? Constant.TIMEOUT_SEARCH : Constant.TIMEOUT_VOD, callable, onSuccess, error -> {
            if (error instanceof ExtractException) liveData.postValue(Result.error(error.getMessage()));
            else liveData.postValue(Result.empty());
            error.printStackTrace();
        });
    }

    /** Apply aggregate-search card/health rules to a single-source page. */
    static Result filterSearchResult(Result value) {
        if (value == null) return null;
        List<Vod> raw = new ArrayList<>(value.getList());
        value.setSearchRawList(raw);
        long revision = VodConfig.getDiscoveryRevision();
        value.setList(raw.stream()
                .filter(ResourceHealth::isSearchCard)
                .filter(item -> !ResourceHealth.isFiltered(revision, item.getSiteKey(), item.getId()))
                .toList());
        return value;
    }

    public void stopSearch() {
        searchGeneration++;
        searches.stop();
    }

    /** Reset the aggregate result page without sending nulls to playback's quick-search observers. */
    public void resetSearch() {
        stopSearch();
        // A recreated CollectActivity starts observing this retained ViewModel
        // after initView. Its previous batch/status must not be replayed.
        search.setValue(null);
        searchStatus.setValue("");
    }

    @Override
    protected void onCleared() {
        stopSearch();
        tasks.cancelAll();
    }

    private static final class SearchTask {
        private static final String FIRST_PAGE = "1";
        private static SiteApi.SearchTask create(Site site, String keyword, boolean quick) {
            return create(site, keyword, quick, FIRST_PAGE);
        }
        private static SiteApi.SearchTask create(Site site, String keyword, boolean quick, String page) {
            return SiteApi.searchTask(site, Trans.t2s(keyword), quick, page);
        }
    }

    private enum TaskType {RESULT, ACTION}
}
