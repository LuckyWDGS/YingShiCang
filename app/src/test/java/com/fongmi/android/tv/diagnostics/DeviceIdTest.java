package com.fongmi.android.tv.diagnostics;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class DeviceIdTest {
    @Test public void sameSeedGivesSameReadableId() {
        String id = DeviceId.fromSeed("ysc-log-device:abc".getBytes(StandardCharsets.UTF_8));
        assertTrue(id, id.matches(DeviceId.PATTERN));
        assertFalse(id, id.matches(".*[ILOU].*"));
        assertEquals(id, DeviceId.fromSeed("ysc-log-device:abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void differentSeedsGiveDifferentIds() {
        assertNotEquals(DeviceId.fromSeed(new byte[]{1}), DeviceId.fromSeed(new byte[]{2}));
    }
}
