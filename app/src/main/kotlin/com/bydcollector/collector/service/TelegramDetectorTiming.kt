package com.bydcollector.collector.service

import org.json.JSONObject

/** Boot-scoped deadlines survive APP recreation without treating silence as another sample. */
data class TelegramDetectorDeadline(
    val elapsedMs: Long,
    val observedAtMs: Long,
    val sourceIdentity: String?
)

data class TelegramDetectorTiming(
    val bootId: String? = null,
    val evaluatedElapsedMs: Long = 0L,
    val evaluatedWallMs: Long = 0L,
    val pending: Map<String, TelegramDetectorDeadline> = emptyMap(),
    val pendingStopAtMs: Long? = null,
    val reportedPowerSessionId: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("boot", bootId ?: JSONObject.NULL)
        put("elapsed", evaluatedElapsedMs)
        put("wall", evaluatedWallMs)
        put("pending", JSONObject().apply {
            pending.forEach { (key, value) -> put(key, JSONObject().apply {
                put("elapsed", value.elapsedMs)
                put("observed", value.observedAtMs)
                put("source", value.sourceIdentity ?: JSONObject.NULL)
            }) }
        })
        put("stop_at", pendingStopAtMs ?: JSONObject.NULL)
        put("reported_power", reportedPowerSessionId ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject?): TelegramDetectorTiming {
            if (json == null) return TelegramDetectorTiming()
            require(json.getInt("version") == 1) { "Unsupported Telegram detector timing version" }
            val pending = json.optJSONObject("pending")
            val entries = buildMap {
                pending?.keys()?.forEach { key ->
                    require(key in setOf("gun", "charging", "full", "low_power", "low_12v", "park")) {
                        "Unknown Telegram detector deadline: $key"
                    }
                    val value = pending.getJSONObject(key)
                    val elapsed = value.getLong("elapsed")
                    val observed = value.getLong("observed")
                    require(elapsed >= 0L && observed >= 0L) { "Negative Telegram deadline" }
                    put(key, TelegramDetectorDeadline(elapsed, observed,
                        if (value.isNull("source")) null else value.getString("source")))
                }
            }
            return TelegramDetectorTiming(
                bootId = if (json.isNull("boot")) null else json.getString("boot"),
                evaluatedElapsedMs = json.getLong("elapsed").also { require(it >= 0L) },
                evaluatedWallMs = json.getLong("wall").also { require(it >= 0L) },
                pending = entries,
                pendingStopAtMs = if (json.isNull("stop_at")) null else json.getLong("stop_at"),
                reportedPowerSessionId = if (json.isNull("reported_power")) null else json.getString("reported_power")
            )
        }
    }
}
