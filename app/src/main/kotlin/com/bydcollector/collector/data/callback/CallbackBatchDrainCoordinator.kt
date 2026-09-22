package com.bydcollector.collector.data.callback

import com.bydcollector.collector.data.direct.CallbackBatchDownload
import com.bydcollector.collector.data.direct.CallbackSpoolActionResult
import com.bydcollector.collector.direct.CallbackSpool
import com.bydcollector.collector.direct.TelemetryCallbackBatch

data class CallbackDrainResult(
    val drained: Boolean,
    val persistedEvents: Int,
    val replayedEvents: Int,
    val duplicateBatches: Int,
    val quarantinedBatches: Int,
    val blockedReason: String? = null,
    val retryable: Boolean = false
)

/** Runs on the existing stream writer. Only a durable raw commit permits ACK. */
class CallbackBatchDrainCoordinator(
    private val download: () -> CallbackBatchDownload,
    private val importBatch: (TelemetryCallbackBatch, String, CallbackDelivery) -> CallbackImportResult,
    private val acknowledge: (CallbackSpool.Descriptor) -> CallbackSpoolActionResult,
    private val quarantine: (CallbackSpool.Descriptor, String) -> CallbackSpoolActionResult,
    private val diagnostic: (String) -> Unit = {},
    private val monotonicNanos: () -> Long = System::nanoTime
) {
    private var persisted = 0L
    private var replayed = 0L
    private var duplicates = 0L
    private var quarantined = 0L
    private var lastDiagnosticNanos: Long? = null

    // Regular cycles are bounded so raw bursts cannot starve fast power/gear/SOC polls.
    // A paused/fenced archive caller may drain its finite backlog completely.
    fun drain(maxBatches: Int = 4): CallbackDrainResult {
        require(maxBatches > 0)
        var events = 0
        var replayEvents = 0
        var duplicateBatches = 0
        var quarantinedBatches = 0
        fun result(drained: Boolean, error: String? = null, retryable: Boolean = false): CallbackDrainResult {
            val now = monotonicNanos()
            if (lastDiagnosticNanos == null || now - lastDiagnosticNanos!! >= 30_000_000_000L) {
                lastDiagnosticNanos = now
                runCatching {
                    diagnostic("persisted_events=$persisted replayed_events=$replayed " +
                        "duplicate_batches=$duplicates quarantined_batches=$quarantined " +
                        "drained=$drained blocked=${error?.take(256).orEmpty()}")
                }
            }
            return CallbackDrainResult(drained, events, replayEvents, duplicateBatches,
                quarantinedBatches, error?.take(512), retryable)
        }
        try {
            repeat(maxBatches) {
                interrupted()
                val payload = download()
                interrupted()
                val descriptor = payload.descriptor
                if (!payload.ok && (!payload.permanentFormatError || descriptor == null)) {
                    return result(
                        false,
                        "callback download status=${payload.status}: ${payload.error}",
                        retryable = !payload.permanentFormatError
                    )
                }
                if (descriptor == null) {
                    return if (payload.batch == null && payload.delivery == null) result(true)
                    else result(false, "callback payload without descriptor")
                }
                val imported = if (!payload.ok) {
                    CallbackImportResult.Rejected(payload.error ?: "invalid callback payload")
                } else {
                    val batch = payload.batch ?: return result(false, "callback descriptor without batch")
                    val delivery = payload.delivery ?: return result(false, "callback delivery missing")
                    importBatch(batch, descriptor.sha256, delivery)
                }
                interrupted()
                when (imported) {
                    is CallbackImportResult.Committed -> {
                        if (imported.duplicate) {
                            duplicateBatches++
                            duplicates++
                        } else {
                            events += imported.eventCount
                            persisted += imported.eventCount
                            if (payload.delivery == CallbackDelivery.REPLAY) {
                                replayEvents += imported.eventCount
                                replayed += imported.eventCount
                            }
                        }
                        val ack = acknowledge(descriptor)
                        if (!ack.ok) return result(false, "callback ACK status=${ack.status}: ${ack.error}", retryable = true)
                    }
                    is CallbackImportResult.Rejected -> {
                        val action = quarantine(descriptor, imported.reason.take(512))
                        if (!action.ok || action.affected != 1) {
                            return result(false, "callback quarantine status=${action.status}: ${action.error}", retryable = true)
                        }
                        quarantinedBatches++
                        quarantined++
                    }
                }
                interrupted()
            }
            return result(false, retryable = true)
        } catch (error: InterruptedException) {
            throw error
        } catch (error: Exception) {
            interrupted()
            // SQLite/binder/ownership faults are retryable, never grounds to discard raw data.
            return result(false, "${error::class.java.simpleName}: ${error.message}", retryable = true)
        }
    }

    private fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("callback drain interrupted")
    }
}
