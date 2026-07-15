package com.bydcollector.collector.data.polling

import com.bydcollector.collector.data.local.CatalogParameter
import com.bydcollector.collector.data.local.Clock
import com.bydcollector.collector.data.local.PersistedPollInput
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.remote.TelemetryClient
import com.bydcollector.collector.data.remote.TelemetryReadResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PollPersistenceCoordinatorTest {
    @Test
    fun successfulPollNotifiesObserverAfterRawPollIsInserted() {
        val readings = listOf(PollReading(rawKey = "SOC", rawValue = "72", descValue = "72%"))
        val store = FakePollStorage()
        val clock = FakeClock(now = "2026-06-12T10:00:00Z")
        var observedPollId: Long? = null
        var observedTimestamp: String? = null
        var observedReadings: List<PollReading>? = null

        val result = PollPersistenceCoordinator(
            store = store,
            client = FakeTelemetryClient(TelemetryReadResult.Success(rawBody = "{}", elapsedMs = 12, readings = readings)),
            clock = clock,
            successfulPollObserver = object : SuccessfulPollObserver {
                override fun onSuccessfulPoll(
                    sessionId: Long,
                    pollId: Long,
                    timestamp: String,
                    readings: List<PollReading>
                ) {
                    store.actions += "observer:$pollId:$timestamp"
                    observedPollId = pollId
                    observedTimestamp = timestamp
                    observedReadings = readings
                }
            }
        ).pollOnce(sessionId = 7L)

        assertTrue(result.ok)
        assertEquals(99L, result.pollId)
        assertEquals(99L, observedPollId)
        assertEquals(clock.now, observedTimestamp)
        assertSame(readings, observedReadings)
        assertEquals(
            listOf("getActiveCatalogParameters", "insertPoll:7:${clock.now}", "observer:99:${clock.now}"),
            store.actions
        )
    }

    @Test
    fun observerFailureDoesNotFailRawPoll() {
        val readings = listOf(PollReading(rawKey = "SOC", rawValue = "72"))
        val store = FakePollStorage()

        val result = PollPersistenceCoordinator(
            store = store,
            client = FakeTelemetryClient(TelemetryReadResult.Success(rawBody = "{}", elapsedMs = 12, readings = readings)),
            clock = FakeClock(now = "2026-06-12T10:00:00Z"),
            successfulPollObserver = object : SuccessfulPollObserver {
                override fun onSuccessfulPoll(
                    sessionId: Long,
                    pollId: Long,
                    timestamp: String,
                    readings: List<PollReading>
                ) {
                    throw IllegalStateException("normalizer unavailable")
                }
            }
        ).pollOnce(sessionId = 7L)

        assertTrue(result.ok)
        assertEquals(99L, result.pollId)
        assertEquals(
            listOf(
                FakeEvent(
                    category = "normalized_write_error",
                    message = "Normalized state write failed",
                    detail = "IllegalStateException: normalizer unavailable"
                )
            ),
            store.events
        )
    }

    @Test
    fun readModeDiagnosticIsRecordedOnlyWhenStateChanges() {
        val store = FakePollStorage()
        val coordinator = PollPersistenceCoordinator(
            store = store,
            client = FakeTelemetryClient(
                TelemetryReadResult.Success(
                    rawBody = "{}",
                    elapsedMs = 12,
                    readings = listOf(PollReading(rawKey = "SOC", rawValue = "72")),
                    diagnosticKey = "native|true|16|0|0|0|",
                    diagnosticMessage = "mode=native native_groups=16 helper_elapsed_ms=2 returned=77"
                )
            ),
            clock = FakeClock(now = "2026-07-15T10:00:00Z")
        )

        coordinator.pollOnce(sessionId = 7L)
        coordinator.pollOnce(sessionId = 7L)

        assertEquals(
            listOf(
                FakeEvent(
                    category = "direct_read_mode",
                    message = "Direct telemetry read mode changed",
                    detail = "mode=native native_groups=16 helper_elapsed_ms=2 returned=77"
                )
            ),
            store.events
        )
    }

    private class FakePollStorage : PollStorage {
        val actions = mutableListOf<String>()
        val events = mutableListOf<FakeEvent>()
        var insertedInput: PersistedPollInput? = null

        override fun getActiveCatalogParameters(): List<CatalogParameter> {
            actions += "getActiveCatalogParameters"
            return listOf(
                CatalogParameter(
                    id = 1L,
                    catalogVersionId = 1L,
                    sourceId = null,
                    key = "SOC",
                    name = "电量百分比",
                    groupName = "battery",
                    includeDesc = true,
                    note = null
                )
            )
        }

        override fun insertPoll(
            sessionId: Long,
            input: PersistedPollInput,
            parameters: List<CatalogParameter>
        ): Long {
            actions += "insertPoll:$sessionId:${input.timestamp}"
            insertedInput = input
            return 99L
        }

        override fun recordEvent(category: String, message: String, detail: String?) {
            events += FakeEvent(category, message, detail)
        }
    }

    private class FakeTelemetryClient(private val result: TelemetryReadResult) : TelemetryClient {
        override fun read(): TelemetryReadResult = result
    }

    private class FakeClock(val now: String) : Clock {
        override fun nowIso(): String = now
        override fun elapsedRealtimeMs(): Long = 123L
    }

    private data class FakeEvent(
        val category: String,
        val message: String,
        val detail: String?
    )
}
