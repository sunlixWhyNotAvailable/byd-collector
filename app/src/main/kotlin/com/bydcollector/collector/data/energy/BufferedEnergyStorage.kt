package com.bydcollector.collector.data.energy

/** A page computes against its own checkpoint; its owner commits the final row atomically with Trips. */
internal class BufferedEnergyStorage(initial: EnergyRuntimeRow?) : EnergyRuntimeStorage {
    private var row = initial
    private val quarantined = mutableListOf<Triple<EnergyRuntimeRow, String, String>>()
    override fun readEnergyRuntimeRow(): EnergyRuntimeRow? = row
    override fun commitEnergyRuntimeRow(stateJson: String, pendingProjectionJson: String?, updatedAt: String) {
        row = EnergyRuntimeRow(stateJson, pendingProjectionJson, updatedAt)
    }
    override fun clearEnergyPending(expectedPendingJson: String, updatedAt: String): Boolean {
        val current = row ?: return false
        if (current.pendingProjectionJson != expectedPendingJson) return false
        row = current.copy(pendingProjectionJson = null, updatedAt = updatedAt)
        return true
    }
    override fun quarantineEnergyRuntimeRow(row: EnergyRuntimeRow, reason: String, updatedAt: String) {
        quarantined += Triple(row, reason, updatedAt)
    }
    fun commitTo(storage: EnergyRuntimeStorage) {
        quarantined.forEach { (row, reason, at) -> storage.quarantineEnergyRuntimeRow(row, reason, at) }
        row?.let { storage.commitEnergyRuntimeRow(it.stateJson, it.pendingProjectionJson, it.updatedAt) }
    }
}
