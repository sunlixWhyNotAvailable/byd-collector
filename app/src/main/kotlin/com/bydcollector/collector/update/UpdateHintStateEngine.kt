package com.bydcollector.collector.update

internal class UpdateHintStateEngine {
    private val latest = linkedMapOf<String, UpdateHintRecord>()
    private val records = linkedMapOf<String, UpdateHintRecord>()
    private val retiredSessions = mutableMapOf<String, MutableSet<String>>()
    private val retiredEvents = mutableMapOf<Pair<String, String>, MutableSet<String>>()

    fun accept(authenticatedOwner: String, record: UpdateHintRecord): Boolean {
        if (authenticatedOwner != record.ownerPackage || !record.isValid()) return false
        if (record.processSessionId in retiredSessions.getOrPut(record.ownerPackage) { mutableSetOf() }) {
            return false
        }
        val current = latest[record.ownerPackage]
        if (current != null) {
            if (record.processSessionId == current.processSessionId) {
                if (record.revision <= current.revision) return false
                if (record.phase != UpdateHintPhase.NONE && record.eventId in
                    retiredEvents[record.ownerPackage to record.processSessionId].orEmpty()) return false
                if (current.phase != UpdateHintPhase.NONE && record.phase != UpdateHintPhase.NONE &&
                    current.eventId == record.eventId &&
                    current.requestedAtElapsedNanos != record.requestedAtElapsedNanos) return false
                if (current.phase == UpdateHintPhase.VISIBLE && record.phase == UpdateHintPhase.VISIBLE &&
                    current.eventId == record.eventId &&
                    current.expiresAtElapsedMs != record.expiresAtElapsedMs) return false
                if (current.phase == UpdateHintPhase.VISIBLE && record.phase == UpdateHintPhase.PENDING &&
                    current.eventId == record.eventId) return false
            } else {
                retireSession(current.ownerPackage, current.processSessionId)
            }
        }
        latest[record.ownerPackage] = record
        if (record.phase == UpdateHintPhase.NONE) {
            val priorEvent = current?.takeIf {
                it.processSessionId == record.processSessionId
            }?.eventId.orEmpty()
            retiredEvents.getOrPut(record.ownerPackage to record.processSessionId) { mutableSetOf() }
                .addAll(listOf(record.eventId, priorEvent).filter { it.isNotBlank() })
            records.remove(record.ownerPackage)
        } else {
            records[record.ownerPackage] = record
        }
        return true
    }

    fun confirmedDeath(ownerPackage: String, processSessionId: String): Boolean {
        val current = latest[ownerPackage] ?: return false
        if (current.processSessionId != processSessionId) return false
        retireSession(ownerPackage, processSessionId)
        latest.remove(ownerPackage)
        records.remove(ownerPackage)
        return true
    }

    fun active(nowElapsedMs: Long, nowElapsedNanos: Long): List<UpdateHintRecord> {
        records.entries.removeAll { !it.value.isActive(nowElapsedMs, nowElapsedNanos) }
        return records.values.sortedWith(
            compareBy<UpdateHintRecord> { it.requestedAtElapsedNanos }
                .thenBy { UpdateHintProtocol.OWNERS.indexOf(it.ownerPackage) }
        )
    }

    fun current(ownerPackage: String): UpdateHintRecord? = latest[ownerPackage]

    private fun retireSession(ownerPackage: String, processSessionId: String) {
        retiredSessions.getOrPut(ownerPackage) { mutableSetOf() }.add(processSessionId)
        retiredEvents.remove(ownerPackage to processSessionId)
    }
}
