package com.bydcollector.collector.util

import kotlin.test.Test
import kotlin.test.assertEquals

class TimedCacheTest {
    @Test
    fun reusesValueInsideTtlAndReloadsAfterExpiry() {
        val cache = TimedCache<String>(ttlMs = 30_000)
        var loads = 0

        assertEquals("v1", cache.get(nowMs = 1_000) { loads += 1; "v$loads" })
        assertEquals("v1", cache.get(nowMs = 10_000) { loads += 1; "v$loads" })
        assertEquals("v2", cache.get(nowMs = 32_000) { loads += 1; "v$loads" })
    }

    @Test
    fun forceReloadBypassesFreshCache() {
        val cache = TimedCache<String>(ttlMs = 30_000)

        assertEquals("old", cache.get(nowMs = 1_000) { "old" })
        assertEquals("new", cache.get(nowMs = 2_000, force = true) { "new" })
    }
}
