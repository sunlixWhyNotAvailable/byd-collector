package com.bydcollector.collector.data.debug

import android.content.Context
import com.bydcollector.collector.data.direct.DirectFidEntry
import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.direct.DirectValueDecoder

data class DirectDebugParameter(
    val key: String,
    val featureGroup: String,
    val dev: Int,
    val fid: Int,
    val tx: Int,
    val featureNames: String,
    val featureRefs: String,
    val candidateSource: String
) {
    init {
        require(isAllowedReadTx(tx)) { "Unsupported debug tx: $tx" }
    }

    fun toDirectFidEntry(): DirectFidEntry {
        val decoder = when (tx) {
            DirectFidRegistry.TX_GET_FLOAT -> DirectValueDecoder.FLOAT_RAW
            DirectFidRegistry.TX_GET_INT -> DirectValueDecoder.INT_RAW
            else -> error("Unsupported debug tx: $tx")
        }
        return DirectFidEntry(
            key = key,
            dev = dev,
            fid = fid,
            tx = tx,
            decoder = decoder,
            groupName = "debug_${featureGroup.lowercase()}",
            featureNames = featureNames,
            classification = "debug_leftover"
        )
    }
}

object DirectDebugParameterAsset {
    const val ASSET_NAME = "direct_debug_round_robin_parameters.csv"
    const val SOURCE_VERSION = "wide-poll-session-20260605_161751-curated-main77-roundrobin6436-charge-current-v2"
    val EXPECTED_HEADER = listOf(
        "key",
        "feature_group",
        "dev",
        "fid",
        "tx",
        "feature_names",
        "feature_refs",
        "candidate_source",
        "source_read_count",
        "source_write_count",
        "source_change_count",
        "seed_last_status",
        "seed_last_raw_present",
        "seed_last_raw_int",
        "seed_last_error",
        "raw_sample",
        "float_sample"
    )

    fun load(context: Context): List<DirectDebugParameter> {
        val text = context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parse(text)
    }

    fun parse(text: String): List<DirectDebugParameter> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size <= 1) return emptyList()
        val header = splitCsvLine(lines.first())
        require(header == EXPECTED_HEADER) { "Unexpected debug asset header: $header" }
        val index = header.withIndex().associate { it.value to it.index }
        return lines.drop(1).map { line ->
            val columns = splitCsvLine(line)
            DirectDebugParameter(
                key = columns.value(index, "key"),
                featureGroup = columns.value(index, "feature_group"),
                dev = columns.value(index, "dev").toInt(),
                fid = columns.value(index, "fid").toInt(),
                tx = columns.value(index, "tx").toInt(),
                featureNames = columns.value(index, "feature_names"),
                featureRefs = columns.value(index, "feature_refs"),
                candidateSource = columns.value(index, "candidate_source")
            )
        }
    }

    private fun List<String>.value(index: Map<String, Int>, name: String): String {
        return this[index.getValue(name)]
    }

    private fun splitCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    values += current.toString()
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            i++
        }
        values += current.toString()
        return values
    }
}

private fun isAllowedReadTx(tx: Int): Boolean {
    return tx == DirectFidRegistry.TX_GET_INT || tx == DirectFidRegistry.TX_GET_FLOAT
}
