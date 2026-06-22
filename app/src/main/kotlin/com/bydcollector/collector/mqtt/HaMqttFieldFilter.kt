package com.bydcollector.collector.mqtt

import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedFieldDefinition

object HaMqttFieldFilter {
    private val fieldsByKey = NormalizedFieldCatalog.fields.associateBy { it.fieldKey }

    fun isPublishable(field: NormalizedFieldDefinition, config: HaMqttConfig): Boolean {
        if (!config.isCategoryEnabled(field.category.mqttKey)) return false
        return field.mqttDefaultEnabled || field.category.mqttKey == HaMqttConfig.DRIVER_ASSIST_CATEGORY
    }

    fun publishableRows(
        rows: List<com.bydcollector.collector.data.normalized.StoredNormalizedState>,
        config: HaMqttConfig
    ): List<com.bydcollector.collector.data.normalized.StoredNormalizedState> {
        return rows.filter { row ->
            val field = fieldsByKey[row.fieldKey] ?: return@filter false
            isPublishable(field, config)
        }
    }
}
