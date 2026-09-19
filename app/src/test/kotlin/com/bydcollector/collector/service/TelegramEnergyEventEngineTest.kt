package com.bydcollector.collector.service

import com.bydcollector.collector.data.energy.EnergyIntegrationQuality
import com.bydcollector.collector.data.energy.EnergySnapshot
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedValue
import com.bydcollector.collector.data.normalized.NormalizedValueType
import com.bydcollector.collector.telegram.TelegramEventType
import com.bydcollector.collector.telegram.TelegramTemplateLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TelegramEnergyEventEngineTest {
    private val config = TelegramEventConfig(
        enabledEvents = setOf(TelegramEventType.TRIP_SUMMARY),
        chargeStepPercent = 5,
        lowVoltageThreshold = 12.0,
        unavailableDelayMs = 60_000L,
        tripEndDelayMs = 10_000L,
        language = TelegramTemplateLanguage.EN
    )

    @Test
    fun parkedDriveEndIsFrozenWhilePowerSessionTotalIncludesIdle() {
        val engine = startDriving()
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.4), config, 4_000L, energy(4_000L, 0.4, 0.05))
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.5), config, 5_000L, energy(5_000L, 0.5, 0.10))
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.6), config, 6_000L, energy(6_000L, 0.7, 0.15))

        val summary = engine.onSuccessfulPoll(
            vehicle("P", 101.0, 10.7), config, 15_000L, energy(15_000L, 1.5, 0.25)
        ).events.single()

        assertEquals("0.3", summary.variables["trip_discharged_kwh"])
        assertEquals("0.1", summary.variables["trip_regenerated_kwh"])
        assertEquals("0.2", summary.variables["trip_net_kwh"])
        assertEquals("1.5", summary.variables["total_discharged_kwh"])
        assertEquals("0.25", summary.variables["total_regenerated_kwh"])
        assertEquals("1.25", summary.variables["total_net_kwh"])
        assertFalse(summary.omitOverall)
    }

    @Test
    fun energyGapDoesNotAppendUserFacingPartialLabels() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(vehicle("P", 100.0, 10.0), config, 0L, energy(5_000L, 0.5, 0.1, partial = true, uncoveredMs = 250L))
        engine.onSuccessfulPoll(vehicle("D", 100.0, 10.1), config, 1_000L, energy(6_000L, 0.6, 0.1, partial = true, uncoveredMs = 250L))
        engine.onSuccessfulPoll(vehicle("D", 100.0, 10.2), config, 2_000L, energy(7_000L, 0.7, 0.1, partial = true, uncoveredMs = 250L))
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.3), config, 3_000L, energy(8_000L, 0.8, 0.2, partial = true, uncoveredMs = 250L))
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.4), config, 4_000L, energy(9_000L, 0.9, 0.2, partial = true, uncoveredMs = 250L))

        val summary = engine.onTick(config, false, null, 14_000L).events.single()
        assertEquals("0.2", summary.variables["trip_discharged_kwh"])
        assertEquals("0.1", summary.variables["trip_regenerated_kwh"])
        assertEquals("0.9", summary.variables["total_discharged_kwh"])
        assertEquals("0.2", summary.variables["total_regenerated_kwh"])
        val custom = "partial note / частково: {total_discharged_kwh}"
        assertEquals("partial note / частково: 0.9", com.bydcollector.collector.telegram.TelegramTemplateRenderer.render(
            TelegramEventType.TRIP_SUMMARY, custom, summary.variables
        ).text)
    }

    @Test
    fun missingUpgradeBaselineLeavesOnlyCurrentLegValuesUnavailable() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(vehicle("P", 100.0, 10.0), config, 0L, energy(1_000L, null, null, partial = true))
        engine.onSuccessfulPoll(vehicle("D", 100.0, 10.1), config, 1_000L, energy(2_000L, null, null, partial = true))
        engine.onSuccessfulPoll(vehicle("D", 100.0, 10.2), config, 2_000L, energy(3_000L, null, null, partial = true))
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.3), config, 3_000L, energy(4_000L, 0.2, 0.05, partial = true))
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.4), config, 4_000L, energy(5_000L, 0.3, 0.05, partial = true))

        val summary = engine.onTick(config, false, null, 14_000L).events.single()
        assertEquals("n/a", summary.variables["trip_discharged_kwh"])
        assertEquals("n/a", summary.variables["trip_net_kwh"])
        assertEquals("0.3", summary.variables["total_discharged_kwh"])
    }

    @Test
    fun knownPowerBoundaryWithNoCoveredIntervalUsesZeroLegBaseline() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(vehicle("D", 100.0, 10.0), config, 0L, energy(0L, null, null))
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.1), config, 1_000L, energy(1_000L, 0.1, 0.02))
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.2), config, 2_000L, energy(2_000L, 0.2, 0.05))

        val summary = engine.onTick(config, false, null, 12_000L).events.single()
        assertEquals("0.2", summary.variables["trip_discharged_kwh"])
        assertEquals("0.05", summary.variables["trip_regenerated_kwh"])
    }

    @Test
    fun powerOffUsesInactiveFinalTotalWithoutMovingParkedDriveEnd() {
        val engine = startDriving()
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.4), config, 4_000L, energy(4_000L, 0.4, 0.05))
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.5), config, 5_000L, energy(5_000L, 0.5, 0.10))

        val summary = engine.onPowerOffConfirmed(
            config,
            TelegramPowerOffSnapshot(
                odometerKm = 101.0,
                soc = 49.0,
                tripEnergyKwh = 10.8,
                energySnapshot = energy(20_000L, 1.0, 0.2, active = false)
            ),
            nowMs = 20_000L
        ).events.single()

        assertEquals("0.3", summary.variables["trip_discharged_kwh"])
        assertEquals("1", summary.variables["total_discharged_kwh"])
        assertNull(engine.state.powerEnergyPoint)
    }

    @Test
    fun secondDriveUsesNewLegBaselineWhileTotalRetainsEnergyBetweenStops() {
        val engine = startDriving()
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.4), config, 4_000L, energy(4_000L, 0.4, 0.05))
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.5), config, 5_000L, energy(5_000L, 0.5, 0.10))
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.6), config, 15_000L, energy(15_000L, 1.5, 0.50))

        engine.onSuccessfulPoll(vehicle("D", 101.0, 10.7), config, 16_000L, energy(16_000L, 1.6, 0.55))
        engine.onSuccessfulPoll(vehicle("D", 101.0, 10.8), config, 17_000L, energy(17_000L, 1.7, 0.55))
        val second = engine.onPowerOffConfirmed(
            config,
            TelegramPowerOffSnapshot(
                odometerKm = 103.0,
                soc = 48.0,
                tripEnergyKwh = 11.5,
                energySnapshot = energy(20_000L, 2.0, 0.65, active = false)
            ),
            nowMs = 20_000L
        ).events.single()

        assertEquals("0.3", second.variables["trip_discharged_kwh"])
        assertEquals("0.1", second.variables["trip_regenerated_kwh"])
        assertEquals("2", second.variables["total_discharged_kwh"])
        assertEquals("0.65", second.variables["total_regenerated_kwh"])
    }

    @Test
    fun leavingParkBeforeDeadlineClearsFrozenEndAndResumesTheSameLeg() {
        val engine = startDriving()
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.4), config, 4_000L, energy(4_000L, 0.4, 0.05))
        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.5), config, 5_000L, energy(5_000L, 0.5, 0.10))
        engine.onSuccessfulPoll(vehicle("D", 101.0, 10.6), config, 6_000L, energy(6_000L, 0.6, 0.10))
        engine.onSuccessfulPoll(vehicle("D", 101.0, 10.7), config, 7_000L, energy(7_000L, 0.7, 0.10))

        val summary = engine.onPowerOffConfirmed(
            config,
            TelegramPowerOffSnapshot(
                odometerKm = 102.0,
                soc = 49.0,
                tripEnergyKwh = 11.0,
                energySnapshot = energy(9_000L, 0.9, 0.15, active = false)
            ),
            nowMs = 9_000L
        ).events.single()

        assertEquals("0.7", summary.variables["trip_discharged_kwh"])
        assertEquals("0.15", summary.variables["trip_regenerated_kwh"])
    }

    @Test
    fun retainedOffSnapshotCannotResurrectClearedPowerTotals() {
        val engine = startDriving()
        val final = energy(4_000L, 0.5, 0.1, active = false)
        engine.onPowerOffConfirmed(
            config,
            TelegramPowerOffSnapshot(101.0, 49.0, 10.5, final),
            nowMs = 4_000L
        )

        engine.onSuccessfulPoll(vehicle("P", 101.0, 10.5), config, 5_000L, final)
        assertNull(engine.state.powerEnergyPoint)
    }

    @Test
    fun advancingPowerProjectionUsesTheExistingHeartbeatInsteadOfEveryPoll() {
        val engine = TelegramEventEngine()
        engine.onSuccessfulPoll(vehicle("P", 100.0, 10.0), config, 0L, energy(0L, null, null))

        val next = engine.onSuccessfulPoll(
            vehicle("P", 100.0, 10.1), config, 500L, energy(500L, 0.01, 0.0)
        )

        assertFalse(next.shouldPersist)
        assertEquals(0.01, next.state.powerEnergyPoint?.dischargedKwh)
    }

    @Test
    fun newEnergyPointsRoundTripAndMalformedPointDoesNotDiscardLegacyState() {
        val engine = startDriving()
        val restored = TelegramEventState.fromJson(engine.state.toJson())
        assertEquals(engine.state.powerEnergyPoint, restored.powerEnergyPoint)
        assertEquals(engine.state.tripStartEnergyPoint, restored.tripStartEnergyPoint)

        val malformed = TelegramEventState.fromJson(
            """{"initialized":true,"tripId":"legacy-leg","powerEnergyPoint":{"power_session_id":"broken"}}"""
        )
        assertEquals("legacy-leg", malformed.tripId)
        assertNull(malformed.powerEnergyPoint)
    }

    @Test
    fun startupRecoveryUsesSameSessionDurablePowerTotalBeforeFreshPoll() {
        val running = startDriving()
        running.onSuccessfulPoll(vehicle("P", 101.0, 10.4), config, 4_000L, energy(4_000L, 0.4, 0.05))
        running.onSuccessfulPoll(vehicle("P", 101.0, 10.5), config, 5_000L, energy(5_000L, 0.5, 0.10))
        val restored = TelegramEventEngine(TelegramEventState.fromJson(running.state.toJson()))

        val summary = restored.recoverPendingTrip(
            config,
            15_000L,
            energy(15_000L, 1.5, 0.25)
        ).events.single()

        assertEquals("0.3", summary.variables["trip_discharged_kwh"])
        assertEquals("1.5", summary.variables["total_discharged_kwh"])
    }

    @Test
    fun startupRecoveryDoesNotMixAReplacementPowerSessionIntoPendingLeg() {
        val running = startDriving()
        running.onSuccessfulPoll(vehicle("P", 101.0, 10.4), config, 4_000L, energy(4_000L, 0.4, 0.05))
        running.onSuccessfulPoll(vehicle("P", 101.0, 10.5), config, 5_000L, energy(5_000L, 0.5, 0.10))
        val restored = TelegramEventEngine(TelegramEventState.fromJson(running.state.toJson()))

        val summary = restored.recoverPendingTrip(
            config,
            15_000L,
            energy(15_000L, 2.0, 0.2, sessionId = "power-2")
        ).events.single()

        assertEquals("n/a", summary.variables["trip_discharged_kwh"])
        assertEquals("n/a", summary.variables["total_discharged_kwh"])
    }

    @Test
    fun displayedNewEnergyCanOmitOverallDespiteDifferentHiddenLegacyEnergy() {
        val start = TelegramEnergyPoint.fromSnapshot(energy(0L, null, null))!!
        val engine = TelegramEventEngine(
            TelegramEventState(
                initialized = true,
                gear = "D",
                tripId = "leg-1",
                tripPowerSessionId = "power-1",
                tripStartedAtMs = 0L,
                tripStartOdometerKm = 100.0,
                tripStartSoc = 50.0,
                tripStartEnergyKwh = 10.0,
                tripAccumulatedEnergyKwh = 0.0,
                lastTripEnergyCounterKwh = 10.0,
                tripStartEnergyPoint = start,
                powerEnergyPoint = start,
                bootStartSoc = 50.0,
                bootEndSoc = 50.0,
                bootTotalEnergyKwh = 5.0
            )
        )

        val summary = engine.onPowerOffConfirmed(
            config,
            TelegramPowerOffSnapshot(
                odometerKm = 101.0,
                soc = 49.0,
                tripEnergyKwh = 11.0,
                energySnapshot = energy(1_000L, 1.0, 0.0, active = false)
            ),
            nowMs = 1_000L
        ).events.single()

        assertEquals("1", summary.variables["trip_energy_kwh"])
        assertEquals("6", summary.variables["total_energy_kwh"])
        assertEquals("1", summary.variables["trip_discharged_kwh"])
        assertEquals("1", summary.variables["total_discharged_kwh"])
        assertEquals(true, summary.omitOverall)
    }

    private fun startDriving(): TelegramEventEngine = TelegramEventEngine().also { engine ->
        engine.onSuccessfulPoll(vehicle("P", 100.0, 10.0), config, 0L, energy(0L, null, null))
        engine.onSuccessfulPoll(vehicle("D", 100.0, 10.1), config, 1_000L, energy(1_000L, 0.1, 0.0))
        engine.onSuccessfulPoll(vehicle("D", 100.0, 10.2), config, 2_000L, energy(2_000L, 0.2, 0.0))
        engine.onSuccessfulPoll(vehicle("D", 100.5, 10.3), config, 3_000L, energy(3_000L, 0.3, 0.05))
    }

    private fun energy(
        elapsedMs: Long,
        dischargedKwh: Double?,
        regeneratedKwh: Double?,
        partial: Boolean = false,
        uncoveredMs: Long = 0L,
        active: Boolean = true,
        sessionId: String = "power-1"
    ) = EnergySnapshot(
        snapshotId = "energy:$sessionId:$elapsedMs",
        sourceIdentity = "$sessionId:$elapsedMs",
        powerSessionId = sessionId,
        startedAt = "2026-09-14T00:00:00Z",
        observedAt = "2026-09-14T00:00:${(elapsedMs / 1_000L).toString().padStart(2, '0')}Z",
        sourceBootId = "boot-1",
        sourceElapsedMs = elapsedMs,
        active = active,
        dischargedKwh = dischargedKwh,
        regeneratedKwh = regeneratedKwh,
        netKwh = if (dischargedKwh != null && regeneratedKwh != null) dischargedKwh - regeneratedKwh else null,
        energyCoveredMs = if (dischargedKwh == null) 0L else elapsedMs - uncoveredMs,
        energyUncoveredMs = uncoveredMs,
        energyPartial = partial,
        integrationQuality = if (partial) EnergyIntegrationQuality.PARTIAL else EnergyIntegrationQuality.COVERED,
        reason = if (partial) "gap_exceeded" else "integrated"
    )

    private fun vehicle(gear: String, odometer: Double, tripEnergy: Double): List<NormalizedObservation> = listOf(
        observation(NormalizedFieldCatalog.gearAutoMode, NormalizedValue(NormalizedValueType.TEXT, text = gear)),
        observation(NormalizedFieldCatalog.odometerKm, NormalizedValue(NormalizedValueType.NUMBER, number = odometer)),
        observation(NormalizedFieldCatalog.tripEnergy, NormalizedValue(NormalizedValueType.NUMBER, number = tripEnergy)),
        observation(NormalizedFieldCatalog.soc, NormalizedValue(NormalizedValueType.NUMBER, number = 50.0))
    )

    private fun observation(
        field: com.bydcollector.collector.data.normalized.NormalizedFieldDefinition,
        value: NormalizedValue
    ) = NormalizedObservation(
        field = field,
        value = value,
        quality = NormalizedQuality.OK,
        sourcePollId = 1L,
        sourceKey = field.sourceKeys.firstOrNull(),
        observedAt = "2026-09-14T00:00:00Z"
    )
}
