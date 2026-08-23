package com.bydcollector.collector.data.trips

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Parses persisted ISO timestamps without changing their raw representation. */
object TripTime {
    fun instant(value: String): Instant? = runCatching {
        OffsetDateTime.parse(value).toInstant()
    }.getOrNull()

    fun localDate(value: String): LocalDate? = instant(value)?.atZone(ZoneId.systemDefault())?.toLocalDate()

    fun localTime(value: String): LocalTime? = instant(value)?.atZone(ZoneId.systemDefault())?.toLocalTime()

    fun durationMs(startedAt: String, endedAt: String): Long? = runCatching {
        Duration.between(instant(startedAt) ?: return@runCatching null, instant(endedAt) ?: return@runCatching null)
            .toMillis()
            .coerceAtLeast(0L)
    }.getOrNull()
}
