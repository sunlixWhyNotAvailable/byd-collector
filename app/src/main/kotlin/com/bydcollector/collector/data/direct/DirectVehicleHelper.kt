package com.bydcollector.collector.data.direct

import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.CallbackValueSource
import com.bydcollector.collector.direct.TelemetryWorkerSampleIdentity

interface DirectVehicleHelper {
    fun isAlive(): Boolean
    fun ownerMode(): DirectHelperOwnerMode? = if (isAlive()) DirectHelperOwnerMode.APP else null
    /** Lifecycle only: an older compatible stop protocol is not telemetry readiness. */
    fun ownerModeForStop(): DirectHelperOwnerMode? = ownerMode()
    fun requestStop(ownerMode: DirectHelperOwnerMode): DirectHelperStopResult =
        DirectHelperStopResult(CollectorHelperProtocol.STATUS_INVALID_REQUEST, false, "helper stop is unavailable")
    fun read(entry: DirectFidEntry): DirectHelperReadResult

    /** Registers one bounded, coalesced KPI invalidation consumer independent of the recording streams. */
    fun subscribeKpi(onInvalidated: (DirectKpiInvalidation) -> Unit): DirectKpiSubscriptionResult =
        DirectKpiSubscriptionResult(CollectorHelperProtocol.STATUS_INVALID_REQUEST,
            error = "helper does not support live KPI subscriptions")

    /** Atomically snapshots the latest raw KPI mailbox and acknowledges its pending invalidation. */
    fun drainKpi(subscriptionId: Long): DirectKpiMailboxSnapshot =
        DirectKpiMailboxSnapshot(CollectorHelperProtocol.STATUS_INVALID_REQUEST,
            subscriptionId = subscriptionId, error = "helper does not support live KPI subscriptions")

    fun unsubscribeKpi(subscriptionId: Long): DirectKpiActionResult =
        DirectKpiActionResult(CollectorHelperProtocol.STATUS_INVALID_REQUEST,
            error = "helper does not support live KPI subscriptions")

    fun readBatch(entries: List<DirectFidEntry>): DirectHelperBatchResult {
        val results = entries.map(::read)
        return DirectHelperBatchResult(
            results = results,
            diagnostics = DirectBatchDiagnostics(
                mode = "scalar_compat",
                nativeAvailable = false,
                nativeGroupCount = 0,
                fallbackGroupCount = entries.distinctBy { it.tx to it.dev }.size,
                fallbackReadCount = entries.size,
                groupFailureCount = 0,
                helperElapsedMs = 0,
                returnedCount = results.size
            )
        )
    }

    /** Dedicated secondary stream; scalar/test helpers retain compatible readBatch behavior. */
    fun readSecondaryBatch(entries: List<DirectFidEntry>): DirectHelperBatchResult = readBatch(entries)
    fun readSecondaryBatch(entries: List<DirectFidEntry>, catalogVersion: String): DirectHelperBatchResult =
        readSecondaryBatch(entries)
}

enum class DirectHelperOwnerMode(val protocolValue: Int) {
    APP(CollectorHelperProtocol.OWNER_MODE_APP),
    APP_GAP_SPOOL(CollectorHelperProtocol.OWNER_MODE_APP_GAP_SPOOL);

    companion object {
        fun fromProtocolValue(value: Int): DirectHelperOwnerMode? = entries.firstOrNull { it.protocolValue == value }
    }
}

data class DirectHelperReadResult(
    val status: Int,
    val raw: Int?,
    val error: String? = null,
    val callbackSource: CallbackValueSource? = null,
    val callbackCached: Boolean = callbackSource != null,
    val getLineage: DirectGetLineage? = null
) {
    val ok: Boolean = status == 0 && raw != null
}

data class DirectHelperBatchResult(
    val results: List<DirectHelperReadResult>,
    val diagnostics: DirectBatchDiagnostics,
    val requestedIndices: List<Int> = results.indices.toList(),
    val noWork: Boolean = false,
    val nextDueDelayMs: Long = 0L
) {
    init {
        require(results.size == requestedIndices.size) { "result/index count mismatch" }
        require(requestedIndices.all { it >= 0 } && requestedIndices.distinct().size == requestedIndices.size) {
            "invalid requested result indices"
        }
        require(nextDueDelayMs >= 0L) { "next due delay must be non-negative" }
        require(!noWork || (diagnostics.status == CollectorHelperProtocol.STATUS_OK &&
            results.isEmpty() && requestedIndices.isEmpty() && nextDueDelayMs > 0L)) {
            "invalid no-work result"
        }
    }
}

data class DirectBatchDiagnostics(
    val mode: String,
    val nativeAvailable: Boolean,
    val nativeGroupCount: Int,
    val fallbackGroupCount: Int,
    val fallbackReadCount: Int,
    val groupFailureCount: Int,
    val helperElapsedMs: Long,
    val returnedCount: Int,
    val error: String? = null,
    val status: Int = CollectorHelperProtocol.STATUS_OK,
    val errorDetailsTruncated: Boolean = false
) {
    val stateKey: String = listOf(
        mode,
        nativeAvailable
    ).joinToString("|")

    fun summary(): String = buildString {
        append("status=").append(status)
        append(" mode=").append(mode)
        append(" native_available=").append(nativeAvailable)
        append(" native_groups=").append(nativeGroupCount)
        append(" fallback_groups=").append(fallbackGroupCount)
        append(" fallback_reads=").append(fallbackReadCount)
        append(" group_failures=").append(groupFailureCount)
        append(" helper_elapsed_ms=").append(helperElapsedMs)
        append(" returned=").append(returnedCount)
        if (errorDetailsTruncated) append(" error_details_truncated=true")
        if (error != null) append(" error=").append(error)
    }
}

data class PendingTelemetryWorkerSamples(
    val status: Int,
    val samples: List<TelemetryWorkerSample>,
    val error: String? = null
) {
    val ok: Boolean = status == 0
}

data class TelemetryWorkerSample(
    val identity: TelemetryWorkerSampleIdentity,
    val catalogVersion: String,
    val capturedWallMs: Long,
    val capturedElapsedMs: Long,
    val pollElapsedMs: Long,
    val batchStatus: Int,
    val batchMode: Int,
    val nativeAvailable: Boolean,
    val groupFailureCount: Int,
    val error: String?,
    val values: List<TelemetryWorkerFieldValue>,
    val recordVersion: Int = 2
)

data class TelemetryWorkerFieldValue(
    val fieldIndex: Int,
    val tx: Int,
    val dev: Int,
    val fid: Int,
    val status: Int,
    val raw: Int?,
    val error: String?,
    val callbackSource: CallbackValueSource? = null,
    val getLineage: DirectGetLineage? = null
)

data class TelemetryWorkerAckResult(
    val status: Int,
    val updated: Boolean,
    val error: String? = null
) {
    val ok: Boolean = status == 0 && updated
}

data class DirectHelperStopResult(
    val status: Int,
    val accepted: Boolean,
    val error: String? = null
) {
    val ok: Boolean = status == 0 && accepted
}

data class DirectKpiInvalidation(
    val subscriptionId: Long,
    val helperBootId: String,
    val helperGeneration: String,
    val sequence: Long?,
    val sourceLost: Boolean = false,
    val reason: String? = null,
    val diagnostic: String? = null,
    val observedWallMs: Long? = null,
    val observedElapsedMs: Long? = null
)

data class DirectKpiSubscriptionResult(
    val status: Int,
    val subscriptionId: Long = 0L,
    val helperBootId: String? = null,
    val helperGeneration: String? = null,
    val listenerStatus: Int = CollectorHelperProtocol.KPI_LISTENER_UNAVAILABLE,
    val listenerError: String? = null,
    val error: String? = null
) {
    val ok: Boolean = status == CollectorHelperProtocol.STATUS_OK && subscriptionId > 0L
}

data class DirectKpiRawValue(
    val tx: Int,
    val dev: Int,
    val fid: Int,
    val status: Int,
    val nativeType: Int,
    val rawBits: Int?,
    val bytes: ByteArray?,
    val observedWallMs: Long,
    val observedElapsedMs: Long,
    val sequence: Long,
    val sourceKind: Int = 0,
    val sourceEpoch: Long = 0L,
    val sourceRecordSequence: Long = 0L,
    val quality: String? = null,
    val sourceIdentity: String? = null,
    val sourceStartWallMs: Long? = null,
    val sourceStartElapsedMs: Long? = null,
    val lossReason: String? = null,
    val diagnostic: String? = null
)

data class DirectKpiMailboxSnapshot(
    val status: Int,
    val subscriptionId: Long,
    val helperBootId: String? = null,
    val helperGeneration: String? = null,
    val sequence: Long = 0L,
    val listenerStatus: Int = CollectorHelperProtocol.KPI_LISTENER_UNAVAILABLE,
    val listenerError: String? = null,
    val values: List<DirectKpiRawValue> = emptyList(),
    val error: String? = null
) {
    val ok: Boolean = status == CollectorHelperProtocol.STATUS_OK
}

data class DirectKpiActionResult(val status: Int, val error: String? = null) {
    val ok: Boolean = status == CollectorHelperProtocol.STATUS_OK
}
