package com.bydcollector.collector.telegram

import kotlin.math.max

internal data class WeightedPowerMedian(val powerKw: Double, val coveredMs: Long)

/** Bounded source-time buffer for the exact trailing-window benchmark rule. */
internal class ChargingPowerWindow {
    private data class Sample(val elapsedMs: Long, val powerKw: Double?)

    private var session: ChargingEtaSessionId? = null
    private val samples = ArrayList<Sample>(MAX_SAMPLES)

    fun reset(session: ChargingEtaSessionId) {
        this.session = session
        samples.clear()
    }

    /** Null, zero, negative, and non-finite samples are retained as gap markers. */
    fun add(session: ChargingEtaSessionId, sourceElapsedMs: Long, powerKw: Double?): Boolean {
        if (this.session != session || sourceElapsedMs < 0L) return false
        val last = samples.lastOrNull()
        if (last != null && sourceElapsedMs <= last.elapsedMs) return false
        val validPower = powerKw?.takeIf { it.isFinite() && it > 0.0 }
        samples += Sample(sourceElapsedMs, validPower)

        val cutoff = sourceElapsedMs - RETAIN_MS
        while (samples.size > 1 && (samples.size > MAX_SAMPLES || samples[1].elapsedMs < cutoff)) {
            samples.removeAt(0)
        }
        return true
    }

    fun weightedMedianKw(
        session: ChargingEtaSessionId,
        asOfElapsedMs: Long,
        actualNowElapsedMs: Long
    ): WeightedPowerMedian? {
        if (this.session != session || asOfElapsedMs < 0L || actualNowElapsedMs < asOfElapsedMs) return null
        val actualCurrent = samples.lastOrNull { it.elapsedMs <= actualNowElapsedMs } ?: return null
        if (actualNowElapsedMs - actualCurrent.elapsedMs > MAX_PAIR_MS) return null
        val endIndex = samples.indexOfLast { it.elapsedMs <= asOfElapsedMs }
        if (endIndex < 1) return null
        val windowEnd = samples[endIndex].elapsedMs
        // Keep the event-time endpoint fresh without extending it to the later processing time.
        if (asOfElapsedMs - windowEnd > MAX_PAIR_MS) return null
        val windowStart = windowEnd - WINDOW_MS
        val weighted = ArrayList<Pair<Double, Long>>()
        var covered = 0L
        for (index in 0 until endIndex) {
            val first = samples[index]
            val second = samples[index + 1]
            val pairMs = second.elapsedMs - first.elapsedMs
            if (pairMs <= 0L || pairMs > MAX_PAIR_MS) continue
            val weightMs = second.elapsedMs - max(first.elapsedMs, windowStart)
            if (weightMs <= 0L) continue
            val firstPower = first.powerKw ?: continue
            val secondPower = second.powerKw ?: continue
            weighted += ((firstPower + secondPower) / 2.0) to weightMs
            covered += weightMs
        }
        if (covered < MIN_COVERAGE_MS || weighted.isEmpty()) return null

        weighted.sortBy { it.first }
        val medianThreshold = covered / 2.0
        var cumulative = 0L
        for ((powerKw, weightMs) in weighted) {
            cumulative += weightMs
            if (cumulative >= medianThreshold) return WeightedPowerMedian(powerKw, covered)
        }
        return null
    }

    companion object {
        const val WINDOW_MS = 60_000L
        const val MIN_COVERAGE_MS = 59_999L
        const val MAX_PAIR_MS = 5_000L
        private const val RETAIN_MS = WINDOW_MS + 120_000L + MAX_PAIR_MS
        private const val MAX_SAMPLES = 4_096
    }
}

internal data class StableOemEta(
    val seconds: Double,
    val sourceWallMs: Long,
    val sourceElapsedMs: Long
)

/** Causal 5-second-grid OEM stabilizer with source and evaluation-age checks. */
internal class OemEtaStabilizer {
    private data class Observation(
        val evaluatedElapsedMs: Long,
        val minuteSourceElapsedMs: Long,
        val cycleSourceElapsedMs: Long,
        val sourceWallMs: Long,
        val seconds: Double?
    )

    private var session: ChargingEtaSessionId? = null
    private val observations = ArrayList<Observation>(MAX_OBSERVATIONS)

    fun reset(session: ChargingEtaSessionId) {
        this.session = session
        observations.clear()
    }

    /**
     * Invalid/sentinel readings are accepted as explicit invalidations so a
     * previous valid value cannot silently survive a reported error.
     */
    fun observe(
        session: ChargingEtaSessionId,
        evaluatedElapsedMs: Long,
        minuteSourceElapsedMs: Long,
        cycleSourceElapsedMs: Long,
        sourceWallMs: Long,
        hours: Int,
        minutes: Int
    ): Boolean {
        if (this.session != session || evaluatedElapsedMs < 0L || minuteSourceElapsedMs < 0L ||
            cycleSourceElapsedMs < 0L || sourceWallMs <= 0L
        ) return false
        if (minuteSourceElapsedMs > evaluatedElapsedMs || cycleSourceElapsedMs > evaluatedElapsedMs) return false
        val last = observations.lastOrNull()
        if (last != null && evaluatedElapsedMs <= last.evaluatedElapsedMs) return false
        if (last != null && (minuteSourceElapsedMs < last.minuteSourceElapsedMs || cycleSourceElapsedMs < last.cycleSourceElapsedMs)) return false

        val totalMinutes = if (hours in 0..254 && minutes in 0..59 && (hours != 0 || minutes != 0)) {
            hours.toLong() * 60L + minutes
        } else {
            null
        }
        observations += Observation(
            evaluatedElapsedMs = evaluatedElapsedMs,
            minuteSourceElapsedMs = minuteSourceElapsedMs,
            cycleSourceElapsedMs = cycleSourceElapsedMs,
            sourceWallMs = sourceWallMs,
            seconds = totalMinutes?.times(60L)?.toDouble()
        )
        val cutoff = evaluatedElapsedMs - HISTORY_MS
        while (observations.size > 1 && (observations.size > MAX_OBSERVATIONS || observations[1].evaluatedElapsedMs < cutoff)) {
            observations.removeAt(0)
        }
        return true
    }

    fun stable(
        session: ChargingEtaSessionId,
        asOfElapsedMs: Long,
        actualNowElapsedMs: Long
    ): StableOemEta? {
        if (this.session != session || asOfElapsedMs < OEM_STABILITY_MS || actualNowElapsedMs < asOfElapsedMs) return null
        val actualCurrent = observations.lastOrNull { it.evaluatedElapsedMs <= actualNowElapsedMs } ?: return null
        if (actualNowElapsedMs - actualCurrent.minuteSourceElapsedMs > MAX_SOURCE_AGE_MS ||
            actualNowElapsedMs - actualCurrent.cycleSourceElapsedMs > MAX_SOURCE_AGE_MS
        ) return null
        val finishes = ArrayList<Double>(GRID_SAMPLES)
        var lastValue: Observation? = null
        var gridTime = asOfElapsedMs - OEM_STABILITY_MS
        while (gridTime <= asOfElapsedMs) {
            val sample = observations.lastOrNull { it.evaluatedElapsedMs <= gridTime } ?: return null
            if (gridTime - sample.evaluatedElapsedMs > GRID_MS) return null
            if (sample.seconds == null || sample.minuteSourceElapsedMs > gridTime || sample.cycleSourceElapsedMs > gridTime) return null
            if (gridTime - sample.minuteSourceElapsedMs > MAX_SOURCE_AGE_MS ||
                gridTime - sample.cycleSourceElapsedMs > MAX_SOURCE_AGE_MS
            ) return null
            finishes += gridTime / 1_000.0 + sample.seconds
            lastValue = sample
            gridTime += GRID_MS
        }
        if (finishes.size != GRID_SAMPLES || finishes.maxOrNull()!! - finishes.minOrNull()!! > MAX_FINISH_SPREAD_SECONDS) {
            return null
        }
        val current = lastValue ?: return null
        return StableOemEta(
            seconds = current.seconds ?: return null,
            sourceWallMs = current.sourceWallMs,
            sourceElapsedMs = current.minuteSourceElapsedMs
        )
    }

    companion object {
        private const val OEM_STABILITY_MS = 60_000L
        private const val GRID_MS = 5_000L
        private const val GRID_SAMPLES = 13
        private const val MAX_SOURCE_AGE_MS = 120_000L
        private const val MAX_FINISH_SPREAD_SECONDS = 120.0
        private const val HISTORY_MS = OEM_STABILITY_MS + MAX_SOURCE_AGE_MS + GRID_MS
        private const val MAX_OBSERVATIONS = 8_192
    }
}
