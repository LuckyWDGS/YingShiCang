package com.fongmi.android.tv.cloudlogin;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class CloudLoginChooserTest {

    private static final List<String> OPTIONS = List.of("夸父扫码登录", "优汐扫码登录", "嘟嘟扫码登录");

    @Test
    public void withoutLoginDataTheFirstDriveIsChosen() {
        assertEquals(0, CloudLoginChooser.choose(OPTIONS, false, false));
    }

    @Test
    public void aDriveThatAlreadyHasLoginDataWins() {
        assertEquals(2, CloudLoginChooser.choose(OPTIONS, false, true));
        assertEquals(0, CloudLoginChooser.choose(OPTIONS, true, false));
        assertEquals(0, CloudLoginChooser.choose(OPTIONS, true, true));
        assertEquals(1, CloudLoginChooser.choose(List.of("优汐扫码登录", "嘟嘟扫码登录"), false, true));
    }
}
