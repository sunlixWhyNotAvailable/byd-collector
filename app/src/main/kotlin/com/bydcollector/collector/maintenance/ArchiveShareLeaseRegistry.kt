package com.bydcollector.collector.maintenance

class ArchiveShareLeaseRegistry(
    private val elapsedRealtimeMs: () -> Long
) {
    class Lease internal constructor(
        internal val ownerId: Long,
        val archiveIds: Set<String>
    )

    private data class OwnerLease(val ownerId: Long, val expiresAtMs: Long)

    private val leasesByArchiveId = mutableMapOf<String, MutableList<OwnerLease>>()
    private var nextOwnerId = 1L

    @Synchronized
    fun acquire(archiveIds: Collection<String>): Lease {
        val nowMs = elapsedRealtimeMs()
        removeExpired(nowMs)
        val ownerId = nextOwnerId++
        val expiresAtMs = nowMs + LEASE_TTL_MS
        val uniqueIds = archiveIds.toSet()
        uniqueIds.forEach { archiveId ->
            leasesByArchiveId.getOrPut(archiveId, ::mutableListOf)
                .add(OwnerLease(ownerId, expiresAtMs))
        }
        return Lease(ownerId, uniqueIds)
    }

    @Synchronized
    fun release(lease: Lease) {
        removeExpired(elapsedRealtimeMs())
        lease.archiveIds.forEach { archiveId ->
            leasesByArchiveId[archiveId]?.removeAll { it.ownerId == lease.ownerId }
            if (leasesByArchiveId[archiveId].isNullOrEmpty()) leasesByArchiveId.remove(archiveId)
        }
    }

    @Synchronized
    fun forceRelease(archiveIds: Collection<String>) {
        removeExpired(elapsedRealtimeMs())
        archiveIds.forEach(leasesByArchiveId::remove)
    }

    @Synchronized
    fun isActive(archiveId: String): Boolean {
        removeExpired(elapsedRealtimeMs())
        return archiveId in leasesByArchiveId
    }

    private fun removeExpired(nowMs: Long) {
        leasesByArchiveId.values.forEach { leases -> leases.removeAll { it.expiresAtMs <= nowMs } }
        leasesByArchiveId.entries.removeAll { it.value.isEmpty() }
    }

    companion object {
        const val LEASE_TTL_MS = 10 * 60_000L
    }
}
