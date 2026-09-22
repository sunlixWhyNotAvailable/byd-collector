package com.bydcollector.collector.data.debug

import android.content.Context
import com.bydcollector.collector.data.direct.DirectFidEntry
import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.direct.DirectValueDecoder
import com.bydcollector.collector.direct.TelemetryCatalogPolicy

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
    val ASSET_NAMES = listOf(
        "direct_debug_round_robin_parameters_1.csv",
        "direct_debug_round_robin_parameters_2.csv",
        "direct_debug_round_robin_parameters_3.csv"
    )
    val EXPECTED_SHARD_SIZES = listOf(7_692, 7_693, 7_698)
    const val SHARD_COUNT = 3
    const val MAX_SHARD_SIZE = 7_698
    const val DEFINITION_COUNT = TelemetryCatalogPolicy.SECONDARY_DEFINITION_COUNT
    const val TOTAL_PARAMETER_COUNT = TelemetryCatalogPolicy.SECONDARY_ACTIVE_COUNT
    const val LEGACY_SOURCE_VERSION = "fid-catalog-20260908-main95-roundrobin23083-both-read-tx-v1"
    const val SOURCE_VERSION = "fid-catalog-20260922-main95-roundrobin23069-exclusions7-v2"
    const val ACTIVE_FINGERPRINT = TelemetryCatalogPolicy.SECONDARY_ACTIVE_FINGERPRINT
    val EXPECTED_HEADER = listOf(
        "key",
        "feature_group",
        "dev",
        "fid",
        "tx",
        "feature_names",
        "feature_refs",
        "candidate_source"
    )

    fun load(context: Context): List<DirectDebugParameter> {
        return loadDefinitions(context).filter(::isRuntimeSelected).also { rows ->
            require(rows.size == TOTAL_PARAMETER_COUNT) { "Unexpected active debug catalog size: ${rows.size}" }
            require(fingerprint(rows) == ACTIVE_FINGERPRINT) { "Unexpected active debug catalog fingerprint" }
        }
    }

    fun loadDefinitions(context: Context): List<DirectDebugParameter> =
        loadShards(context).flatten().also { rows ->
            require(rows.size == DEFINITION_COUNT) { "Unexpected debug definition count: ${rows.size}" }
        }

    fun isRuntimeSelected(parameter: DirectDebugParameter): Boolean =
        TelemetryCatalogPolicy.isRuntimeSelected(parameter.dev, parameter.fid)

    fun parametersForCatalog(
        catalogVersion: String,
        definitions: List<DirectDebugParameter>,
        active: List<DirectDebugParameter>
    ): List<DirectDebugParameter>? = when (catalogVersion) {
        SOURCE_VERSION -> active
        LEGACY_SOURCE_VERSION -> definitions
        else -> null
    }

    fun fingerprint(parameters: List<DirectDebugParameter>): String {
        val digest = TelemetryCatalogPolicy.newFingerprint()
        parameters.forEach { TelemetryCatalogPolicy.addToFingerprint(digest, it.dev, it.fid, it.tx) }
        return TelemetryCatalogPolicy.finishFingerprint(digest)
    }

    fun loadShards(context: Context): List<List<DirectDebugParameter>> {
        return ASSET_NAMES.mapIndexed { index, assetName ->
            val text = context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(text).also { rows ->
                require(rows.size == EXPECTED_SHARD_SIZES[index]) {
                    "Unexpected debug shard size for $assetName: ${rows.size}"
                }
            }
        }
    }

    fun parse(text: String): List<DirectDebugParameter> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size <= 1) return emptyList()
        val header = splitCsvLine(lines.first())
        require(header == EXPECTED_HEADER) { "Unexpected debug asset header: $header" }
        val index = header.withIndex().associate { it.value to it.index }
        return lines.drop(1).map { line ->
            val columns = splitCsvLine(line)
            require(columns.size == header.size) { "Unexpected debug asset row width: ${columns.size}" }
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
