package com.bydcollector.collector.influx

import java.time.OffsetDateTime

internal const val INFLUX_MAX_PAYLOAD_BYTES = 2 * 1024 * 1024

internal data class InfluxPayload(
    val lines: List<String>,
    val bytes: Int,
    val oversizedPointBytes: Int? = null
)

object InfluxLineProtocol {
    internal fun boundedPayload(
        rows: List<InfluxPendingHistoryRow>,
        config: InfluxConfig,
        maxBytes: Int = INFLUX_MAX_PAYLOAD_BYTES
    ): InfluxPayload {
        require(maxBytes > 0)
        val lines = mutableListOf<String>()
        var bytes = 0
        for (row in rows) {
            val line = toLine(row, config)
            val lineBytes = line.toByteArray(Charsets.UTF_8).size
            val separatorBytes = if (lines.isEmpty()) 0 else 1
            if (lineBytes.toLong() + separatorBytes > maxBytes.toLong() - bytes) {
                return InfluxPayload(lines, bytes, lineBytes.takeIf { lines.isEmpty() })
            }
            lines += line
            bytes += lineBytes + separatorBytes
        }
        return InfluxPayload(lines, bytes)
    }

    fun toLine(row: InfluxPendingHistoryRow, config: InfluxConfig): String {
        require(config.validateVehicleId() == null) {
            "InfluxDB vehicle ID cannot contain carriage returns, line feeds, or NUL characters"
        }
        val measurement = escapeMeasurement(config.normalizedMeasurement())
        val tags = listOf(
            "vehicle=${escapeTag(config.normalizedVehicleId())}",
            "field_key=${escapeTag(row.fieldKey)}",
            "category=${escapeTag(row.category)}",
            "quality=${escapeTag(row.quality.lowercase())}",
            "unit=${escapeTag(row.unit ?: "none")}"
        ).joinToString(",")
        val fields = buildList {
            row.valueNumber?.let { add("value_num=$it") }
            row.valueBool?.let { add("value_bool=$it") }
            row.valueText?.let { add("value_str=\"${escapeFieldString(it)}\"") }
            add("raw_value=\"${escapeFieldString(row.valueText ?: row.valueNumber?.toString() ?: row.valueBool?.toString().orEmpty())}\"")
            add("desc_value=\"${escapeFieldString(row.valueText ?: "")}\"")
            row.sourcePollId?.let { add("source_poll_id=${it}i") }
            add("changed_at=\"${escapeFieldString(row.changedAt)}\"")
            row.qualityDetail?.let { add("quality_detail=\"${escapeFieldString(it)}\"") }
        }.joinToString(",")
        val baseLine = "$measurement,$tags $fields"
        return timestampNanos(row.observedAt)?.let { timestamp -> "$baseLine $timestamp" } ?: baseLine
    }

    fun escapeMeasurement(value: String): String = value
        .replace("\\", "\\\\")
        .replace(" ", "\\ ")
        .replace(",", "\\,")

    fun escapeTag(value: String): String = value
        .replace("\\", "\\\\")
        .replace(" ", "\\ ")
        .replace(",", "\\,")
        .replace("=", "\\=")

    fun escapeFieldString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    fun timestampNanos(iso: String): Long? {
        return runCatching {
            val parsed = OffsetDateTime.parse(iso)
            parsed.toInstant().epochSecond * 1_000_000_000L + parsed.nano
        }.getOrNull()
    }
}
