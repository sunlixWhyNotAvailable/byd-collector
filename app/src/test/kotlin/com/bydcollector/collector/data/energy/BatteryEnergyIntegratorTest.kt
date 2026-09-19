package com.bydcollector.collector.data.energy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryEnergyIntegratorTest {
    @Test
    fun constantPowerZeroAndShortIntervalsUseMonotonicMilliseconds() {
        var state = State().advance(input(elapsedMs = 0, current = 100.0))
        state = state.advance(input(elapsedMs = 500, current = 100.0))
        state = state.advance(input(elapsedMs = 1_000, current = 0.0))

        assertClose(0.008333333333333333, state.totals.dischargedKwh)
        assertEquals(0.0, state.totals.regeneratedKwh)
        assertEquals(1_000L, state.totals.coveredMs)
        assertEquals(0L, state.totals.uncoveredMs)
        assertFalse(state.totals.partial)
        assertEquals(EnergyIntegrationQuality.COVERED, state.quality)

        var zero = State().advance(input(elapsedMs = 0, current = 0.0))
        zero = zero.advance(input(elapsedMs = 500, current = 0.0))
        assertEquals(0.0, zero.totals.dischargedKwh)
        assertEquals(0.0, zero.totals.regeneratedKwh)
        assertEquals(500L, zero.totals.coveredMs)
    }

    @Test
    fun exactZeroCrossingSplitsGrossEnergyAndAllowsNegativeNet() {
        var crossing = State().advance(input(elapsedMs = 0, voltage = 1_000.0, current = 10.0))
        crossing = crossing.advance(input(elapsedMs = 2_000, voltage = 1_000.0, current = -10.0))
        assertClose(0.001388888888888889, crossing.totals.dischargedKwh)
        assertClose(0.001388888888888889, crossing.totals.regeneratedKwh)
        assertClose(0.0, crossing.totals.netKwh)

        var negative = State().advance(input(elapsedMs = 0, voltage = 1_000.0, current = -20.0))
        negative = negative.advance(input(elapsedMs = 1_000, voltage = 1_000.0, current = -20.0))
        assertEquals(0.0, negative.totals.dischargedKwh)
        assertClose(0.005555555555555556, negative.totals.regeneratedKwh)
        assertTrue(negative.totals.netKwh < 0.0)
    }

    @Test
    fun twoSecondsIsCoveredButTwoSecondsAndOneMillisecondIsAGap() {
        val anchor = State().advance(input(elapsedMs = 0))
        val covered = anchor.advance(input(elapsedMs = 2_000))
        assertEquals(2_000L, covered.totals.coveredMs)
        assertEquals(EnergyIntegrationReason.INTEGRATED, covered.reason)

        val gap = anchor.advance(input(elapsedMs = 2_001))
        assertEquals(0L, gap.totals.coveredMs)
        assertEquals(2_001L, gap.totals.uncoveredMs)
        assertTrue(gap.totals.partial)
        assertEquals(EnergyIntegrationReason.GAP_EXCEEDED, gap.reason)
        assertEquals(2_001L, gap.anchor?.elapsedMs)
    }

    @Test
    fun duplicateBackwardTimeAndBootChangeBreakThenReanchor() {
        val anchor = State().advance(input(elapsedMs = 500))
        val duplicate = anchor.advance(input(elapsedMs = 500))
        assertEquals(EnergyIntegrationReason.NON_MONOTONIC_TIME, duplicate.reason)
        assertEquals(500L, duplicate.anchor?.elapsedMs)
        assertTrue(duplicate.totals.partial)

        val backward = anchor.advance(input(elapsedMs = 0))
        assertEquals(EnergyIntegrationReason.NON_MONOTONIC_TIME, backward.reason)
        assertEquals(0L, backward.totals.uncoveredMs)

        val reboot = anchor.advance(input(bootId = "boot-b", elapsedMs = 10))
        assertEquals(EnergyIntegrationReason.BOOT_CHANGED, reboot.reason)
        assertEquals("boot-b", reboot.anchor?.bootId)
        assertTrue(reboot.totals.partial)
    }

    @Test
    fun invalidMeasurementClearsAnchorCountsKnownGapAndPreservesPartial() {
        val anchor = State(
            totals = EnergyTotals(partial = true)
        ).advance(input(elapsedMs = 0))
        val invalid = anchor.advance(input(elapsedMs = 500, voltage = Double.NaN))

        assertNull(invalid.anchor)
        assertEquals(500L, invalid.totals.uncoveredMs)
        assertTrue(invalid.totals.partial)
        assertEquals(EnergyIntegrationReason.INVALID_SAMPLE, invalid.reason)

        val reanchored = invalid.advance(input(elapsedMs = 1_000))
        assertEquals(EnergyIntegrationQuality.ANCHOR_ONLY, reanchored.quality)
        assertEquals(1_000L, reanchored.anchor?.elapsedMs)
        assertEquals(0L, reanchored.totals.coveredMs)
    }

    @Test
    fun confirmedDisconnectedGunAllowsUnknownExternalChargingOnly() {
        val unknownExternal = State().advance(input(externalCharging = null))
        assertEquals(EnergyIntegrationQuality.ANCHOR_ONLY, unknownExternal.quality)
        assertEquals(EnergyIntegrationReason.INITIAL_ANCHOR, unknownExternal.reason)

        val unknownGun = State().advance(input(gunDisconnected = null, externalCharging = null))
        assertEquals(EnergyIntegrationReason.CHARGE_GUN_NOT_CONFIRMED, unknownGun.reason)
        assertEquals(EnergyIntegrationQuality.PARTIAL, unknownGun.quality)
        assertTrue(unknownGun.totals.partial)

        val charging = State().advance(input(externalCharging = true))
        assertEquals(EnergyIntegrationReason.EXTERNAL_CHARGING, charging.reason)
        assertEquals(EnergyIntegrationQuality.EXCLUDED, charging.quality)
        assertFalse(charging.totals.partial)

        val connected = State().advance(input(gunDisconnected = false))
        assertEquals(EnergyIntegrationReason.CHARGE_GUN_CONNECTED, connected.reason)
        assertEquals(EnergyIntegrationQuality.EXCLUDED, connected.quality)

        val powerUnknown = State().advance(input(powerOn = null))
        assertEquals(EnergyIntegrationReason.POWER_NOT_CONFIRMED, powerUnknown.reason)
        assertTrue(powerUnknown.totals.partial)

        val powerOff = State().advance(input(powerOn = false))
        assertEquals(EnergyIntegrationReason.POWER_OFF, powerOff.reason)
        assertEquals(EnergyIntegrationQuality.EXCLUDED, powerOff.quality)
    }

    @Test
    fun confirmedOffIntegratesTheRealClosingEndpointAndAlwaysClearsAnchor() {
        val anchored = State().advance(input(elapsedMs = 1_000L, current = 100.0))
        val finished = BatteryEnergyIntegrator.finishSession(
            anchored.anchor,
            anchored.totals,
            input(elapsedMs = 2_000L, current = 100.0, powerOn = false)
        )

        assertClose(0.011111111111111112, finished.totals.dischargedKwh)
        assertEquals(1_000L, finished.totals.coveredMs)
        assertFalse(finished.totals.partial)
        assertNull(finished.anchor)
        assertEquals(EnergyIntegrationQuality.COVERED, finished.quality)
        assertEquals(EnergyIntegrationReason.INTEGRATED, finished.reason)
    }

    @Test
    fun invalidClosingEndpointIsAnExplicitGapAndNeverLeavesAnAnchor() {
        val anchored = State().advance(input(elapsedMs = 1_000L))
        val invalid = listOf(
            input(elapsedMs = 2_000L, powerOn = false, voltage = null) to EnergyIntegrationReason.INVALID_SAMPLE,
            input(elapsedMs = 2_000L, powerOn = false, gunDisconnected = null) to EnergyIntegrationReason.CHARGE_GUN_NOT_CONFIRMED,
            input(elapsedMs = 2_000L, powerOn = false, externalCharging = true) to EnergyIntegrationReason.EXTERNAL_CHARGING,
            input(bootId = "boot-b", elapsedMs = 2_000L, powerOn = false) to EnergyIntegrationReason.BOOT_CHANGED,
            input(elapsedMs = 3_001L, powerOn = false) to EnergyIntegrationReason.GAP_EXCEEDED
        )

        invalid.forEach { (endpoint, expectedReason) ->
            val result = BatteryEnergyIntegrator.finishSession(anchored.anchor, anchored.totals, endpoint)
            assertNull(result.anchor)
            assertTrue(result.totals.partial)
            assertEquals(0L, result.totals.coveredMs)
            assertEquals(expectedReason, result.reason)
        }
        assertFails {
            BatteryEnergyIntegrator.finishSession(anchored.anchor, anchored.totals, input(powerOn = true))
        }
    }

    @Test
    fun climateLikeStationarySamplesRemainEligibleAndCalendarIsIrrelevant() {
        var state = State().advance(input(elapsedMs = 86_399_500, current = 5.0))
        state = state.advance(input(elapsedMs = 86_400_000, current = 5.0))

        assertEquals(500L, state.totals.coveredMs)
        assertTrue(state.totals.dischargedKwh > 0.0)
        assertEquals(EnergyIntegrationReason.INTEGRATED, state.reason)
    }

    @Test
    fun historicalAssessmentArithmeticVectorsRepeatAtTwoSecondCadence() {
        val constantOutput = integrateLinear(startKw = 12.0, endKw = 12.0, durationMs = 600_000)
        assertClose(2.0, constantOutput.dischargedKwh)
        assertEquals(0.0, constantOutput.regeneratedKwh)

        val constantInput = integrateLinear(startKw = -6.0, endKw = -6.0, durationMs = 600_000)
        assertEquals(0.0, constantInput.dischargedKwh)
        assertClose(1.0, constantInput.regeneratedKwh)

        val crossing = integrateLinear(startKw = 12.0, endKw = -12.0, durationMs = 600_000)
        assertClose(0.5, crossing.dischargedKwh)
        assertClose(0.5, crossing.regeneratedKwh)

        val asymmetric = integrateLinear(startKw = -3.0, endKw = 9.0, durationMs = 3_600_000)
        assertClose(0.375, asymmetric.regeneratedKwh)
        assertClose(3.0, asymmetric.netKwh)
        assertEquals(3_600_000L, asymmetric.coveredMs)
    }

    @Test
    fun savedAssessmentCorpusFragmentMatchesIndependentSplitCalculation() {
        // Read-only extraction from assessment source 20260826_174746, session 69,
        // poll IDs 767070..767109 inside EC 905. No location or unrelated fields retained.
        val samples = listOf(
            CorpusSample(0L, 553.0, -11.699982),
            CorpusSample(402L, 551.0, -1.5),
            CorpusSample(718L, 551.0, -1.5),
            CorpusSample(1_243L, 551.0, 3.100006),
            CorpusSample(1_741L, 550.0, 5.200012),
            CorpusSample(2_247L, 551.0, 6.600006),
            CorpusSample(2_956L, 551.0, 0.399994),
            CorpusSample(3_266L, 551.0, 1.300018),
            CorpusSample(3_746L, 552.0, 0.300018),
            CorpusSample(4_291L, 552.0, -0.199982),
            CorpusSample(4_802L, 551.0, 0.0),
            CorpusSample(5_307L, 550.0, 7.600006),
            CorpusSample(5_814L, 550.0, 8.800018),
            CorpusSample(6_324L, 551.0, 7.5),
            CorpusSample(6_992L, 550.0, 5.899994),
            CorpusSample(7_271L, 550.0, 10.300018),
            CorpusSample(7_730L, 550.0, 10.300018),
            CorpusSample(8_276L, 551.0, 3.200012),
            CorpusSample(8_813L, 551.0, 1.300018),
            CorpusSample(9_321L, 552.0, -0.600006),
            CorpusSample(10_427L, 552.0, -3.799988),
            CorpusSample(10_482L, 552.0, -3.799988),
            CorpusSample(11_021L, 552.0, -5.799988),
            CorpusSample(11_491L, 553.0, -6.899994),
            CorpusSample(12_141L, 553.0, -7.5),
            CorpusSample(12_508L, 552.0, -7.0),
            CorpusSample(13_405L, 552.0, -4.399994),
            CorpusSample(13_522L, 552.0, -2.600006),
            CorpusSample(14_015L, 550.0, 2.600006),
            CorpusSample(14_645L, 550.0, 11.600006),
            CorpusSample(15_006L, 551.0, 11.600006),
            CorpusSample(15_579L, 551.0, 1.899994),
            CorpusSample(16_348L, 551.0, 1.899994),
            CorpusSample(16_566L, 551.0, 1.800018),
            CorpusSample(17_017L, 551.0, 1.899994),
            CorpusSample(17_739L, 551.0, 2.600006),
            CorpusSample(18_411L, 550.0, 3.700012),
            CorpusSample(18_595L, 549.0, 7.300018),
            CorpusSample(19_596L, 550.0, 7.800018),
            CorpusSample(19_816L, 550.0, 7.800018)
        )
        var state = State()
        samples.forEach { sample ->
            state = state.advance(
                input(
                    bootId = "assessment-20260826",
                    elapsedMs = sample.elapsedMs,
                    voltage = sample.voltage,
                    current = sample.current
                )
            )
        }

        assertClose(0.009961753284283783, state.totals.dischargedKwh, 1e-9)
        assertClose(0.003801327259144893, state.totals.regeneratedKwh, 1e-9)
        assertClose(0.006160426025138889, state.totals.netKwh, 1e-9)
        assertEquals(19_816L, state.totals.coveredMs)
        assertEquals(0L, state.totals.uncoveredMs)
        assertFalse(state.totals.partial)
    }

    @Test
    fun totalsRejectNegativeOrNonFiniteAggregates() {
        assertFails { EnergyTotals(dischargedKwh = -0.1) }
        assertFails { EnergyTotals(regeneratedKwh = Double.NaN) }
        assertFails { EnergyTotals(coveredMs = -1) }
        assertFails { EnergyTotals(uncoveredMs = -1) }
    }

    private fun integrateLinear(startKw: Double, endKw: Double, durationMs: Long): EnergyTotals {
        var state = State().advance(
            input(elapsedMs = 0, voltage = 1_000.0, current = startKw)
        )
        var elapsed = BatteryEnergyIntegrator.MAX_INTERVAL_MS
        while (elapsed <= durationMs) {
            val fraction = elapsed.toDouble() / durationMs.toDouble()
            val power = startKw + (endKw - startKw) * fraction
            state = state.advance(input(elapsedMs = elapsed, voltage = 1_000.0, current = power))
            elapsed += BatteryEnergyIntegrator.MAX_INTERVAL_MS
        }
        return state.totals
    }

    private fun input(
        bootId: String = "boot-a",
        elapsedMs: Long = 0L,
        voltage: Double? = 400.0,
        current: Double? = 100.0,
        powerOn: Boolean? = true,
        gunDisconnected: Boolean? = true,
        externalCharging: Boolean? = false
    ) = EnergyInput(
        bootId = bootId,
        elapsedMs = elapsedMs,
        voltage = voltage,
        current = current,
        powerOn = powerOn,
        gunDisconnected = gunDisconnected,
        externalCharging = externalCharging
    )

    private data class State(
        val totals: EnergyTotals = EnergyTotals(),
        val anchor: EnergyAnchor? = null,
        val quality: EnergyIntegrationQuality? = null,
        val reason: EnergyIntegrationReason? = null
    ) {
        fun advance(input: EnergyInput): State {
            val result = BatteryEnergyIntegrator.integrate(anchor, totals, input)
            return State(result.totals, result.anchor, result.quality, result.reason)
        }
    }

    private data class CorpusSample(
        val elapsedMs: Long,
        val voltage: Double,
        val current: Double
    )

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-12) {
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected=$expected actual=$actual")
    }

    private inline fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
