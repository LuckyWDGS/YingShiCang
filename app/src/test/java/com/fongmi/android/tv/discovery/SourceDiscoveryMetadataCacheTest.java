package com.fongmi.android.tv.discovery;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class SourceDiscoveryMetadataCacheTest {
    @Test public void categoryAndResolvedExtensionCacheIsImmutableAndScopedToSourceSignature() {
        SourceDiscoveryRunner.MetadataCache cache = new SourceDiscoveryRunner.MetadataCache();
        List<SourceDiscoveryPlanner.Category> categories = new ArrayList<>();
        categories.add(new SourceDiscoveryPlanner.Category("films", "电影", List.of()));
        var value = new SourceDiscoveryRunner.MetadataCache.Metadata(categories, "resolved-extension");
        assertNull(cache.get(1, "source/API/ext-v1"));
        cache.put(1, "source/API/ext-v1", value); categories.clear();
        assertEquals(1, cache.get(1, "source/API/ext-v1").categories().size());
        assertEquals("resolved-extension", cache.get(1, "source/API/ext-v1").extension());
        assertNull(cache.get(1, "source/API/ext-v2"));
        try { value.categories().clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void lateOldConfigurationWorkCannotEvictOrPopulateCurrentCache() {
        SourceDiscoveryRunner.MetadataCache cache = new SourceDiscoveryRunner.MetadataCache();
        var old = new SourceDiscoveryRunner.MetadataCache.Metadata(List.of(), "old-extension");
        var fresh = new SourceDiscoveryRunner.MetadataCache.Metadata(List.of(), "new-extension");
        cache.get(1, "same-key"); cache.put(1, "same-key", old);
        assertNull(cache.get(2, "same-key")); cache.put(2, "same-key", fresh);
        assertNull(cache.get(1, "same-key")); cache.put(1, "same-key", old);
        assertSame(fresh, cache.get(2, "same-key"));
        cache.clear(); assertNull(cache.get(2, "same-key"));
    }
}
