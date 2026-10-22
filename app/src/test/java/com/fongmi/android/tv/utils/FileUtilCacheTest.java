package com.fongmi.android.tv.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileUtilCacheTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void clearingKeepsSourcePluginFiles() throws IOException {
        for (String dir : new String[]{"jar", "js", "py", "chaquopy", "oat_primary"}) assertFalse(dir, FileUtil.isClearable(folder.newFolder(dir)));
        for (String file : new String[]{"danMugo_v8.so", "tv.lck", "data_ID", "data_IDbd"}) assertFalse(file, FileUtil.isClearable(folder.newFile(file)));
        for (String dir : new String[]{"image_manager_disk_cache", "epg", "exo", "mpv", "WebView"}) assertTrue(dir, FileUtil.isClearable(folder.newFolder(dir)));
        assertTrue(FileUtil.isClearable(folder.newFile("ysc-update-1.7.5.apk")));
    }
}
