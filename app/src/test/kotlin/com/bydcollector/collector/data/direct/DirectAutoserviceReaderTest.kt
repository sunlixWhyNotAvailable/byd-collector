package com.bydcollector.collector.data.direct

import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.CallbackValueSource
import com.bydcollector.collector.direct.TelemetryCallbackBatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectAutoserviceReaderTest {
    @Test
    fun cachedCallbackSourceSurvivesHelperSnapshotWithoutMarkingOtherReads() {
        val entries = DirectFidRegistry.entries.take(2)
        val callback = CallbackValueSource(
            "boot-a", "generation-a", 1, 7L, 11L,
            entries[0].dev, entries[0].fid, TelemetryCallbackBatch.TYPE_INT, 17,
            1_000L, 900L, null, "usable"
        )
        val helper = object : DirectVehicleHelper {
            override fun isAlive(): Boolean = true
            override fun read(entry: DirectFidEntry) = error("scalar read must not be used")
            override fun readBatch(entries: List<DirectFidEntry>) = DirectHelperBatchResult(
                listOf(
                    DirectHelperReadResult(0, 17, callbackSource = callback),
                    DirectHelperReadResult(0, 18)
                ),
                testDiagnostics(entries.size)
            )
        }

        val readings = DirectAutoserviceReader(helper, entries).readSnapshot().readings

        assertEquals(callback, readings[0].callbackSource)
        assertEquals(null, readings[1].callbackSource)
        assertTrue(DirectHelperReadResult(0, 19, callbackCached = true).callbackCached)
        assertEquals(null, DirectHelperReadResult(0, 19, callbackCached = true).callbackSource)
    }

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
        assertEquals(CollectorHelperProtocol.STATUS_OK, first.status)
        assertTrue(first.summary().startsWith("status=0 mode=native"))
    }

    @Test
    fun rejectedReplayBatchIsTypedAndPreservedInCompactDiagnostics() {
        val entries = DirectFidRegistry.entries.take(2)
        val helper = batchHelper(
            CollectorHelperProtocol.STATUS_REPLAY_PENDING,
            "app-gap spool pending; replay before live read"
        )

        val snapshot = DirectAutoserviceReader(helper, entries).readSnapshot()
        val compact = snapshot.toJson(ok = false, includeFields = false)

        assertTrue(snapshot.replayPending)
        assertEquals(CollectorHelperProtocol.STATUS_REPLAY_PENDING, snapshot.batchStatus)
        assertTrue(snapshot.fields.all { it.status == CollectorHelperProtocol.STATUS_REPLAY_PENDING })
        assertTrue(compact.contains("\"status\":-915"))
        assertTrue(compact.contains("\"mode\":\"rejected\""))
    }

    @Test
    fun replayBatchStatusSurvivesMissingFieldResults() {
        val entries = DirectFidRegistry.entries.take(2)
        val helper = object : DirectVehicleHelper {
            override fun isAlive(): Boolean = true
            override fun read(entry: DirectFidEntry): DirectHelperReadResult = error("scalar read must not be used")
            override fun readBatch(entries: List<DirectFidEntry>): DirectHelperBatchResult = DirectHelperBatchResult(
                results = emptyList(),
                diagnostics = testDiagnostics(0).copy(
                    mode = "rejected",
                    error = "app-gap spool pending; replay before live read",
                    status = CollectorHelperProtocol.STATUS_REPLAY_PENDING
                )
            )
        }

        val snapshot = DirectAutoserviceReader(helper, entries).readSnapshot()

        assertTrue(snapshot.replayPending)
        assertEquals(CollectorHelperProtocol.STATUS_REPLAY_PENDING, snapshot.batchStatus)
        assertTrue(snapshot.fields.all { it.status == CollectorHelperProtocol.STATUS_SPOOL_UNAVAILABLE })
    }

    @Test
    fun notWhitelistedFieldDoesNotBecomeReplayPending() {
        val entries = DirectFidRegistry.entries.take(2)
        val snapshot = DirectAutoserviceReader(
            batchHelper(CollectorHelperProtocol.STATUS_NOT_WHITELISTED, "address is not whitelisted"),
            entries
        ).readSnapshot()

        assertFalse(snapshot.replayPending)
        assertTrue(snapshot.fields.all { it.status == CollectorHelperProtocol.STATUS_NOT_WHITELISTED })
    }

    private fun batchHelper(status: Int, error: String) =
        object : DirectVehicleHelper {
            override fun isAlive(): Boolean = true
            override fun read(entry: DirectFidEntry): DirectHelperReadResult = error("scalar read must not be used")
            override fun readBatch(entries: List<DirectFidEntry>): DirectHelperBatchResult = DirectHelperBatchResult(
                results = entries.map { DirectHelperReadResult(status, null, error) },
                diagnostics = testDiagnostics(entries.size).copy(
                    mode = "rejected",
                    error = error,
                    status = if (status == CollectorHelperProtocol.STATUS_REPLAY_PENDING) {
                        CollectorHelperProtocol.STATUS_REPLAY_PENDING
                    } else {
                        CollectorHelperProtocol.STATUS_INVALID_REQUEST
                    }
                )
            )
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
