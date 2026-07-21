package com.bydcollector.collector.maintenance

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveShareLeaseRegistryTest {
    @Test
    fun leasesCanBeReleasedAndExpireLazilyAfterTenMinutes() {
        var nowMs = 1_000L
        val registry = ArchiveShareLeaseRegistry { nowMs }

        val lease = registry.acquire(listOf("first.zip", "second.zip"))
        assertTrue(registry.isActive("first.zip"))
        assertTrue(registry.isActive("second.zip"))

        registry.forceRelease(listOf("first.zip"))
        assertFalse(registry.isActive("first.zip"))

        nowMs += ArchiveShareLeaseRegistry.LEASE_TTL_MS - 1L
        assertTrue(registry.isActive("second.zip"))
        nowMs += 1L
        assertFalse(registry.isActive("second.zip"))
        registry.release(lease)
    }

    @Test
    fun releasingOneOwnerDoesNotCancelAnotherChooserLease() {
        val registry = ArchiveShareLeaseRegistry { 1_000L }
        val first = registry.acquire(listOf("shared.zip"))
        val second = registry.acquire(listOf("shared.zip"))

        registry.release(second)
        assertTrue(registry.isActive("shared.zip"))

        registry.release(first)
        assertFalse(registry.isActive("shared.zip"))
    }
}
