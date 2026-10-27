package com.bydcollector.collector.service

import com.bydcollector.collector.data.direct.DirectFidEntry
import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.direct.DirectKpiMailboxSnapshot
import com.bydcollector.collector.data.direct.DirectKpiRawValue
import com.bydcollector.collector.data.direct.DirectValueDecoders
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedSourceInput
import com.bydcollector.collector.data.normalized.NormalizedSourceKind
import com.bydcollector.collector.data.normalized.NormalizedSourceStamp
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.polling.LivePollSource
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.TelemetryCallbackBatch

/** Latest independently acquired KPI inputs. Source freshness ends only on explicit loss or reset. */
internal class KpiLiveSourceBuffer(
    entries: List<DirectFidEntry> = DirectFidRegistry.entries,
    private val bootId: String = LivePollSource.liveBootId,
    private val normalizer: VehicleStateNormalizer = VehicleStateNormalizer(NormalizedFieldCatalog.kpiFields)
) {
    private val sourceEntries = entries.filter { it.key in NormalizedFieldCatalog.kpiSourceKeys }
    private val powerEntry = entries.firstOrNull { it.key == POWER_KEY }
    private val entriesByAddress = (sourceEntries + listOfNotNull(powerEntry))
        .associateBy { Triple(it.tx, it.dev, it.fid) }
    private val inputs = linkedMapOf<String, NormalizedSourceInput>()
    private val lastRecords = mutableMapOf<String, RecordVersion>()
    private var helperBootId: String? = null
    private var helperGeneration: String? = null
    private var generationLost = false

    init {
        require(sourceEntries.size == NormalizedFieldCatalog.kpiSourceKeys.size) {
            "KPI source catalog and direct FID catalog do not match"
        }
        require(powerEntry != null) { "KPI power source is missing from the direct FID catalog" }
    }

    @Synchronized
    fun acceptSubscription(boot: String?, generation: String?, listenerStatus: Int): KpiSourceUpdate {
        if (boot.isNullOrBlank() || generation.isNullOrBlank()) return KpiSourceUpdate()
        val reset = setHelperIdentity(boot, generation)
        return if (reset) KpiSourceUpdate(
            helperGenerationChanged = true,
            invalidatedFieldKeys = NormalizedFieldCatalog.kpiKeys,
            powerStateChanged = true
        ) else KpiSourceUpdate()
    }

    @Synchronized
    fun acceptMailbox(snapshot: DirectKpiMailboxSnapshot, nowElapsedMs: Long): KpiSourceUpdate {
        if (!snapshot.ok || snapshot.helperBootId.isNullOrBlank() || snapshot.helperGeneration.isNullOrBlank()) {
            return KpiSourceUpdate()
        }
        val reset = setHelperIdentity(snapshot.helperBootId, snapshot.helperGeneration)
        if (generationLost) return KpiSourceUpdate(
            helperGenerationChanged = reset,
            invalidatedFieldKeys = if (reset) NormalizedFieldCatalog.kpiKeys else emptySet(),
            powerStateChanged = reset
        )

        val changedSourceKeys = linkedSetOf<String>()
        val invalidatedSourceKeys = linkedSetOf<String>()
        val losses = mutableListOf<KpiSourceLoss>()
        var powerStateChanged = reset
        var powerState: KpiPowerSourceState? = null
        snapshot.values.forEach { value ->
            val entry = entriesByAddress[Triple(value.tx, value.dev, value.fid)] ?: return@forEach
            if (value.sourceKind !in SOURCE_CALLBACK..SOURCE_LOST) return@forEach
            if (value.sourceEpoch < 0L || value.sourceRecordSequence <= 0L) return@forEach
            val previous = lastRecords[entry.key]
            if (previous != null && (value.sourceEpoch < previous.sourceEpoch ||
                    value.sourceRecordSequence <= previous.sourceRecordSequence)
            ) return@forEach

            val epochChanged = previous != null && value.sourceEpoch > previous.sourceEpoch
            if (epochChanged) {
                if (entry.key == POWER_KEY) {
                    powerStateChanged = true
                    powerState = null
                } else {
                    inputs.remove(entry.key)
                    invalidatedSourceKeys += entry.key
                }
            }
            lastRecords[entry.key] = RecordVersion(value.sourceEpoch, value.sourceRecordSequence)

            val stamp = sourceStamp(snapshot.helperBootId, snapshot.helperGeneration, entry, value)
            val validClocks = hasUsableClocks(value, nowElapsedMs)
            val raw = if (value.sourceKind == SOURCE_CALLBACK || value.sourceKind == SOURCE_GET) {
                validRaw(entry, value, validClocks)
            } else null
            if (value.sourceKind == SOURCE_LOST || raw == null || stamp == null) {
                val reason = value.lossReason ?: when {
                    !validClocks -> "invalid_source_clock"
                    raw == null && value.sourceKind != SOURCE_LOST -> "invalid_kpi_source_value"
                    else -> "source_lost"
                }
                val diagnostic = value.diagnostic ?: value.quality
                losses += value.toSourceLoss(entry.key, snapshot.helperBootId, snapshot.helperGeneration,
                    reason, diagnostic)
                if (entry.key == POWER_KEY) {
                    powerStateChanged = true
                    powerState = value.toPowerState(snapshot.helperBootId, snapshot.helperGeneration, level = null)
                } else {
                    inputs.remove(entry.key)
                    invalidatedSourceKeys += entry.key
                }
                return@forEach
            }

            if (entry.key == POWER_KEY) {
                powerStateChanged = true
                powerState = value.toPowerState(snapshot.helperBootId, snapshot.helperGeneration, raw)
            } else {
                val decoded = DirectValueDecoders.decode(entry, raw) ?: run {
                    losses += value.toSourceLoss(entry.key, snapshot.helperBootId, snapshot.helperGeneration,
                        value.lossReason ?: "invalid_kpi_source_value", value.diagnostic ?: "direct value decoder rejected KPI raw value")
                    inputs.remove(entry.key)
                    invalidatedSourceKeys += entry.key
                    return@forEach
                }
                inputs[entry.key] = NormalizedSourceInput(
                    PollReading(entry.key, DirectValueDecoders.rawString(raw), decoded), stamp, null
                )
                changedSourceKeys += entry.key
            }
        }

        val affectedSourceKeys = changedSourceKeys + invalidatedSourceKeys
        val observations = normalizer.normalizeSparse(inputs, affectedSourceKeys)
        val invalidatedFields = linkedSetOf<String>()
        if (reset) invalidatedFields += NormalizedFieldCatalog.kpiKeys
        invalidatedSourceKeys.forEach { sourceKey ->
            NormalizedFieldCatalog.kpiFields.asSequence()
                .filter { sourceKey in it.sourceKeys }
                .mapTo(invalidatedFields) { it.fieldKey }
        }
        return KpiSourceUpdate(
            helperGenerationChanged = reset,
            observations = observations,
            invalidatedFieldKeys = invalidatedFields,
            powerStateChanged = powerStateChanged,
            powerState = powerState,
            sourceLosses = losses
        )
    }

    /** Only a confirmed Binder death invalidates the named helper generation. */
    @Synchronized
    fun confirmedHelperDeath(
        boot: String,
        generation: String,
        reason: String,
        diagnostic: String?,
        observedWallMs: Long,
        observedElapsedMs: Long
    ): KpiSourceUpdate {
        if (boot != helperBootId || generation != helperGeneration || generationLost) return KpiSourceUpdate()
        generationLost = true
        inputs.clear()
        lastRecords.clear()
        return KpiSourceUpdate(
            invalidatedFieldKeys = NormalizedFieldCatalog.kpiKeys,
            powerStateChanged = true,
            sourceLosses = listOf(KpiSourceLoss(
                key = null,
                helperBootId = boot,
                helperGeneration = generation,
                sourceKind = null,
                sourceEpoch = null,
                sourceRecordSequence = null,
                observedWallMs = observedWallMs,
                observedElapsedMs = observedElapsedMs,
                quality = "source_lost",
                reason = reason,
                diagnostic = diagnostic
            ))
        )
    }

    @Synchronized
    fun sourceCount(): Int = inputs.size

    @Synchronized
    fun helperGeneration(): String? = helperGeneration

    private fun setHelperIdentity(boot: String, generation: String): Boolean {
        if (boot == helperBootId && generation == helperGeneration) return false
        helperBootId = boot
        helperGeneration = generation
        generationLost = false
        inputs.clear()
        lastRecords.clear()
        return true
    }

    private fun hasUsableClocks(value: DirectKpiRawValue, nowElapsedMs: Long): Boolean {
        if (value.observedWallMs < 0L || value.observedElapsedMs < 0L || value.observedElapsedMs > nowElapsedMs) {
            return false
        }
        if (value.sourceKind == SOURCE_GET) {
            val startWallMs = value.sourceStartWallMs ?: return false
            val startElapsedMs = value.sourceStartElapsedMs ?: return false
            if (value.sourceIdentity.isNullOrBlank() || startWallMs < 0L || startElapsedMs < 0L ||
                startElapsedMs > value.observedElapsedMs
            ) return false
        }
        return true
    }

    private fun validRaw(entry: DirectFidEntry, value: DirectKpiRawValue, validClocks: Boolean): Int? {
        if (!validClocks || value.status != CollectorHelperProtocol.STATUS_OK || value.bytes != null) return null
        val expectedQuality = when (value.sourceKind) {
            SOURCE_CALLBACK -> QUALITY_CALLBACK_USABLE
            SOURCE_GET -> QUALITY_GET_USABLE
            else -> return null
        }
        if (value.quality != expectedQuality) return null
        val typeMatches = when (entry.tx) {
            DirectFidRegistry.TX_GET_INT -> value.nativeType == TelemetryCallbackBatch.TYPE_INT
            DirectFidRegistry.TX_GET_FLOAT -> value.nativeType == TelemetryCallbackBatch.TYPE_FLOAT
            else -> false
        }
        val raw = value.rawBits?.takeIf { typeMatches } ?: return null
        if (entry.tx == DirectFidRegistry.TX_GET_FLOAT && Float.fromBits(raw) == 65_535.0f) return null
        if (entry.key == POWER_KEY && (raw < 0 || raw == 65_535)) return null
        return raw.takeIf { DirectValueDecoders.decode(entry, it) != null }
    }

    private fun sourceStamp(
        boot: String,
        generation: String,
        entry: DirectFidEntry,
        value: DirectKpiRawValue
    ): NormalizedSourceStamp? {
        val kind = when (value.sourceKind) {
            SOURCE_CALLBACK -> NormalizedSourceKind.CALLBACK
            SOURCE_GET -> NormalizedSourceKind.POLL
            else -> return null
        }
        val identity = when (value.sourceKind) {
            SOURCE_CALLBACK -> "kpi-callback:${component(generation)}:${entry.key}:${value.sourceEpoch}:${value.sourceRecordSequence}"
            SOURCE_GET -> "kpi-get:${component(checkNotNull(value.sourceIdentity))}:${value.sourceEpoch}:${value.sourceRecordSequence}"
            else -> return null
        }
        return NormalizedSourceStamp(
            kind = kind,
            identity = identity,
            bootId = boot,
            generatorId = "kpi-source:${component(generation)}:${entry.key}:${value.sourceEpoch}",
            sequence = value.sourceRecordSequence,
            wallMs = value.observedWallMs,
            elapsedMs = value.observedElapsedMs,
            sourceEpoch = value.sourceEpoch
        )
    }

    private fun component(value: String): String = "${value.length}:$value"

    private fun DirectKpiRawValue.toSourceLoss(
        key: String,
        boot: String,
        generation: String,
        reason: String,
        diagnostic: String?
    ) = KpiSourceLoss(
        key = key,
        helperBootId = boot,
        helperGeneration = generation,
        sourceKind = sourceKind,
        sourceEpoch = sourceEpoch,
        sourceRecordSequence = sourceRecordSequence,
        observedWallMs = observedWallMs,
        observedElapsedMs = observedElapsedMs,
        quality = quality,
        reason = reason,
        diagnostic = diagnostic
    )

    private fun DirectKpiRawValue.toPowerState(
        boot: String,
        generation: String,
        level: Int? = null
    ) = KpiPowerSourceState(
        level = level,
        helperBootId = boot,
        helperGeneration = generation,
        sourceKind = sourceKind,
        sourceEpoch = sourceEpoch,
        sourceRecordSequence = sourceRecordSequence,
        sourceIdentity = sourceIdentity,
        sourceStartWallMs = sourceStartWallMs,
        sourceStartElapsedMs = sourceStartElapsedMs,
        observedWallMs = observedWallMs,
        observedElapsedMs = observedElapsedMs,
        quality = quality,
        lossReason = lossReason,
        diagnostic = diagnostic
    )

    private data class RecordVersion(val sourceEpoch: Long, val sourceRecordSequence: Long)

    companion object {
        const val SOURCE_CALLBACK = 1
        const val SOURCE_GET = 2
        const val SOURCE_LOST = 3
        const val POWER_KEY = "bodywork_power_level"
        private const val QUALITY_CALLBACK_USABLE = "callback_usable"
        private const val QUALITY_GET_USABLE = "get_usable"
    }
}

internal data class KpiSourceUpdate(
    val helperGenerationChanged: Boolean = false,
    val observations: List<NormalizedObservation> = emptyList(),
    /** Clear affected normalized fields before applying observations from this same snapshot. */
    val invalidatedFieldKeys: Set<String> = emptySet(),
    val powerStateChanged: Boolean = false,
    val powerState: KpiPowerSourceState? = null,
    val sourceLosses: List<KpiSourceLoss> = emptyList()
)

internal data class KpiPowerSourceState(
    val level: Int?,
    val helperBootId: String,
    val helperGeneration: String,
    val sourceKind: Int,
    val sourceEpoch: Long,
    val sourceRecordSequence: Long,
    val sourceIdentity: String?,
    val sourceStartWallMs: Long?,
    val sourceStartElapsedMs: Long?,
    val observedWallMs: Long,
    val observedElapsedMs: Long,
    val quality: String?,
    val lossReason: String?,
    val diagnostic: String?
)

/** A helper death has APP observation clocks and deliberately has no helper record sequence. */
internal data class KpiSourceLoss(
    val key: String?,
    val helperBootId: String,
    val helperGeneration: String,
    val sourceKind: Int?,
    val sourceEpoch: Long?,
    val sourceRecordSequence: Long?,
    val observedWallMs: Long,
    val observedElapsedMs: Long,
    val quality: String?,
    val reason: String,
    val diagnostic: String?
)
