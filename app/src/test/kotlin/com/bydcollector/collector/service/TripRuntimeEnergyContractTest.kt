package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TripRuntimeEnergyContractTest {
    @Test
    fun runtimePersistsResetSafeEnergyInsteadOfRawCounterDelta() {
        val source = sourceFile("com/bydcollector/collector/service/TripRuntimeCoordinator.kt").readText()

        assertTrue(source.contains("energyKwh = startEnergy?.let { 0.0 }"))
        assertTrue(source.contains("TripMetrics.advanceEnergyCounter("))
        assertTrue(source.contains("val energy = energyUpdate.accumulatedKwh"))
        assertTrue(source.contains("energyKwh = energy"))
        assertTrue(source.contains("lastTripEnergyKwh = energyUpdate.lastCounterKwh"))
    }

    @Test
    fun firstValidPowerZeroClosesARecoveredSessionAndNotifiesTelegram() {
        val tracker = sourceFile("com/bydcollector/collector/service/VehiclePowerBoundaryTracker.kt").readText()
        val runtime = sourceFile("com/bydcollector/collector/service/TripRuntimeCoordinator.kt").readText()

        assertFalse(tracker.contains("offConfirmationCount"))
        assertFalse(tracker.contains("consecutiveZeroes"))
        assertInOrder(runtime, "ensureInitialized()", "powerTracker.observe(decodedPower)")
        assertTrue(runtime.contains("VehiclePowerState.OFF -> handlePowerOff(timestamp, snapshot, diagnosticPowerSession)"))
        assertTrue(runtime.contains("transition?.let(powerTracker::rollback)"))
        assertInOrder(runtime, "private fun handlePowerOff", "prepareConfirmedPowerOff(")
        assertInOrder(runtime, "prepareConfirmedPowerOff(", "state = TripSession.STATE_CLOSED")
        assertInOrder(runtime, "state = TripSession.STATE_CLOSED", "deliverAfterClose()")
    }

    private fun sourceFile(path: String): File = listOf(
        File("src/main/kotlin/$path"),
        File("app/src/main/kotlin/$path")
    ).firstOrNull(File::isFile) ?: error("Missing source file: $path")

    private fun assertInOrder(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue(firstIndex >= 0, "Missing token: $first")
        assertTrue(secondIndex > firstIndex, "Expected $first before $second")
    }
}
