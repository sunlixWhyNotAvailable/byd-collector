package com.bydcollector.collector.service

import com.bydcollector.collector.data.normalized.NormalizedSourceKind
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
import org.json.JSONObject

data class TelegramOdometerPoint(val km: Double, val source: NormalizedSourceStamp) {
    init { require(km.isFinite() && km >= 0.0) }
    fun toJson(): JSONObject = JSONObject().put("km", km).put("source", source.toJson())
    companion object {
        fun fromJson(json: JSONObject): TelegramOdometerPoint = TelegramOdometerPoint(
            json.getDouble("km"), requireNotNull(TelegramRawSourceCursor.stampFromJson(json.getJSONObject("source"))))
    }
}

/** Counter endpoints survive sparse callbacks; missing coverage never means a zero-length trip. */
data class TelegramTripDistance(
    val boundary: NormalizedSourceStamp? = null,
    val start: TelegramOdometerPoint? = null,
    val end: TelegramOdometerPoint? = null,
    val startComplete: Boolean = false,
    val endUsable: Boolean = false,
    val error: String? = null
) {
    val knownKm: Double? get() = if (error != null) null else start?.let { first ->
        end?.let { last -> (last.km - first.km).takeIf { it in 0.0..2_000.0 } }
    }
    val completeKm: Double? get() = knownKm.takeIf { startComplete && endUsable }

    fun observe(point: TelegramOdometerPoint?): TelegramTripDistance {
        if (boundary == null) return this
        if (point == null) return copy(endUsable = false)
        val beforeStart = point.source.bootId == boundary.bootId && point.source.elapsedMs <= boundary.elapsedMs
        val first = if (start == null || (!startComplete && beforeStart)) point else start
        val coversStart = startComplete || beforeStart
        val previous = end
        if (previous != null && point.source.bootId == previous.source.bootId &&
            point.source.elapsedMs < previous.source.elapsedMs) {
            return copy(start = first, startComplete = coversStart)
        }
        val reset = previous != null && point.km < previous.km
        val tooLarge = point.km - first.km > 2_000.0
        return copy(start = first, end = point, startComplete = coversStart, endUsable = true,
            error = error ?: if (reset) "odometer_reset" else if (tooLarge) "odometer_jump" else null)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("boundary", boundary?.toJson() ?: JSONObject.NULL)
        put("start", start?.toJson() ?: JSONObject.NULL)
        put("end", end?.toJson() ?: JSONObject.NULL)
        put("start_complete", startComplete)
        put("end_usable", endUsable)
        put("error", error ?: JSONObject.NULL)
    }

    companion object {
        fun begin(boundary: NormalizedSourceStamp, first: TelegramOdometerPoint?) =
            TelegramTripDistance(boundary = boundary).observe(first)

        fun fromJson(json: JSONObject): TelegramTripDistance = TelegramTripDistance(
            boundary = json.optJSONObject("boundary")?.let { requireNotNull(TelegramRawSourceCursor.stampFromJson(it)) },
            start = json.optJSONObject("start")?.let(TelegramOdometerPoint::fromJson),
            end = json.optJSONObject("end")?.let(TelegramOdometerPoint::fromJson),
            startComplete = json.getBoolean("start_complete"),
            endUsable = json.getBoolean("end_usable"),
            error = if (json.isNull("error")) null else json.getString("error")
        )

        fun legacy(startKm: Double?, endKm: Double?, startedAt: Long?, endedAt: Long?): TelegramTripDistance {
            fun stamp(at: Long?) = NormalizedSourceStamp(NormalizedSourceKind.POLL,
                "legacy-odometer:${at ?: 0}", "legacy-unverified", null, null,
                (at ?: 0).coerceAtLeast(0), (at ?: 0).coerceAtLeast(0))
            fun point(km: Double?, at: Long?) = km?.takeIf { it.isFinite() && it >= 0.0 }
                ?.let { TelegramOdometerPoint(it, stamp(at)) }
            return TelegramTripDistance(boundary = stamp(startedAt), start = point(startKm, startedAt),
                end = point(endKm, endedAt), endUsable = endKm != null)
        }
    }
}
