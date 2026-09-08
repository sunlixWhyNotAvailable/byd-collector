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
        var observedOrigin: PollOrigin? = null
        val coordinator = coordinator(
            storage = storage,
            sample = sample,
            actions = actions,
            observer = object : SuccessfulPollObserver {
                override fun onSuccessfulPoll(
                    sessionId: Long,
                    pollId: Long,
                    timestamp: String,
                    readings: List<PollReading>,
                    origin: PollOrigin
                ) {
                    actions += "observer:$pollId"
                    observedOrigin = origin
                }
            }
        )

        val result = coordinator.replayNextBatch(sessionId = 7L)

        assertFalse(result.needsReplay)
        assertEquals(41L, result.cycleResult?.pollId)
        assertEquals(1L, result.cycleResult?.pollRowsPersisted)
        assertEquals(
            listOf("parameters", "insert:7", "observer:41", "ack:999", "event:worker_spool_replayed"),
            actions
        )
        assertEquals("1970-01-01T00:00:01Z", storage.input?.timestamp)
        assertEquals(PollReading("test_percent", "72", "72"), storage.input?.readings?.single())
        assertEquals(PollOrigin.REPLAY, observedOrigin)
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
                    readings: List<PollReading>,
                    origin: PollOrigin
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
        var pendingCalls = 0
        val replay = TelemetryWorkerReplayCoordinator(
            store = storage,
            ensureHelper = { null },
            pendingSamples = {
                pendingCalls += 1
                PendingTelemetryWorkerSamples(
                    status = CollectorHelperProtocol.STATUS_OK,
                    samples = if (pendingCalls == 1) listOf(sample()) else emptyList()
                )
            },
            acknowledgeSample = { _, _ ->
                actions += "ack:999"
                TelemetryWorkerAckResult(CollectorHelperProtocol.STATUS_OK, updated = true)
            },
            successfulPollObserver = null,
            replayEntriesForCatalog = ::testCatalogEntries,
            acknowledgedAtMs = { 999L }
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
        assertEquals(2, pendingCalls)
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
            replayEntriesForCatalog = ::testCatalogEntries,
            acknowledgedAtMs = { 999L }
        )
        val runner = TelemetryWorkerReplayPollCycleRunner(replay)

        assertEquals(null, runner.pollOnce(7L))
        assertEquals(41L, runner.pollOnce(7L)?.pollId)
        assertEquals(2, pendingCalls)
    }

    @Test
    fun expandedCurrentCatalogImportsLegacyAndCurrentSamplesWithoutInventingMissingFields() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions)
        val legacyEntries = requireNotNull(
            DirectFidRegistry.workerReplayEntriesForCatalog(DirectFidRegistry.LEGACY_WORKER_CATALOG_VERSION)
        )
        val currentEntries = DirectFidRegistry.entries
        val legacySample = sample(
            DirectFidRegistry.LEGACY_WORKER_CATALOG_VERSION,
            legacyEntries,
            List(legacyEntries.size) { 0 },
            sequence = 1L
        )
        val currentSample = sample(
            DirectFidRegistry.CATALOG_VERSION,
            currentEntries,
            List(currentEntries.size) { 0 },
            sequence = 2L
        )
        val partialLegacySample = sample(
            DirectFidRegistry.LEGACY_WORKER_CATALOG_VERSION,
            legacyEntries,
            List(legacyEntries.size) { if (it == 0) null else 0 },
            sequence = 3L
        )
        val coordinator = TelemetryWorkerReplayCoordinator(
            store = storage,
            ensureHelper = { null },
            pendingSamples = {
                PendingTelemetryWorkerSamples(
                    status = CollectorHelperProtocol.STATUS_OK,
                    samples = listOf(legacySample, currentSample, partialLegacySample)
                )
            },
            acknowledgeSample = { identity, _ ->
                actions += "ack:${identity.pollSequence}"
                TelemetryWorkerAckResult(CollectorHelperProtocol.STATUS_OK, updated = true)
            }
        )

        val result = coordinator.replayNextBatch(7L)

        assertFalse(result.needsReplay)
        assertEquals(82, storage.inputs[0].readings.size)
        assertEquals(95, storage.inputs[1].readings.size)
        assertEquals(legacyEntries.map { it.key }, storage.inputs[0].readings.map { it.rawKey })
        assertEquals(currentEntries.map { it.key }, storage.inputs[1].readings.map { it.rawKey })
        assertEquals(listOf(82, 95, 82), storage.requestedParameters.map { it.size })
        assertEquals(legacyEntries.map { it.key }, storage.requestedParameters[0].map { it.key })
        assertEquals(currentEntries.map { it.key }, storage.requestedParameters[1].map { it.key })
        assertEquals(emptyList(), storage.inputs[0].readings.map { it.rawKey }.intersect(currentEntries.takeLast(4).map { it.key }.toSet()).toList())
        assertEquals(81, storage.inputs[2].readings.size)
        assertEquals("autoservice_partial_failure", storage.inputs[2].errorCategory)
        assertEquals(List(3) { "1970-01-01T00:00:01Z" }, storage.inputs.map { it.timestamp })
        assertEquals(
            listOf("parameters", "insert:7", "ack:1", "insert:7", "ack:2", "insert:7", "ack:3", "event:worker_spool_replayed"),
            actions
        )
    }

    @Test
    fun repeatedCommittedSampleStaysIdempotentAndAcknowledgesOnlyAfterStorage() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions)
        val replayed = sample()
        var ackCalls = 0
        val coordinator = TelemetryWorkerReplayCoordinator(
            store = storage,
            ensureHelper = { null },
            pendingSamples = {
                PendingTelemetryWorkerSamples(CollectorHelperProtocol.STATUS_OK, listOf(replayed))
            },
            acknowledgeSample = { _, _ ->
                ackCalls += 1
                actions += "ack-attempt:$ackCalls"
                if (ackCalls == 1) {
                    TelemetryWorkerAckResult(
                        CollectorHelperProtocol.STATUS_READ_ERROR,
                        updated = false,
                        error = "retry"
                    )
                } else {
                    TelemetryWorkerAckResult(CollectorHelperProtocol.STATUS_OK, updated = true)
                }
            },
            replayEntriesForCatalog = ::testCatalogEntries
        )

        assertTrue(coordinator.replayNextBatch(7L).needsReplay)
        val second = coordinator.replayNextBatch(7L)

        assertFalse(second.needsReplay)
        assertEquals(listOf(true, false), storage.inserted)
        assertEquals(0L, second.cycleResult?.pollRowsPersisted)
        assertTrue(actions.indexOf("insert:7") < actions.indexOf("ack-attempt:1"))
        assertTrue(actions.lastIndexOf("insert:7") < actions.indexOf("ack-attempt:2"))
    }

    @Test
    fun addressCorruptCatalogSampleIsRejectedBeforeStorageAndAck() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions)
        val wrongAddress = TEST_ENTRY.copy(fid = TEST_ENTRY.fid + 1)
        val coordinator = TelemetryWorkerReplayCoordinator(
            store = storage,
            ensureHelper = { null },
            pendingSamples = {
                PendingTelemetryWorkerSamples(
                    CollectorHelperProtocol.STATUS_OK,
                    listOf(sample("known", listOf(wrongAddress), listOf(72)))
                )
            },
            acknowledgeSample = { _, _ ->
                actions += "ack"
                TelemetryWorkerAckResult(CollectorHelperProtocol.STATUS_OK, updated = true)
            },
            replayEntriesForCatalog = { catalog -> listOf(TEST_ENTRY).takeIf { catalog == "known" } }
        )

        val corrupt = coordinator.replayNextBatch(7L)

        assertTrue(corrupt.needsReplay)
        assertEquals("worker_replay_error", corrupt.cycleResult?.category)
        assertTrue(storage.inputs.isEmpty())
        assertFalse(actions.contains("ack"))
    }

    @Test
    fun unknownCatalogSampleIsRejectedBeforeStorageAndAck() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions)
        val coordinator = TelemetryWorkerReplayCoordinator(
            store = storage,
            ensureHelper = { null },
            pendingSamples = {
                PendingTelemetryWorkerSamples(
                    CollectorHelperProtocol.STATUS_OK,
                    listOf(sample("unknown", listOf(TEST_ENTRY), listOf(72)))
                )
            },
            acknowledgeSample = { _, _ ->
                actions += "ack"
                TelemetryWorkerAckResult(CollectorHelperProtocol.STATUS_OK, updated = true)
            },
            replayEntriesForCatalog = { null }
        )

        val unknown = coordinator.replayNextBatch(7L)

        assertTrue(unknown.needsReplay)
        assertEquals("worker_replay_error", unknown.cycleResult?.category)
        assertTrue(storage.inputs.isEmpty())
        assertFalse(actions.contains("ack"))
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
            replayEntriesForCatalog = ::testCatalogEntries,
            acknowledgedAtMs = { 999L }
        )
    }

    private fun sample(): TelemetryWorkerSample =
        sample(DirectFidRegistry.CATALOG_VERSION, listOf(TEST_ENTRY), listOf(72))

    private fun sample(
        catalogVersion: String,
        entries: List<DirectFidEntry>,
        rawValues: List<Int?>,
        sequence: Long = 1L
    ): TelemetryWorkerSample {
        require(entries.size == rawValues.size)
        return TelemetryWorkerSample(
            identity = TelemetryWorkerSampleIdentity("boot-a", "generation-a", sequence),
            catalogVersion = catalogVersion,
            capturedWallMs = 1_000L,
            capturedElapsedMs = 900L,
            pollElapsedMs = 12L,
            batchStatus = CollectorHelperProtocol.STATUS_OK,
            batchMode = CollectorHelperProtocol.MODE_NATIVE,
            nativeAvailable = true,
            groupFailureCount = 0,
            error = null,
            values = entries.mapIndexed { index, entry ->
                TelemetryWorkerFieldValue(
                    fieldIndex = index,
                    tx = entry.tx,
                    dev = entry.dev,
                    fid = entry.fid,
                    status = CollectorHelperProtocol.STATUS_OK,
                    raw = rawValues[index],
                    error = null
                )
            }
        )
    }

    private class FakeWorkerPollStorage(
        private val actions: MutableList<String>
    ) : WorkerPollStorage {
        var input: PersistedPollInput? = null
        val inputs = mutableListOf<PersistedPollInput>()
        val requestedParameters = mutableListOf<List<CatalogParameter>>()
        val inserted = mutableListOf<Boolean>()
        private val identities = mutableSetOf<TelemetryWorkerSampleIdentity>()

        override fun getActiveCatalogParameters(): List<CatalogParameter> {
            actions += "parameters"
            return (DirectFidRegistry.entries + TEST_ENTRY).mapIndexed { index, entry ->
                CatalogParameter(index + 1L, 1L, entry.sourceId, entry.key, entry.featureNames, entry.groupName, true, entry.note)
            }
        }

        override fun insertWorkerPoll(
            sessionId: Long,
            identity: TelemetryWorkerSampleIdentity,
            input: PersistedPollInput,
            parameters: List<CatalogParameter>
        ): WorkerPollImportResult {
            actions += "insert:$sessionId"
            this.input = input
            inputs += input
            requestedParameters += parameters
            val wasInserted = identities.add(identity)
            inserted += wasInserted
            return WorkerPollImportResult(pollId = 41L, inserted = wasInserted)
        }

        override fun recordEvent(category: String, message: String, detail: String?) {
            actions += "event:$category"
        }
    }

    companion object {
        private fun testCatalogEntries(catalogVersion: String): List<DirectFidEntry>? =
            listOf(TEST_ENTRY).takeIf { catalogVersion == DirectFidRegistry.CATALOG_VERSION }

        private val TEST_ENTRY = DirectFidEntry(
            key = "test_percent",
            dev = 1001,
            fid = 42,
            tx = DirectFidRegistry.TX_GET_INT,
            decoder = DirectValueDecoder.INT_PERCENT
        )
    }
}
