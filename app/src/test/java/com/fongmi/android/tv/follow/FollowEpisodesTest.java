package com.fongmi.android.tv.follow;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class FollowEpisodesTest {
    private Flag line(String... names) {
        Flag flag = new Flag();
        for (int i = 0; i < names.length; i++) {
            String name = names[i], url = "file-" + i;
            flag.getEpisodes().add(new Episode() {
                @Override public String getName() { return name; }
                @Override public String getUrl() { return url; }
            });
        }
        return flag;
    }

    @Test public void duplicateEpisodeCopiesDoNotAnnounceUpdates() {
        assertEquals(3, FollowEpisodes.count(List.of(line("第21集", "22", "[1.2GB]22.mp4", "第23集"))));
    }

    @Test public void differentSeasonsAndGenericNamesRemainDistinct() {
        assertEquals(3, FollowEpisodes.count(List.of(line("S01E01", "S02E01", "S02E02", "S02E02"))));
        assertEquals(3, FollowEpisodes.count(List.of(line("播放", "播放", "播放"))));
    }

    @Test public void alternateLinesDoNotAddToTheTotal() {
        assertEquals(3, FollowEpisodes.count(List.of(line("1", "2"), line("1", "2", "3"))));
    }
}
