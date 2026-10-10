package com.fongmi.android.tv.maintenance;

import java.io.File;
import java.io.IOException;

public final class UpdateStorage {
    private UpdateStorage() {}
    public static long availableFor(File destination) throws IOException {
        File directory = destination.getAbsoluteFile().getParentFile();
        if (directory == null || !directory.isDirectory()) throw new IOException("更新缓存目录不可用");
        // statvfs on a not-yet-created APK returns zero on Android. Query its directory.
        return directory.getUsableSpace();
    }
}
