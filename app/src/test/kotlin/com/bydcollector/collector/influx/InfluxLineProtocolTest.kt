package com.bydcollector.collector.influx

import kotlin.test.Test
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
