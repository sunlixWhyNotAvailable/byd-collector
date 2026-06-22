package com.bydcollector.collector.mqtt

import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject

class HaDiscoveryBuilderTest {
    @Test
    fun discoveryUsesGroupedCategoryStateTopicForSoc() {
        val messages = HaDiscoveryBuilder.discoveryMessages(
            config = config(),
            fields = listOf(NormalizedFieldCatalog.soc)
        )

        val message = messages.single()
        assertEquals("homeassistant/sensor/byd_sealion_07/soc/config", message.topic)
        assertTrue(message.retained)
        assertEquals(1, message.qos)

        val json = JSONObject(message.payload)
        assertEquals("Battery SOC display", json.getString("name"))
        assertEquals("byd_sealion_07_soc", json.getString("unique_id"))
        assertEquals("bydcollector/state/battery", json.getString("state_topic"))
        assertEquals("bydcollector/status", json.getString("availability_topic"))
        assertEquals("{{ value_json.availability }}", json.getString("availability_template"))
        assertEquals("online", json.getString("payload_available"))
        assertEquals("offline", json.getString("payload_not_available"))
        assertEquals("{{ value_json.fields.soc }}", json.getString("value_template"))
        assertEquals("battery", json.getString("device_class"))
        assertEquals("measurement", json.getString("state_class"))
        assertEquals("%", json.getString("unit_of_measurement"))
        assertEquals(
            HaDiscoveryBuilder.DEVICE_IDENTIFIER,
            json.getJSONObject("device").getJSONArray("identifiers").getString(0)
        )
    }

    @Test
    fun discoverySkipsDisabledCategories() {
        val messages = HaDiscoveryBuilder.discoveryMessages(
            config = config(enabledCategories = setOf("motion")),
            fields = listOf(NormalizedFieldCatalog.soc, NormalizedFieldCatalog.speedKmh)
        )

        assertEquals(1, messages.size)
        assertTrue(messages.single().topic.contains("/speed_kmh/"))
        assertFalse(messages.single().topic.contains("/soc/"))
    }

    @Test
    fun discoveryNormalizesBlankPrefixes() {
        val messages = HaDiscoveryBuilder.discoveryMessages(
            config = config(topicPrefix = "///", discoveryPrefix = ""),
            fields = listOf(NormalizedFieldCatalog.soc)
        )

        val message = messages.single()
        assertEquals("homeassistant/sensor/byd_sealion_07/soc/config", message.topic)
        assertEquals(
            "bydcollector/state/battery",
            JSONObject(message.payload).getString("state_topic")
        )
    }

    @Test
    fun binarySensorDiscoveryUsesStableBooleanPayloadMapping() {
        val message = HaDiscoveryBuilder.discoveryMessages(
            config = config(enabledCategories = setOf("body")),
            fields = listOf(NormalizedFieldCatalog.driverDoor)
        ).single()

        assertEquals("homeassistant/binary_sensor/byd_sealion_07/driver_door_open/config", message.topic)
        val json = JSONObject(message.payload)
        assertEquals("true", json.getString("payload_on"))
        assertEquals("false", json.getString("payload_off"))
        assertEquals(
            "{{ 'true' if value_json.fields.driver_door_open is true else 'false' if value_json.fields.driver_door_open is false else '' }}",
            json.getString("value_template")
        )
        assertEquals("door", json.getString("device_class"))
    }

    @Test
    fun discoverySkipsNonDefaultRawFieldsWithinEnabledDefaultCategory() {
        val tireStateRaw = NormalizedFieldCatalog.fields.single { it.fieldKey == "tyre_state_lf" }
        val radarDistance = NormalizedFieldCatalog.fields.single { it.fieldKey == "radar_1025_neg_1728053151_5" }
        val lowVoltageWarning = NormalizedFieldCatalog.fields.single { it.fieldKey == "low_voltage_warning_raw" }

        val messages = HaDiscoveryBuilder.discoveryMessages(
            config = config(enabledCategories = setOf("safety", "battery")),
            fields = listOf(tireStateRaw, radarDistance, lowVoltageWarning)
        )

        assertEquals(emptyList(), messages)
    }

    @Test
    fun curatedCatalogDoesNotAdvertiseRemovedLegacyDiscoveryFields() {
        val removed = setOf(
            "adas_acc_mode_raw",
            "total_elec_consumption_kwh",
            "trip_elec_consumption_kwh",
            "charging_type_raw"
        )
        val fields = NormalizedFieldCatalog.fields.map { it.fieldKey }.toSet()

        assertEquals(emptySet(), removed.intersect(fields))
    }

    private fun config(
        topicPrefix: String = HaMqttConfig.DEFAULT_TOPIC_PREFIX,
        discoveryPrefix: String = HaMqttConfig.DEFAULT_DISCOVERY_PREFIX,
        enabledCategories: Set<String> = HaMqttConfig.DEFAULT_CATEGORIES
    ): HaMqttConfig {
        return HaMqttConfig(
            enabled = true,
            discoveryEnabled = true,
            host = "mqtt.local",
            port = 1883,
            username = null,
            password = null,
            clientId = HaMqttConfig.DEFAULT_CLIENT_ID,
            topicPrefix = topicPrefix,
            discoveryPrefix = discoveryPrefix,
            enabledCategories = enabledCategories
        )
    }
}
