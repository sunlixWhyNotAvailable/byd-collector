package com.bydcollector.collector.data.polling

import com.bydcollector.collector.data.direct.DirectFidEntry
import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.direct.DirectValueDecoder
import com.bydcollector.collector.data.direct.PendingTelemetryWorkerSamples
import com.bydcollector.collector.data.direct.TelemetryWorkerAckResult
import com.bydcollector.collector.data.direct.TelemetryWorkerFieldValue
import com.bydcollector.collector.data.direct.TelemetryWorkerSample
import com.bydcollector.collector.data.local.CatalogParameter
import com.bydcollector.collector.data.local.PersistedPollInput
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.local.WorkerPollImportResult
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.TelemetryWorkerSampleIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryWorkerReplayCoordinatorTest {
    @Test
    fun commitsRawPollAndObserverBeforeAcknowledgingTheHelperSample() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions)
        val sample = sample()
        val coordinator = coordinator(
            storage = storage,
            sample = sample,
            actions = actions,
            observer = object : SuccessfulPollObserver {
                override fun onSuccessfulPoll(
                    sessionId: Long,
                    pollId: Long,
                    timestamp: String,
                    readings: List<PollReading>
                ) {
                    actions += "observer:$pollId"
                }
            }
        )

        val result = coordinator.replayNextBatch(sessionId = 7L)

        assertFalse(result.needsReplay)
        assertEquals(41L, result.cycleResult?.pollId)
        assertEquals(1L, result.cycleResult?.pollRowsPersisted)
        assertEquals(listOf("parameters", "insert:7", "observer:41", "ack:999"), actions)
        assertEquals("1970-01-01T00:00:01Z", storage.input?.timestamp)
        assertEquals(PollReading("test_percent", "72", "72"), storage.input?.readings?.single())
    }

    @Test
    fun observerFailureLeavesTheSampleUnacknowledgedForReplay() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions)
        val coordinator = coordinator(
            storage = storage,
            sample = sample(),
            actions = actions,
            observer = object : SuccessfulPollObserver {
                override fun onSuccessfulPoll(
                    sessionId: Long,
                    pollId: Long,
                    timestamp: String,
                    readings: List<PollReading>
                ) {
                    actions += "observer:$pollId"
                    throw IllegalStateException("normalizer unavailable")
                }
            }
        )

        val result = coordinator.replayNextBatch(sessionId = 7L)

        assertTrue(result.needsReplay)
        assertEquals("worker_replay_error", result.cycleResult?.category)
        assertEquals(1L, result.cycleResult?.pollRowsPersisted)
        assertFalse(actions.any { it.startsWith("ack:") })
    }

    @Test
    fun replayCompletesBeforeTheFirstLivePoll() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions)
        val replay = coordinator(
            storage = storage,
            sample = sample(),
            actions = actions,
            observer = object : SuccessfulPollObserver {
                override fun onSuccessfulPoll(
                    sessionId: Long,
                    pollId: Long,
                    timestamp: String,
                    readings: List<PollReading>
                ) = Unit
            }
        )
        var livePolls = 0
        val runner = TelemetryWorkerReplayPollCycleRunner(
            replay = replay,
            live = object : PollCycleRunner {
                override fun pollOnce(sessionId: Long): PollCycleResult {
                    livePolls += 1
                    return PollCycleResult(88L, true, null, 5L, 1)
                }
            }
        )

        assertEquals(41L, runner.pollOnce(7L)?.pollId)
        assertEquals(0, livePolls)
        assertEquals(88L, runner.pollOnce(7L)?.pollId)
        assertEquals(1, livePolls)
    }

    @Test
    fun workerOnlyRunnerKeepsCheckingTheSpoolWithoutLivePolling() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions)
        var pendingCalls = 0
        val replay = TelemetryWorkerReplayCoordinator(
            store = storage,
            ensureHelper = { null },
            pendingSamples = {
                pendingCalls += 1
                PendingTelemetryWorkerSamples(
                    status = CollectorHelperProtocol.STATUS_OK,
                    samples = if (pendingCalls == 1) emptyList() else listOf(sample())
                )
            },
            acknowledgeSample = { _, _ ->
                TelemetryWorkerAckResult(
                    status = CollectorHelperProtocol.STATUS_OK,
                    updated = true
                )
            },
            successfulPollObserver = null,
            entries = listOf(TEST_ENTRY),
            acknowledgedAtMs = { 999L }
        )
        val runner = TelemetryWorkerReplayPollCycleRunner(replay)

        assertEquals(null, runner.pollOnce(7L))
        assertEquals(41L, runner.pollOnce(7L)?.pollId)
        assertEquals(2, pendingCalls)
    }

    private fun coordinator(
        storage: FakeWorkerPollStorage,
        sample: TelemetryWorkerSample,
        actions: MutableList<String>,
        observer: SuccessfulPollObserver
    ): TelemetryWorkerReplayCoordinator {
        return TelemetryWorkerReplayCoordinator(
            store = storage,
            ensureHelper = { null },
            pendingSamples = {
                PendingTelemetryWorkerSamples(
                    status = CollectorHelperProtocol.STATUS_OK,
                    samples = listOf(sample)
                )
            },
            acknowledgeSample = { _, acknowledgedAtMs ->
                actions += "ack:$acknowledgedAtMs"
                TelemetryWorkerAckResult(
                    status = CollectorHelperProtocol.STATUS_OK,
                    updated = true
                )
            },
            successfulPollObserver = observer,
            entries = listOf(TEST_ENTRY),
            acknowledgedAtMs = { 999L }
        )
    }

    private fun sample(): TelemetryWorkerSample {
        return TelemetryWorkerSample(
            identity = TelemetryWorkerSampleIdentity("boot-a", "generation-a", 1L),
            catalogVersion = DirectFidRegistry.CATALOG_VERSION,
            capturedWallMs = 1_000L,
            capturedElapsedMs = 900L,
            pollElapsedMs = 12L,
            batchStatus = CollectorHelperProtocol.STATUS_OK,
            batchMode = CollectorHelperProtocol.MODE_NATIVE,
            nativeAvailable = true,
            groupFailureCount = 0,
            error = null,
            values = listOf(
                TelemetryWorkerFieldValue(
                    fieldIndex = 0,
                    tx = TEST_ENTRY.tx,
                    dev = TEST_ENTRY.dev,
                    fid = TEST_ENTRY.fid,
                    status = CollectorHelperProtocol.STATUS_OK,
                    raw = 72,
                    error = null
                )
            )
        )
    }

    private class FakeWorkerPollStorage(
        private val actions: MutableList<String>
    ) : WorkerPollStorage {
        var input: PersistedPollInput? = null

        override fun getActiveCatalogParameters(): List<CatalogParameter> {
            actions += "parameters"
            return emptyList()
        }

        override fun insertWorkerPoll(
            sessionId: Long,
            identity: TelemetryWorkerSampleIdentity,
            input: PersistedPollInput,
            parameters: List<CatalogParameter>
        ): WorkerPollImportResult {
            actions += "insert:$sessionId"
            this.input = input
            return WorkerPollImportResult(pollId = 41L, inserted = true)
        }

        override fun recordEvent(category: String, message: String, detail: String?) {
            actions += "event:$category"
        }
    }

    companion object {
        private val TEST_ENTRY = DirectFidEntry(
            key = "test_percent",
            dev = 1001,
            fid = 42,
            tx = DirectFidRegistry.TX_GET_INT,
            decoder = DirectValueDecoder.INT_PERCENT
        )
    }
}
