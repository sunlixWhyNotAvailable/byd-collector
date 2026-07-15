package com.bydcollector.collector.data.direct

interface DirectVehicleHelper {
    fun isAlive(): Boolean
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
}

data class DirectHelperReadResult(
    val status: Int,
    val raw: Int?,
    val error: String? = null
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
    val error: String? = null
) {
    val stateKey: String = listOf(
        mode,
        nativeAvailable
    ).joinToString("|")

    fun summary(): String = buildString {
        append("mode=").append(mode)
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
