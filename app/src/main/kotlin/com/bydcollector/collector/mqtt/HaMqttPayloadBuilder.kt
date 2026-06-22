package com.bydcollector.collector.mqtt

import com.bydcollector.collector.data.normalized.StoredNormalizedState
import java.util.Locale
import org.json.JSONObject

object HaMqttPayloadBuilder {
    fun categoryState(category: String, timestamp: String, rows: List<StoredNormalizedState>): String {
        val fields = JSONObject()
        val quality = JSONObject()
        val observedAt = JSONObject()
        val changedAt = JSONObject()
        val sourcePollId = JSONObject()
        val sourceKeys = JSONObject()

        rows.forEach { row ->
            fields.put(row.fieldKey, jsonValue(row))
            quality.put(row.fieldKey, row.quality.lowercase(Locale.US))
            observedAt.put(row.fieldKey, row.observedAt)
            changedAt.put(row.fieldKey, row.changedAt)
            sourcePollId.put(row.fieldKey, row.sourcePollId ?: JSONObject.NULL)
            sourceKeys.put(row.fieldKey, row.sourceKeys)
        }

        return JSONObject()
            .put("category", category)
            .put("ts", timestamp)
            .put("fields", fields)
            .put("quality", quality)
            .put("observed_at", observedAt)
            .put("changed_at", changedAt)
            .put("source_poll_id", sourcePollId)
            .put("source_keys", sourceKeys)
            .toString()
    }

    fun status(status: HaMqttStatus): String {
        return JSONObject()
            .put("availability", status.availability)
            .put("polling", status.polling)
            .put("collector_status", status.collectorStatus)
            .put("adb", status.adb)
            .put("helper", status.helper)
            .put("last_success_at", status.lastSuccessAt ?: JSONObject.NULL)
            .put("last_error", status.lastError ?: JSONObject.NULL)
            .put("categories", JSONObject(status.categories))
            .toString()
    }

    fun offlineStatus(): String {
        return status(
            HaMqttStatus(
                availability = "offline",
                polling = false,
                collectorStatus = "offline",
                adb = "unknown",
                helper = "unknown",
                lastSuccessAt = null,
                lastError = null,
                categories = emptyMap()
            )
        )
    }

    private fun jsonValue(row: StoredNormalizedState): Any {
        return when (row.valueType.uppercase(Locale.US)) {
            "NUMBER" -> row.valueNumber ?: JSONObject.NULL
            "BOOLEAN" -> row.valueBool ?: JSONObject.NULL
            "TEXT" -> row.valueText ?: JSONObject.NULL
            else -> row.valueText ?: row.valueNumber ?: row.valueBool ?: JSONObject.NULL
        }
    }
}
