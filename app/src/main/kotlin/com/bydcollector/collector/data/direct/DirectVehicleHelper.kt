package com.bydcollector.collector.data.direct

import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.CallbackValueSource
import com.bydcollector.collector.direct.TelemetryWorkerSampleIdentity

interface DirectVehicleHelper {
    fun isAlive(): Boolean
    fun ownerMode(): DirectHelperOwnerMode? = if (isAlive()) DirectHelperOwnerMode.APP else null
    fun requestStop(ownerMode: DirectHelperOwnerMode): DirectHelperStopResult =
        DirectHelperStopResult(CollectorHelperProtocol.STATUS_INVALID_REQUEST, false, "helper stop is unavailable")
    fun read(entry: DirectFidEntry): DirectHelperReadResult

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
    val callbackCached: Boolean = callbackSource != null
) {
    val ok: Boolean = status == 0 && raw != null
}

data class DirectHelperBatchResult(
    val results: List<DirectHelperReadResult>,
    val diagnostics: DirectBatchDiagnostics
)

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
    val status: Int = CollectorHelperProtocol.STATUS_OK
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
    val values: List<TelemetryWorkerFieldValue>
)

data class TelemetryWorkerFieldValue(
    val fieldIndex: Int,
    val tx: Int,
    val dev: Int,
    val fid: Int,
    val status: Int,
    val raw: Int?,
    val error: String?,
    val callbackSource: CallbackValueSource? = null
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
