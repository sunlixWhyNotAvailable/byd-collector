package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.local.TelegramOutboxMessage
import com.bydcollector.collector.data.trips.TripCompletionIntent
import com.bydcollector.collector.data.trips.TripTime
import com.bydcollector.collector.service.TelegramEventConfig
import com.bydcollector.collector.service.TelegramEventEngine
import com.bydcollector.collector.service.TelegramEventState
import com.bydcollector.collector.service.TelegramDetectedEvent
import com.bydcollector.collector.service.TelegramPowerOffSnapshot
import com.bydcollector.collector.service.telegramLocationSnapshot
import org.json.JSONArray
import org.json.JSONObject

internal fun encodeTelegramMessages(messages: List<TelegramOutboxMessage>): String = JSONArray().apply {
    messages.forEach { message -> put(JSONObject().put("dedupe", message.dedupeKey)
        .put("event", message.eventType).put("payload", message.payload)
        .put("dependency", message.waitsForSummaryKey ?: JSONObject.NULL)
        .put("occurred_at_ms", message.occurredAtMs ?: JSONObject.NULL)) }
}.toString()

internal fun decodeTelegramMessages(value: String): List<TelegramOutboxMessage> {
    val values = JSONArray(value)
    return (0 until values.length()).map { index ->
        val item = values.getJSONObject(index)
        TelegramOutboxMessage(item.getString("dedupe"), item.getString("event"), item.getString("payload"),
            if (item.isNull("dependency")) null else item.getString("dependency"),
            if (item.isNull("occurred_at_ms")) null else item.getLong("occurred_at_ms"))
    }
}

/** Historical OFF recovery has its own detector and cannot clear the live trip. */
internal fun recoverClosedTripEvents(intent: TripCompletionIntent, config: TelegramEventConfig): List<TelegramDetectedEvent> {
    check(intent.session != null) { "Archived completion has no closed session for report reconstruction" }
    val time = checkNotNull(TripTime.instant(intent.observedAt)) { "Archived completion time is invalid" }.toEpochMilli()
    return TelegramEventEngine(TelegramEventState()).onPowerOffConfirmed(config,
        TelegramPowerOffSnapshot(intent.odometerKm, intent.soc, intent.tripEnergyKwh,
            intent.energySnapshot, intent.session, intent.identity),
        location = intent.lastLocation?.let { telegramLocationSnapshot(it, time) }, nowMs = time).events
}
