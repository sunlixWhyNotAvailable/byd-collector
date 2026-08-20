package com.bydcollector.collector.location

import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedValue
import com.bydcollector.collector.data.normalized.NormalizedValueType

/** Converts Android GPS fixes into normalized observations without inventing a poll id. */
object LocationNormalizer {
    fun observations(sample: GpsLocationSample, nowMs: Long = sample.wallTimeMs): List<NormalizedObservation> {
        val quality = if (sample.accuracyM != null && sample.accuracyM > 100.0) "degraded" else "ok"
        val ageMs = (nowMs - sample.wallTimeMs).coerceAtLeast(0L).toDouble()
        return listOf(
            number(NormalizedFieldCatalog.locationLatitude, sample.latitude, sample.observedAt),
            number(NormalizedFieldCatalog.locationLongitude, sample.longitude, sample.observedAt),
            number(NormalizedFieldCatalog.locationAccuracy, sample.accuracyM, sample.observedAt),
            number(NormalizedFieldCatalog.locationSpeed, sample.speedKmh, sample.observedAt),
            number(NormalizedFieldCatalog.locationAltitude, sample.altitudeM, sample.observedAt),
            number(NormalizedFieldCatalog.locationBearing, sample.bearingDeg, sample.observedAt),
            number(NormalizedFieldCatalog.locationFixAge, ageMs, sample.observedAt),
            text(NormalizedFieldCatalog.locationFixTimestamp, sample.observedAt),
            text(NormalizedFieldCatalog.locationQuality, quality)
        )
    }

    fun gap(observedAt: String, reason: String): List<NormalizedObservation> = listOf(
        number(NormalizedFieldCatalog.locationLatitude, null, observedAt, NormalizedQuality.MISSING),
        number(NormalizedFieldCatalog.locationLongitude, null, observedAt, NormalizedQuality.MISSING),
        number(NormalizedFieldCatalog.locationAccuracy, null, observedAt, NormalizedQuality.MISSING),
        number(NormalizedFieldCatalog.locationSpeed, null, observedAt, NormalizedQuality.MISSING),
        number(NormalizedFieldCatalog.locationAltitude, null, observedAt, NormalizedQuality.MISSING),
        number(NormalizedFieldCatalog.locationBearing, null, observedAt, NormalizedQuality.MISSING),
        number(NormalizedFieldCatalog.locationFixAge, null, observedAt, NormalizedQuality.MISSING),
        text(NormalizedFieldCatalog.locationFixTimestamp, observedAt, NormalizedQuality.MISSING, observedAt),
        text(NormalizedFieldCatalog.locationQuality, "gap:$reason", NormalizedQuality.MISSING, observedAt)
    )

    private fun number(field: com.bydcollector.collector.data.normalized.NormalizedFieldDefinition, value: Double?, observedAt: String, quality: NormalizedQuality? = null): NormalizedObservation {
        return NormalizedObservation(field, NormalizedValue(NormalizedValueType.NUMBER, number = value), quality ?: if (value?.isFinite() == true) NormalizedQuality.OK else NormalizedQuality.MISSING, null, "android_gps", observedAt)
    }

    private fun text(field: com.bydcollector.collector.data.normalized.NormalizedFieldDefinition, value: String, quality: NormalizedQuality = NormalizedQuality.OK, observedAt: String = value): NormalizedObservation {
        return NormalizedObservation(field, NormalizedValue(NormalizedValueType.TEXT, text = value), quality, null, "android_gps", observedAt)
    }
}
