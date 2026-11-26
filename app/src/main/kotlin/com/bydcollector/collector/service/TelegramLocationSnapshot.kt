package com.bydcollector.collector.service

import com.bydcollector.collector.data.trips.TripCompletionLocation
import java.time.Instant

internal fun telegramLocationSnapshot(sample: TripCompletionLocation, nowMs: Long = System.currentTimeMillis()): TelegramLocationSnapshot {
    val latitude = sample.latitude
    val longitude = sample.longitude
    return TelegramLocationSnapshot(latitude, longitude, sample.capturedAt,
        (nowMs - Instant.parse(sample.capturedAt).toEpochMilli()).coerceAtLeast(0L) / 1_000L,
        "https://www.openstreetmap.org/?mlat=$latitude&mlon=$longitude#map=17/$latitude/$longitude",
        "https://www.google.com/maps/search/?api=1&query=$latitude,$longitude",
        "https://maps.apple.com/?ll=$latitude,$longitude",
        "https://www.waze.com/ul?ll=$latitude%2C$longitude&navigate=yes")
}
