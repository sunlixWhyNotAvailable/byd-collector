package com.bydcollector.collector.keepalive

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeepAliveReconcileStateTest {
    @Test
    fun tracksConfigChangesAndFreshAliveTtl() {
        val state = KeepAliveReconcileState(aliveTtlMs = 600_000)
        val enabled = KeepAliveConfig(
            keepWifi = true,
            keepMobileData = false,
            keepBluetooth = false,
            recoverCollectorService = false
        )

        assertTrue(state.configChanged(enabled))
        state.markConfigApplied(enabled)
        assertFalse(state.configChanged(enabled))

        state.markAlive(1_000)

        assertTrue(state.aliveFresh(100_000))
        assertFalse(state.aliveFresh(700_000))
    }

    @Test
    fun clearingAliveMakesNextEnabledReconcileCheckStatus() {
        val state = KeepAliveReconcileState(aliveTtlMs = 600_000)

        state.markAlive(1_000)
        state.clearAlive()

        assertFalse(state.aliveFresh(2_000))
    }

    @Test
    fun disabledConfigStopsDaemonWhenConfigChangedEvenAfterProcessRestart() {
        val state = KeepAliveReconcileState(aliveTtlMs = 600_000)

        assertTrue(state.shouldStopDaemonForDisabledConfig(configChanged = true))
        assertFalse(state.shouldStopDaemonForDisabledConfig(configChanged = false))
    }
}
