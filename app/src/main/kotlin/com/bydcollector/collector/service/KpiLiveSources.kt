package com.bydcollector.collector.service

import com.bydcollector.collector.data.direct.DirectFidEntry
import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.direct.DirectHelperReadResult
import com.bydcollector.collector.data.direct.DirectKpiMailboxSnapshot
import com.bydcollector.collector.data.direct.DirectKpiRawValue
import com.bydcollector.collector.data.direct.DirectValueDecoders
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedSourceInput
import com.bydcollector.collector.data.normalized.NormalizedSourceKind
import com.bydcollector.collector.data.normalized.NormalizedSourceOrdering
import com.bydcollector.collector.data.normalized.NormalizedSourceOrder
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.polling.LivePollSource
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.TelemetryCallbackBatch

/** Catalog-bounded latest raw inputs for display-only live KPI derivation. */
internal class KpiLiveSourceBuffer(
    entries: List<DirectFidEntry> = DirectFidRegistry.entries,
    private val bootId: String = LivePollSource.liveBootId,
    private val maxAgeMs: Long = 3_000L,
    private val normalizer: VehicleStateNormalizer = VehicleStateNormalizer(NormalizedFieldCatalog.kpiFields)
) {
    private val sourceEntries = entries.filter { it.key in NormalizedFieldCatalog.kpiSourceKeys }
    private val entriesByAddress = sourceEntries.associateBy { Triple(it.tx, it.dev, it.fid) }
    private val entriesByKey = sourceEntries.associateBy { it.key }
    private val inputs = linkedMapOf<String, NormalizedSourceInput>()
    private val callbackWatermarks = mutableMapOf<String, Long>()
    private val callbackProven = mutableSetOf<String>()
    private var helperBootId: String? = null
    private var helperGeneration: String? = null
    private var listenerReady = false
    private var pollSequence = 0L
    private var transportEpoch = 0L

    init {
        require(sourceEntries.size == NormalizedFieldCatalog.kpiSourceKeys.size) {
            "KPI source catalog and direct FID catalog do not match"
        }
        require(maxAgeMs > 0L)
    }

    @Synchronized
    fun acceptMailbox(snapshot: DirectKpiMailboxSnapshot, nowElapsedMs: Long): KpiSourceUpdate {
        if (!snapshot.ok || snapshot.helperBootId.isNullOrBlank() || snapshot.helperGeneration.isNullOrBlank()) {
            return KpiSourceUpdate()
        }
        val reset = setHelperIdentity(snapshot.helperBootId, snapshot.helperGeneration)
        val wasListenerReady = listenerReady
        listenerReady = snapshot.listenerStatus == CollectorHelperProtocol.KPI_LISTENER_READY
        var newlyProven = false
        val changed = linkedSetOf<String>()
        snapshot.values.forEach { value ->
            val entry = entriesByAddress[Triple(value.tx, value.dev, value.fid)] ?: return@forEach
            val raw = validRaw(entry, value) ?: return@forEach
            val elapsed = value.observedElapsedMs
            if (value.observedWallMs < 0L || elapsed < 0L || elapsed > nowElapsedMs ||
                nowElapsedMs - elapsed >= maxAgeMs
            ) return@forEach
            newlyProven = callbackProven.add(entry.key) || newlyProven
            callbackWatermarks[entry.key] = maxOf(callbackWatermarks[entry.key] ?: Long.MIN_VALUE, elapsed)
            val stamp = NormalizedSourceStamp(
                kind = NormalizedSourceKind.CALLBACK,
                identity = "kpi:${component(snapshot.helperBootId)}:${component(snapshot.helperGeneration)}:${entry.key}:${value.sequence}",
                bootId = snapshot.helperBootId,
                generatorId = "kpi:${component(snapshot.helperGeneration)}:${entry.key}",
                sequence = value.sequence,
                wallMs = value.observedWallMs,
                elapsedMs = elapsed
            )
            if (apply(entry, raw, stamp, nowElapsedMs)) changed += entry.key
        }
        return KpiSourceUpdate(
            helperGenerationChanged = reset,
            observations = normalize(changed),
            cadenceChanged = reset || newlyProven || wasListenerReady != listenerReady
        )
    }

    @Synchronized
    fun acceptSubscription(boot: String?, generation: String?, listenerStatus: Int): KpiSourceUpdate {
        if (boot.isNullOrBlank() || generation.isNullOrBlank()) {
            return KpiSourceUpdate(cadenceChanged = setListenerStatus(listenerStatus))
        }
        val reset = setHelperIdentity(boot, generation)
        val wasReady = listenerReady
        listenerReady = listenerStatus == CollectorHelperProtocol.KPI_LISTENER_READY
        return KpiSourceUpdate(reset, cadenceChanged = reset || wasReady != listenerReady)
    }

    @Synchronized
    fun acceptPoll(
        results: List<Pair<DirectFidEntry, DirectHelperReadResult>>,
        requestWallMs: Long,
        requestElapsedMs: Long,
        nowElapsedMs: Long,
        pollIdentity: String,
        expectedTransportEpoch: Long? = null
    ): KpiSourceUpdate {
        if (expectedTransportEpoch != null && expectedTransportEpoch != transportEpoch) return KpiSourceUpdate()
        if (requestElapsedMs < 0L || requestElapsedMs > nowElapsedMs ||
            nowElapsedMs - requestElapsedMs >= maxAgeMs
        ) return KpiSourceUpdate()
        val changed = linkedSetOf<String>()
        val sequence = ++pollSequence
        results.forEach { (entry, result) ->
            if (entry.key !in entriesByKey || result.status != CollectorHelperProtocol.STATUS_OK ||
                result.callbackCached
            ) return@forEach
            val raw = result.raw ?: return@forEach
            val callbackAt = callbackWatermarks[entry.key]
            // A batch has no per-field start time. Same-millisecond callbacks are also kept
            // conservatively because elapsed timestamps have millisecond precision.
            if (callbackAt != null && callbackAt >= requestElapsedMs) return@forEach
            val stamp = NormalizedSourceStamp(
                kind = NormalizedSourceKind.POLL,
                identity = "kpi-poll:$pollIdentity:$sequence",
                bootId = bootId,
                generatorId = "kpi-poll:$pollIdentity",
                sequence = sequence,
                wallMs = requestWallMs,
                elapsedMs = requestElapsedMs
            )
            if (apply(entry, raw, stamp, nowElapsedMs)) changed += entry.key
        }
        return KpiSourceUpdate(observations = normalize(changed))
    }

    @Synchronized
    fun setListenerStatus(status: Int): Boolean {
        val ready = status == CollectorHelperProtocol.KPI_LISTENER_READY
        if (ready == listenerReady) return false
        listenerReady = ready
        return true
    }

    @Synchronized
    fun listenerReady(): Boolean = listenerReady

    @Synchronized
    fun callbackProven(sourceKey: String): Boolean = sourceKey in callbackProven

    @Synchronized
    fun resetTransport(): Boolean {
        val changed = helperBootId != null || helperGeneration != null || inputs.isNotEmpty() ||
            callbackProven.isNotEmpty() || listenerReady
        transportEpoch++
        helperBootId = null
        helperGeneration = null
        listenerReady = false
        inputs.clear()
        callbackWatermarks.clear()
        callbackProven.clear()
        return changed
    }

    @Synchronized
    fun sourceCount(): Int = inputs.size

    @Synchronized
    fun transportEpoch(): Long = transportEpoch

    private fun setHelperIdentity(boot: String, generation: String): Boolean {
        if (boot == helperBootId && generation == helperGeneration) return false
        transportEpoch++
        helperBootId = boot
        helperGeneration = generation
        listenerReady = false
        inputs.clear()
        callbackWatermarks.clear()
        callbackProven.clear()
        return true
    }

    private fun validRaw(entry: DirectFidEntry, value: DirectKpiRawValue): Int? {
        if (value.status != CollectorHelperProtocol.STATUS_OK || value.bytes != null) return null
        val typeMatches = when (entry.tx) {
            DirectFidRegistry.TX_GET_INT -> value.nativeType == TelemetryCallbackBatch.TYPE_INT
            DirectFidRegistry.TX_GET_FLOAT -> value.nativeType == TelemetryCallbackBatch.TYPE_FLOAT
            else -> false
        }
        val raw = value.rawBits?.takeIf { typeMatches } ?: return null
        if (entry.tx == DirectFidRegistry.TX_GET_FLOAT && Float.fromBits(raw) == 65_535.0f) return null
        return raw.takeIf { DirectValueDecoders.decode(entry, it) != null }
    }

    private fun apply(
        entry: DirectFidEntry,
        raw: Int,
        stamp: NormalizedSourceStamp,
        nowElapsedMs: Long
    ): Boolean {
        if (entry.key !in entriesByKey || stamp.bootId != bootId || stamp.elapsedMs < 0L ||
            stamp.elapsedMs > nowElapsedMs || nowElapsedMs - stamp.elapsedMs >= maxAgeMs
        ) return false
        val previous = inputs[entry.key]?.stamp
        if (previous != null) {
            when (NormalizedSourceOrdering.compare(stamp, previous, bootId)) {
                NormalizedSourceOrder.OLDER, NormalizedSourceOrder.EQUAL -> return false
                NormalizedSourceOrder.INCOMPARABLE -> if (stamp.elapsedMs <= previous.elapsedMs) return false
                NormalizedSourceOrder.NEWER -> Unit
            }
        }
        if (entry.tx == DirectFidRegistry.TX_GET_FLOAT && Float.fromBits(raw) == 65_535.0f) return false
        val decoded = DirectValueDecoders.decode(entry, raw) ?: return false
        inputs[entry.key] = NormalizedSourceInput(
            PollReading(entry.key, DirectValueDecoders.rawString(raw), decoded), stamp, null
        )
        check(inputs.size <= NormalizedFieldCatalog.kpiSourceKeys.size)
        return true
    }

    private fun normalize(changedKeys: Set<String>): List<NormalizedObservation> =
        if (changedKeys.isEmpty()) emptyList() else normalizer.normalizeSparse(inputs, changedKeys)

    private fun component(value: String): String = "${value.length}:$value"
}

internal data class KpiSourceUpdate(
    val helperGenerationChanged: Boolean = false,
    val observations: List<NormalizedObservation> = emptyList(),
    val cadenceChanged: Boolean = false
)

/** Poll cadence policy; timestamps are getter request starts, never batch completion times. */
internal class KpiPollCadence(
    private val normalIntervalMs: Long = 1_000L,
    private val visibleCallbackIntervalMs: Long = 2_000L,
    private val backgroundIntervalMs: Long = 300_000L
) {
    private val lastRequestStart = mutableMapOf<String, Long>()
    private val failureCounts = mutableMapOf<String, Int>()
    private val unsupportedUntilMs = mutableMapOf<String, Long>()
    private var helperIdentity: Any? = null

    fun due(
        entries: List<DirectFidEntry>,
        nowElapsedMs: Long,
        interactive: Boolean,
        visibleLive: Boolean,
        listenerReady: Boolean,
        callbackProven: (String) -> Boolean,
        forceSeed: Boolean = false
    ): List<DirectFidEntry> = entries.filter { entry ->
        forceSeed || remainingDelayMs(entry, nowElapsedMs, interactive, visibleLive, listenerReady, callbackProven) == 0L
    }

    fun markStarted(entries: List<DirectFidEntry>, elapsedMs: Long) {
        entries.forEach { entry ->
            lastRequestStart[entry.key] = elapsedMs
            failureCounts[entry.key] = ((failureCounts[entry.key] ?: 0) + 1).coerceAtMost(RETRY_DELAYS_MS.size)
        }
    }

    fun markResults(
        results: List<Pair<DirectFidEntry, DirectHelperReadResult>>,
        requestElapsedMs: Long,
        nowElapsedMs: Long,
        invalidSourceKeys: Set<String> = emptySet()
    ) {
        val ageMs = nowElapsedMs - requestElapsedMs
        results.forEach { (entry, result) ->
            if (result.status == CollectorHelperProtocol.STATUS_INVALID_REQUEST ||
                result.status == CollectorHelperProtocol.STATUS_NOT_WHITELISTED
            ) {
                unsupportedUntilMs[entry.key] = nowElapsedMs + backgroundIntervalMs
                return@forEach
            }

            val raw = result.raw ?: return@forEach
            val decodable = result.status == CollectorHelperProtocol.STATUS_OK && !result.callbackCached &&
                requestElapsedMs >= 0L && ageMs >= 0L && ageMs < SOURCE_MAX_AGE_MS && entry.key !in invalidSourceKeys &&
                !(entry.tx == DirectFidRegistry.TX_GET_FLOAT && Float.fromBits(raw) == 65_535.0f) &&
                DirectValueDecoders.decode(entry, raw) != null
            if (decodable) {
                failureCounts.remove(entry.key)
                unsupportedUntilMs.remove(entry.key)
            }
        }
    }

    fun nextDelayMs(
        entries: List<DirectFidEntry>,
        nowElapsedMs: Long,
        interactive: Boolean,
        visibleLive: Boolean,
        listenerReady: Boolean,
        callbackProven: (String) -> Boolean
    ): Long = entries.minOfOrNull { entry ->
        remainingDelayMs(entry, nowElapsedMs, interactive, visibleLive, listenerReady, callbackProven)
    } ?: normalIntervalMs

    fun observeHelper(identity: Any?): Boolean {
        if (identity == null || identity === helperIdentity) return false
        helperIdentity = identity
        clear()
        return true
    }

    fun clear() {
        lastRequestStart.clear()
        failureCounts.clear()
        unsupportedUntilMs.clear()
    }

    private fun remainingDelayMs(
        entry: DirectFidEntry,
        nowElapsedMs: Long,
        interactive: Boolean,
        visibleLive: Boolean,
        listenerReady: Boolean,
        callbackProven: (String) -> Boolean
    ): Long {
        unsupportedUntilMs[entry.key]?.let { until ->
            if (until > nowElapsedMs) return until - nowElapsedMs
        }
        failureCounts[entry.key]?.takeIf { it > 0 }?.let { count ->
            val interval = RETRY_DELAYS_MS[count - 1]
            val started = lastRequestStart[entry.key] ?: return 0L
            val age = nowElapsedMs - started
            return if (age < 0L) interval else (interval - age).coerceAtLeast(0L)
        }

        val interval = when {
            !interactive || !visibleLive -> backgroundIntervalMs
            listenerReady && callbackProven(entry.key) -> visibleCallbackIntervalMs
            else -> normalIntervalMs
        }
        val started = lastRequestStart[entry.key] ?: return 0L
        val age = nowElapsedMs - started
        return if (age < 0L) interval else (interval - age).coerceAtLeast(0L)
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 5_000L, 15_000L, 30_000L)
        const val SOURCE_MAX_AGE_MS = 3_000L
    }
}
