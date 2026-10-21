package com.fongmi.android.tv.maintenance;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class ReleaseNotesTest {
    private static final String NOTES = "## 影视仓 1.3.0\n\n### 版本更新\n- 每次打开应用都会检查是否有新版本。\n"
            + "- **启动**时提示一次。\n\n### 下载说明\n- 大多数电视请选 `arm64-v8a`；见[说明](https://example.com/a)。\n";

    @Test public void sectionHeadingsTagTheirBullets() {
        List<ReleaseNotes.Item> items = ReleaseNotes.items(NOTES);
        assertEquals(3, items.size());
        assertEquals(new ReleaseNotes.Item("版本更新", "每次打开应用都会检查是否有新版本。"), items.get(0));
        assertEquals(new ReleaseNotes.Item("版本更新", "启动时提示一次。"), items.get(1));
        assertEquals(new ReleaseNotes.Item("下载说明", "大多数电视请选 arm64-v8a；见说明。"), items.get(2));
    }

    @Test public void untaggedTextAndNumberedListsAreKept() {
        List<ReleaseNotes.Item> items = ReleaseNotes.items("修复若干问题\n1. 第一项\n2) 第二项");
        assertEquals(List.of(new ReleaseNotes.Item("", "修复若干问题"), new ReleaseNotes.Item("", "第一项"),
                new ReleaseNotes.Item("", "第二项")), items);
    }

    @Test public void emptyNotesHaveNoItems() {
        assertTrue(ReleaseNotes.items(null).isEmpty());
        assertTrue(ReleaseNotes.items("  \n ").isEmpty());
    }

    @Test public void hiddenMarkersAreReadButNeverShown() {
        String notes = "<!-- ysc-min-version: 1.3.0 -->\n<!-- ysc-important: 旧版本无法播放网盘资源 -->\n### 修复\n- 修复网盘播放\n<!-- 普通备注 -->";
        assertEquals("1.3.0", ReleaseNotes.minVersion(notes));
        assertEquals("旧版本无法播放网盘资源", ReleaseNotes.importantReason(notes));
        assertEquals(List.of(new ReleaseNotes.Item("修复", "修复网盘播放")), ReleaseNotes.items(notes));
        assertEquals("修复\n- 修复网盘播放", ReleaseNotes.plain(notes));
    }

    @Test public void notesWithoutMarkersAreOptional() {
        assertEquals("", ReleaseNotes.minVersion(NOTES));
        assertEquals("", ReleaseNotes.importantReason(NOTES));
        assertEquals("", ReleaseNotes.minVersion(null));
        assertEquals("", ReleaseNotes.minVersion("ysc-min-version: 1.3.0"));
    }

    @Test public void plainTextDropsMarkdown() {
        assertEquals("影视仓 1.3.0\n\n版本更新\n- 每次打开应用都会检查是否有新版本。\n- 启动时提示一次。\n\n下载说明\n- 大多数电视请选 arm64-v8a；见说明。",
                ReleaseNotes.plain(NOTES));
    }
}
