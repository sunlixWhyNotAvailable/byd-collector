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
    fun energyCounterAccumulatesAcrossResetWithoutDoubleCountingNoise() {
        var state = TripMetrics.advanceEnergyCounter(0.0, 10.0, 10.5)
        assertEquals(0.5, state.accumulatedKwh)
        assertEquals(false, state.resetObserved)

        state = TripMetrics.advanceEnergyCounter(state.accumulatedKwh, state.lastCounterKwh, 10.45)
        assertEquals(0.5, state.accumulatedKwh)
        assertEquals(10.5, state.lastCounterKwh)

        state = TripMetrics.advanceEnergyCounter(state.accumulatedKwh, state.lastCounterKwh, 0.2)
        assertEquals(0.5, state.accumulatedKwh)
        assertEquals(true, state.resetObserved)

        state = TripMetrics.advanceEnergyCounter(state.accumulatedKwh, state.lastCounterKwh, 0.9)
        assertEquals(1.2, checkNotNull(state.accumulatedKwh), 1e-9)

        state = TripMetrics.advanceEnergyCounter(null, null, 4.0)
        assertEquals(0.0, state.accumulatedKwh)
        state = TripMetrics.advanceEnergyCounter(state.accumulatedKwh, state.lastCounterKwh, 4.3)
        assertEquals(0.3, checkNotNull(state.accumulatedKwh), 1e-9)
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

    @Test
    fun untrustedPointRetainsRawCoordinatesAndQuality() {
        val point = TripMetrics.untrustedPoint("trip", 1L, sample(), "mock_source")
        assertEquals(RoutePoint.KIND_UNTRUSTED, point.kind)
        assertEquals(50.0, point.latitude)
        assertEquals("untrusted:mock_source", point.quality)
    }

    @Test
    fun routePointPersistsReceiveWallClockForRestartRecovery() {
        val point = TripMetrics.routePoint("trip", 1L, sample().copy(receiveWallTimeMs = 42_000L))
        assertEquals(42_000L, point.receiveWallTimeMs)
    }

    @Test
    fun invalidUntrustedCoordinateFallsBackToDiagnosticGap() {
        val point = TripMetrics.untrustedPoint("trip", 2L, sample(latitude = 100.0), "invalid_numeric")
        assertEquals(RoutePoint.KIND_GAP, point.kind)
        assertNull(point.latitude)
        assertNull(point.longitude)
        assertEquals("untrusted:invalid_numeric", point.quality)
    }

    private fun sample(latitude: Double = 50.0) = com.bydcollector.collector.location.GpsLocationSample(
        "2026-08-20T12:00:00Z", 1_000L, 1_000_000_000L, "boot", "segment", latitude, 30.0, 5.0, 10.0, null, null
    )
}
