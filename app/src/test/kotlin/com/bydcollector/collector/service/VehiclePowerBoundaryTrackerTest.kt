package com.bydcollector.collector.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VehiclePowerBoundaryTrackerTest {
    @Test
    fun `one valid nonzero wakes and first valid zero stops`() {
        val tracker = VehiclePowerBoundaryTracker()

        assertEquals(VehiclePowerState.ON, tracker.observe(2)?.current)
        assertEquals(VehiclePowerState.OFF, tracker.observe(0)?.current)
    }

    @Test
    fun `missing and invalid samples do not fabricate transitions`() {
        val tracker = VehiclePowerBoundaryTracker()
        tracker.observe(2)

        assertNull(tracker.observe(null))
        assertNull(tracker.observe(-1))
        assertNull(tracker.observe(2))
        assertEquals(VehiclePowerState.OFF, tracker.observe(0)?.current)
    }

    @Test
    fun `repeated samples do not repeat transitions`() {
        val tracker = VehiclePowerBoundaryTracker()

        assertEquals(VehiclePowerState.ON, tracker.observe(2)?.current)
        assertNull(tracker.observe(2))
        assertEquals(VehiclePowerState.OFF, tracker.observe(0)?.current)
        assertNull(tracker.observe(0))
    }

    @Test
    fun `failed boundary can be retried`() {
        val tracker = VehiclePowerBoundaryTracker()
        tracker.observe(2)
        val failed = tracker.observe(0)!!

        tracker.rollback(failed)

        assertEquals(VehiclePowerState.ON, tracker.current())
        assertEquals(
            VehiclePowerTransition(VehiclePowerState.ON, VehiclePowerState.OFF),
            tracker.observe(0)
        )
    }
}
