package com.bydcollector.collector.data.polling

import com.bydcollector.collector.data.direct.DirectAutoserviceField
import com.bydcollector.collector.data.direct.DirectAutoserviceSnapshot
import com.bydcollector.collector.data.direct.DirectBatchDiagnostics
import com.bydcollector.collector.data.direct.DirectFidEntry
import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.direct.DirectValueDecoders
import com.bydcollector.collector.data.direct.PendingTelemetryWorkerSamples
import com.bydcollector.collector.data.direct.TelemetryWorkerAckResult
import com.bydcollector.collector.data.direct.TelemetryWorkerSample
import com.bydcollector.collector.data.local.CatalogParameter
import com.bydcollector.collector.data.local.PersistedPollInput
import com.bydcollector.collector.data.local.WorkerPollImportResult
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.TelemetryWorkerSampleIdentity
import java.time.Instant

interface WorkerPollStorage {
    fun getActiveCatalogParameters(): List<CatalogParameter>

    fun insertWorkerPoll(
        sessionId: Long,
        identity: TelemetryWorkerSampleIdentity,
        input: PersistedPollInput,
        parameters: List<CatalogParameter>
    ): WorkerPollImportResult

    fun recordEvent(category: String, message: String, detail: String?)
}

data class WorkerReplayBatchResult(
    val needsReplay: Boolean,
    val cycleResult: PollCycleResult?
)

//replays durable helper samples before live polling so older observations cannot replace newer state
class TelemetryWorkerReplayCoordinator(
    private val store: WorkerPollStorage,
    private val ensureHelper: () -> String?,
    private val pendingSamples: (Int) -> PendingTelemetryWorkerSamples,
    private val acknowledgeSample: (TelemetryWorkerSampleIdentity, Long) -> TelemetryWorkerAckResult,
    private val successfulPollObserver: SuccessfulPollObserver? = null,
    private val replayEntriesForCatalog: (String) -> List<DirectFidEntry>? =
        DirectFidRegistry::workerReplayEntriesForCatalog,
    private val acknowledgedAtMs: () -> Long = { System.currentTimeMillis() },
    private val monotonicNanos: () -> Long = { System.nanoTime() }
) {
    private var lastFailureKey: String? = null
    private var lastFailureLoggedAtNanos = 0L
    private var repeatedFailureCount = 0L

    fun replayNextBatch(sessionId: Long): WorkerReplayBatchResult {
        var batchCount = 0
        var importAttempts = 0L
        var importedPolls = 0L
        var insertedPolls = 0L
        var duplicatePolls = 0L
        var ackAttempts = 0L
        var successfulAcks = 0L
        var failedAcks = 0L
        var firstIdentity: TelemetryWorkerSampleIdentity? = null
        var lastIdentity: TelemetryWorkerSampleIdentity? = null
        var lastPollId: Long? = null
        var lastTimestamp: String? = null
        var lastElapsedMs = 0L
        try {
            ensureHelper()?.let { error ->
                return failure("worker_helper_unavailable", error, needsReplay = true)
            }
            val pending = pendingSamples(CollectorHelperProtocol.MAX_PENDING_WORKER_SAMPLES)
            if (!pending.ok) {
                val unavailable = pending.status == CollectorHelperProtocol.STATUS_SPOOL_UNAVAILABLE
                return failure(
                    category = if (unavailable) "worker_spool_unavailable" else "worker_spool_read_error",
                    message = pending.error ?: "status=${pending.status}",
                    needsReplay = !unavailable
                )
            }
            if (pending.samples.isEmpty()) {
                clearFailureAggregation()
                return WorkerReplayBatchResult(needsReplay = false, cycleResult = null)
            }
            batchCount = pending.samples.size
            firstIdentity = pending.samples.first().identity
            val parametersByKey = store.getActiveCatalogParameters().associateBy { it.key }
            for (sample in pending.samples) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                lastIdentity = sample.identity
                val entries = requireNotNull(replayEntriesForCatalog(sample.catalogVersion)) {
                    "unsupported worker catalog: ${sample.catalogVersion}"
                }
                val input = persistedInput(sample, entries)
                // Count the fields this helper actually requested, not newly added APP fields.
                val parameters = entries.map { entry ->
                    requireNotNull(parametersByKey[entry.key]) { "missing replay parameter: ${entry.key}" }
                }
                importAttempts += 1L
                val imported = store.insertWorkerPoll(sessionId, sample.identity, input, parameters)
                importedPolls += 1L
                if (imported.inserted) insertedPolls += 1L else duplicatePolls += 1L
                lastPollId = imported.pollId
                lastTimestamp = input.timestamp
                lastElapsedMs = sample.pollElapsedMs

                if (input.ok) {
                    successfulPollObserver?.onSuccessfulPoll(
                        sessionId,
                        imported.pollId,
                        input.timestamp,
                        input.readings,
                        PollOrigin.REPLAY
                    )
                }
                ackAttempts += 1L
                val ack = try {
                    acknowledgeSample(sample.identity, acknowledgedAtMs())
                } catch (error: RuntimeException) {
                    failedAcks += 1L
                    throw error
                }
                if (!ack.ok) {
                    failedAcks += 1L
                    return failure(
                        category = "worker_spool_ack_error",
                        message = "identity=${sample.identity} ${ack.error ?: "status=${ack.status}"}",
                        needsReplay = true,
                        pollId = lastPollId,
                        timestamp = lastTimestamp,
                        elapsedMs = lastElapsedMs,
                        insertedPolls = insertedPolls,
                        diagnosticDetail = replayEvidence(
                            batchCount,
                            importAttempts,
                            importedPolls,
                            insertedPolls,
                            duplicatePolls,
                            ackAttempts,
                            successfulAcks,
                            failedAcks,
                            firstIdentity,
                            lastIdentity
                        )
                    )
                }
                successfulAcks += 1L
            }

            runCatching {
                store.recordEvent(
                    "worker_spool_replayed",
                    "Replayed helper telemetry samples into app storage",
                    replayEvidence(
                        batchCount,
                        importAttempts,
                        importedPolls,
                        insertedPolls,
                        duplicatePolls,
                        ackAttempts,
                        successfulAcks,
                        failedAcks,
                        firstIdentity,
                        lastIdentity
                    )
                )
            }

            clearFailureAggregation()
            return WorkerReplayBatchResult(
                needsReplay = pending.samples.size == CollectorHelperProtocol.MAX_PENDING_WORKER_SAMPLES,
                cycleResult = PollCycleResult(
                    pollId = lastPollId,
                    ok = true,
                    category = null,
                    elapsedMs = lastElapsedMs,
                    requestCount = 0,
                    timestamp = lastTimestamp,
                    pollRowsPersisted = insertedPolls,
                    valueRowsPersisted = insertedPolls
                )
            )
        } catch (error: RuntimeException) {
            return failure(
                category = "worker_replay_error",
                message = "${error::class.java.simpleName}: ${error.message ?: "no message"}",
                needsReplay = true,
                pollId = lastPollId,
                timestamp = lastTimestamp,
                elapsedMs = lastElapsedMs,
                insertedPolls = insertedPolls,
                diagnosticDetail = replayEvidence(
                    batchCount,
                    importAttempts,
                    importedPolls,
                    insertedPolls,
                    duplicatePolls,
                    ackAttempts,
                    successfulAcks,
                    failedAcks,
                    firstIdentity,
                    lastIdentity
                )
            )
        }
    }

    private fun persistedInput(sample: TelemetryWorkerSample, entries: List<DirectFidEntry>): PersistedPollInput {
        require(sample.values.size == entries.size) {
            "worker field count mismatch: expected=${entries.size} actual=${sample.values.size}"
        }
        val fields = entries.mapIndexed { index, entry ->
            val value = sample.values[index]
            require(
                value.fieldIndex == index &&
                    value.tx == entry.tx &&
                    value.dev == entry.dev &&
                    value.fid == entry.fid
            ) {
                "worker field mismatch at index=$index"
            }
            DirectAutoserviceField(
                entry = entry,
                status = value.status,
                raw = value.raw,
                decoded = value.raw?.let { DirectValueDecoders.decode(entry, it) },
                error = value.error
            )
        }
        val snapshot = DirectAutoserviceSnapshot(
            fields = fields,
            batchDiagnostics = DirectBatchDiagnostics(
                mode = modeName(sample.batchMode),
                nativeAvailable = sample.nativeAvailable,
                nativeGroupCount = 0,
                fallbackGroupCount = 0,
                fallbackReadCount = 0,
                groupFailureCount = sample.groupFailureCount,
                helperElapsedMs = sample.pollElapsedMs,
                returnedCount = sample.values.size,
                error = sample.error
            )
        )
        val warning = listOfNotNull(
            snapshot.errorSummary().takeIf { it.isNotBlank() },
            sample.error?.takeIf { it.isNotBlank() },
            sample.batchStatus.takeIf { it != CollectorHelperProtocol.STATUS_OK }?.let { "batch_status=$it" },
            sample.groupFailureCount.takeIf { it > 0 }?.let { "group_failures=$it" }
        ).joinToString("; ").ifBlank { null }
        val ok = snapshot.readings.isNotEmpty()
        val category = when {
            !ok -> "autoservice_snapshot_empty"
            warning != null -> "autoservice_partial_failure"
            else -> null
        }
        val message = warning ?: if (ok) null else "worker sample returned no usable readings"
        return PersistedPollInput(
            timestamp = Instant.ofEpochMilli(sample.capturedWallMs).toString(),
            ok = ok,
            elapsedMs = sample.pollElapsedMs,
            requestCount = 1,
            errors = category?.let { "$it: $message" },
            errorCategory = category,
            errorMessage = message,
            rawResponseBody = null,
            readings = snapshot.readings
        )
    }

    private fun failure(
        category: String,
        message: String,
        needsReplay: Boolean,
        pollId: Long? = null,
        timestamp: String? = null,
        elapsedMs: Long = 0L,
        insertedPolls: Long = 0L,
        diagnosticDetail: String? = null
    ): WorkerReplayBatchResult {
        val key = "$category:$message"
        val nowNanos = monotonicNanos()
        val sameFailure = key == lastFailureKey
        repeatedFailureCount = if (sameFailure) repeatedFailureCount + 1L else 1L
        val recurrenceDue = sameFailure &&
            nowNanos - lastFailureLoggedAtNanos >= FAILURE_RECURRENCE_NANOS
        if (!sameFailure || recurrenceDue) {
            val detail = listOf(
                diagnosticDetail ?: replayEvidence(0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, null, null),
                "repeated_failures=$repeatedFailureCount",
                "error=${bounded(message)}"
            ).joinToString(" ")
            runCatching { store.recordEvent(category, "Telemetry worker replay failed", detail) }
            lastFailureKey = key
            lastFailureLoggedAtNanos = nowNanos
            repeatedFailureCount = 0L
        }
        return WorkerReplayBatchResult(
            needsReplay = needsReplay,
            cycleResult = PollCycleResult(
                pollId = pollId,
                ok = false,
                category = category,
                elapsedMs = elapsedMs,
                requestCount = 0,
                timestamp = timestamp,
                errorMessage = message,
                pollRowsPersisted = insertedPolls,
                valueRowsPersisted = insertedPolls
            )
        )
    }

    private fun clearFailureAggregation() {
        lastFailureKey = null
        lastFailureLoggedAtNanos = 0L
        repeatedFailureCount = 0L
    }

    private fun replayEvidence(
        batchCount: Int,
        importAttempts: Long,
        importedPolls: Long,
        insertedPolls: Long,
        duplicatePolls: Long,
        ackAttempts: Long,
        successfulAcks: Long,
        failedAcks: Long,
        firstIdentity: TelemetryWorkerSampleIdentity?,
        lastIdentity: TelemetryWorkerSampleIdentity?
    ): String =
        "batch=$batchCount import_attempted=$importAttempts imported=$importedPolls " +
            "inserted=$insertedPolls duplicates=$duplicatePolls ack_attempted=$ackAttempts " +
            "ack_succeeded=$successfulAcks ack_failed=$failedAcks " +
            "first=${identityDetail(firstIdentity)} last=${identityDetail(lastIdentity)}"

    private fun identityDetail(identity: TelemetryWorkerSampleIdentity?): String = when (identity) {
        null -> "none"
        else -> "${bounded(identity.bootId, IDENTITY_COMPONENT_LIMIT)}:" +
            "${bounded(identity.helperGeneration, IDENTITY_COMPONENT_LIMIT)}:${identity.pollSequence}"
    }

    private fun bounded(value: String, limit: Int = ERROR_DETAIL_LIMIT): String =
        if (value.length <= limit) value else value.take(limit) + "..."

    private fun modeName(mode: Int): String = when (mode) {
        CollectorHelperProtocol.MODE_NATIVE -> "native"
        CollectorHelperProtocol.MODE_NATIVE_WITH_FALLBACK -> "native_with_fallback"
        CollectorHelperProtocol.MODE_SCALAR_FALLBACK -> "scalar_fallback"
        else -> "rejected"
    }

    private companion object {
        const val FAILURE_RECURRENCE_NANOS = 30_000_000_000L
        const val IDENTITY_COMPONENT_LIMIT = 80
        const val ERROR_DETAIL_LIMIT = 320
    }
}

class TelemetryWorkerReplayPollCycleRunner(
    private val replay: TelemetryWorkerReplayCoordinator,
    private val live: PollCycleRunner? = null
) : PollCycleRunner {
    override fun pollOnce(sessionId: Long): PollCycleResult? {
        //claim and drain the gap spool before every live read; the app writer remains authoritative
        val result = replay.replayNextBatch(sessionId)
        result.cycleResult?.let { return it }
        return live?.pollOnce(sessionId)
    }
}
