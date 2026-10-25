package com.fongmi.android.tv.discovery;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Map;
import java.util.function.Function;

public class TitleInitialsTest {

    // Main readings as the transliterator gives them.
    private static final Map<String, String> MAIN = Map.of("重", "zhong", "生", "sheng", "之", "zhi", "门", "men",
            "和", "he", "离", "li", "后", "hou", "第", "di");
    private static final Function<String, String> READING = han -> MAIN.getOrDefault(han, "");

    @Test
    public void polyphoneMatchesEitherReading() {
        String[] slots = TitleInitials.slots("重生之门", READING);
        assertEquals(0, TitleInitials.indexOf(slots, "cs"));
        assertEquals(0, TitleInitials.indexOf(slots, "zs"));
        assertEquals(1, TitleInitials.indexOf(slots, "szm"));
        assertEquals(-1, TitleInitials.indexOf(slots, "sm"));
    }

    @Test
    public void punctuationIsSkippedAndDigitsKept() {
        String[] slots = TitleInitials.slots("和离后，第2季", READING);
        assertEquals(0, TitleInitials.indexOf(slots, "hlhd2"));
        assertEquals(-1, TitleInitials.indexOf(slots, "hlhdd"));
    }

    @Test
    public void unknownReadingKeepsItsPlace() {
        String[] slots = TitleInitials.slots("和X离", han -> "");
        assertEquals(0, TitleInitials.indexOf(slots, "hx"));
        assertEquals(-1, TitleInitials.indexOf(slots, "hl"));
    }
}
