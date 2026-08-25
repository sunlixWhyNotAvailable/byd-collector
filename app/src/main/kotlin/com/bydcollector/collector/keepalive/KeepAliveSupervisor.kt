package com.bydcollector.collector.keepalive

import android.content.Context
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.util.namedSingleThreadExecutor
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

//mirrors app keep-alive settings into shell-visible flags and owns the delegate lifecycle
class KeepAliveSupervisor(
    private val context: Context,
    private val store: TelemetryStore,
    private val shellFactory: () -> KeepAliveShell = {
        AdbKeepAliveShell(AdbLocalClient(File(context.applicationContext.filesDir, "adb_keys")))
    },
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    private val executor: ExecutorService = namedSingleThreadExecutor("byd-keepalive")
    private val reconcileLock = Any()
    private val reconcileState = KeepAliveReconcileState(ALIVE_TTL_MS)

    fun reconcile(config: KeepAliveConfig) {
        executor.execute {
            runReconcileSerialized(config)
        }
    }

    fun reconcileBlocking(config: KeepAliveConfig) {
        runReconcileSerialized(config)
    }

    fun reconcileThen(config: KeepAliveConfig, after: () -> Unit) {
        executor.execute {
            try {
                runReconcileSerialized(config)
            } finally {
                after()
            }
        }
    }

    private fun runReconcileSerialized(config: KeepAliveConfig) {
        synchronized(reconcileLock) {
            runReconcile(config)
        }
    }

    private fun runReconcile(config: KeepAliveConfig) {
        try {
            val nowMs = clockMs()
            val userShutdown = CollectorSettings(context.applicationContext, store).isUserShutdownRequested()
            val configChanged = reconcileState.configChanged(config, userShutdown)
            val shouldStopDisabledDaemon = reconcileState.shouldStopDaemonForDisabledConfig(configChanged)
            if (config.keepBluetooth) reconcileState.markBluetoothProfilesMayBeOverridden()
            val shouldRestoreBluetoothProfiles = reconcileState.shouldRestoreBluetoothProfiles(config)
            if (
                config.anyEnabled &&
                !configChanged &&
                !shouldRestoreBluetoothProfiles &&
                reconcileState.aliveFresh(nowMs)
            ) return
            if (!config.anyEnabled && !shouldStopDisabledDaemon && !shouldRestoreBluetoothProfiles) return

            val shell = shellFactory()
            var configSynchronized = !configChanged
            if (configChanged) {
                //writes every flag before launch/stop so the daemon loop observes a complete desired state
                val mirrorResults = KeepAliveShellPlanner.mirrorSettingsCommands(config, userShutdown).map { command ->
                    runCommand(shell, command, "keep_alive_setting_sync")
                }
                if (mirrorResults.all { it.ok }) {
                    reconcileState.markConfigApplied(config, userShutdown)
                    configSynchronized = true
                }
            }
            if (configSynchronized && shouldRestoreBluetoothProfiles) {
                val rollback = runCommand(
                    shell,
                    KeepAliveShellPlanner.bluetoothProfilesRestoreCommand(),
                    "keep_alive_bluetooth_profiles_restore"
                )
                if (rollback.ok) reconcileState.markBluetoothProfilesRestored()
            }

            if (config.anyEnabled) {
                val status = runCommand(shell, KeepAliveShellPlanner.daemonStatusCommand(), "keep_alive_daemon_status")
                if (status.ok) {
                    reconcileState.markAlive(clockMs())
                    return
                }
                //starts the delegate only after a stale/failed one-shot status check
                runCommand(
                    shell,
                    KeepAliveShellPlanner.daemonLaunchCommand(context.applicationInfo.sourceDir),
                    "keep_alive_daemon_start"
                )
                val retryStatus = runCommand(shell, KeepAliveShellPlanner.daemonStatusRetryCommand(), "keep_alive_daemon_status")
                if (retryStatus.ok) {
                    reconcileState.markAlive(clockMs())
                } else {
                    //captures the daemon tail because shell startup failures are otherwise invisible in the app ui
                    val tail = shell.exec(KeepAliveShellPlanner.daemonLogTailCommand(), timeoutMs = 10_000)
                    store.recordEvent(
                        category = "keep_alive_daemon_log_tail",
                        message = "Keep-alive daemon log tail captured",
                        detail = tail.output.take(1_000)
                    )
                }
            } else {
                reconcileState.clearAlive()
                if (shouldStopDisabledDaemon) {
                    runCommand(shell, KeepAliveShellPlanner.daemonStopCommand(), "keep_alive_daemon_stop")
                }
            }
        } catch (error: RuntimeException) {
            store.recordEvent(
                "keep_alive_reconcile_error",
                "Keep-alive reconcile failed",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        } finally {
        }
    }

    fun shutdown() {
        shutdownAndAwait(0L)
    }

    fun shutdownAndAwait(timeoutMs: Long): Boolean {
        executor.shutdownNow()
        return try {
            executor.awaitTermination(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun runCommand(shell: KeepAliveShell, command: String, category: String): KeepAliveShellResult {
        val result = shell.exec(command, timeoutMs = 10_000)
        //persists shell command outcomes into collector_events so live tests can be audited after the fact
        val detail = buildString {
            append("command=").append(command)
            append(" elapsed_ms=").append(result.elapsedMs)
            result.error?.let { append(" error=").append(it) }
            if (result.output.isNotBlank()) append(" output=").append(result.output.take(500))
        }
        store.recordEvent(
            category = if (result.ok) category else "${category}_failed",
            message = if (result.ok) "Keep-alive shell command completed" else "Keep-alive shell command failed",
            detail = detail
        )
        return result
    }

    companion object {
        private const val ALIVE_TTL_MS = 10 * 60 * 1000L
    }
}
