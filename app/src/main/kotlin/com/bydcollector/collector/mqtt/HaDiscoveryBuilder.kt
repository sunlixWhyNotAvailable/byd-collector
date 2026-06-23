package com.bydcollector.collector.mqtt

import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedFieldDefinition
import org.json.JSONArray
import org.json.JSONObject

//generates home assistant discovery configs for the normalized BYD Collector device
object HaDiscoveryBuilder {
    const val DEVICE_ID = "byd_sealion_07"
    const val DEVICE_IDENTIFIER = "bydcollector_sealion_07"

    fun discoveryMessages(
        config: HaMqttConfig,
        fields: List<NormalizedFieldDefinition> = NormalizedFieldCatalog.fields
    ): List<HaMqttMessage> {
        val topicPrefix = config.normalizedTopicPrefix()
        val discoveryPrefix = config.normalizedDiscoveryPrefix()
        val availabilityTopic = "$topicPrefix/status"

        //publishes only fields that are enabled and safe for ha entity semantics
        return fields
            .filter { HaMqttFieldFilter.isPublishable(it, config) }
            .map { field ->
                val stateTopic = "$topicPrefix/state/${field.category.mqttKey}"
                val discoveryTopic = "$discoveryPrefix/${field.entityPlatform}/$DEVICE_ID/${field.fieldKey}/config"
                HaMqttMessage(
                    topic = discoveryTopic,
                    payload = discoveryPayload(field, stateTopic, availabilityTopic),
                    retained = true,
                    qos = 1
                )
            }
    }

    private fun discoveryPayload(
        field: NormalizedFieldDefinition,
        stateTopic: String,
        availabilityTopic: String
    ): String {
        val payload = JSONObject()
            .put("name", field.displayName)
            .put("unique_id", "${DEVICE_ID}_${field.fieldKey}")
            .put("state_topic", stateTopic)
            .put("availability_topic", availabilityTopic)
            .put("availability_template", "{{ value_json.availability }}")
            .put("payload_available", "online")
            .put("payload_not_available", "offline")
            .put("value_template", valueTemplate(field))
            .put("device", deviceJson())

        if (field.entityPlatform == "binary_sensor") {
            //binary sensors need explicit true/false payloads because ha templates otherwise treat strings loosely
            payload
                .put("payload_on", "true")
                .put("payload_off", "false")
        }
        field.deviceClass?.let { payload.put("device_class", it) }
        field.stateClass?.let { payload.put("state_class", it) }
        field.unit?.let { payload.put("unit_of_measurement", it) }

        return payload.toString()
    }

    private fun valueTemplate(field: NormalizedFieldDefinition): String {
        return if (field.entityPlatform == "binary_sensor") {
            //returns blank for missing booleans so ha marks the entity unavailable instead of guessing false
            "{{ 'true' if value_json.fields.${field.fieldKey} is true else 'false' if value_json.fields.${field.fieldKey} is false else '' }}"
        } else {
            "{{ value_json.fields.${field.fieldKey} }}"
        }
    }

    private fun deviceJson(): JSONObject {
        return JSONObject()
            .put("identifiers", JSONArray().put(DEVICE_IDENTIFIER))
            .put("name", "BYD Sea Lion 07")
            .put("manufacturer", "BYD")
            .put("model", "Sea Lion 07")
    }
}
