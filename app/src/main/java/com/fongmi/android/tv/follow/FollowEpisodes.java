package com.fongmi.android.tv.follow;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Count identifiable episodes once, while keeping generic same-name files distinct. */
final class FollowEpisodes {
    private static final Pattern NUMBER = Pattern.compile("(?i)^(?:第)?(\\d{1,4})(?:集|话|期)?(?:\\.(?:mp4|mkv|avi|ts|m3u8))?$");
    private static final Pattern SEASON = Pattern.compile("(?i)^s(\\d{1,2})e(\\d{1,4})(?:\\b.*)?$");

    static int count(List<Flag> flags) {
        int longest = 0;
        for (Flag flag : flags) {
            Set<String> keys = new HashSet<>();
            int unknown = 0;
            for (Episode episode : flag.getEpisodes()) {
                String name = episode.getName().replaceAll("\\[[^\\]]*]", "").trim();
                Matcher number = NUMBER.matcher(name), season = SEASON.matcher(name);
                if (number.matches()) keys.add("episode:" + Integer.parseInt(number.group(1)));
                else if (season.matches()) keys.add("season:" + Integer.parseInt(season.group(1)) + ":" + Integer.parseInt(season.group(2)));
                else if (!episode.getUrl().isEmpty()) keys.add("url:" + episode.getUrl());
                else keys.add("unknown:" + unknown++);
            }
            longest = Math.max(longest, keys.size());
        }
        return longest;
    }
}
