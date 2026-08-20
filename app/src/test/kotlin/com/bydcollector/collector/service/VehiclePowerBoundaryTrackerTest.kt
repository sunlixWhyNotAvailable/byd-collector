package com.bydcollector.collector.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VehiclePowerBoundaryTrackerTest {
    @Test
    fun `one valid nonzero wakes and three valid zeroes stop`() {
        val tracker = VehiclePowerBoundaryTracker()

        assertEquals(VehiclePowerState.ON, tracker.observe(2)?.current)
        assertNull(tracker.observe(0))
        assertNull(tracker.observe(0))
        assertEquals(VehiclePowerState.OFF, tracker.observe(0)?.current)
    }

    @Test
    fun `missing invalid and nonzero samples reset shutdown confirmation`() {
        val tracker = VehiclePowerBoundaryTracker()
        tracker.observe(2)

        assertNull(tracker.observe(0))
        assertNull(tracker.observe(null))
        assertNull(tracker.observe(0))
        assertNull(tracker.observe(-1))
        assertNull(tracker.observe(0))
        assertNull(tracker.observe(2))
        assertNull(tracker.observe(0))
        assertNull(tracker.observe(0))
        assertEquals(VehiclePowerState.OFF, tracker.observe(0)?.current)
    }

    @Test
    fun `repeated samples do not repeat transitions`() {
        val tracker = VehiclePowerBoundaryTracker()

        assertEquals(VehiclePowerState.ON, tracker.observe(2)?.current)
        assertNull(tracker.observe(2))
        repeat(2) { assertNull(tracker.observe(0)) }
        assertEquals(VehiclePowerState.OFF, tracker.observe(0)?.current)
        assertNull(tracker.observe(0))
    }

    @Test
    fun `only current telemetry may start live gps capture`() {
        val now = 1_800_000L

        assertTrue(isLiveTripTelemetryTimestamp("1970-01-01T00:29:55Z", now, 10_000L))
        assertFalse(isLiveTripTelemetryTimestamp("1970-01-01T00:29:00Z", now, 10_000L))
        assertFalse(isLiveTripTelemetryTimestamp("1970-01-01T00:30:01Z", now, 10_000L))
    }
}
