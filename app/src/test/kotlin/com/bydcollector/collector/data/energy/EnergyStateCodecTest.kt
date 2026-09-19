package com.bydcollector.collector.data.energy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnergyStateCodecTest {
    @Test
    fun roundTripsCheckpointAnchorTotalsAndNullableSnapshotValues() {
        val snapshot = EnergySnapshot(
            snapshotId = "energy:source-1",
            sourceIdentity = "source-1",
            powerSessionId = "trip-1",
            startedAt = "2026-09-14T10:00:00Z",
            observedAt = "2026-09-14T10:00:01Z",
            sourceBootId = "boot-a",
            sourceElapsedMs = 1_000L,
            active = true,
            dischargedKwh = null,
            regeneratedKwh = null,
            netKwh = null,
            energyCoveredMs = 0L,
            energyUncoveredMs = 400L,
            energyPartial = true,
            integrationQuality = EnergyIntegrationQuality.PARTIAL,
            reason = "started_mid_power_session"
        )
        val state = EnergyRuntimeState(
            powerState = EnergyPowerState.ON,
            powerSessionId = "trip-1",
            startedAt = "2026-09-14T10:00:00Z",
            active = true,
            lastSourceIdentity = "source-1",
            lastSourceBootId = "boot-a",
            lastSourceElapsedMs = 1_000L,
            anchor = EnergyAnchor("boot-a", 1_000L, -12.5),
            totals = EnergyTotals(uncoveredMs = 400L, partial = true),
            integrationQuality = EnergyIntegrationQuality.PARTIAL,
            reason = "started_mid_power_session",
            currentSnapshot = snapshot,
            recoveryState = EnergyRecoveryState.NONE
        )

        assertEquals(state, EnergyStateCodec.decodeState(EnergyStateCodec.encodeState(state)))
        val projection = EnergyPendingProjection(snapshot)
        assertEquals(projection, EnergyStateCodec.decodeProjection(EnergyStateCodec.encodeProjection(projection)))
    }

    @Test
    fun rejectsNonFiniteDecodedValues() {
        val encoded = EnergyStateCodec.encodeState(EnergyRuntimeState())
            .replace("\"discharged_kwh\":0", "\"discharged_kwh\":1e999")
        assertFailsWith<IllegalArgumentException> { EnergyStateCodec.decodeState(encoded) }
    }

    @Test
    fun rejectsPendingSnapshotThatClaimsCoverageWithoutSplitTotals() {
        val projection = EnergyPendingProjection(
            EnergySnapshot(
                snapshotId = "energy:source-1",
                sourceIdentity = "source-1",
                powerSessionId = "trip-1",
                startedAt = "2026-09-14T10:00:00Z",
                observedAt = "2026-09-14T10:00:01Z",
                sourceBootId = "boot-a",
                sourceElapsedMs = 1_000L,
                active = true,
                dischargedKwh = null,
                regeneratedKwh = null,
                netKwh = null,
                energyCoveredMs = 0L,
                energyUncoveredMs = 0L,
                energyPartial = true,
                integrationQuality = EnergyIntegrationQuality.PARTIAL,
                reason = "unavailable"
            )
        )
        val poisoned = EnergyStateCodec.encodeProjection(projection)
            .replace("\"energy_covered_ms\":0", "\"energy_covered_ms\":1000")

        assertFailsWith<IllegalArgumentException> { EnergyStateCodec.decodeProjection(poisoned) }
    }

    @Test
    fun oldCheckpointWithoutRecoveryFieldDefaultsToNormal() {
        val encoded = EnergyStateCodec.encodeState(EnergyRuntimeState())
            .replace(",\"recovery_state\":\"NONE\"", "")

        assertEquals(EnergyRecoveryState.NONE, EnergyStateCodec.decodeState(encoded).recoveryState)
    }
}
