package com.fongmi.android.tv.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SearchCapacityTest {

    private static final long MB = 1024L * 1024;

    @Test public void twoGigabyteDevicesSearchFourAtATime() {
        // The Xiaomi TV in the 2026-11-23 log reports 2009 MB.
        assertEquals(SearchCapacity.LOW, SearchCapacity.lanes(2009 * MB, false));
        assertEquals(SearchCapacity.LOW, SearchCapacity.lanes(1800 * MB, false));
        // A device the system itself calls low on RAM, whatever it reports.
        assertEquals(SearchCapacity.LOW, SearchCapacity.lanes(4096 * MB, true));
    }

    @Test public void threeGigabytesAndUpKeepSixteen() {
        assertEquals(SearchCapacity.FULL, SearchCapacity.lanes(2780 * MB, false));
        assertEquals(SearchCapacity.FULL, SearchCapacity.lanes(7600 * MB, false));
        // Memory that could not be read is not taken for a small device.
        assertEquals(SearchCapacity.FULL, SearchCapacity.lanes(0, false));
    }

    @Test public void withoutADeviceTheFullCountIsUsed() {
        assertEquals(SearchCapacity.FULL, SearchCapacity.lanes());
    }
}
