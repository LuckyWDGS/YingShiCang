package com.fongmi.android.tv.cloudlogin;

import com.fongmi.android.tv.bean.Flag;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;

public class DriveLoginsTest {

    private static final Predicate<String> ONLY_BAIDU = Set.of("baidu")::contains;
    private static final Predicate<String> NONE = family -> false;

    private static Flag line(String name) {
        return new Flag() { @Override public String getFlag() { return name; } };
    }

    private static List<Flag> lines(String... names) {
        List<Flag> flags = new ArrayList<>();
        for (String name : names) flags.add(line(name));
        return flags;
    }

    private static List<String> names(List<Flag> flags) {
        return flags.stream().map(Flag::getFlag).toList();
    }

    @Test
    public void linesNameTheirDrive() {
        assertEquals("quark", DriveLogins.family("夸父"));
        assertEquals("quark", DriveLogins.family("夸克2"));
        assertEquals("baidu", DriveLogins.family("嘟嘟"));
        assertEquals("uc", DriveLogins.family("UC"));
        assertEquals("other", DriveLogins.family("线路一"));
    }

    @Test
    public void aScannedDriveComesFirstAndTheRestKeepTheirOrder() {
        List<Flag> flags = lines("夸父", "UC", "线路一", "嘟嘟", "嘟嘟2");
        DriveLogins.prefer(flags, ONLY_BAIDU);
        assertEquals(List.of("嘟嘟", "嘟嘟2", "夸父", "UC", "线路一"), names(flags));
    }

    @Test
    public void nothingScannedLeavesTheSourcesOrder() {
        List<Flag> flags = lines("夸父", "UC", "嘟嘟");
        DriveLogins.prefer(flags, NONE);
        assertEquals(List.of("夸父", "UC", "嘟嘟"), names(flags));
    }

    @Test
    public void aSavedDriveNotScannedGivesWayToAScannedOne() {
        List<Flag> flags = lines("嘟嘟", "夸父", "UC");
        assertEquals("嘟嘟", DriveLogins.entry(flags, line("UC"), ONLY_BAIDU).getFlag());
    }

    @Test
    public void aSavedLineThatNeedsNoScanStays() {
        List<Flag> flags = lines("嘟嘟", "线路一");
        assertEquals("线路一", DriveLogins.entry(flags, flags.get(1), ONLY_BAIDU).getFlag());
    }

    @Test
    public void aSavedDriveStaysWhenNoDriveIsScanned() {
        List<Flag> flags = lines("夸父", "UC");
        assertEquals("UC", DriveLogins.entry(flags, flags.get(1), NONE).getFlag());
    }

    @Test
    public void aMissingSavedLineOpensTheFirst() {
        List<Flag> flags = lines("嘟嘟", "夸父");
        assertEquals("嘟嘟", DriveLogins.entry(flags, null, ONLY_BAIDU).getFlag());
    }

    @Test public void ordinaryFailuresAreNotExpiredLogins() {
        org.junit.Assert.assertFalse(DriveLogins.authenticationFailure(new java.net.SocketTimeoutException("timeout")));
        org.junit.Assert.assertFalse(DriveLogins.authenticationFailure(new java.io.IOException("HTTP 403")));
        org.junit.Assert.assertFalse(DriveLogins.authenticationFailure(new Exception("分享已失效")));
        org.junit.Assert.assertFalse(DriveLogins.authenticationFailure(new java.util.concurrent.CancellationException("请重新扫码")));
        org.junit.Assert.assertTrue(DriveLogins.authenticationFailure(new Exception("provider", new Exception("登录已过期，请重新扫码"))));
    }

    @Test public void unverifiedUcStateIsHonestAndSavedLineIsKept() {
        assertEquals("UC 状态未知", DriveLogins.summary(List.of(new DriveLogins.Status("UC", false, false, false))));
        var flags = lines("夸克", "UC");
        assertEquals("UC", DriveLogins.entry(flags, flags.get(1)).getFlag());
    }

    @Test
    public void theSummaryNamesEachDrive() {
        assertEquals("夸克 已扫码 · 百度 可能已失效 · UC 未扫码", DriveLogins.summary(List.of(
                new DriveLogins.Status("夸克", true, false),
                new DriveLogins.Status("百度", true, true),
                new DriveLogins.Status("UC", false, true))));
    }
}
