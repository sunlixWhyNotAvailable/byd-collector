package com.bydcollector.collector.service

import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedQuality

/** Main-thread-owned per-field source age; sparse events never refresh other fields. */
internal class KpiFreshness(private val bootId: String, private val maxAgeMs: Long) {
    private val fields = mutableMapOf<String, NormalizedObservation>()
    private val kpiKeys = NormalizedFieldCatalog.kpiKeys
    private val lastRejected = mutableMapOf<String, String>()
    private val expiredKeys = mutableSetOf<String>()
    private val expiriesSinceReport = mutableMapOf<String, Long>()
    private var lastDiagnosticMs = Long.MIN_VALUE

    private fun isRecent(sourceBootId: String, sourceElapsedMs: Long, nowMs: Long): Boolean =
        sourceBootId == bootId && sourceElapsedMs >= 0 && sourceElapsedMs <= nowMs &&
            nowMs - sourceElapsedMs < maxAgeMs

    fun accept(observations: List<NormalizedObservation>, nowMs: Long): Boolean {
        var changed = false
        observations.forEach { observation ->
            val key = observation.field.fieldKey
            if (key !in kpiKeys) return@forEach
            val source = observation.sourceStamp
            val rejection = when {
                source == null -> "missing_source_clock"
                source.bootId != bootId -> "boot_mismatch"
                source.elapsedMs < 0 || source.elapsedMs > nowMs -> "invalid_source_clock"
                nowMs - source.elapsedMs >= maxAgeMs -> "source_age"
                fields[key]?.sourceStamp?.let { source.elapsedMs < it.elapsedMs } == true -> "old_replay"
                else -> null
            }
            if (rejection != null) {
                lastRejected[key] = rejection
                return@forEach
            }
            lastRejected.remove(key)
            expiredKeys.remove(key)
            fields[key] = observation
            changed = true
        }
        return changed
    }

    fun freshObservations(nowMs: Long): List<NormalizedObservation> = fields.values.filter {
        val source = checkNotNull(it.sourceStamp)
        isRecent(source.bootId, source.elapsedMs, nowMs).also { recent ->
            val key = it.field.fieldKey
            if (!recent && expiredKeys.add(key)) expiriesSinceReport[key] = (expiriesSinceReport[key] ?: 0L) + 1L
        }
    }

    /** Next individual expiry, not the newest field's deadline. */
    fun remainingMs(nowMs: Long): Long = freshObservations(nowMs)
        .minOfOrNull { maxAgeMs - (nowMs - checkNotNull(it.sourceStamp).elapsedMs) } ?: 0L

    /** Bounded catalog-sized evidence, at most once per 30 s; never guesses transport failure. */
    fun expiryDiagnostics(nowMs: Long): String? {
        if (lastDiagnosticMs != Long.MIN_VALUE && nowMs - lastDiagnosticMs < 30_000L) return null
        lastDiagnosticMs = nowMs
        freshObservations(nowMs) // Count expiry once, including short gaps recovered before this report.
        val detail = NormalizedFieldCatalog.kpiFields.mapNotNull { field ->
            val observation = fields[field.fieldKey]
            val source = observation?.sourceStamp
            val reason = when {
                observation == null -> "not_observed"
                source == null || !isRecent(source.bootId, source.elapsedMs, nowMs) -> "source_age"
                observation.quality != NormalizedQuality.OK -> "quality_${observation.quality.name.lowercase()}"
                (expiriesSinceReport[field.fieldKey] ?: 0L) > 0L -> "recovered_after_source_age"
                else -> return@mapNotNull null
            }
            "field=${field.fieldKey} sources=${field.sourceKeys.joinToString(",")} reason=$reason " +
                "age_ms=${source?.let { (nowMs - it.elapsedMs).coerceAtLeast(0L) }} " +
                "last_rejection=${lastRejected[field.fieldKey]} expiries=${expiriesSinceReport[field.fieldKey] ?: 0L}"
        }.takeIf { it.isNotEmpty() }?.joinToString("; ")
        expiriesSinceReport.clear()
        return detail
    }

    fun clear() {
        fields.clear(); lastRejected.clear(); expiredKeys.clear(); expiriesSinceReport.clear()
        lastDiagnosticMs = Long.MIN_VALUE
    }
}
