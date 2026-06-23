package com.bydcollector.collector.keepalive

import android.content.Context
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.data.local.TelemetryStore
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

//mirrors app keep-alive settings into shell-visible flags and owns the delegate lifecycle
class KeepAliveSupervisor(
    private val context: Context,
    private val store: TelemetryStore,
    private val shellFactory: () -> KeepAliveShell = {
        AdbKeepAliveShell(AdbLocalClient(File(context.applicationContext.filesDir, "adb_keys")))
    }
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val reconcileLock = Any()

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
            val shell = shellFactory()
            //writes every flag before launch/stop so the daemon loop observes a complete desired state
            KeepAliveShellPlanner.mirrorSettingsCommands(config).forEach { command ->
                runCommand(shell, command, "keep_alive_setting_sync")
            }

            if (config.anyEnabled) {
                //starts the delegate only when at least one keep-alive feature needs shell privileges
                runCommand(shell, KeepAliveShellPlanner.daemonLaunchCommand(context.applicationInfo.sourceDir), "keep_alive_daemon_start")
                val status = runCommand(shell, KeepAliveShellPlanner.daemonStatusRetryCommand(), "keep_alive_daemon_status")
                if (!status.ok) {
                    //captures the daemon tail because shell startup failures are otherwise invisible in the app ui
                    val tail = shell.exec(KeepAliveShellPlanner.daemonLogTailCommand(), timeoutMs = 10_000)
                    store.recordEvent(
                        category = "keep_alive_daemon_log_tail",
                        message = "Keep-alive daemon log tail captured",
                        detail = tail.output.take(1_000)
                    )
                }
            } else {
                runCommand(shell, KeepAliveShellPlanner.daemonStopCommand(), "keep_alive_daemon_stop")
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
        executor.shutdownNow()
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
}
