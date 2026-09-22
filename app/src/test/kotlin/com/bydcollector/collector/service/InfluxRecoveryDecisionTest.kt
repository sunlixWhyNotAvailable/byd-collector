package com.bydcollector.collector.service

import kotlin.test.Test
import kotlin.test.assertEquals

class InfluxRecoveryDecisionTest {
    @Test
    fun `queued running and scheduled recovery preserve existing work`() {
        assertEquals(
            InfluxRecoveryAction.PRESERVE,
            decide(sessionInitialized = false, workActive = true)
        )
        assertEquals(
            InfluxRecoveryAction.PRESERVE,
            decide(sessionInitialized = true, workActive = true)
        )
        assertEquals(
            InfluxRecoveryAction.PRESERVE,
            decide(sessionInitialized = true, retryScheduled = true)
        )
    }

    @Test
    fun `initialized idle recovery requests an ordinary cycle`() {
        assertEquals(
            InfluxRecoveryAction.REQUEST_CYCLE,
            decide(sessionInitialized = true)
        )
    }

    @Test
    fun `new or rejected initialization remains retryable`() {
        val uninitialized = { decide(sessionInitialized = false) }

        assertEquals(InfluxRecoveryAction.INITIALIZE, uninitialized())
        assertEquals(InfluxRecoveryAction.INITIALIZE, uninitialized())
    }

    private fun decide(
        sessionInitialized: Boolean,
        workActive: Boolean = false,
        retryScheduled: Boolean = false
    ): InfluxRecoveryAction = influxRecoveryAction(
        sessionInitialized = sessionInitialized,
        workActive = workActive,
        retryScheduled = retryScheduled
    )

}
