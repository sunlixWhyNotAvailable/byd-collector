package com.bydcollector.collector.keepalive;

import org.junit.Test;
import static org.junit.Assert.*;

/** Real production scheduling with elapsed-time inputs; no platform outcomes are substituted. */
public class CellularApnScheduleTest {
    @Test public void unknownSettingsPauseOffWorkWithoutInventingShutdown() {
        CellularApnSchedule schedule = new CellularApnSchedule();
        schedule.settingsChanged(true, false);
        schedule.powerChanged(0, 1_000);
        schedule.settingsUnavailable();
        assertEquals(Long.MAX_VALUE, schedule.dueAt());
        assertFalse(schedule.networkLost(CellularApnSchedule.APN_LOST));
        schedule.ownedBacklight(true);
        schedule.powerChanged(1, 2_000);
        assertEquals(CellularApnSchedule.POWER, schedule.beginCheck(2_000));
        schedule.finishCheck(2_100, false);
        assertEquals(Long.MAX_VALUE, schedule.dueAt());
        schedule.powerChanged(0, 3_000);
        schedule.settingsChanged(true, false);
        assertEquals(CellularApnSchedule.STARTUP, schedule.beginCheck(3_000));
        schedule.finishCheck(3_100, false);
        assertEquals(8_000, schedule.dueAt());
        schedule.settingsChanged(true, true);
        schedule.settingsUnavailable();
        schedule.ownedBacklight(true);
        schedule.powerChanged(1, 4_000);
        assertEquals(Long.MAX_VALUE, schedule.dueAt());
    }

    @Test public void offCadenceIsAnchoredAndRepeatedOffDoesNotExtendIt() {
        CellularApnSchedule schedule = new CellularApnSchedule();
        schedule.settingsChanged(true, false);
        assertTrue(schedule.powerChanged(0, 1_000));
        assertTrue(schedule.beginCheck(1_000) != 0);
        schedule.finishCheck(1_400, false);
        assertEquals(6_000, schedule.dueAt());
        for (long at = 6_000; at <= 31_000; at += 5_000) {
            assertEquals(0, schedule.beginCheck(at - 1));
            assertEquals(CellularApnSchedule.TIMER, schedule.beginCheck(at));
            assertFalse(schedule.powerChanged(0, at));
            schedule.finishCheck(at + 300, false);
        }
        assertEquals(61_000, schedule.dueAt());
        assertEquals(CellularApnSchedule.TIMER, schedule.beginCheck(61_000));
        schedule.finishCheck(61_300, false);
        assertEquals(91_000, schedule.dueAt());
    }

    @Test public void slowChecksSkipSlotsAndLossesCoalesceWithoutOverlap() {
        CellularApnSchedule schedule = new CellularApnSchedule();
        schedule.settingsChanged(true, false);
        schedule.powerChanged(0, 0);
        schedule.beginCheck(0);
        assertTrue(schedule.networkLost(CellularApnSchedule.APN_LOST));
        assertTrue(schedule.networkLost(CellularApnSchedule.WIFI_LOST));
        assertTrue(schedule.networkLost(CellularApnSchedule.WIFI_LOST));
        assertEquals(0, schedule.beginCheck(1_000));
        schedule.finishCheck(12_000, false);
        assertEquals(CellularApnSchedule.APN_LOST | CellularApnSchedule.WIFI_LOST, schedule.beginCheck(12_000));
        schedule.finishCheck(12_100, false);
        assertEquals(15_000, schedule.dueAt());
        schedule.beginCheck(15_000);
        schedule.finishCheck(35_000, false);
        assertEquals(60_000, schedule.dueAt());
    }

    @Test public void onAndShutdownCancelLossWorkWhileAllowingOwnedBacklightCleanup() {
        CellularApnSchedule schedule = new CellularApnSchedule();
        schedule.settingsChanged(true, false);
        schedule.beginCheck(0);
        schedule.observePower(0, 100); // First real check discovers already-OFF at startup.
        schedule.finishCheck(200, false);
        assertEquals(5_100, schedule.dueAt());
        schedule.networkLost(CellularApnSchedule.WIFI_LOST);
        assertEquals(CellularApnSchedule.WIFI_LOST, schedule.beginCheck(300));
        assertTrue(schedule.powerChanged(1, 1_000));
        assertFalse(schedule.networkLost(CellularApnSchedule.APN_LOST));
        assertEquals(0, schedule.beginCheck(1_000));
        schedule.finishCheck(1_100, true);
        assertEquals(CellularApnSchedule.POWER, schedule.beginCheck(1_100));
        schedule.finishCheck(1_200, true);
        assertEquals(31_200, schedule.dueAt());
        schedule.beginCheck(31_200);
        schedule.finishCheck(31_300, false);
        assertEquals(Long.MAX_VALUE, schedule.dueAt());
        schedule.powerChanged(0, 32_000);
        schedule.beginCheck(32_000);
        schedule.stop();
        schedule.finishCheck(32_100, false);
        assertFalse(schedule.networkLost(CellularApnSchedule.WIFI_LOST));
        assertFalse(schedule.powerChanged(1, 33_000));
        assertEquals(0, schedule.beginCheck(33_000));
        assertEquals(Long.MAX_VALUE, schedule.dueAt());
    }

    @Test public void disabledWorkStaysIdleAndReenableKeepsOriginalOffWindow() {
        CellularApnSchedule schedule = new CellularApnSchedule();
        schedule.powerChanged(0, 1_000);
        assertEquals(Long.MAX_VALUE, schedule.dueAt());
        assertFalse(schedule.networkLost(CellularApnSchedule.WIFI_LOST));
        schedule.settingsChanged(true, false);
        assertNotEquals(0, schedule.beginCheck(40_000));
        schedule.settingsChanged(false, false); // Toggle while the bounded check is completing.
        schedule.finishCheck(40_100, true);
        assertEquals(Long.MAX_VALUE, schedule.dueAt());
        assertFalse(schedule.networkLost(CellularApnSchedule.APN_LOST));
        schedule.settingsChanged(true, false);
        assertNotEquals(0, schedule.beginCheck(45_000));
        schedule.finishCheck(45_100, true);
        assertEquals(61_000, schedule.dueAt());
        schedule.settingsChanged(true, false); // Duplicate notification must not reset the deadline.
        assertEquals(61_000, schedule.dueAt());
        schedule.settingsChanged(false, false);
        schedule.powerChanged(1, 46_000);
        assertEquals(CellularApnSchedule.POWER, schedule.beginCheck(46_000));
        schedule.finishCheck(46_100, false);
        assertEquals(Long.MAX_VALUE, schedule.dueAt());
        schedule.ownedBacklight(true);
        schedule.settingsChanged(true, true); // Explicit Shutdown also blocks restoration.
        schedule.powerChanged(0, 47_000);
        schedule.powerChanged(1, 48_000);
        assertEquals(0, schedule.beginCheck(48_000));
    }
}
