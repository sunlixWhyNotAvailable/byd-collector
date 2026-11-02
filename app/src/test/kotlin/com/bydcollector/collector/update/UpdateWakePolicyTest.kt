package com.bydcollector.collector.update

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateWakePolicyTest {
    @Test fun carAndDisplayEdgesPreserveOrRestartTheThirtySecondDeadline() {
        // Inputs enter below Android/Binder; this verifies the real policy and scheduler, not OEM delivery.
        for (screenFirst in listOf(false, true)) {
            val policy = UpdateWakePolicy()
            var now = 0L
            val scheduler = UpdateAutoCheckScheduler(30_000L, { now })
            assertFalse(policy.onState(null, screenFirst))
            assertFalse(policy.ready)
            assertFalse(policy.onState(if (screenFirst) 0 else 2, screenFirst))
            assertEquals(UpdateAutoCheckAction.None, scheduler.onRuntimeStarted(policy.ready))

            now = 5_000L
            assertTrue(policy.onState(2, true))
            assertEquals(UpdateAutoCheckAction.Schedule(30_000L), scheduler.onRuntimeStarted(policy.ready))
            now = 15_000L
            assertFalse(policy.onState(2, true))
            assertEquals(UpdateAutoCheckAction.Schedule(20_000L), scheduler.onRuntimeStarted(policy.ready))

            // Either loss cancels eligibility; the next complete pair gets a fresh interval.
            assertTrue(policy.onState(if (screenFirst) 0 else 2, screenFirst))
            scheduler.onAutoCheckEnabledChanged(policy.ready)
            now = 35_000L
            assertEquals(UpdateAutoCheckAction.None, scheduler.onTimerElapsed(policy.ready, false))
            assertTrue(policy.onState(2, true))
            scheduler.reset()
            assertEquals(UpdateAutoCheckAction.Schedule(30_000L), scheduler.onRuntimeStarted(policy.ready))
            now = 64_999L
            assertEquals(UpdateAutoCheckAction.Schedule(1L), scheduler.onTimerElapsed(policy.ready, false))
            now = 65_000L
            assertEquals(UpdateAutoCheckAction.Run, scheduler.onTimerElapsed(policy.ready, false))

            assertTrue(policy.onState(null, true)) // Confirmed source loss is not a remembered ON.
            assertFalse(policy.onState(-10011, true))
            assertFalse(policy.ready)
            assertTrue(policy.onState(2, true)) // Valid seed after source recovery.
            policy.reset()
            assertFalse(policy.ready)
            assertFalse(policy.onState(null, true)) // A 4G/display wake alone cannot arm anything.
            assertTrue(policy.onState(2, true))
        }
    }
}
