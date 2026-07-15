package com.bydcollector.collector.data.direct

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectAutoserviceReaderTest {
    @Test
    fun snapshotPollsEveryRegistryEntryAndKeepsPartialErrors() {
        val results = DirectFidRegistry.entries.associate { entry ->
            entry.key to when (entry.key) {
                "charging_charge_current" -> DirectHelperReadResult(0, java.lang.Float.floatToIntBits(81.5f))
                "statistic_max_charge_power_allow" -> DirectHelperReadResult(0, 858)
                "engine_front_motor_speed" -> DirectHelperReadResult(-10013, null, "wrong transact")
                else -> DirectHelperReadResult(0, 1)
            }
        }
        val helper = object : DirectVehicleHelper {
            var batchCalls = 0
            override fun isAlive(): Boolean = true
            override fun read(entry: DirectFidEntry): DirectHelperReadResult = error("scalar read must not be used")
            override fun readBatch(entries: List<DirectFidEntry>): DirectHelperBatchResult {
                batchCalls++
                return DirectHelperBatchResult(
                    results = entries.map { results.getValue(it.key) },
                    diagnostics = testDiagnostics(entries.size)
                )
            }
        }

        val snapshot = DirectAutoserviceReader(helper).readSnapshot()

        assertEquals(1, helper.batchCalls)
        assertEquals("81.5", snapshot.readings.first { it.rawKey == "charging_charge_current" }.descValue)
        assertEquals("858", snapshot.readings.first { it.rawKey == "statistic_max_charge_power_allow" }.descValue)
        assertTrue(snapshot.errors.any { it.startsWith("engine_front_motor_speed:status=-10013") })
        assertTrue(snapshot.toJson().contains("\"source\":\"direct_autoservice_helper\""))
        assertTrue(snapshot.toJson().contains("\"mode\":\"native\""))
    }

    @Test
    fun compactFailureJsonStoresCountsAndCappedErrorSamples() {
        val entries = DirectFidRegistry.entries.take(20)
        val helper = object : DirectVehicleHelper {
            override fun isAlive(): Boolean = true
            override fun read(entry: DirectFidEntry): DirectHelperReadResult {
                return DirectHelperReadResult(status = -10013, raw = null, error = "wrong transact")
            }
        }

        val snapshot = DirectAutoserviceReader(helper, entries).readSnapshot()
        val compact = snapshot.toJson(ok = false, includeFields = false, maxErrorSamples = 3)

        assertTrue(compact.contains("\"field_count\":20"))
        assertTrue(compact.contains("\"error_count\":20"))
        assertTrue(compact.contains("\"error_samples\""))
        assertFalse(compact.contains("\"fields\""))
        assertTrue(snapshot.errorSummary(maxSamples = 3).contains("omitted=17"))
    }

    @Test
    fun diagnosticStateKeyIgnoresPerCycleGroupCounts() {
        val first = testDiagnostics(77)
        val rotatedDebugCycle = first.copy(
            nativeGroupCount = 14,
            fallbackGroupCount = 3,
            fallbackReadCount = 25,
            groupFailureCount = 3,
            returnedCount = 500,
            error = "different rotating group failure"
        )

        assertEquals(first.stateKey, rotatedDebugCycle.stateKey)
    }

    private fun testDiagnostics(count: Int) = DirectBatchDiagnostics(
        mode = "native",
        nativeAvailable = true,
        nativeGroupCount = 16,
        fallbackGroupCount = 0,
        fallbackReadCount = 0,
        groupFailureCount = 0,
        helperElapsedMs = 2,
        returnedCount = count
    )
}
