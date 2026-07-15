package com.bydcollector.collector.data.direct

import com.bydcollector.collector.data.local.PollReading
import org.json.JSONArray
import org.json.JSONObject

//turns curated autoservice fid reads into raw poll readings while preserving per-field failures
class DirectAutoserviceReader(
    private val helper: DirectVehicleHelper,
    private val entries: List<DirectFidEntry> = DirectFidRegistry.entries
) {
    fun readSnapshot(): DirectAutoserviceSnapshot {
        val batch = helper.readBatch(entries)
        val fields = entries.mapIndexed { index, entry ->
            val result = batch.results.getOrElse(index) {
                DirectHelperReadResult(-913, null, "batch result missing at index $index")
            }
            val raw = result.raw
            DirectAutoserviceField(
                entry = entry,
                status = result.status,
                raw = raw,
                decoded = raw?.let { DirectValueDecoders.decode(entry, it) },
                error = result.error
            )
        }
        return DirectAutoserviceSnapshot(fields, batch.diagnostics)
    }
}

data class DirectAutoserviceSnapshot(
    val fields: List<DirectAutoserviceField>,
    val batchDiagnostics: DirectBatchDiagnostics
) {
    private val errorFields: List<DirectAutoserviceField> = fields
        .filter { it.status != 0 || it.raw == null }

    val readings: List<PollReading> = fields
        .filter { it.status == 0 && it.raw != null }
        .map { field ->
            PollReading(
                rawKey = field.entry.key,
                rawValue = DirectValueDecoders.rawString(field.raw!!),
                descValue = field.decoded
            )
        }

    val errors: List<String> = errorFields.map { field -> errorSummary(field) }

    fun errorSummary(maxSamples: Int = DEFAULT_ERROR_SAMPLE_LIMIT): String {
        if (errors.isEmpty()) return ""
        val sample = errors.take(maxSamples).joinToString(separator = "; ")
        val omitted = (errors.size - maxSamples).coerceAtLeast(0)
        return buildString {
            append("errors=").append(errors.size).append('/').append(fields.size)
            append(" samples=[").append(sample).append(']')
            if (omitted > 0) append(" omitted=").append(omitted)
        }
    }

    fun toJson(
        ok: Boolean = readings.isNotEmpty(),
        includeFields: Boolean = true,
        maxErrorSamples: Int = DEFAULT_ERROR_SAMPLE_LIMIT
    ): String {
        val json = JSONObject()
        json.put("ok", ok)
        json.put("source", "direct_autoservice_helper")
        json.put("field_count", fields.size)
        json.put("reading_count", readings.size)
        json.put("error_count", errors.size)
        json.put("batch", JSONObject().apply {
            put("mode", batchDiagnostics.mode)
            put("native_available", batchDiagnostics.nativeAvailable)
            put("native_groups", batchDiagnostics.nativeGroupCount)
            put("fallback_groups", batchDiagnostics.fallbackGroupCount)
            put("fallback_reads", batchDiagnostics.fallbackReadCount)
            put("group_failures", batchDiagnostics.groupFailureCount)
            put("helper_elapsed_ms", batchDiagnostics.helperElapsedMs)
            put("returned_count", batchDiagnostics.returnedCount)
            if (batchDiagnostics.error != null) put("error", batchDiagnostics.error)
        })
        if (includeFields) {
            //full field json is diagnostic-only because storing every failed field can get large
            json.put("fields", JSONArray().also { array ->
                fields.forEach { field ->
                    array.put(JSONObject().apply {
                        put("key", field.entry.key)
                        put("dev", field.entry.dev)
                        put("fid", field.entry.fid)
                        put("tx", field.entry.tx)
                        put("decoder", field.entry.decoder.name)
                        put("status", field.status)
                        if (field.raw != null) put("raw", field.raw)
                        if (field.decoded != null) put("decoded", field.decoded)
                        if (field.error != null) put("error", field.error)
                    })
                }
            })
        } else {
            //compact error samples keep repeated poll failures queryable without bloating sqlite rows
            json.put("error_samples", JSONArray().also { array ->
                errorFields.take(maxErrorSamples).forEach { field ->
                    array.put(JSONObject().apply {
                        put("key", field.entry.key)
                        put("dev", field.entry.dev)
                        put("fid", field.entry.fid)
                        put("tx", field.entry.tx)
                        put("status", field.status)
                        if (field.error != null) put("error", field.error)
                    })
                }
            })
        }
        return json.toString()
    }

    private fun errorSummary(field: DirectAutoserviceField): String {
        return "${field.entry.key}:status=${field.status}${field.error?.let { " error=$it" }.orEmpty()}"
    }

    companion object {
        private const val DEFAULT_ERROR_SAMPLE_LIMIT = 12
    }
}

data class DirectAutoserviceField(
    val entry: DirectFidEntry,
    val status: Int,
    val raw: Int?,
    val decoded: String?,
    val error: String?
)
