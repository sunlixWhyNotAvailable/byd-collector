package com.bydcollector.collector.service

import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
import org.json.JSONObject

/** The confirmed ON boundary is independent of driving legs and Trips history settings. */
data class TelegramPowerSession(
    val id: String,
    val boundary: NormalizedSourceStamp?,
    val startedAtMs: Long = requireNotNull(boundary).wallMs,
    val distance: TelegramTripDistance = TelegramTripDistance(boundary = boundary),
    val startSoc: Double? = null,
    val endSoc: Double? = null
) {
    fun observe(odometer: TelegramOdometerPoint?, soc: Double?, socSource: NormalizedSourceStamp?) = copy(
        distance = distance.observe(odometer),
        startSoc = startSoc ?: soc?.takeIf {
            boundary != null && socSource?.bootId == boundary.bootId && socSource.elapsedMs <= boundary.elapsedMs
        },
        endSoc = soc
    )

    fun toJson(): JSONObject = JSONObject().put("id", id).put("boundary", boundary?.toJson() ?: JSONObject.NULL)
        .put("startedAtMs", startedAtMs)
        .put("distance", distance.toJson()).put("startSoc", startSoc ?: JSONObject.NULL)
        .put("endSoc", endSoc ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): TelegramPowerSession {
            val boundary = json.optJSONObject("boundary")?.let { requireNotNull(TelegramRawSourceCursor.stampFromJson(it)) }
            return TelegramPowerSession(
                json.getString("id"), boundary,
                if (json.has("startedAtMs")) json.getLong("startedAtMs") else requireNotNull(boundary).wallMs,
                TelegramTripDistance.fromJson(json.getJSONObject("distance")),
                if (json.isNull("startSoc")) null else json.getDouble("startSoc"),
                if (json.isNull("endSoc")) null else json.getDouble("endSoc")
            )
        }
    }
}
