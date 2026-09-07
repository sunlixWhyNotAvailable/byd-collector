package com.bydcollector.collector.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramRecoveryCoalescerTest {
    @Test
    fun identicalActiveAndPendingHintsAreCollapsed() {
        val coalescer = TelegramRecoveryCoalescer()

        val startup = coalescer.request("startup")
        assertEquals(setOf("startup"), startup?.reasons)
        assertNull(coalescer.request("startup"))
        assertNull(coalescer.request("network"))
        assertNull(coalescer.request("network"))

        val network = coalescer.complete(startup!!.token)
        assertEquals(setOf("network"), network?.reasons)
        assertNull(coalescer.request("network"))
        assertNull(coalescer.complete(network!!.token))
    }

    @Test
    fun invalidationDropsFollowUpHints() {
        val coalescer = TelegramRecoveryCoalescer()

        coalescer.request("vehicle_on")
        coalescer.request("network")
        coalescer.invalidate()

        assertNull(coalescer.complete(1L))
        assertEquals(setOf("startup"), coalescer.request("startup")?.reasons)
    }

    @Test
    fun staleCompletionCannotReleaseAReplacementOwner() {
        val coalescer = TelegramRecoveryCoalescer()

        val first = coalescer.request("startup")!!
        coalescer.invalidate(first.token)
        val replacement = coalescer.request("network", key = "network:2")!!

        assertNull(coalescer.complete(first.token))
        assertEquals(true, coalescer.isCurrent(replacement.token))
        assertNull(coalescer.complete(replacement.token))
    }

    @Test
    fun newerNetworkRevisionReplacesAnOlderPendingRevision() {
        val coalescer = TelegramRecoveryCoalescer()

        val active = coalescer.request("startup")!!
        assertNull(coalescer.request("network", key = "network:1", replacePendingKey = "network"))
        assertNull(coalescer.request("network", key = "network:2", replacePendingKey = "network"))

        val followUp = coalescer.complete(active.token)!!
        assertEquals(setOf("network"), followUp.reasons)
        assertTrue(coalescer.isCurrent(followUp.token))
    }
}
