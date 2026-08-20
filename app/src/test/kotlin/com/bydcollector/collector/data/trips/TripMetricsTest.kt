package com.bydcollector.collector.data.trips

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TripMetricsTest {
    @Test
    fun computesAverageOnlyForPositiveDistance() {
        assertEquals(20.0, TripMetrics.averageConsumptionKwhPer100Km(10.0, 50.0))
        assertNull(TripMetrics.averageConsumptionKwhPer100Km(10.0, 0.0))
    }

    @Test
    fun instantaneousConsumptionIsNullWhileStationaryAndPreservesRegenSign() {
        assertEquals(40.0, TripMetrics.instantaneousConsumptionKwhPer100Km(8.0, 20.0))
        assertEquals(-40.0, TripMetrics.instantaneousConsumptionKwhPer100Km(-8.0, 20.0))
        assertNull(TripMetrics.instantaneousConsumptionKwhPer100Km(8.0, 0.5))
    }

    @Test
    fun tripIdIsStableForSameBootAndElapsedStart() {
        assertEquals(TripId.forPowerSession("boot", 42L), TripId.forPowerSession("boot", 42L))
    }

    @Test
    fun persistedBaselinesProduceSameDeltasAfterResume() {
        val resumed = TripSession(
            tripId = "boot:42",
            startedAt = "2026-08-20T12:00:00Z",
            startOdometerKm = 100.0,
            lastOdometerKm = 123.4,
            startTripEnergyKwh = 4.0,
            lastTripEnergyKwh = 8.7
        )
        assertEquals(23.4, checkNotNull(TripMetrics.delta(resumed.startOdometerKm, resumed.lastOdometerKm)), 1e-9)
        assertEquals(4.7, checkNotNull(TripMetrics.delta(resumed.startTripEnergyKwh, resumed.lastTripEnergyKwh)), 1e-9)
    }

    @Test
    fun gapPointCannotCarryCoordinates() {
        val gap = TripMetrics.gapPoint("trip", 0L, "2026-08-20T12:00:00Z", "boot", "segment", "reboot")
        assertNull(gap.latitude)
        assertNull(gap.longitude)
    }
}
