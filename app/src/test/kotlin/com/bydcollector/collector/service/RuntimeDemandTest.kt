package com.bydcollector.collector.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeDemandTest {
    @Test
    fun everyStandaloneRuntimeQualifiesForRecovery() {
        val cases = listOf(
            RuntimeDemand(main = true) to RuntimeRecoveryAction.MAIN,
            RuntimeDemand(debug = true) to RuntimeRecoveryAction.DEBUG,
            RuntimeDemand(mqtt = true) to RuntimeRecoveryAction.MQTT,
            RuntimeDemand(influx = true) to RuntimeRecoveryAction.INFLUX,
            RuntimeDemand(telegram = true) to RuntimeRecoveryAction.TELEGRAM,
            RuntimeDemand(keepAlive = true) to RuntimeRecoveryAction.KEEP_ALIVE
        )

        cases.forEach { (demand, action) ->
            assertTrue(demand.any)
            assertEquals(listOf(action), demand.recoveryActions())
        }
    }

    @Test
    fun nonMainRecoveryNeverUsesGenericMainStart() {
        val demand = RuntimeDemand(telegram = true, keepAlive = true)

        assertFalse(demand.recoveryActions().contains(RuntimeRecoveryAction.MAIN))
        assertEquals(
            listOf(RuntimeRecoveryAction.TELEGRAM, RuntimeRecoveryAction.KEEP_ALIVE),
            demand.recoveryActions()
        )
    }

    @Test
    fun influxDemandRetainsRecoveryOwnerAndLivenessTracksWork() {
        assertTrue(RuntimeDemand(influx = true).requiresPersistentOwner)
        val lifecycle = listOf(
            RuntimeLiveness(influxQueued = true),
            RuntimeLiveness(influxInFlight = true),
            RuntimeLiveness(influxRetryScheduled = true),
            RuntimeLiveness()
        ).map(RuntimeLiveness::active)

        assertEquals(listOf(true, true, true, false), lifecycle)
    }
}
