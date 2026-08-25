package com.bydcollector.collector.keepalive

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeepAliveReconcileStateTest {
    @Test
    fun tracksConfigChangesAndOrdinaryAliveCache() {
        val state = KeepAliveReconcileState(aliveTtlMs = 600_000)
        val enabled = KeepAliveConfig(
            keepWifi = true,
            keepMobileData = false,
            keepBluetooth = false,
            recoverCollectorService = false
        )

        assertTrue(state.configChanged(enabled, userShutdown = false))
        state.markConfigApplied(enabled, userShutdown = false)
        assertFalse(state.configChanged(enabled, userShutdown = false))
        assertTrue(state.configChanged(enabled, userShutdown = true))

        state.markAlive(1_000)
        assertTrue(state.aliveFresh(100_000))
        assertFalse(state.aliveFresh(700_000))
    }

    @Test
    fun disabledConfigRetriesStopUntilTheDisabledStateIsConfirmed() {
        val state = KeepAliveReconcileState(aliveTtlMs = 600_000)
        val disabled = KeepAliveConfig(false, false, false, false)

        assertTrue(state.shouldStopDaemonForDisabledConfig(state.configChanged(disabled, userShutdown = true)))
        assertTrue(state.configChanged(disabled, userShutdown = true))
        state.markConfigApplied(disabled, userShutdown = true)
        assertFalse(state.shouldStopDaemonForDisabledConfig(state.configChanged(disabled, userShutdown = true)))
    }

    @Test
    fun bluetoothRollbackRetriesUntilSuccessAndResetsAfterKeepAliveIsEnabled() {
        val state = KeepAliveReconcileState(aliveTtlMs = 600_000)
        val disabled = KeepAliveConfig(false, false, false, false)
        val enabled = disabled.copy(keepBluetooth = true)

        assertTrue(state.shouldRestoreBluetoothProfiles(disabled))
        assertTrue(state.shouldRestoreBluetoothProfiles(disabled))

        state.markBluetoothProfilesRestored()
        assertFalse(state.shouldRestoreBluetoothProfiles(disabled))

        state.markBluetoothProfilesMayBeOverridden()
        assertFalse(state.shouldRestoreBluetoothProfiles(enabled))
        assertTrue(state.shouldRestoreBluetoothProfiles(disabled))
    }

    @Test
    fun shutdownReconcileRetriesUntilSuccessWithinThreeAttempts() {
        val results = ArrayDeque(
            listOf(
                KeepAliveShellResult(false, "", "injected shell failure", 1),
                KeepAliveShellResult(true, "", null, 1)
            )
        )
        val shell = object : KeepAliveShell {
            override fun exec(command: String, timeoutMs: Int): KeepAliveShellResult = results.removeFirst()
        }

        assertTrue(retryKeepAliveReconcile { shell.exec("stop-and-confirm", 10_000).ok })
        assertTrue(results.isEmpty())
    }

    @Test
    fun shutdownReconcileStopsAfterThreeFailures() {
        var attempts = 0

        assertFalse(retryKeepAliveReconcile { attempts++; false })
        assertTrue(attempts == 3)
    }

    @Test
    fun forcedWatchdogCheckDoesNotReuseFreshCachedPid() {
        assertTrue(
            canReuseKeepAliveStatus(
                anyEnabled = true,
                forceStatusCheck = false,
                configChanged = false,
                shouldRestoreBluetoothProfiles = false,
                aliveFresh = true
            )
        )
        assertFalse(
            canReuseKeepAliveStatus(
                anyEnabled = true,
                forceStatusCheck = true,
                configChanged = false,
                shouldRestoreBluetoothProfiles = false,
                aliveFresh = true
            )
        )
    }
}
