package com.bydcollector.collector.service

import org.junit.Assert.*
import org.junit.Test

class StartupCollectionGateTest {
    @Test fun waitsForActualSessionsAndReleasesOnlyOnce() {
        val gate = StartupCollectionGate()
        gate.commandsHandled()
        assertFalse(gate.releaseIfReady(true, true))
        assertFalse(gate.releaseIfReady(false, true))
        assertTrue(gate.releaseIfReady(false, false))
        assertTrue(gate.open)
        assertFalse(gate.releaseIfReady(false, false))
    }

    @Test fun noCollectionStillWaitsForFirstCommand() {
        val gate = StartupCollectionGate()
        assertFalse(gate.releaseIfReady(false, false))
        gate.commandsHandled()
        assertTrue(gate.releaseIfReady(false, false))
    }

    @Test fun retryRemainsPendingButDefinitiveFailureOrCancellationCanRelease() {
        val gate = StartupCollectionGate()
        gate.commandsHandled()
        assertFalse(gate.releaseIfReady(true, false))
        assertFalse(gate.releaseIfReady(true, false))
        assertTrue(gate.releaseIfReady(false, false))
    }
}
