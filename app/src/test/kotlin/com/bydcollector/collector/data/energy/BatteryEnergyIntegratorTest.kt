package com.bydcollector.collector.data.energy

import kotlin.test.Test
import kotlin.test.assertEquals
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
