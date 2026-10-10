package com.fongmi.android.tv.maintenance;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.diagnostics.RingLog;
import java.io.*;
import java.util.*;

public final class ApkVerifier {
    private ApkVerifier() {}
    @SuppressWarnings("deprecation")
    public static void verify(File apk, UpdateManifest manifest) throws Exception {
        if (apk.length() != manifest.size || !RingLog.digest(apk).equalsIgnoreCase(manifest.sha256)) throw new IOException("安装包大小或 SHA-256 校验失败");
        PackageManager pm = App.get().getBaseContext().getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo incoming = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = pm.getPackageInfo(BuildConfig.APPLICATION_ID, flags);
        if (incoming == null || !BuildConfig.APPLICATION_ID.equals(incoming.packageName)
                || incoming.versionCode != manifest.versionCode || incoming.versionCode <= BuildConfig.VERSION_CODE)
            throw new IOException("安装包身份或版本与清单不符");
        Signature[] actual = Build.VERSION.SDK_INT >= 28 ? incoming.signingInfo.getApkContentsSigners() : incoming.signatures;
        Signature[] expected = Build.VERSION.SDK_INT >= 28 ? installed.signingInfo.getApkContentsSigners() : installed.signatures;
        if (actual == null || expected == null || actual.length == 0 || !new HashSet<>(Arrays.asList(actual)).equals(new HashSet<>(Arrays.asList(expected))))
            throw new IOException("安装包签名不同，拒绝安装");
    }
}
