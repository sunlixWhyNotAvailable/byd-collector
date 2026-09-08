package com.bydcollector.collector.mqtt

import com.bydcollector.collector.data.normalized.StoredNormalizedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject

class HaMqttPayloadBuilderTest {
    @Test
    fun categoryStateGroupsFieldsWithQualityAndTimestamps() {
        val payload = HaMqttPayloadBuilder.categoryState(
            category = "battery",
            timestamp = "2026-06-12T12:00:10+03:00",
            rows = listOf(
                storedState(
                    fieldKey = "soc",
                    valueType = "NUMBER",
                    valueNumber = 73.5,
                    quality = "OK",
                    sourcePollId = 42L,
                    sourceKeys = "statistic_1014_1145045040_5"
                ),
                storedState(
                    fieldKey = "charging",
                    valueType = "BOOLEAN",
                    valueBool = true,
                    quality = "STALE",
                    sourcePollId = null,
                    sourceKeys = ""
                ),
                storedState(
                    fieldKey = "charge_mode",
                    valueType = "TEXT",
                    valueText = null,
                    quality = "MISSING",
                    sourcePollId = 43L,
                    sourceKeys = "charging_mode"
                ),
                storedState(
                    fieldKey = "charging_time_remaining",
                    valueType = "TEXT",
                    valueText = "36:50:00",
                    quality = "OK",
                    sourcePollId = 44L,
                    sourceKeys = "charging_1009_1146095640_5+charging_1009_1146095648_5"
                )
            )
        )

        val json = JSONObject(payload)
        assertEquals("battery", json.getString("category"))
        assertEquals("2026-06-12T12:00:10+03:00", json.getString("ts"))
        assertEquals(73.5, json.getJSONObject("fields").getDouble("soc"))
        assertTrue(json.getJSONObject("fields").getBoolean("charging"))
        assertTrue(json.getJSONObject("fields").isNull("charge_mode"))
        assertEquals("36:50:00", json.getJSONObject("fields").getString("charging_time_remaining"))
        assertEquals("ok", json.getJSONObject("quality").getString("soc"))
        assertEquals("stale", json.getJSONObject("quality").getString("charging"))
        assertEquals("missing", json.getJSONObject("quality").getString("charge_mode"))
        assertEquals("ok", json.getJSONObject("quality").getString("charging_time_remaining"))
        assertEquals("2026-06-12T12:00:00+03:00", json.getJSONObject("observed_at").getString("soc"))
        assertEquals("2026-06-12T12:00:05+03:00", json.getJSONObject("changed_at").getString("soc"))
        assertEquals(42L, json.getJSONObject("source_poll_id").getLong("soc"))
        assertEquals("2026-06-12T12:00:00+03:00", json.getJSONObject("observed_at").getString("charging_time_remaining"))
        assertTrue(json.getJSONObject("source_poll_id").isNull("charging"))
        assertEquals(
            "statistic_1014_1145045040_5",
            json.getJSONObject("source_keys").getString("soc")
        )
    }

    @Test
    fun statusUsesSeparateAvailabilityAndCollectorStatusFields() {
        val payload = HaMqttPayloadBuilder.status(
            HaMqttStatus(
                availability = "online",
                polling = true,
                collectorStatus = "polling",
                adb = "granted",
                helper = "running",
                lastSuccessAt = "2026-06-12T12:00:00+03:00",
                lastError = null,
                categories = mapOf("battery" to "ok", "motion" to "stale")
            )
        )

        val json = JSONObject(payload)
        assertEquals("online", json.getString("availability"))
        assertTrue(json.getBoolean("polling"))
        assertEquals("polling", json.getString("collector_status"))
        assertEquals("granted", json.getString("adb"))
        assertEquals("running", json.getString("helper"))
        assertEquals("2026-06-12T12:00:00+03:00", json.getString("last_success_at"))
        assertTrue(json.isNull("last_error"))
        assertEquals("ok", json.getJSONObject("categories").getString("battery"))
        assertEquals("stale", json.getJSONObject("categories").getString("motion"))
    }

    @Test
    fun categoryStatePublishesNormalizedPhysicalNumberValues() {
        val payload = HaMqttPayloadBuilder.categoryState(
            category = "battery",
            timestamp = "2026-06-15T12:00:10+03:00",
            rows = listOf(
                storedState(
                    fieldKey = "battery_lowest_cell_voltage_raw",
                    valueType = "NUMBER",
                    valueNumber = 3.312,
                    quality = "OK",
                    sourcePollId = 59L,
                    sourceKeys = "statistic_lowest_battery_voltage"
                )
            )
        )

        val json = JSONObject(payload)
        assertEquals(3.312, json.getJSONObject("fields").getDouble("battery_lowest_cell_voltage_raw"))
    }

    @Test
    fun step3CategoryPayloadsPreserveNumbersBooleansQualityAndTimestamps() {
        val body = JSONObject(HaMqttPayloadBuilder.categoryState(
            category = "body",
            timestamp = "2026-09-08T10:00:05Z",
            rows = listOf(storedState("rf_window_percent", "NUMBER", valueNumber = 42.0, quality = "OK", sourcePollId = 120L, sourceKeys = "bodywork_1001_1267728400_5", category = "body"))
        ))
        val climateRows = listOf(
            storedState("perfume_1_remaining_percent", "NUMBER", valueNumber = 79.0, quality = "OK", sourcePollId = 120L, sourceKeys = "ac_1000_1242562584_5", category = "climate"),
            storedState("perfume_2_remaining_percent", "NUMBER", valueNumber = 81.0, quality = "OK", sourcePollId = 120L, sourceKeys = "ac_1000_1242562592_5", category = "climate"),
            storedState("perfume_3_remaining_percent", "NUMBER", valueNumber = 83.0, quality = "OK", sourcePollId = 120L, sourceKeys = "ac_1000_1242562600_5", category = "climate"),
            storedState("perfume_1_installed", "BOOLEAN", valueBool = false, quality = "OK", sourcePollId = 120L, sourceKeys = "ac_1000_1242562612_5", category = "climate"),
            storedState("perfume_2_installed", "BOOLEAN", valueBool = true, quality = "OK", sourcePollId = 120L, sourceKeys = "ac_1000_1242562614_5", category = "climate"),
            storedState("perfume_3_installed", "BOOLEAN", valueBool = false, quality = "OK", sourcePollId = 120L, sourceKeys = "ac_1000_1242562616_5", category = "climate")
        )
        val climate = JSONObject(HaMqttPayloadBuilder.categoryState("climate", "2026-09-08T10:00:05Z", climateRows))
        val motionRows = listOf(
            storedState("front_motor_current_raw", "NUMBER", valueNumber = -1.0, quality = "OK", sourcePollId = 120L, sourceKeys = "charging_1009_1186988040_7", category = "motion"),
            storedState("rear_motor_current_raw", "NUMBER", valueNumber = -226.7, quality = "OK", sourcePollId = 120L, sourceKeys = "charging_1009_1186988056_7", category = "motion")
        )
        val motion = JSONObject(HaMqttPayloadBuilder.categoryState("motion", "2026-09-08T10:00:05Z", motionRows))

        assertEquals(42.0, body.getJSONObject("fields").getDouble("rf_window_percent"))
        assertEquals("ok", body.getJSONObject("quality").getString("rf_window_percent"))
        climateRows.forEach { row -> assertTrue(climate.getJSONObject("fields").has(row.fieldKey)) }
        assertTrue(climate.getJSONObject("fields").getBoolean("perfume_2_installed"))
        assertEquals(-1.0, motion.getJSONObject("fields").getDouble("front_motor_current_raw"))
        assertEquals(-226.7, motion.getJSONObject("fields").getDouble("rear_motor_current_raw"))
        assertEquals("2026-06-12T12:00:00+03:00", motion.getJSONObject("observed_at").getString("front_motor_current_raw"))
    }

    @Test
    fun offlineStatusPublishesOfflineAvailabilityAndCollectorStatus() {
        val json = JSONObject(HaMqttPayloadBuilder.offlineStatus())

        assertEquals("offline", json.getString("availability"))
        assertEquals("offline", json.getString("collector_status"))
        assertFalse(json.optBoolean("polling", true))
    }

    private fun storedState(
        fieldKey: String,
        valueType: String,
        valueText: String? = null,
        valueNumber: Double? = null,
        valueBool: Boolean? = null,
        quality: String,
        sourcePollId: Long?,
        sourceKeys: String,
        category: String = "battery"
    ): StoredNormalizedState {
        return StoredNormalizedState(
            fieldKey = fieldKey,
            category = category,
            valueType = valueType,
            valueText = valueText,
            valueNumber = valueNumber,
            valueBool = valueBool,
            quality = quality,
            unit = null,
            sourcePollId = sourcePollId,
            sourceKeys = sourceKeys,
            observedAt = "2026-06-12T12:00:00+03:00",
            changedAt = "2026-06-12T12:00:05+03:00"
        )
    }
}
