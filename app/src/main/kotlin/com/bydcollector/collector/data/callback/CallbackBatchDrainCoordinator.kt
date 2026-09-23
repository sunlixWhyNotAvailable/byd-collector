package com.bydcollector.collector.data.callback

import com.bydcollector.collector.data.direct.CallbackBatchDownload
import com.bydcollector.collector.data.direct.CallbackSpoolActionResult
import com.bydcollector.collector.direct.CallbackSpool
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.TelemetryCallbackBatch

enum class CallbackDrainKind { EMPTY, PROGRESS, PENDING, FAULT }

data class CallbackDrainResult(
    val drained: Boolean,
    val persistedEvents: Int,
    val replayedEvents: Int,
    val duplicateBatches: Int,
    val quarantinedBatches: Int,
    val blockedReason: String? = null,
    val retryable: Boolean = false,
    val status: Int? = null,
    val fault: Throwable? = null,
    val kind: CallbackDrainKind = when {
        drained && (persistedEvents > 0 || replayedEvents > 0 || duplicateBatches > 0 || quarantinedBatches > 0) ->
            CallbackDrainKind.PROGRESS
        drained -> CallbackDrainKind.EMPTY
        blockedReason == null -> CallbackDrainKind.PROGRESS
        status == CollectorHelperProtocol.STATUS_REPLAY_PENDING || status == CollectorHelperProtocol.STATUS_STALE_TOKEN ->
            CallbackDrainKind.PENDING
        else -> CallbackDrainKind.FAULT
    },
    val oldestObservedWallMs: Long? = null,
    val lastRawCommitWallMs: Long? = null,
    val lastProgressWallMs: Long? = null
)

/** Imports each spool batch to SQLite before acknowledging its exact immutable descriptor. */
class CallbackBatchDrainCoordinator(
    private val download: () -> CallbackBatchDownload,
    private val importBatch: (TelemetryCallbackBatch, String, CallbackDelivery) -> CallbackImportResult,
    private val acknowledge: (CallbackSpool.Descriptor) -> CallbackSpoolActionResult,
    private val quarantine: (CallbackSpool.Descriptor, String) -> CallbackSpoolActionResult,
    private val wallTimeMs: () -> Long = System::currentTimeMillis
) {
    /** A finite slice lets the serial owner recheck cancellation between packets. */
    fun drain(maxBatches: Int = 4): CallbackDrainResult {
        require(maxBatches > 0)
        var events = 0
        var replayEvents = 0
        var duplicateBatches = 0
        var quarantinedBatches = 0
        var progressed = false
        var oldestObservedWallMs: Long? = null
        var lastRawCommitWallMs: Long? = null
        var lastProgressWallMs: Long? = null

        fun result(
            drained: Boolean,
            error: String? = null,
            retryable: Boolean = false,
            status: Int? = null,
            fault: Throwable? = null,
            kind: CallbackDrainKind? = null
        ) = CallbackDrainResult(
            drained = drained,
            persistedEvents = events,
            replayedEvents = replayEvents,
            duplicateBatches = duplicateBatches,
            quarantinedBatches = quarantinedBatches,
            blockedReason = error?.take(512),
            retryable = retryable,
            status = status,
            fault = fault,
            oldestObservedWallMs = oldestObservedWallMs,
            lastRawCommitWallMs = lastRawCommitWallMs,
            lastProgressWallMs = lastProgressWallMs,
            kind = kind ?: when {
                drained && progressed -> CallbackDrainKind.PROGRESS
                drained -> CallbackDrainKind.EMPTY
                error == null && progressed -> CallbackDrainKind.PROGRESS
                isTransientStatus(status) -> CallbackDrainKind.PENDING
                else -> CallbackDrainKind.FAULT
            }
        )

        try {
            repeat(maxBatches) {
                interrupted()
                val payload = download()
                interrupted()
                payload.batch?.events?.minOfOrNull { it.receivedWallMs }?.let { oldestInBatch ->
                    oldestObservedWallMs = oldestObservedWallMs?.let { minOf(it, oldestInBatch) } ?: oldestInBatch
                }
                val descriptor = payload.descriptor
                if (!payload.ok && (!payload.permanentFormatError || descriptor == null)) {
                    return result(
                        drained = false,
                        error = "callback download status=${payload.status}: ${payload.error}",
                        retryable = !payload.permanentFormatError,
                        status = payload.status
                    )
                }
                if (descriptor == null) {
                    return if (payload.batch == null && payload.delivery == null) {
                        result(drained = true)
                    } else {
                        result(drained = false, error = "callback payload without descriptor", status = payload.status)
                    }
                }
                val imported = if (!payload.ok) {
                    CallbackImportResult.Rejected(payload.error ?: "invalid callback payload")
                } else {
                    val batch = payload.batch
                        ?: return result(drained = false, error = "callback descriptor without batch", status = payload.status)
                    val delivery = payload.delivery
                        ?: return result(drained = false, error = "callback delivery missing", status = payload.status)
                    importBatch(batch, descriptor.sha256, delivery)
                }
                if (imported is CallbackImportResult.Committed && !imported.duplicate) {
                    val committedAtWallMs = wallTimeMs()
                    lastRawCommitWallMs = committedAtWallMs
                    lastProgressWallMs = committedAtWallMs
                }
                interrupted()
                when (imported) {
                    is CallbackImportResult.Committed -> {
                        if (imported.duplicate) {
                            duplicateBatches++
                        } else {
                            events += imported.eventCount
                            if (payload.delivery == CallbackDelivery.REPLAY) replayEvents += imported.eventCount
                        }
                        val ack = acknowledge(descriptor)
                        if (!ack.ok) {
                            return result(
                                drained = false,
                                error = "callback ACK status=${ack.status}: ${ack.error}",
                                retryable = true,
                                status = ack.status
                            )
                        }
                        progressed = true
                        lastProgressWallMs = wallTimeMs()
                    }
                    is CallbackImportResult.Rejected -> {
                        val action = quarantine(descriptor, imported.reason.take(512))
                        if (!action.ok || action.affected != 1) {
                            return result(
                                drained = false,
                                error = "callback quarantine status=${action.status}: ${action.error}",
                                retryable = true,
                                status = action.status
                            )
                        }
                        quarantinedBatches++
                        progressed = true
                        lastProgressWallMs = wallTimeMs()
                    }
                }
                interrupted()
            }
            // Exhausting a slice after successful durable ACKs is progress, not a failure.
            return result(drained = false, kind = if (progressed) CallbackDrainKind.PROGRESS else CallbackDrainKind.FAULT)
        } catch (error: InterruptedException) {
            throw error
        } catch (error: Exception) {
            interrupted()
            return result(
                drained = false,
                error = "${error::class.java.simpleName}: ${error.message ?: "no message"}",
                retryable = true,
                fault = error,
                kind = CallbackDrainKind.FAULT
            )
        }
    }

    private fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("callback drain interrupted")
    }

    private fun isTransientStatus(status: Int?): Boolean =
        status == CollectorHelperProtocol.STATUS_REPLAY_PENDING ||
            status == CollectorHelperProtocol.STATUS_STALE_TOKEN
}
