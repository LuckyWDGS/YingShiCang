package com.fongmi.android.tv.maintenance;

import org.junit.Test;

import static org.junit.Assert.*;

public class UpdateNoticeTest {
    @Test public void comparesEachNumberNotText() {
        assertTrue(UpdateNotice.newer("1.2.0", "1.1.0"));
        assertTrue(UpdateNotice.newer("1.10.0", "1.9.9"));
        assertTrue(UpdateNotice.newer("2.0", "1.99.99"));
        assertFalse(UpdateNotice.newer("1.1.0", "1.2.0"));
        assertFalse(UpdateNotice.newer("1.2.0", "1.2.0"));
        assertFalse(UpdateNotice.newer("1.2", "1.2.0"));
    }

    @Test public void mandatoryOnlyBelowMinimumThatTheReleaseSatisfies() {
        assertTrue(UpdateNotice.mandatory("1.3.0", "1.3.0", "1.2.0"));
        assertTrue(UpdateNotice.mandatory("1.3.0", "1.5.0", "1.2.9"));
        assertFalse(UpdateNotice.mandatory("1.3.0", "1.5.0", "1.3.0"));
        assertFalse(UpdateNotice.mandatory("1.3.0", "1.5.0", "1.4.0"));
        // A minimum above the release itself is a publishing mistake; installing it could never satisfy it.
        assertFalse(UpdateNotice.mandatory("1.6.0", "1.5.0", "1.2.0"));
        assertFalse(UpdateNotice.mandatory("", "1.5.0", "1.2.0"));
        assertFalse(UpdateNotice.mandatory("v1.3", "1.5.0", "1.2.0"));
    }

    @Test public void malformedVersionsAreNeverNewer() {
        assertFalse(UpdateNotice.newer("", "1.1.0"));
        assertFalse(UpdateNotice.newer(null, "1.1.0"));
        assertFalse(UpdateNotice.newer("v1.2.0", "1.1.0"));
        assertFalse(UpdateNotice.newer("1.2.0-beta", "1.1.0"));
        assertFalse(UpdateNotice.newer("99999999999.0.0", "1.1.0"));
    }
}
