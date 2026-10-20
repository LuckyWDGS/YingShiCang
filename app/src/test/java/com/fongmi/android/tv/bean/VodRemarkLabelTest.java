package com.fongmi.android.tv.bean;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VodRemarkLabelTest {

    @Test
    public void bareZeroScoresAreHidden() {
        assertEquals("", Vod.remarkLabel("0"));
        assertEquals("", Vod.remarkLabel(" 0.0 "));
        assertEquals("", Vod.remarkLabel("00"));
        assertEquals("", Vod.remarkLabel("0分"));
        assertEquals("", Vod.remarkLabel(null));
    }

    @Test
    public void realRemarksAreKept() {
        assertEquals("8.1", Vod.remarkLabel("8.1"));
        assertEquals("0.5", Vod.remarkLabel("0.5"));
        assertEquals("10", Vod.remarkLabel("10"));
        assertEquals("更新至0集", Vod.remarkLabel("更新至0集"));
        assertEquals("HD中字", Vod.remarkLabel("HD中字"));
        assertEquals("全59集", Vod.remarkLabel(" 全59集 "));
    }
}
