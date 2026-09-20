package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateWakePolicyTest {
    @Test
    fun coldEntryInitializesAwakeOrSleepingWithoutInventingWake() {
        val awake = UpdateWakePolicy()
        assertFalse(awake.onEntry(now = 1_000L, interactive = true))
        assertFalse(awake.sleeping)

        val off = UpdateWakePolicy()
        assertFalse(off.onEntry(now = 1_000L, interactive = false))
        assertTrue(off.sleeping)
    }

    @Test
    fun repeatedAwakeEntryIsIgnoredButAsleepInteractiveEntryIsAdmitted() {
        val policy = UpdateWakePolicy()
        policy.onEntry(now = 1_000L, interactive = true)
        assertFalse(policy.onEntry(now = 2_000L, interactive = true))
        assertFalse(policy.onEntry(now = 3_000L, interactive = false))
        assertTrue(policy.sleeping)
        assertTrue(policy.onEntry(now = 3_001L, interactive = true))
        assertFalse(policy.sleeping)
    }

    @Test
    fun duplicateAwakeBootBurstIsIgnored() {
        val policy = UpdateWakePolicy()
        policy.onEntry(now = 1_000L, interactive = true)

        assertFalse(policy.onWake("android.intent.action.SCREEN_ON", now = 2_000L))
        assertFalse(policy.onWake("android.intent.action.USER_PRESENT", now = 2_001L))
        assertFalse(policy.onWake("android.intent.action.BOOT_COMPLETED", now = 2_002L))
    }

    @Test
    fun quickbootFallbackRequiresThirtySecondsSinceLastEntryOrWake() {
        val policy = UpdateWakePolicy()
        policy.onEntry(now = 10_000L, interactive = true)

        assertFalse(policy.onWake(UpdateWakePolicy.ACTION_QUICKBOOT_POWERON, now = 39_999L))
        assertTrue(policy.onWake(UpdateWakePolicy.ACTION_QUICKBOOT_POWERON, now = 40_000L))
        assertFalse(policy.onWake(UpdateWakePolicy.ACTION_QUICKBOOT_POWERON, now = 69_999L))
        assertTrue(policy.onWake(UpdateWakePolicy.ACTION_QUICKBOOT_POWERON, now = 70_000L))
    }

    @Test
    fun repeatedAwakeEntryDoesNotMoveQuickbootFallbackBaseline() {
        val policy = UpdateWakePolicy()
        policy.onEntry(now = 0L, interactive = true)
        assertFalse(policy.onEntry(now = 29_000L, interactive = true))

        assertTrue(policy.onWake(UpdateWakePolicy.ACTION_QUICKBOOT_POWERON, now = 30_000L))
    }

    @Test
    fun genuineSleepWakeIsAlwaysAdmittedAndResetMakesWakeFresh() {
        val policy = UpdateWakePolicy()
        policy.onEntry(now = 100_000L, interactive = true)
        policy.onSleep()
        assertTrue(policy.onWake("android.intent.action.SCREEN_ON", now = 100_001L))
        assertFalse(policy.onWake("android.intent.action.USER_PRESENT", now = 100_002L))

        policy.reset()
        assertFalse(policy.sleeping)
        assertTrue(policy.onWake("android.intent.action.BOOT_COMPLETED", now = 100_003L))
    }
}
