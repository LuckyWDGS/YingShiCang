package com.fongmi.android.tv.maintenance;

import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class UpdateStorageTest {
    @Test public void checksExistingDirectoryBeforeApkExists() throws Exception {
        File folder = Files.createTempDirectory("update-space").toFile();
        File apk = new File(folder,"new.apk");
        try { assertFalse(apk.exists()); assertTrue(UpdateStorage.availableFor(apk)>0); assertFalse(apk.exists()); }
        finally { folder.delete(); }
    }
    @Test public void rejectsMissingCacheDirectory() throws Exception {
        File folder = Files.createTempDirectory("missing-update").toFile();
        folder.delete();
        assertThrows(IOException.class, () -> UpdateStorage.availableFor(new File(folder,"new.apk")));
    }
}
