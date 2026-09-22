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
import com.bydcollector.collector.direct.CallbackValueSource
import com.bydcollector.collector.direct.TelemetryCallbackBatch
import com.bydcollector.collector.direct.TelemetryWorkerSampleIdentity
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryWorkerReplayCoordinatorTest {
    @Test
    fun failedRawSampleNotifiesOnlyFailureObserverBeforeAcknowledgement() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions)
        var successfulCallbacks = 0
        var failedSource: PollSampleSource? = null
        val coordinator = coordinator(
            storage = storage,
            sample = sample(DirectFidRegistry.CATALOG_VERSION, listOf(TEST_ENTRY), listOf(null)),
            actions = actions,
            observer = object : SuccessfulPollObserver {
                override fun onSuccessfulPoll(
                    sessionId: Long,
                    pollId: Long,
                    timestamp: String,
                    readings: List<PollReading>,
                    origin: PollOrigin
                ) {
                    successfulCallbacks += 1
                }

                override fun onSourceFailure(
                    sessionId: Long,
                    pollId: Long,
                    timestamp: String,
                    origin: PollOrigin,
                    source: PollSampleSource
                ) {
                    actions += "failureObserver:$pollId"
                    assertEquals(PollOrigin.REPLAY, origin)
                    failedSource = source
                }
            }
        )

        val result = coordinator.replayNextBatch(sessionId = 7L)

        assertFalse(result.needsReplay)
        assertEquals(0, successfulCallbacks)
        assertEquals("helper:boot-a:generation-a:1", failedSource?.identity)
        assertEquals(900L, failedSource?.capturedElapsedMs)
        assertEquals(
            listOf("parameters", "insert:7", "failureObserver:41", "ack:999", "event:worker_spool_replayed"),
            actions
        )
    }

    @Test
    fun failedSampleObserverFailureLeavesReplaySampleUnacknowledged() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions)
        val coordinator = coordinator(
            storage = storage,
            sample = sample(DirectFidRegistry.CATALOG_VERSION, listOf(TEST_ENTRY), listOf(null)),
            actions = actions,
            observer = object : SuccessfulPollObserver {
                override fun onSuccessfulPoll(
                    sessionId: Long,
                    pollId: Long,
                    timestamp: String,
                    readings: List<PollReading>,
                    origin: PollOrigin
                ) = Unit

                override fun onSourceFailure(
                    sessionId: Long,
                    pollId: Long,
                    timestamp: String,
                    origin: PollOrigin,
                    source: PollSampleSource
                ) {
                    throw Exception("checked energy storage failure")
                }
            }
        )

        val result = coordinator.replayNextBatch(sessionId = 7L)

        assertTrue(result.needsReplay)
        assertEquals("worker_replay_error", result.cycleResult?.category)
        assertFalse(actions.any { it.startsWith("ack:") })
    }

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
        assertContains(
            storage.events.single().third.orEmpty(),
            "batch=1 import_attempted=1 imported=1 inserted=1 duplicates=0 " +
                "ack_attempted=1 ack_succeeded=1 ack_failed=0"
        )
        assertContains(storage.events.single().third.orEmpty(), "first=boot-a:generation-a:1 last=boot-a:generation-a:1")
    }

    @Test
    fun replayPreservesCachedCallbackIdentityAndLeavesGetterValuesUnlabeled() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions)
        val source = callbackSource()
        val cached = sample().let { sample ->
            sample.copy(values = listOf(sample.values.single().copy(callbackSource = source)))
        }
        val coordinator = coordinator(
            storage,
            cached,
            actions,
            object : SuccessfulPollObserver {
                override fun onSuccessfulPoll(
                    sessionId: Long,
                    pollId: Long,
                    timestamp: String,
                    readings: List<PollReading>,
                    origin: PollOrigin
                ) = Unit
            }
        )

        coordinator.replayNextBatch(7L)

        assertEquals(source, storage.input?.readings?.single()?.callbackSource)
        assertEquals(null, sample().values.single().callbackSource)
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
        assertTrue(storage.events.isEmpty())
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
        assertEquals(listOf("worker_spool_ack_error", "worker_spool_replayed"), storage.events.map { it.first })
        assertContains(
            storage.events[0].third.orEmpty(),
            "inserted=1 duplicates=0 ack_attempted=1 ack_succeeded=0 ack_failed=1"
        )
        assertContains(
            storage.events[1].third.orEmpty(),
            "inserted=0 duplicates=1 ack_attempted=1 ack_succeeded=1 ack_failed=0"
        )
    }

    @Test
    fun partialBatchFailureRecordsCompletedImportsAndAcksWithLastAttemptedIdentity() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions)
        var observerCalls = 0
        val coordinator = TelemetryWorkerReplayCoordinator(
            store = storage,
            ensureHelper = { null },
            pendingSamples = {
                PendingTelemetryWorkerSamples(
                    CollectorHelperProtocol.STATUS_OK,
                    listOf(sample(sequence = 1L), sample(sequence = 2L))
                )
            },
            acknowledgeSample = { identity, _ ->
                actions += "ack:${identity.pollSequence}"
                TelemetryWorkerAckResult(CollectorHelperProtocol.STATUS_OK, updated = true)
            },
            successfulPollObserver = object : SuccessfulPollObserver {
                override fun onSuccessfulPoll(
                    sessionId: Long,
                    pollId: Long,
                    timestamp: String,
                    readings: List<PollReading>,
                    origin: PollOrigin
                ) {
                    observerCalls += 1
                    if (observerCalls == 2) throw IllegalStateException("observer stopped batch")
                }
            },
            replayEntriesForCatalog = ::testCatalogEntries
        )

        val result = coordinator.replayNextBatch(7L)

        assertTrue(result.needsReplay)
        assertEquals(2L, result.cycleResult?.pollRowsPersisted)
        assertEquals(listOf("ack:1"), actions.filter { it.startsWith("ack:") })
        assertContains(
            storage.events.single().third.orEmpty(),
            "batch=2 import_attempted=2 imported=2 inserted=2 duplicates=0 " +
                "ack_attempted=1 ack_succeeded=1 ack_failed=0"
        )
        assertContains(storage.events.single().third.orEmpty(), "first=boot-a:generation-a:1 last=boot-a:generation-a:2")
    }

    @Test
    fun partialStorageFailureDoesNotClaimTheUncompletedImportOrAck() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions).apply { failInsertAttempt = 2 }
        val coordinator = TelemetryWorkerReplayCoordinator(
            store = storage,
            ensureHelper = { null },
            pendingSamples = {
                PendingTelemetryWorkerSamples(
                    CollectorHelperProtocol.STATUS_OK,
                    listOf(sample(sequence = 1L), sample(sequence = 2L))
                )
            },
            acknowledgeSample = { identity, _ ->
                actions += "ack:${identity.pollSequence}"
                TelemetryWorkerAckResult(CollectorHelperProtocol.STATUS_OK, updated = true)
            },
            replayEntriesForCatalog = ::testCatalogEntries
        )

        val result = coordinator.replayNextBatch(7L)

        assertTrue(result.needsReplay)
        assertEquals(1L, result.cycleResult?.pollRowsPersisted)
        assertEquals(listOf("ack:1"), actions.filter { it.startsWith("ack:") })
        assertContains(
            storage.events.single().third.orEmpty(),
            "batch=2 import_attempted=2 imported=1 inserted=1 duplicates=0 " +
                "ack_attempted=1 ack_succeeded=1 ack_failed=0"
        )
        assertContains(storage.events.single().third.orEmpty(), "last=boot-a:generation-a:2")
    }

    @Test
    fun diagnosticWriteFailureDoesNotChangeCommittedOrAcknowledgedOutcome() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions).apply { failEventWrites = true }
        val coordinator = TelemetryWorkerReplayCoordinator(
            store = storage,
            ensureHelper = { null },
            pendingSamples = {
                PendingTelemetryWorkerSamples(CollectorHelperProtocol.STATUS_OK, listOf(sample()))
            },
            acknowledgeSample = { _, _ ->
                actions += "ack"
                TelemetryWorkerAckResult(CollectorHelperProtocol.STATUS_OK, updated = true)
            },
            replayEntriesForCatalog = ::testCatalogEntries
        )

        val result = coordinator.replayNextBatch(7L)

        assertFalse(result.needsReplay)
        assertTrue(result.cycleResult?.ok == true)
        assertEquals(listOf(true), storage.inserted)
        assertTrue(actions.indexOf("insert:7") < actions.indexOf("ack"))
        assertTrue(actions.indexOf("ack") < actions.indexOf("event:worker_spool_replayed"))
    }

    @Test
    fun repeatedIdenticalFailuresAreSummarizedAtThirtySecondIntervals() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions)
        var nowNanos = 0L
        val coordinator = TelemetryWorkerReplayCoordinator(
            store = storage,
            ensureHelper = { null },
            pendingSamples = {
                PendingTelemetryWorkerSamples(
                    CollectorHelperProtocol.STATUS_READ_ERROR,
                    emptyList(),
                    "same read failure"
                )
            },
            acknowledgeSample = { _, _ -> error("ack must not run") },
            replayEntriesForCatalog = ::testCatalogEntries,
            monotonicNanos = { nowNanos }
        )

        coordinator.replayNextBatch(7L)
        nowNanos = 10_000_000_000L
        coordinator.replayNextBatch(7L)
        nowNanos = 31_000_000_000L
        coordinator.replayNextBatch(7L)

        assertEquals(2, storage.events.size)
        assertContains(storage.events[0].third.orEmpty(), "repeated_failures=1")
        assertContains(storage.events[1].third.orEmpty(), "repeated_failures=2")
    }

    @Test
    fun addressCorruptCatalogSampleIsPreservedBeforeAck() {
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

        assertFalse(corrupt.needsReplay)
        val input = storage.inputs.single()
        assertEquals("worker_sample_quarantined", input.errorCategory)
        assertTrue(input.readings.isEmpty())
        val raw = org.json.JSONObject(input.rawResponseBody!!)
        assertEquals(wrongAddress.fid, raw.getJSONArray("values").getJSONObject(0).getInt("fid"))
        assertEquals(72, raw.getJSONArray("values").getJSONObject(0).getInt("raw"))
        assertTrue(actions.indexOf("insert:7") < actions.indexOf("ack"))
    }

    @Test
    fun unknownCatalogSampleIsPreservedBeforeAckAndDiskFailureRemainsRetryable() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions)
        val unknownSample = sample("unknown", listOf(TEST_ENTRY), listOf(72)).let { sample ->
            sample.copy(values = listOf(sample.values.single().copy(callbackSource = callbackSource())))
        }
        val coordinator = TelemetryWorkerReplayCoordinator(
            store = storage,
            ensureHelper = { null },
            pendingSamples = {
                PendingTelemetryWorkerSamples(
                    CollectorHelperProtocol.STATUS_OK,
                    listOf(unknownSample)
                )
            },
            acknowledgeSample = { _, _ ->
                actions += "ack"
                TelemetryWorkerAckResult(CollectorHelperProtocol.STATUS_OK, updated = true)
            },
            replayEntriesForCatalog = { null }
        )

        val unknown = coordinator.replayNextBatch(7L)

        assertFalse(unknown.needsReplay)
        assertEquals("worker_sample_quarantined", storage.inputs.single().errorCategory)
        val raw = org.json.JSONObject(storage.inputs.single().rawResponseBody!!)
        assertEquals("unknown", raw.getString("catalog_version"))
        assertEquals("boot-a", raw.getJSONObject("identity").getString("boot_id"))
        assertEquals(
            "boot-callback",
            raw.getJSONArray("values").getJSONObject(0).getJSONObject("callback_source").getString("boot_id")
        )
        assertTrue(actions.indexOf("insert:7") < actions.indexOf("ack"))

        storage.failInsertAttempt = 2
        actions.clear()
        assertTrue(coordinator.replayNextBatch(7L).needsReplay)
        assertFalse(actions.contains("ack"))
    }

    @Test
    fun mismatchedCallbackSourceIsQuarantinedInsteadOfLabelingPollData() {
        val actions = mutableListOf<String>()
        val storage = FakeWorkerPollStorage(actions)
        val corrupt = sample().let { sample ->
            sample.copy(values = listOf(sample.values.single().copy(callbackSource = callbackSource(raw = 71))))
        }
        val coordinator = coordinator(
            storage,
            corrupt,
            actions,
            object : SuccessfulPollObserver {
                override fun onSuccessfulPoll(
                    sessionId: Long,
                    pollId: Long,
                    timestamp: String,
                    readings: List<PollReading>,
                    origin: PollOrigin
                ) = Unit
            }
        )

        val result = coordinator.replayNextBatch(7L)

        assertFalse(result.needsReplay)
        assertEquals("worker_sample_quarantined", storage.input?.errorCategory)
        assertTrue(storage.input?.readings.orEmpty().isEmpty())
        assertTrue(actions.any { it.startsWith("ack:") })
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

    private fun sample(sequence: Long = 1L): TelemetryWorkerSample =
        sample(DirectFidRegistry.CATALOG_VERSION, listOf(TEST_ENTRY), listOf(72), sequence)

    private fun callbackSource(raw: Int = 72) = CallbackValueSource(
        "boot-callback", "generation-callback", 1, 5L, 8L,
        TEST_ENTRY.dev, TEST_ENTRY.fid, TelemetryCallbackBatch.TYPE_INT, raw,
        1_000L, 900L, 950L, "usable"
    )

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
        val events = mutableListOf<Triple<String, String, String?>>()
        var failEventWrites = false
        var failInsertAttempt: Int? = null
        private var insertAttempts = 0
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
            insertAttempts += 1
            if (failInsertAttempt == insertAttempts) throw IllegalStateException("storage unavailable")
            this.input = input
            inputs += input
            requestedParameters += parameters
            val wasInserted = identities.add(identity)
            inserted += wasInserted
            return WorkerPollImportResult(pollId = 41L, inserted = wasInserted)
        }

        override fun recordEvent(category: String, message: String, detail: String?) {
            actions += "event:$category"
            events += Triple(category, message, detail)
            if (failEventWrites) throw IllegalStateException("event writer unavailable")
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
