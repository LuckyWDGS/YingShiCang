package com.fongmi.android.tv.api.compat;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PluginNoticeCompatibilityTest {

    @Test
    public void onlyTheExactInformationalNoticesAreSilent() {
        assertTrue(PluginNoticeCompatibility.isSilentNotice("已过滤视频中广告(12条)"));
        assertTrue(PluginNoticeCompatibility.isSilentNotice("已过滤视频中广告（3条）"));
        assertTrue(PluginNoticeCompatibility.isSilentNotice("已过滤视频中广告 (3条)"));
        assertFalse(PluginNoticeCompatibility.isSilentNotice("已过滤视频中广告(3条)，请登录"));
        assertFalse(PluginNoticeCompatibility.isSilentNotice("已过滤视频中广告"));
        assertTrue(PluginNoticeCompatibility.isSilentNotice("哔哩游客模式，可前往【哔哔】主页扫码登录解锁高分辨率"));
        assertTrue(PluginNoticeCompatibility.isSilentNotice("弹幕【嫁金钗-1】加载成功"));
        // Later plugin builds add the number of lines (user 2026-11-25 screenshot).
        assertTrue(PluginNoticeCompatibility.isSilentNotice("弹幕【孤独的美食家 第十一季-1】加载成功 共94条"));
        assertTrue(PluginNoticeCompatibility.isSilentNotice("弹幕【孤独的美食家 第十一季-1】加载成功  共94条"));
        assertTrue(PluginNoticeCompatibility.isSilentNotice("弹幕【嫁金钗-1】加载成功，共1261条"));
        assertFalse(PluginNoticeCompatibility.isSilentNotice("弹幕【嫁金钗-1】加载成功 共94条，请登录"));
        assertFalse(PluginNoticeCompatibility.isSilentNotice("弹幕【嫁金钗-1】加载成功 共条"));
        assertFalse(PluginNoticeCompatibility.isSilentNotice("弹幕【嫁金钗-1】加载失败"));
        assertFalse(PluginNoticeCompatibility.isSilentNotice("弹幕【】加载成功"));
        assertFalse(PluginNoticeCompatibility.isSilentNotice("提示：弹幕【嫁金钗-1】加载成功"));
        assertFalse(PluginNoticeCompatibility.isSilentNotice("哔哩登陆模式"));
        assertFalse(PluginNoticeCompatibility.isSilentNotice(null));
    }
}
