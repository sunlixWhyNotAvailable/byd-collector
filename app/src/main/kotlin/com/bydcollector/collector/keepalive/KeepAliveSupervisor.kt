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

    fun reconcile(config: KeepAliveConfig, forceStatusCheck: Boolean = false) {
        executor.execute {
            runReconcileSerialized(config, forceStatusCheck)
        }
    }

    fun reconcileBlocking(config: KeepAliveConfig, forceStatusCheck: Boolean = false): Boolean {
        return runReconcileSerialized(config, forceStatusCheck)
    }

    fun reconcileThen(config: KeepAliveConfig, after: (Boolean) -> Unit) {
        val task = Runnable {
            val reconciled = try {
                retryKeepAliveReconcile { runReconcileSerialized(config, forceStatusCheck = true) }
            } catch (error: Throwable) {
                recordReconcileThenFailure("keep_alive_reconcile_then_error", error)
                false
            }
            runCatching { after(reconciled) }
                .onFailure { error -> recordReconcileThenFailure("keep_alive_reconcile_callback_error", error) }
        }
        try {
            executor.execute(task)
        } catch (error: Throwable) {
            recordReconcileThenFailure("keep_alive_reconcile_submit_failed", error)
            runCatching { after(false) }
                .onFailure { callbackError -> recordReconcileThenFailure("keep_alive_reconcile_callback_error", callbackError) }
        }
    }

    private fun recordReconcileThenFailure(category: String, error: Throwable) {
        runCatching {
            store.recordEvent(
                category,
                "Keep-alive reconcile completion failed",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }
    }

    private fun runReconcileSerialized(config: KeepAliveConfig, forceStatusCheck: Boolean): Boolean {
        return synchronized(reconcileLock) {
            runReconcile(config, forceStatusCheck)
        }
    }

    private fun runReconcile(config: KeepAliveConfig, forceStatusCheck: Boolean): Boolean {
        return try {
            val nowMs = clockMs()
            val userShutdown = CollectorSettings(context.applicationContext, store).isUserShutdownRequested()
            val effectiveConfig = if (userShutdown) KeepAliveConfig(false, false, false, false) else config
            val configChanged = reconcileState.configChanged(effectiveConfig, userShutdown)
            val shouldStopDisabledDaemon = forceStatusCheck ||
                reconcileState.shouldStopDaemonForDisabledConfig(configChanged)
            if (effectiveConfig.keepBluetooth) reconcileState.markBluetoothProfilesMayBeOverridden()
            val shouldRestoreBluetoothProfiles = reconcileState.shouldRestoreBluetoothProfiles(effectiveConfig)
            if (
                canReuseKeepAliveStatus(
                    anyEnabled = effectiveConfig.anyEnabled,
                    forceStatusCheck = forceStatusCheck,
                    configChanged = configChanged,
                    shouldRestoreBluetoothProfiles = shouldRestoreBluetoothProfiles,
                    aliveFresh = reconcileState.aliveFresh(nowMs)
                )
            ) return true
            if (!effectiveConfig.anyEnabled && !shouldStopDisabledDaemon && !shouldRestoreBluetoothProfiles) return true

            val shell = shellFactory()
            if (configChanged) {
                //writes every flag before launch/stop so the daemon loop observes a complete desired state
                for (command in KeepAliveShellPlanner.mirrorSettingsCommands(effectiveConfig, userShutdown)) {
                    if (!runCommand(shell, command, "keep_alive_setting_sync").ok) return false
                }
                if (effectiveConfig.anyEnabled) reconcileState.markConfigApplied(effectiveConfig, userShutdown)
            }
            if (shouldRestoreBluetoothProfiles) {
                val rollback = runCommand(
                    shell,
                    KeepAliveShellPlanner.bluetoothProfilesRestoreCommand(),
                    "keep_alive_bluetooth_profiles_restore"
                )
                if (!rollback.ok) return false
                reconcileState.markBluetoothProfilesRestored()
            }

            if (effectiveConfig.anyEnabled) {
                val status = runCommand(shell, KeepAliveShellPlanner.daemonStatusCommand(), "keep_alive_daemon_status")
                if (status.ok) {
                    reconcileState.markAlive(clockMs())
                    return true
                }
                reconcileState.clearAlive()
                //starts the delegate only after a stale/failed one-shot status check
                runCommand(
                    shell,
                    KeepAliveShellPlanner.daemonLaunchCommand(context.applicationInfo.sourceDir),
                    "keep_alive_daemon_start"
                )
                val retryStatus = runCommand(shell, KeepAliveShellPlanner.daemonStatusRetryCommand(), "keep_alive_daemon_status")
                if (!retryStatus.ok) {
                    //captures the daemon tail because shell startup failures are otherwise invisible in the app ui
                    val tail = shell.exec(KeepAliveShellPlanner.daemonLogTailCommand(), timeoutMs = 10_000)
                    store.recordEvent(
                        category = "keep_alive_daemon_log_tail",
                        message = "Keep-alive daemon log tail captured",
                        detail = tail.output.take(1_000)
                    )
                } else {
                    reconcileState.markAlive(clockMs())
                }
                retryStatus.ok
            } else {
                reconcileState.clearAlive()
                //The stop command exits successfully only after the detached daemon is confirmed absent.
                val stopped = !shouldStopDisabledDaemon ||
                    runCommand(shell, KeepAliveShellPlanner.daemonStopCommand(), "keep_alive_daemon_stop").ok
                if (stopped && configChanged) reconcileState.markConfigApplied(effectiveConfig, userShutdown)
                stopped
            }
        } catch (error: RuntimeException) {
            store.recordEvent(
                "keep_alive_reconcile_error",
                "Keep-alive reconcile failed",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
            false
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

    /** Cancels queued reconciles and proves any already-running shell writer has exited. */
    fun quiesceForUserShutdown(timeoutMs: Long): Boolean = shutdownAndAwait(timeoutMs)

    fun isShutdown(): Boolean = executor.isShutdown

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

internal fun retryKeepAliveReconcile(attempt: () -> Boolean): Boolean {
    repeat(3) {
        if (attempt()) return true
    }
    return false
}

internal fun canReuseKeepAliveStatus(
    anyEnabled: Boolean,
    forceStatusCheck: Boolean,
    configChanged: Boolean,
    shouldRestoreBluetoothProfiles: Boolean,
    aliveFresh: Boolean
): Boolean {
    return anyEnabled &&
        !forceStatusCheck &&
        !configChanged &&
        !shouldRestoreBluetoothProfiles &&
        aliveFresh
}
