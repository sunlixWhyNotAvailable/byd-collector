package com.bydcollector.collector.influx

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfluxLineProtocolTest {
    @Test
    fun escapesTagsAndStringFields() {
        val row = InfluxPendingHistoryRow(
            id = 1,
            fieldKey = "field,with space",
            category = "battery",
            valueType = "TEXT",
            valueText = "quoted \"value\"",
            valueNumber = null,
            valueBool = null,
            quality = "OK",
            unit = "kW h",
            sourcePollId = 42,
            sourceKeys = "source",
            observedAt = "2026-06-15T10:20:30Z",
            changedAt = "2026-06-15T10:20:31Z"
        )

        val line = InfluxLineProtocol.toLine(row, config())

        assertTrue(line.startsWith("byd_state,vehicle=sea_lion_07,field_key=field\\,with\\ space"))
        assertTrue(line.contains("unit=kW\\ h"))
        assertTrue(line.contains("value_str=\"quoted \\\"value\\\"\""))
        assertTrue(line.contains("source_poll_id=42i"))
    }

    @Test
    fun writesNormalizedPhysicalNumberValues() {
        val row = InfluxPendingHistoryRow(
            id = 2,
            fieldKey = "battery_lowest_cell_voltage_raw",
            category = "battery",
            valueType = "NUMBER",
            valueText = null,
            valueNumber = 3.312,
            valueBool = null,
            quality = "OK",
            unit = "V",
            sourcePollId = 59,
            sourceKeys = "statistic_lowest_battery_voltage",
            observedAt = "2026-06-15T10:20:30Z",
            changedAt = "2026-06-15T10:20:31Z"
        )

        val line = InfluxLineProtocol.toLine(row, config())

        assertTrue(line.contains("field_key=battery_lowest_cell_voltage_raw"))
        assertTrue(line.contains("unit=V"))
        assertTrue(line.contains("value_num=3.312"))
    }

    @Test
    fun writesChargingTextWithOriginalObservationTimestamp() {
        val row = InfluxPendingHistoryRow(
            id = 4,
            fieldKey = "charging_time_remaining",
            category = "battery",
            valueType = "TEXT",
            valueText = "100:05:00",
            valueNumber = null,
            valueBool = null,
            quality = "OK",
            unit = null,
            sourcePollId = 110,
            sourceKeys = "charging_1009_1146095640_5+charging_1009_1146095648_5",
            observedAt = "2026-09-08T09:02:00Z",
            changedAt = "2026-09-08T09:02:01Z"
        )

        val line = InfluxLineProtocol.toLine(row, config())

        assertTrue(line.contains("field_key=charging_time_remaining"))
        assertTrue(line.contains("value_str=\"100:05:00\""))
        val expectedTimestampNanos = java.time.Instant.parse(row.observedAt).toEpochMilli() * 1_000_000L
        assertTrue(line.endsWith(expectedTimestampNanos.toString()))
    }

    @Test
    fun writesAllStep3ValuesWithOriginalObservationTimestamps() {
        val rows = listOf(
            Triple("rf_window_percent", "body", 42.0),
            Triple("perfume_1_remaining_percent", "climate", 79.0),
            Triple("perfume_2_remaining_percent", "climate", 81.0),
            Triple("perfume_3_remaining_percent", "climate", 83.0),
            Triple("front_motor_current_raw", "motion", -1.0),
            Triple("rear_motor_current_raw", "motion", -226.7)
        ).mapIndexed { index, (fieldKey, category, value) ->
            InfluxPendingHistoryRow(index.toLong(), fieldKey, category, "NUMBER", null, value, null, "OK", if (fieldKey.endsWith("percent")) "%" else null, 120, fieldKey, "2026-09-08T10:00:00Z", "2026-09-08T10:00:01Z")
        } + listOf("perfume_1_installed", "perfume_2_installed", "perfume_3_installed").mapIndexed { index, fieldKey ->
            InfluxPendingHistoryRow((index + 6).toLong(), fieldKey, "climate", "BOOLEAN", null, null, index == 1, "OK", null, 120, fieldKey, "2026-09-08T10:00:00Z", "2026-09-08T10:00:01Z")
        }
        val timestampNanos = java.time.Instant.parse("2026-09-08T10:00:00Z").toEpochMilli() * 1_000_000L

        rows.forEach { row ->
            val line = InfluxLineProtocol.toLine(row, config())
            assertTrue(line.contains("field_key=${row.fieldKey}"))
            assertTrue(line.contains("category=${row.category}"))
            assertTrue(line.endsWith(timestampNanos.toString()))
        }
    }

    @Test
    fun omitsTimestampWhenObservedAtCannotBeParsed() {
        val row = InfluxPendingHistoryRow(
            id = 3,
            fieldKey = "soc_display_percent",
            category = "battery",
            valueType = "NUMBER",
            valueText = null,
            valueNumber = 96.0,
            valueBool = null,
            quality = "OK",
            unit = "%",
            sourcePollId = 88,
            sourceKeys = "statistic_1014_1145045040_5",
            observedAt = "bad timestamp",
            changedAt = "2026-06-15T10:20:31Z"
        )

        val line = InfluxLineProtocol.toLine(row, config())

        assertTrue(line.endsWith("changed_at=\"2026-06-15T10:20:31Z\""))
    }

    @Test
    fun locationCategoryUsesTheSameMembershipGate() {
        assertTrue(config().copy(enabledCategories = setOf("location")).isCategoryEnabled("location"))
        assertFalse(config().isCategoryEnabled("location"))
    }

    private fun config(): InfluxConfig = InfluxConfig(
        enabled = true,
        host = "influx.local",
        port = 8086,
        database = "bydcollector",
        username = null,
        password = null,
        measurement = "byd_state",
        enabledCategories = setOf("battery")
    )
}
