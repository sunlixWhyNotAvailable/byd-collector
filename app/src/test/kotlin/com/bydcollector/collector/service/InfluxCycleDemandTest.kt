package com.bydcollector.collector.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InfluxCycleDemandTest {
    @Test
    fun `persisted deadline wins over pending demand`() {
        assertEquals(
            InfluxFollowUpAction.SCHEDULE_RETRY,
            influxFollowUpAction(currentRequest = true, retryDelayMs = 30_000L, followUpDemand = true)
        )
        assertEquals(
            InfluxFollowUpAction.REQUEST_CYCLE,
            influxFollowUpAction(currentRequest = true, retryDelayMs = null, followUpDemand = true)
        )
        assertEquals(
            InfluxFollowUpAction.IDLE,
            influxFollowUpAction(currentRequest = true, retryDelayMs = null, followUpDemand = false)
        )
    }

    @Test
    fun `same-generation stale scheduler cannot change newer timing`() {
        assertEquals(
            InfluxFollowUpAction.IGNORE_STALE,
            influxFollowUpAction(currentRequest = false, retryDelayMs = null, followUpDemand = true)
        )
        assertEquals(
            InfluxFollowUpAction.IGNORE_STALE,
            influxFollowUpAction(currentRequest = false, retryDelayMs = 1_000L, followUpDemand = false)
        )
    }

    @Test
    fun `signal after settlement is visible to live scheduling decision`() {
        val demand = InfluxCycleDemand()
        val request = assertNotNull(demand.tryAcquire(revision = 4, generation = 2))
        assertFalse(demand.settle(request, represented = true).followUpDemand)

        demand.signal()

        assertEquals(
            InfluxFollowUpAction.REQUEST_CYCLE,
            influxFollowUpAction(
                currentRequest = true,
                retryDelayMs = null,
                followUpDemand = demand.pending
            )
        )
    }

    @Test
    fun `signals during work coalesce into one follow-up`() {
        val demand = InfluxCycleDemand()
        val first = assertNotNull(demand.tryAcquire(revision = 1, generation = 4))

        demand.signal()
        demand.signal()

        assertNull(demand.tryAcquire(revision = 2, generation = 4))
        assertTrue(demand.settle(first, represented = true).followUpDemand)
        val followUp = assertNotNull(demand.tryAcquire(revision = 2, generation = 4))
        assertTrue(followUp.consumedDemand)
        assertFalse(demand.settle(followUp, represented = true).followUpDemand)
    }

    @Test
    fun `rejected submission restores consumed demand`() {
        val demand = InfluxCycleDemand()
        demand.signal()
        val rejected = assertNotNull(demand.tryAcquire(revision = 7, generation = 2))

        assertTrue(rejected.consumedDemand)
        assertTrue(demand.settle(rejected, represented = false).followUpDemand)
        assertTrue(assertNotNull(demand.tryAcquire(revision = 8, generation = 2)).consumedDemand)
    }

    @Test
    fun `stale settlement cannot release replacement owner or clear demand`() {
        val demand = InfluxCycleDemand()
        val stale = assertNotNull(demand.tryAcquire(revision = 1, generation = 1))
        demand.invalidate()
        val replacement = assertNotNull(demand.tryAcquire(revision = 3, generation = 2))
        demand.signal()

        val staleSettlement = demand.settle(stale, represented = true)

        assertFalse(staleSettlement.current)
        assertNull(demand.tryAcquire(revision = 4, generation = 2))
        assertTrue(demand.settle(replacement, represented = true).followUpDemand)
    }

    @Test
    fun `generation invalidation drops pending demand`() {
        val demand = InfluxCycleDemand()
        demand.signal()

        demand.invalidate()

        assertFalse(demand.pending)
        assertFalse(assertNotNull(demand.tryAcquire(revision = 2, generation = 2)).consumedDemand)
    }

    @Test
    fun `acquired request cannot submit after generation invalidation`() {
        val demand = InfluxCycleDemand()
        val request = assertNotNull(demand.tryAcquire(revision = 1, generation = 9))

        demand.invalidate()

        assertFalse(isInfluxSubmissionCurrent(request.generation, currentGeneration = 10))
        assertTrue(isInfluxSubmissionCurrent(expectedGeneration = null, currentGeneration = 10))
    }
}
