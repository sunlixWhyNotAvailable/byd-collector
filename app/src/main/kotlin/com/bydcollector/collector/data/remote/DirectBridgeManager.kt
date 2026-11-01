package com.bydcollector.collector.data.remote

import android.content.Context
import android.os.Process
import com.bydcollector.collector.adb.AdbCancellation
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.adb.AdbOperationCancelledException
import com.bydcollector.collector.adb.AdbShellResult
import com.bydcollector.collector.data.direct.DirectHelperOwnerMode
import com.bydcollector.collector.data.direct.DirectStreamController
import com.bydcollector.collector.data.direct.DirectVehicleHelper
import com.bydcollector.collector.data.direct.DirectVehicleHelperClient
import com.bydcollector.collector.data.direct.DirectFidEntry
import com.bydcollector.collector.data.direct.DirectValueDecoder
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.util.diagnosticDetail
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

//starts and verifies the shell-owned binder helper that reads autoservice for the app uid
object DirectBridgeManager {
    private val launchLock = ReentrantLock()
    // Binder cannot be interrupted reliably. Never queue more calls behind a stuck transaction.
    private val lifecycleExecutor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        SynchronousQueue(), { work -> Thread(work, "byd-helper-lifecycle").apply { isDaemon = true } })

    fun status(): String = if (DirectVehicleHelperClient().isAlive()) "ready" else "unavailable"

    fun ensureRunning(
        context: Context,
        adbClient: AdbLocalClient,
        helper: DirectVehicleHelper = DirectVehicleHelperClient(),
        ownerMode: DirectHelperOwnerMode = DirectHelperOwnerMode.APP,
        cancellation: AdbCancellation = AdbCancellation(),
        shellRunner: ((command: String, timeoutMs: Int) -> AdbShellResult)? = null
    ): DirectBridgeResult {
        cancellation.throwIfCancelled()
        try {
            launchLock.lockInterruptibly()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AdbOperationCancelledException()
        }
        try {
            cancellation.throwIfCancelled()
            val appContext = context.applicationContext
            val settings = CollectorSettings(appContext)
            var replacedOwner: String? = null
            val updateTime = installedUpdateTime(appContext)
            val replacementPending = settings.helperReplacementPending(updateTime)
            if (replacementPending && !settings.markHelperReplacementPending()) {
                return DirectBridgeResult(false, "Could not persist pending helper replacement")
            }
            val helperAlive = !replacementPending && helper.isAlive()
            // Stream desire is reconciled through DirectStreamController. Owner mode is used only
            // for the helper's guarded stop request, never as a readiness requirement.
            if (helperAlive && !replacementPending) {
                return DirectBridgeResult(ok = true, message = "Direct helper already running")
            }
            recordLifecycle(appContext, "helper_launch_requested",
                "replacement_pending=$replacementPending helper_alive=$helperAlive installed_update_ms=$updateTime")
            fun execShell(command: String, timeoutMs: Int): AdbShellResult =
                shellRunner?.invoke(command, timeoutMs)
                    ?: adbClient.execShell(command, timeoutMs = timeoutMs)

            if (replacementPending || !helperAlive) {
                if (settings.isUserShutdownRequested() || replacementPending && !settings.helperReplacementAllowed()) {
                    return DirectBridgeResult(false, "Helper replacement is pending until collection is eligible")
                }
                if (replacementPending && updateTime == null) {
                    return DirectBridgeResult(false, "Installed package update time is unavailable")
                }
                val snapshot = execShell(HelperReplacementShell.snapshotCommand(), 5_000)
                if (!snapshot.ok) return DirectBridgeResult(false, shellDiagnostic("helper_owner_snapshot", snapshot))
                val oldOwner = HelperReplacementShell.owner(snapshot.output)
                replacedOwner = oldOwner
                recordLifecycle(appContext, "helper_replacement_owner", "old_owner=${oldOwner ?: "absent"}")
                if (oldOwner != null) {
                    try {
                        val stop = lifecycleCall { DirectVehicleHelperClient().requestStopForReplacement() }
                        recordLifecycle(appContext, "helper_replacement_stop",
                            "old_owner=$oldOwner accepted=${stop.ok} status=${stop.status} error=${stop.error.orEmpty()}")
                    } catch (error: AdbOperationCancelledException) {
                        throw error
                    } catch (error: Exception) {
                        recordLifecycle(appContext, "helper_replacement_stop_failed",
                            "old_owner=$oldOwner ${error.diagnosticDetail()}")
                    }
                    cancellation.throwIfCancelled()
                    if (settings.isUserShutdownRequested() || replacementPending && !settings.helperReplacementAllowed()) {
                        return DirectBridgeResult(false, "Helper replacement cancelled before owner termination")
                    }
                    val absence = execShell(HelperReplacementShell.finishStopCommand(oldOwner), 45_000)
                    recordLifecycle(appContext, "helper_replacement_retired",
                        shellDiagnostic("old_owner=$oldOwner", absence))
                    if (!absence.ok) return DirectBridgeResult(false, shellDiagnostic("helper_owner_retire", absence))
                }
                cancellation.throwIfCancelled()
                if (settings.isUserShutdownRequested() || replacementPending && !settings.helperReplacementAllowed()) {
                    return DirectBridgeResult(false, "Helper replacement deferred by Shutdown or stopped collection")
                }
            }

            cancellation.throwIfCancelled()
            if (settings.isUserShutdownRequested()) {
                return DirectBridgeResult(false, "Helper launch cancelled by Shutdown")
            }
            DirectStreamController.invalidateHelper()
            val launch = execShell(launchCommand(appContext, ownerMode), 15_000)
            if (!launch.ok) {
                return DirectBridgeResult(
                    ok = false,
                    message = shellDiagnostic("helper_launch", launch)
                )
            }

            var startupFailure = "Helper did not answer the current protocol handshake"
            repeat(12) {
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw AdbOperationCancelledException()
                }
                cancellation.throwIfCancelled()
                val ready = try {
                    lifecycleCall {
                        val client = DirectVehicleHelperClient()
                        if (!client.isAlive()) false else {
                            // One startup GET exercises the reader, not just the lifecycle Binder endpoint.
                            val read = client.read(DirectFidEntry(
                                "helper_startup_power", 1001, 315621418, 5, DirectValueDecoder.INT_RAW))
                            check(read.ok) {
                                "Helper startup read failed: status=${read.status} raw=${read.raw} error=${read.error}"
                            }
                            recordLifecycle(appContext, "helper_startup_read",
                                "power_raw=${read.raw} status=${read.status}")
                            true
                        }
                    }
                } catch (error: AdbOperationCancelledException) {
                    throw error
                } catch (error: Exception) {
                    startupFailure = error.diagnosticDetail()
                    recordLifecycle(appContext, "helper_startup_read_failed", startupFailure)
                    false
                }
                if (ready) {
                    if (replacementPending) {
                        if (!settings.helperReplacementAllowed()) {
                            return DirectBridgeResult(false, "Helper replacement interrupted by Shutdown or stopped collection")
                        }
                        val identity = execShell(HelperReplacementShell.snapshotCommand(appContext.applicationInfo.sourceDir), 5_000)
                        if (!identity.ok) return DirectBridgeResult(false, shellDiagnostic("helper_new_owner", identity))
                        val newOwner = HelperReplacementShell.owner(identity.output)
                            ?: return DirectBridgeResult(false, "New helper exited before replacement confirmation")
                        if (newOwner == replacedOwner) {
                            return DirectBridgeResult(false, "Old helper still owns the service after replacement: $newOwner")
                        }
                        cancellation.throwIfCancelled()
                        if (!settings.helperReplacementAllowed()) {
                            return DirectBridgeResult(false, "Helper replacement confirmation cancelled by Shutdown or stopped collection")
                        }
                        recordLifecycle(appContext, "helper_replacement_ready", "new_owner=$newOwner installed_update_ms=$updateTime")
                        if (!settings.confirmHelperReplacement(updateTime!!)) {
                            return DirectBridgeResult(false, "Could not confirm the installed helper version")
                        }
                    }
                    recordLifecycle(appContext, "helper_launch_confirmed",
                        "replacement=$replacementPending installed_update_ms=$updateTime")
                    return DirectBridgeResult(ok = true, message = "Direct helper started")
                }
            }
            recordLifecycle(appContext, "helper_launch_timeout", "installed_update_ms=$updateTime error=$startupFailure")
            return DirectBridgeResult(ok = false, message = "Direct helper readiness failed: $startupFailure")
        } finally {
            launchLock.unlock()
        }
    }

    private fun <T> lifecycleCall(block: () -> T): T {
        val future = lifecycleExecutor.submit(Callable(block))
        try {
            return future.get(3, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AdbOperationCancelledException().apply { initCause(error) }
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        } finally {
            if (!future.isDone) future.cancel(true)
        }
    }

    private fun shellDiagnostic(stage: String, result: AdbShellResult): String =
        "stage=$stage ok=${result.ok} elapsed_ms=${result.elapsedMs} error=${result.error.orEmpty()} output=${result.output}"

    private fun recordLifecycle(context: Context, event: String, detail: String) {
        runCatching {
            (context.applicationContext as com.bydcollector.collector.BydCollectorApplication)
                .operationalEventJournal.tryAppend(java.time.Instant.now().toString(),
                    android.os.SystemClock.elapsedRealtime(), "helper_lifecycle", event, detail)
        }
    }

    fun launchCommand(
        context: Context,
        ownerMode: DirectHelperOwnerMode = DirectHelperOwnerMode.APP
    ): String {
        return launchCommand(
            apkPath = context.applicationContext.applicationInfo.sourceDir,
            appUid = Process.myUid(),
            ownerMode = ownerMode
        )
    }

    fun launchCommand(
        apkPath: String,
        appUid: Int,
        ownerMode: DirectHelperOwnerMode = DirectHelperOwnerMode.APP
    ): String {
        val quotedApk = shellQuote(apkPath)
        val modeArgument = if (ownerMode == DirectHelperOwnerMode.APP_GAP_SPOOL) {
            " ${CollectorHelperProtocol.SPOOL_MODE_ARG}"
        } else {
            ""
        }
        // Only the verified replacement path may signal an owner; never unlink its lock inode.
        val cleanup = "if pidof ${CollectorHelperProtocol.PROCESS_NAME} >/dev/null; then " +
            "echo HELPER_OWNER_STILL_PRESENT >&2; exit 73; fi; "
        return cleanup + bootstrapHistoryCommand() +
            "CLASSPATH=$quotedApk setsid app_process /system/bin --nice-name=${CollectorHelperProtocol.PROCESS_NAME} " +
            "${CollectorHelperProtocol.HELPER_CLASS} $appUid $quotedApk$modeArgument </dev/null >>${CollectorHelperProtocol.LOG_PATH} 2>&1 & " +
            "for i in 1 2 3; do service list 2>/dev/null | grep -q ${CollectorHelperProtocol.SERVICE_NAME} && break; sleep 1; done"
    }

    // This captures failures before the JVM reaches our bounded output adapter.
    // A stopped previous owner cannot still hold/write the file while it rotates.
    internal fun bootstrapHistoryCommand(): String = buildString {
        val log = CollectorHelperProtocol.LOG_PATH
        append("if [ -f $log ]; then ")
        append("bootstrap_history_ok=1; ")
        for (index in 3 downTo 1) {
            val source = if (index == 1) log else "$log.${index - 1}"
            val target = "$log.$index"
            append("if [ -f $source ]; then ")
            append("if tail -c 2097152 $source >$target.tmp && mv -f $target.tmp $target; ")
            append("then :; else bootstrap_history_ok=0; fi; fi; ")
        }
        // Preserve the original if even one part of history could not be saved.
        append("if [ \"${'$'}bootstrap_history_ok\" = 1 ]; then : >$log; ")
        append("else echo HELPER_BOOTSTRAP_HISTORY_PARTIAL >&2; fi; fi; ")
    }

    @Suppress("DEPRECATION")
    private fun installedUpdateTime(context: Context): Long? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.takeIf { it > 0L }
    }.getOrNull()

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}

data class DirectBridgeResult(
    val ok: Boolean,
    val message: String
)
