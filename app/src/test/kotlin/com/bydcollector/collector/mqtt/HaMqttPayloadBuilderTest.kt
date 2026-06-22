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
                )
            )
        )

        val json = JSONObject(payload)
        assertEquals("battery", json.getString("category"))
        assertEquals("2026-06-12T12:00:10+03:00", json.getString("ts"))
        assertEquals(73.5, json.getJSONObject("fields").getDouble("soc"))
        assertTrue(json.getJSONObject("fields").getBoolean("charging"))
        assertTrue(json.getJSONObject("fields").isNull("charge_mode"))
        assertEquals("ok", json.getJSONObject("quality").getString("soc"))
        assertEquals("stale", json.getJSONObject("quality").getString("charging"))
        assertEquals("missing", json.getJSONObject("quality").getString("charge_mode"))
        assertEquals("2026-06-12T12:00:00+03:00", json.getJSONObject("observed_at").getString("soc"))
        assertEquals("2026-06-12T12:00:05+03:00", json.getJSONObject("changed_at").getString("soc"))
        assertEquals(42L, json.getJSONObject("source_poll_id").getLong("soc"))
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
        sourceKeys: String
    ): StoredNormalizedState {
        return StoredNormalizedState(
            fieldKey = fieldKey,
            category = "battery",
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
