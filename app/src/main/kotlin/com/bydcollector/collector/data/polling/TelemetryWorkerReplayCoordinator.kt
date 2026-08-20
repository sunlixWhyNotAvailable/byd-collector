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

    fun recordEvent(category: String, message: String, detail: String? = null)
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
    private val entries: List<DirectFidEntry> = DirectFidRegistry.entries,
    private val acknowledgedAtMs: () -> Long = { System.currentTimeMillis() }
) {
    private var lastFailureKey: String? = null

    fun replayNextBatch(sessionId: Long): WorkerReplayBatchResult {
        var insertedPolls = 0L
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
                lastFailureKey = null
                return WorkerReplayBatchResult(needsReplay = false, cycleResult = null)
            }
            val parameters = store.getActiveCatalogParameters()
            for (sample in pending.samples) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val input = persistedInput(sample)
                val imported = store.insertWorkerPoll(sessionId, sample.identity, input, parameters)
                if (imported.inserted) insertedPolls += 1L
                lastPollId = imported.pollId
                lastTimestamp = input.timestamp
                lastElapsedMs = sample.pollElapsedMs

                if (input.ok) {
                    successfulPollObserver?.onSuccessfulPoll(
                        sessionId,
                        imported.pollId,
                        input.timestamp,
                        input.readings
                    )
                }
                val ack = acknowledgeSample(sample.identity, acknowledgedAtMs())
                if (!ack.ok) {
                    return failure(
                        category = "worker_spool_ack_error",
                        message = "identity=${sample.identity} ${ack.error ?: "status=${ack.status}"}",
                        needsReplay = true,
                        pollId = lastPollId,
                        timestamp = lastTimestamp,
                        elapsedMs = lastElapsedMs,
                        insertedPolls = insertedPolls
                    )
                }
            }

            lastFailureKey = null
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
                insertedPolls = insertedPolls
            )
        }
    }

    private fun persistedInput(sample: TelemetryWorkerSample): PersistedPollInput {
        require(sample.catalogVersion == DirectFidRegistry.CATALOG_VERSION) {
            "worker catalog mismatch: expected=${DirectFidRegistry.CATALOG_VERSION} actual=${sample.catalogVersion}"
        }
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
        insertedPolls: Long = 0L
    ): WorkerReplayBatchResult {
        val key = "$category:$message"
        if (key != lastFailureKey) {
            runCatching { store.recordEvent(category, "Telemetry worker replay failed", message) }
            lastFailureKey = key
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

    private fun modeName(mode: Int): String = when (mode) {
        CollectorHelperProtocol.MODE_NATIVE -> "native"
        CollectorHelperProtocol.MODE_NATIVE_WITH_FALLBACK -> "native_with_fallback"
        CollectorHelperProtocol.MODE_SCALAR_FALLBACK -> "scalar_fallback"
        else -> "rejected"
    }
}

class TelemetryWorkerReplayPollCycleRunner(
    private val replay: TelemetryWorkerReplayCoordinator,
    private val live: PollCycleRunner
) : PollCycleRunner {
    private var replayPending = true

    override fun pollOnce(sessionId: Long): PollCycleResult {
        if (replayPending) {
            val result = replay.replayNextBatch(sessionId)
            replayPending = result.needsReplay
            result.cycleResult?.let { return it }
        }
        return live.pollOnce(sessionId)
    }
}
