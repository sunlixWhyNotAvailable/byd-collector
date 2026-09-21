package com.bydcollector.collector.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `service recovery decision uses real session work and timer state`() {
        val service = sourceFile("com/bydcollector/collector/service/CollectorService.kt").readText()
        val start = service.substringAfter("private fun startInfluxExport")
            .substringBefore("private fun maybeActivateTailscaleAfterHaFailure")
        val recovery = start.substringAfter("if (!clearManualStop)")

        assertTrue(recovery.contains("influxRequestQueued.get() || influxWorkInFlight.get() > 0"))
        assertTrue(recovery.contains("sessionInitialized = influxCoordinator.sessionFrozen"))
        assertTrue(recovery.contains("retryScheduled = influxRetryScheduled"))
        assertTrue(recovery.contains("InfluxRecoveryAction.PRESERVE -> return"))
        assertTrue(recovery.contains("InfluxRecoveryAction.REQUEST_CYCLE"))
        assertTrue(recovery.contains("requestInfluxCycle()"))
        assertTrue(start.indexOf("settings.setInfluxEnabled(true)") < start.indexOf("if (!clearManualStop)"))
        assertTrue(start.indexOf("if (!clearManualStop)") < start.indexOf("advanceInfluxGeneration()"))
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

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
