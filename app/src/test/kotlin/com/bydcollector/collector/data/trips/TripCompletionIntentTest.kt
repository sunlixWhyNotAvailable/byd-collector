package com.bydcollector.collector.data.trips

import com.bydcollector.collector.data.energy.EnergyIntegrationQuality
import com.bydcollector.collector.data.energy.EnergySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TripCompletionIntentTest {
    @Test
    fun roundTripsCompleteSessionLocationEnergyAndMetrics() {
        val session = TripSession(
            tripId = "trip-1",
            state = TripSession.STATE_CLOSED,
            startedAt = "2026-09-19T10:00:00Z",
            endedAt = "2026-09-19T10:30:00Z",
            startElapsedMs = 10L,
            endElapsedMs = 1_800_010L,
            startBootId = "boot-a",
            endBootId = "boot-a",
            startSegmentId = "segment-a",
            endSegmentId = "segment-b",
            movementObserved = true,
            startSoc = 80.0,
            endSoc = 72.5,
            startOdometerKm = 1_000.0,
            lastOdometerKm = 1_025.25,
            startTripEnergyKwh = 100.0,
            lastTripEnergyKwh = 106.5,
            durationMs = 1_800_000L,
            distanceKm = 25.25,
            energyKwh = 6.5,
            averageConsumptionKwhPer100Km = 25.7425742574,
            dischargedKwh = 7.0,
            regeneratedKwh = 0.5,
            netKwh = 6.5,
            energyCoveredMs = 1_799_000L,
            energyUncoveredMs = 1_000L,
            energyPartial = true,
            energyObservedAt = "2026-09-19T10:30:00Z",
            termination = "power_off",
            quality = "partial",
            telegramEligible = true,
            telegramEnqueued = false
        )
        val energy = EnergySnapshot(
            snapshotId = "energy:receipt-1",
            sourceIdentity = "receipt-1",
            powerSessionId = "trip-1",
            startedAt = session.startedAt,
            observedAt = session.endedAt!!,
            sourceBootId = "boot-a",
            sourceElapsedMs = 1_800_010L,
            active = false,
            dischargedKwh = 7.0,
            regeneratedKwh = 0.5,
            netKwh = 6.5,
            energyCoveredMs = 1_799_000L,
            energyUncoveredMs = 1_000L,
            energyPartial = true,
            integrationQuality = EnergyIntegrationQuality.PARTIAL,
            reason = "power_off"
        )
        val intent = TripCompletionIntent(
            sequence = 7L,
            identity = "trip-complete:trip-1",
            observedAt = session.endedAt!!,
            session = session,
            lastLocation = TripCompletionLocation(50.4501, 30.5234, session.endedAt!!),
            energySnapshot = energy
        )

        assertEquals(intent, TripCompletionIntentCodec.decode(TripCompletionIntentCodec.encode(intent)))
        assertEquals(session.lastOdometerKm, intent.odometerKm)
        assertEquals(session.endSoc, intent.soc)
        assertEquals(session.lastTripEnergyKwh, intent.tripEnergyKwh)
    }

    @Test
    fun roundTripsUnknownNullableValuesWithoutInventingZeros() {
        val intent = TripCompletionIntent(identity = "trip-complete:unknown", observedAt = "2026-09-19T10:00:00Z")

        val decoded = TripCompletionIntentCodec.decode(TripCompletionIntentCodec.encode(intent))

        assertEquals(intent, decoded)
        assertNull(decoded.odometerKm)
        assertNull(decoded.soc)
        assertNull(decoded.tripEnergyKwh)
    }

    @Test
    fun rejectsNonFiniteCompletionMetrics() {
        assertFailsWith<IllegalArgumentException> {
            TripCompletionIntent(
                identity = "trip-complete:bad",
                observedAt = "2026-09-19T10:00:00Z",
                odometerKm = Double.NaN
            )
        }
    }
}
