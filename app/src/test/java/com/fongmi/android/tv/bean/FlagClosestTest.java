package com.fongmi.android.tv.bean;

import com.fongmi.android.tv.utils.Util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FlagClosestTest {

    private static Episode episode(String name) {
        return new Episode() {
            @Override public String getName() { return name; }
            @Override public int getNumber() { return Util.getNumber(name); }
        };
    }

    private static Flag line(String... names) {
        Flag flag = new Flag();
        for (String name : names) flag.getEpisodes().add(episode(name));
        return flag;
    }

    private static Flag numbered(int from, int to) {
        String[] names = new String[to - from + 1];
        for (int i = 0; i < names.length; i++) names[i] = "第" + (from + i) + "集";
        return line(names);
    }

    @Test
    public void theSameEpisodeUnderAnotherNameIsFound() {
        Flag flag = line("[1.2GB]21.mp4", "[1.2GB]22.mp4", "[1.2GB]23.mp4");
        assertEquals("[1.2GB]22.mp4", flag.closest("第22集", true).getName());
    }

    @Test
    public void aMissingEpisodeLandsOnTheClosestNumber() {
        assertEquals("第20集", numbered(1, 20).closest("第22集", true).getName());
        assertEquals("第21集", line("第1集", "第21集", "第24集").closest("第22集", true).getName());
    }

    @Test
    public void aTieGoesToTheEarlierEpisode() {
        assertEquals("第21集", line("第21集", "第23集").closest("第22集", true).getName());
    }

    @Test
    public void theSavedSeasonComesFirst() {
        Flag flag = line("庆余年.S01E21.mp4", "庆余年.S01E22.mp4", "庆余年.S02E21.mp4", "庆余年.S02E22.mp4");
        assertEquals("庆余年.S02E22.mp4", flag.closest("第二季 第22集", true).getName());
        assertEquals("庆余年.S02E21.mp4", line("庆余年.S01E22.mp4", "庆余年.S02E20.mp4", "庆余年.S02E21.mp4").closest("S02E25", true).getName());
    }

    @Test
    public void namesWithoutNumbersKeepTheOldAnswer() {
        assertNull(line("正片", "花絮").closest("高清", true));
        assertEquals("正片", line("正片", "花絮").closest("高清", false).getName());
    }

    @Test
    public void findStaysStrictForSameNameHistory() {
        assertNull(numbered(1, 20).find("第22集", true));
    }

    @Test
    public void seasonsReadFromNames() {
        assertEquals(2, Episode.season("庆余年.S02E22.mp4"));
        assertEquals(2, Episode.season("第二季 第22集"));
        assertEquals(3, Episode.season("第3季"));
        assertEquals(-1, Episode.season("第22集"));
    }
}
