package com.fongmi.android.tv.resource;

import static org.junit.Assert.assertEquals;

import com.fongmi.android.tv.bean.Site;

import org.junit.Test;

import java.util.List;

public class SourceWarmupTest {

    private static Site site(String key, int type, boolean searchable) {
        Site site = Site.get(key, key);
        site.setType(type);
        site.setSearchable(searchable ? 1 : 0);
        return site;
    }

    private static List<String> keys(List<Site> sites) {
        return sites.stream().map(Site::getKey).toList();
    }

    @Test
    public void usedSourcesComeFirstThenSearchSourcesEachOncePluginsOnly() {
        Site wogg = site("wogg", 3, true), aidj = site("aidj", 3, false), cms = site("cms", 1, true);
        Site bpan = site("bpan", 3, true), hidden = site("hidden", 3, false);
        List<Site> order = SourceWarmup.order(List.of(aidj, wogg, cms, wogg), List.of(bpan, wogg, hidden, cms));
        // A plugin the user opens is started even if it is not searchable; HTTP sources need no start.
        assertEquals(List.of("aidj", "wogg", "bpan"), keys(order));
    }

    @Test
    public void withoutRoomForSearchOnlyUsedSourcesStart() {
        assertEquals(List.of("wogg"), keys(SourceWarmup.order(List.of(site("wogg", 3, true)), List.of())));
    }
}
