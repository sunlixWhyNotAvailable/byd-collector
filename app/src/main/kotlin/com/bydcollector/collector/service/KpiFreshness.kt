package com.bydcollector.collector.service

import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedSourceOrder
import com.bydcollector.collector.data.normalized.NormalizedSourceOrdering

/** Indefinite held KPI state; only a confirmed loss/reset removes an observation. */
internal class KpiFreshness(private val bootId: String) {
    private val fields = linkedMapOf<String, NormalizedObservation>()
    private val kpiKeys = NormalizedFieldCatalog.kpiKeys

    fun accept(observations: List<NormalizedObservation>, nowElapsedMs: Long): Boolean {
        var semanticChange = false
        observations.forEach { observation ->
            val key = observation.field.fieldKey
            if (key !in kpiKeys) return@forEach
            val source = observation.sourceStamp ?: return@forEach
            if (source.bootId != bootId || source.elapsedMs < 0L || source.elapsedMs > nowElapsedMs) return@forEach
            val previous = fields[key]
            if (previous != null) {
                val previousSource = previous.sourceStamp ?: return@forEach
                when (NormalizedSourceOrdering.compare(source, previousSource, bootId)) {
                    NormalizedSourceOrder.OLDER, NormalizedSourceOrder.EQUAL -> return@forEach
                    NormalizedSourceOrder.INCOMPARABLE -> if (source.elapsedMs <= previousSource.elapsedMs) return@forEach
                    NormalizedSourceOrder.NEWER -> Unit
                }
            }
            fields[key] = observation
            if (previous == null || previous.value != observation.value || previous.quality != observation.quality) {
                semanticChange = true
            }
        }
        return semanticChange
    }

    /** Drops only values whose input lost validity; unrelated normalized fields remain held. */
    fun clear(keys: Set<String>): Boolean {
        var changed = false
        keys.forEach { key ->
            if (key in kpiKeys && fields.remove(key) != null) changed = true
        }
        return changed
    }

    /** Applies a coalesced per-field state; null explicitly means invalidated. */
    fun apply(changes: Map<String, NormalizedObservation?>, nowElapsedMs: Long): Boolean {
        val before = semanticState()
        changes.forEach { (key, observation) ->
            if (key !in kpiKeys) return@forEach
            if (observation == null) fields.remove(key) else accept(listOf(observation), nowElapsedMs)
        }
        return before != semanticState()
    }

    fun observations(): List<NormalizedObservation> = fields.values.toList()

    fun clear() {
        fields.clear()
    }

    private fun semanticState(): Map<String, Pair<Any, NormalizedQuality>> = fields.mapValues { (_, observation) ->
        observation.value to observation.quality
    }
}
