package com.fongmi.android.tv.maintenance;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class CacheJanitorTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private File file(String path, long age) throws IOException {
        File file = new File(folder.getRoot(), path);
        file.getParentFile().mkdirs();
        file.createNewFile();
        file.setLastModified(NOW - age);
        return file;
    }

    private static Set<String> names(List<File> files) {
        Set<String> names = new TreeSet<>();
        for (File file : files) names.add(file.getParentFile().getName() + "/" + file.getName());
        return names;
    }

    @Test
    public void pluginsUnusedForAMonthGoWithTheirMarkerAndCompiledCode() throws IOException {
        file("jar/old.jar", 40 * DAY);
        file("jar/old.jar.verified", 35 * DAY);
        file("jar/oat/x86_64/old.odex", 40 * DAY);
        file("jar/used.jar", 90 * DAY);
        file("jar/used.jar.verified", DAY); // rewritten on each load: still in use
        file("jar/plugin-123.jar", 2 * DAY);
        file("jar/plugin-456.jar", 3_600_000);
        assertEquals(Set.of("jar/old.jar", "jar/old.jar.verified", "x86_64/old.odex", "jar/plugin-123.jar"),
                names(CacheJanitor.stalePlugins(new File(folder.getRoot(), "jar"), NOW, Set.of())));
    }

    @Test
    public void pluginsLoadedByThisProcessAreKeptHoweverOld() throws IOException {
        File bundled = file("jar/bundled.jar", 60 * DAY); // the bundled fallback writes no marker
        assertEquals(Set.of(), names(CacheJanitor.stalePlugins(new File(folder.getRoot(), "jar"), NOW, Set.of(bundled.getAbsolutePath()))));
    }

    @Test
    public void guidesFromEarlierDaysAreRemoved() throws IOException {
        file("epg/a.xml", 3 * DAY);
        file("epg/b.xml", DAY / 2);
        assertEquals(Set.of("epg/a.xml"), names(CacheJanitor.older(new File(folder.getRoot(), "epg"), NOW - CacheJanitor.EPG_MS)));
    }
}
