package com.bydcollector.collector.data.energy

/** A page computes locally; its owner commits every touched session atomically with Trips. */
internal class BufferedEnergyStorage(initial: EnergyRuntimeRow?) : EnergyRuntimeStorage {
    private var row = initial
    private val sessionRows = linkedMapOf<String, EnergyRuntimeRow>()
    private val quarantined = mutableListOf<Triple<EnergyRuntimeRow, String, String>>()
    override fun readEnergyRuntimeRow(): EnergyRuntimeRow? = row
    override fun commitEnergyRuntimeRow(stateJson: String, pendingProjectionJson: String?, updatedAt: String) {
        val next = EnergyRuntimeRow(stateJson, pendingProjectionJson, updatedAt)
        row = next
        EnergyStateCodec.decodeState(stateJson).powerSessionId?.let { sessionRows[it] = next }
    }
    override fun clearEnergyPending(expectedPendingJson: String, updatedAt: String): Boolean {
        val current = row ?: return false
        if (current.pendingProjectionJson != expectedPendingJson) return false
        row = current.copy(pendingProjectionJson = null, updatedAt = updatedAt)
        EnergyStateCodec.decodeState(current.stateJson).powerSessionId?.let { sessionRows[it] = row!! }
        return true
    }
    override fun quarantineEnergyRuntimeRow(row: EnergyRuntimeRow, reason: String, updatedAt: String) {
        quarantined += Triple(row, reason, updatedAt)
    }
    fun commitTo(storage: EnergyRuntimeStorage) {
        quarantined.forEach { (row, reason, at) -> storage.quarantineEnergyRuntimeRow(row, reason, at) }
        // All writes stay inside the owner's page transaction, including sessions closed before its last input.
        sessionRows.values.filter { it != row }.forEach {
            storage.commitEnergyRuntimeRow(it.stateJson, it.pendingProjectionJson, it.updatedAt)
        }
        row?.let { storage.commitEnergyRuntimeRow(it.stateJson, it.pendingProjectionJson, it.updatedAt) }
    }
}
