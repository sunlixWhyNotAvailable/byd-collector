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
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.service.CollectorSettings
import java.util.concurrent.locks.ReentrantLock

//starts and verifies the shell-owned binder helper that reads autoservice for the app uid
object DirectBridgeManager {
    private val launchLock = ReentrantLock()

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
            val updateTime = installedUpdateTime(appContext)
            val replacementPending = settings.helperReplacementPending(updateTime)
            if (replacementPending && !settings.markHelperReplacementPending()) {
                return DirectBridgeResult(false, "Could not persist pending helper replacement")
            }
            val helperAlive = helper.isAlive()
            // Stream desire is reconciled through DirectStreamController. Owner mode is used only
            // for the helper's guarded stop request, never as a readiness requirement.
            if (helperAlive && !replacementPending) {
                return DirectBridgeResult(ok = true, message = "Direct helper already running")
            }
            val actualOwnerMode = if (helperAlive && replacementPending) helper.ownerMode() else null
            fun execShell(command: String, timeoutMs: Int): AdbShellResult =
                shellRunner?.invoke(command, timeoutMs)
                    ?: adbClient.execShell(command, timeoutMs = timeoutMs)

            if (replacementPending) {
                if (!settings.helperReplacementAllowed()) {
                    return DirectBridgeResult(false, "Helper replacement is pending until collection is eligible")
                }
                if (updateTime == null) {
                    return DirectBridgeResult(false, "Installed package update time is unavailable")
                }
                if (actualOwnerMode != null) {
                    val stop = helper.requestStop(actualOwnerMode)
                    if (!stop.ok) {
                        return DirectBridgeResult(false, "Direct helper stop failed: ${stop.error ?: stop.status}")
                    }
                    cancellation.throwIfCancelled()
                }
                val absence = execShell(helperAbsenceCommand(), 10_000)
                if (!absence.ok) {
                    return DirectBridgeResult(
                        false,
                        absence.error ?: absence.output.ifBlank { "Direct helper did not stop before replacement" }
                    )
                }
                cancellation.throwIfCancelled()
                if (!settings.helperReplacementAllowed()) {
                    return DirectBridgeResult(false, "Helper replacement deferred by Shutdown or stopped collection")
                }
            }

            cancellation.throwIfCancelled()
            DirectStreamController.invalidateHelper()
            val launch = execShell(launchCommand(appContext, ownerMode), 15_000)
            if (!launch.ok) {
                return DirectBridgeResult(
                    ok = false,
                    message = launch.error ?: launch.output.ifBlank { "Direct helper launch failed" }
                )
            }

            repeat(12) {
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw AdbOperationCancelledException()
                }
                cancellation.throwIfCancelled()
                if (helper.isAlive()) {
                    if (replacementPending) {
                        if (!settings.helperReplacementAllowed()) {
                            helper.ownerMode()?.let(helper::requestStop)
                            return DirectBridgeResult(false, "Helper replacement interrupted by Shutdown or stopped collection")
                        }
                        if (!settings.confirmHelperReplacement(updateTime!!)) {
                            return DirectBridgeResult(false, "Could not confirm the installed helper version")
                        }
                    }
                    return DirectBridgeResult(ok = true, message = "Direct helper started")
                }
            }
            return DirectBridgeResult(ok = false, message = "Direct helper did not register Binder service after launch")
        } finally {
            launchLock.unlock()
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
        //stop and verify the previous owner before removing its lock or starting another reader
        val cleanup = "for pid in ${'$'}(pidof ${CollectorHelperProtocol.PROCESS_NAME} 2>/dev/null); " +
            "do kill \"${'$'}pid\" 2>/dev/null || true; done; " +
            "for i in 1 2 3 4 5; do pidof ${CollectorHelperProtocol.PROCESS_NAME} >/dev/null || break; sleep 1; done; " +
            "if pidof ${CollectorHelperProtocol.PROCESS_NAME} >/dev/null; then echo HELPER_STOP_TIMEOUT >&2; exit 73; fi; " +
            "rm -f ${CollectorHelperProtocol.LOCK_PATH}; "
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

    internal fun helperAbsenceCommand(): String = buildString {
        append("for i in 1 2 3 4 5; do ")
        append("pidof ${CollectorHelperProtocol.PROCESS_NAME} >/dev/null 2>&1 || exit 0; sleep 1; done; ")
        append("if pidof ${CollectorHelperProtocol.PROCESS_NAME} >/dev/null 2>&1; then ")
        append("echo HELPER_STOP_TIMEOUT >&2; exit 73; fi")
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
