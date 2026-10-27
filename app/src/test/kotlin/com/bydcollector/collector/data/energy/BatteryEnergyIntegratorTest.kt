package com.bydcollector.collector.data.energy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryEnergyIntegratorTest {

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
    fun sameEvaluationTimeMovesAnchorWithoutAddingEnergyOrPartialTime() {
        val first = BatteryEnergyIntegrator.integrate(null, EnergyTotals(), input(elapsedMs = 5_000L))
        val sameTime = BatteryEnergyIntegrator.integrate(
            first.anchor,
            first.totals,
            input(elapsedMs = 5_000L, current = 200.0)
        )

        assertEquals(0L, sameTime.totals.coveredMs)
        assertEquals(0L, sameTime.totals.uncoveredMs)
        assertFalse(sameTime.totals.partial)
        assertEquals(80.0, sameTime.anchor?.powerKw)
        assertEquals(EnergyIntegrationQuality.ANCHOR_ONLY, sameTime.quality)
        assertEquals(EnergyIntegrationReason.ZERO_INTERVAL, sameTime.reason)

        val closed = BatteryEnergyIntegrator.finishSession(
            sameTime.anchor,
            sameTime.totals,
            input(elapsedMs = 5_000L, powerOn = false, current = 200.0)
        )
        assertEquals(sameTime.totals, closed.totals)
        assertEquals(null, closed.anchor)
        assertFalse(closed.totals.partial)
    }

    @Test
    fun lateChangedSourceMarksUncoveredIntervalAndReanchorsAtEvaluationTime() {
        val totals = EnergyTotals(dischargedKwh = 0.01, coveredMs = 1_000L)
        val result = BatteryEnergyIntegrator.reanchorAfterLateSource(
            previousAnchor = EnergyAnchor("boot-a", 1_000L, 40.0),
            totals = totals,
            input = input(elapsedMs = 2_500L, current = 200.0)
        )

        assertEquals(0.01, result.totals.dischargedKwh)
        assertEquals(1_000L, result.totals.coveredMs)
        assertEquals(1_500L, result.totals.uncoveredMs)
        assertTrue(result.totals.partial)
        assertEquals(EnergyAnchor("boot-a", 2_500L, 80.0), result.anchor)
        assertEquals(EnergyIntegrationReason.LATE_SOURCE_REANCHORED, result.reason)
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

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-12) {
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected=$expected actual=$actual")
    }

}
