package com.bydcollector.collector.influx

import java.time.OffsetDateTime

object InfluxLineProtocol {
    fun toLine(row: InfluxPendingHistoryRow, config: InfluxConfig, vehicle: String = "sea_lion_07"): String {
        val measurement = escapeMeasurement(config.normalizedMeasurement())
        val tags = listOf(
            "vehicle=${escapeTag(vehicle)}",
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
        }.joinToString(",")
        return "$measurement,$tags $fields ${timestampNanos(row.observedAt)}"
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

    fun timestampNanos(iso: String): Long {
        return runCatching {
            val parsed = OffsetDateTime.parse(iso)
            parsed.toInstant().epochSecond * 1_000_000_000L + parsed.nano
        }.getOrDefault(0L)
    }
}
