package com.bydcollector.collector.service

import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedSourceInput
import com.bydcollector.collector.data.normalized.NormalizedSourceKind
import com.bydcollector.collector.data.normalized.NormalizedSourceOrder
import com.bydcollector.collector.data.normalized.NormalizedSourceOrdering
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
import com.bydcollector.collector.data.normalized.normalizedSourceInputForPollReading
import com.bydcollector.collector.diagnostics.diagnosticSha256
import org.json.JSONArray
import org.json.JSONObject

data class TelegramRawSourceCursor(
    val rawValue: String?,
    val descValue: String?,
    val stamp: NormalizedSourceStamp
) {
    fun toInput(rawKey: String) = NormalizedSourceInput(
        reading = PollReading(rawKey, rawValue, descValue),
        stamp = stamp,
        sourcePollId = null
    )

    fun toJson(rawKey: String) = JSONObject().apply {
        put("key", rawKey)
        putNullable("raw", rawValue)
        putNullable("desc", descValue)
        put("kind", stamp.kind.storageValue)
        put("identity", stamp.identity)
        put("boot", stamp.bootId)
        putNullable("generator", stamp.generatorId)
        putNullable("sequence", stamp.sequence)
        put("wall_ms", stamp.wallMs)
        put("elapsed_ms", stamp.elapsedMs)
    }

    companion object {
        fun fromJson(json: JSONObject): Pair<String, TelegramRawSourceCursor>? = runCatching {
            val key = json.optString("key").takeIf { it in TELEGRAM_RAW_SOURCE_KEYS } ?: return null
            val kind = NormalizedSourceKind.entries.first { it.storageValue == json.optString("kind") }
            val identity = json.optString("identity").takeIf(String::isNotBlank) ?: return null
            val boot = json.optString("boot").takeIf(String::isNotBlank) ?: return null
            val wall = json.optLong("wall_ms", -1L).takeIf { it >= 0L } ?: return null
            val elapsed = json.optLong("elapsed_ms", -1L).takeIf { it >= 0L } ?: return null
            key to TelegramRawSourceCursor(
                rawValue = json.optStringOrNull("raw"),
                descValue = json.optStringOrNull("desc"),
                stamp = NormalizedSourceStamp(
                    kind = kind,
                    identity = identity,
                    bootId = boot,
                    generatorId = json.optStringOrNull("generator"),
                    sequence = json.optLongOrNull("sequence"),
                    wallMs = wall,
                    elapsedMs = elapsed
                )
            )
        }.getOrNull()
    }
}

data class TelegramSourceOrderStats(
    val accepted: Int = 0,
    val seeded: Int = 0,
    val repeated: Int = 0,
    val older: Int = 0,
    val incomparable: Int = 0,
    val conflictingEqual: Int = 0,
    val invalid: Int = 0,
    val sourceRefs: Set<String> = emptySet(),
    val keyRefs: Set<String> = emptySet()
) {
    val rejected: Int get() = repeated + older + incomparable + conflictingEqual + invalid
}

internal data class TelegramOrderedPollInput(
    val cursors: Map<String, TelegramRawSourceCursor>,
    val inputs: Map<String, NormalizedSourceInput>,
    val acceptedSourceKeys: Set<String>,
    val freshSourceKeys: Set<String>,
    val stats: TelegramSourceOrderStats,
    private val currentBootId: String?
) {
    fun sourceKeys(fieldKey: String): List<String> =
        TELEGRAM_FIELDS_BY_KEY[fieldKey]?.sourceKeys.orEmpty()

    fun hasFresh(fieldKey: String): Boolean = sourceKeys(fieldKey).any { it in freshSourceKeys }

    fun hasFreshSource(fieldKey: String): Boolean = hasFresh(fieldKey)

    fun hasSingleBoot(fieldKey: String): Boolean {
        val keys = sourceKeys(fieldKey)
        val stamps = keys.mapNotNull { inputs[it]?.stamp }
        return stamps.size == keys.size && stamps.map { it.bootId }.distinct().size == 1
    }

    fun sourceStamp(fieldKey: String, freshOnly: Boolean = true): NormalizedSourceStamp? {
        val keys = sourceKeys(fieldKey).filter { !freshOnly || it in freshSourceKeys }
        if (keys.isEmpty()) return null
        if (sourceKeys(fieldKey).size > 1 && !hasSingleBoot(fieldKey)) return null
        return newest(keys.mapNotNull { inputs[it]?.stamp })
    }

    fun latestStamp(vararg fieldKeys: String): NormalizedSourceStamp? =
        newest(fieldKeys.flatMap(::sourceKeys).distinct().mapNotNull { inputs[it]?.stamp })

    fun latestFreshStamp(vararg fieldKeys: String): NormalizedSourceStamp? =
        newest(fieldKeys.flatMap(::sourceKeys).distinct().filter { it in freshSourceKeys }
            .mapNotNull { inputs[it]?.stamp })

    private fun newest(stamps: List<NormalizedSourceStamp>): NormalizedSourceStamp? =
        stamps.reduceOrNull { current, candidate ->
            when (NormalizedSourceOrdering.compare(candidate, current, currentBootId)) {
                NormalizedSourceOrder.NEWER -> candidate
                NormalizedSourceOrder.INCOMPARABLE -> if (candidate.wallMs > current.wallMs) candidate else current
                else -> current
            }
        }
}

internal object TelegramOrderedInput {
    val rawSourceKeys: Set<String> = TELEGRAM_RAW_SOURCE_KEYS

    fun merge(
        previous: Map<String, TelegramRawSourceCursor>,
        readings: List<PollReading>,
        pollStamp: NormalizedSourceStamp,
        pollId: Long,
        currentBootId: String?
    ): TelegramOrderedPollInput {
        val cursors = previous.filterKeys { it in rawSourceKeys }.toMutableMap()
        val accepted = linkedSetOf<String>()
        val fresh = linkedSetOf<String>()
        var seeded = 0
        var repeated = 0
        var older = 0
        var incomparable = 0
        var conflicting = 0
        var invalid = 0
        val sourceRefs = linkedSetOf<String>()
        val keyRefs = linkedSetOf<String>()

        readings.asSequence()
            .filter { it.rawKey in rawSourceKeys }
            .forEach { reading ->
                val incoming = normalizedSourceInputForPollReading(reading, pollStamp, pollId)
                if (incoming == null) {
                    invalid += 1
                    keyRefs += diagnosticSha256(reading.rawKey)
                    sourceRefs += diagnosticSha256(reading.callbackSource?.let {
                        "callback:${it.bootId}:${it.helperGeneration}:${it.stream}:${it.epoch}:${it.eventSequence}"
                    } ?: pollStamp.identity)
                    return@forEach
                }
                val key = reading.rawKey
                val previousCursor = cursors[key]
                if (previousCursor == null) {
                    cursors[key] = TelegramRawSourceCursor(
                        incoming.reading.rawValue, incoming.reading.descValue, incoming.stamp
                    )
                    accepted += key
                    seeded += 1
                    return@forEach
                }
                when (NormalizedSourceOrdering.compare(incoming.stamp, previousCursor.stamp, currentBootId)) {
                    NormalizedSourceOrder.NEWER -> {
                        cursors[key] = TelegramRawSourceCursor(
                            incoming.reading.rawValue, incoming.reading.descValue, incoming.stamp
                        )
                        accepted += key
                        fresh += key
                    }
                    NormalizedSourceOrder.EQUAL -> {
                        if (incoming.reading.rawValue == previousCursor.rawValue &&
                            incoming.reading.descValue == previousCursor.descValue
                        ) {
                            repeated += 1
                        } else {
                            conflicting += 1
                        }
                        sourceRefs += diagnosticSha256(incoming.stamp.identity)
                        keyRefs += diagnosticSha256(key)
                    }
                    NormalizedSourceOrder.OLDER -> {
                        older += 1
                        sourceRefs += diagnosticSha256(incoming.stamp.identity)
                        keyRefs += diagnosticSha256(key)
                    }
                    NormalizedSourceOrder.INCOMPARABLE -> {
                        incomparable += 1
                        sourceRefs += diagnosticSha256(incoming.stamp.identity)
                        keyRefs += diagnosticSha256(key)
                    }
                }
            }

        val inputs = cursors.mapValues { (key, cursor) -> cursor.toInput(key) }
        return TelegramOrderedPollInput(
            cursors = cursors,
            inputs = inputs,
            acceptedSourceKeys = accepted,
            freshSourceKeys = fresh,
            stats = TelegramSourceOrderStats(
                accepted = fresh.size,
                seeded = seeded,
                repeated = repeated,
                older = older,
                incomparable = incomparable,
                conflictingEqual = conflicting,
                invalid = invalid,
                sourceRefs = sourceRefs,
                keyRefs = keyRefs
            ),
            currentBootId = currentBootId
        )
    }

    fun decodeCursors(value: JSONArray?): Map<String, TelegramRawSourceCursor> {
        if (value == null) return emptyMap()
        return buildMap {
            for (index in 0 until minOf(value.length(), rawSourceKeys.size)) {
                val entry = value.optJSONObject(index) ?: continue
                TelegramRawSourceCursor.fromJson(entry)?.let { (key, cursor) -> put(key, cursor) }
            }
        }
    }

    fun encodeCursors(value: Map<String, TelegramRawSourceCursor>): JSONArray = JSONArray().apply {
        value.toSortedMap().forEach { (key, cursor) ->
            if (key in rawSourceKeys) put(cursor.toJson(key))
        }
    }
}

private val TELEGRAM_EVENT_FIELD_KEYS = setOf(
    "gear_auto_mode_raw",
    "odometer_km",
    "soc",
    "trip_energy_kwh",
    "charge_gun_connected_raw",
    "charging_battery_device_state",
    "battery_charge_power_kw",
    "battery_remaining_energy_kwh",
    "remaining_range_km",
    "aux_voltage_v"
)

private val TELEGRAM_FIELDS_BY_KEY = NormalizedFieldCatalog.fields
    .filter { it.fieldKey in TELEGRAM_EVENT_FIELD_KEYS }
    .associateBy { it.fieldKey }

private val TELEGRAM_RAW_SOURCE_KEYS = TELEGRAM_FIELDS_BY_KEY.values.flatMap { it.sourceKeys }.toSet()

private fun JSONObject.putNullable(key: String, value: Any?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)
