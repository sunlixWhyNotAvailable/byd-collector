package com.bydcollector.collector.data.trips

import com.bydcollector.collector.data.energy.EnergyIntegrationQuality
import com.bydcollector.collector.data.energy.EnergySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TripEnergyModelsTest {
    @Test
    fun matchingSnapshotUpdatesOnlyAdditiveEnergyFields() {
        val original = TripSession(
            tripId = "trip-1",
            startedAt = "2026-09-14T10:00:00Z",
            distanceKm = 12.0,
            energyKwh = 2.0,
            quality = "legacy-quality"
        )

        val updated = original.withEnergySnapshot(snapshot("trip-1"))

        assertEquals(12.0, updated.distanceKm)
        assertEquals(2.0, updated.energyKwh)
        assertEquals("legacy-quality", updated.quality)
        assertEquals(1.2, updated.dischargedKwh)
        assertEquals(0.3, updated.regeneratedKwh)
        assertEquals(0.9, updated.netKwh)
        assertEquals(2_000L, updated.energyCoveredMs)
        assertEquals(500L, updated.energyUncoveredMs)
        assertEquals(true, updated.energyPartial)
        assertEquals("2026-09-14T10:00:03Z", updated.energyObservedAt)
    }

    @Test
    fun differentPowerSessionCannotOverwriteTripEnergy() {
        val original = TripSession("trip-1", startedAt = "2026-09-14T10:00:00Z")
        val updated = original.withEnergySnapshot(snapshot("trip-2"))
        assertEquals(original, updated)
        assertNull(updated.dischargedKwh)
    }

    private fun snapshot(sessionId: String) = EnergySnapshot(
        snapshotId = "energy:source-3",
        sourceIdentity = "source-3",
        powerSessionId = sessionId,
        startedAt = "2026-09-14T10:00:00Z",
        observedAt = "2026-09-14T10:00:03Z",
        sourceBootId = "boot-a",
        sourceElapsedMs = 3_000L,
        active = true,
        dischargedKwh = 1.2,
        regeneratedKwh = 0.3,
        netKwh = 0.9,
        energyCoveredMs = 2_000L,
        energyUncoveredMs = 500L,
        energyPartial = true,
        integrationQuality = EnergyIntegrationQuality.COVERED,
        reason = "integrated"
    )
}
