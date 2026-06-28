package com.bydcollector.collector.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals

class DirectBridgeStatusCacheTest {
    @Test
    fun avoidsAuthorizationProbeInsideTtlWhenHelperIsNotAlive() {
        val cache = DirectBridgeStatusCache(ttlMs = 30_000)
        var authChecks = 0

        val first = cache.status(nowMs = 1_000, helperAlive = { false }) {
            authChecks += 1
            "needs Grant ADB"
        }
        val second = cache.status(nowMs = 5_000, helperAlive = { false }) {
            authChecks += 1
            "ready"
        }

        assertEquals("needs Grant ADB", first)
        assertEquals("needs Grant ADB", second)
        assertEquals(1, authChecks)
    }

    @Test
    fun readyHelperBypassesCachedAuthorizationFailure() {
        val cache = DirectBridgeStatusCache(ttlMs = 30_000)

        cache.status(nowMs = 1_000, helperAlive = { false }) { "unavailable" }

        assertEquals("ready", cache.status(nowMs = 2_000, helperAlive = { true }) { "unavailable" })
    }

    @Test
    fun forceBypassesCachedFallbackStatus() {
        val cache = DirectBridgeStatusCache(ttlMs = 30_000)

        cache.status(nowMs = 1_000, helperAlive = { false }) { "ready" }

        assertEquals("unavailable", cache.status(nowMs = 2_000, force = true, helperAlive = { false }) { "unavailable" })
    }
}
