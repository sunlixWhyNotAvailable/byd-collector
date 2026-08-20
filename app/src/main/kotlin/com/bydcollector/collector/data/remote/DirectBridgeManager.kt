package com.bydcollector.collector.data.remote

import android.content.Context
import android.os.Process
import com.bydcollector.collector.adb.AdbCancellation
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.adb.AdbOperationCancelledException
import com.bydcollector.collector.data.direct.DirectHelperOwnerMode
import com.bydcollector.collector.data.direct.DirectVehicleHelper
import com.bydcollector.collector.data.direct.DirectVehicleHelperClient
import com.bydcollector.collector.direct.CollectorHelperProtocol
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
        cancellation: AdbCancellation = AdbCancellation()
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
            //rechecks under the launch lock so concurrent callers cannot replace a correct fresh helper
            if (helper.ownerMode() == ownerMode) {
                return DirectBridgeResult(ok = true, message = "Direct helper already running in ${ownerMode.name} mode")
            }

            val launch = adbClient.execShell(launchCommand(context, ownerMode), timeoutMs = 15_000)
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
                if (helper.ownerMode() == ownerMode) {
                    return DirectBridgeResult(ok = true, message = "Direct helper started in ${ownerMode.name} mode")
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
        val modeArgument = if (ownerMode == DirectHelperOwnerMode.AUTONOMOUS_WORKER) {
            " ${CollectorHelperProtocol.WORKER_MODE_ARG}"
        } else {
            ""
        }
        //stop and verify the previous owner before removing its lock or starting another reader
        val cleanup = "for pid in ${'$'}(pidof ${CollectorHelperProtocol.PROCESS_NAME} 2>/dev/null); " +
            "do kill \"${'$'}pid\" 2>/dev/null || true; done; " +
            "for i in 1 2 3 4 5; do pidof ${CollectorHelperProtocol.PROCESS_NAME} >/dev/null || break; sleep 1; done; " +
            "if pidof ${CollectorHelperProtocol.PROCESS_NAME} >/dev/null; then echo HELPER_STOP_TIMEOUT >&2; exit 73; fi; " +
            "rm -f ${CollectorHelperProtocol.LOCK_PATH}; "
        return cleanup +
            "CLASSPATH=$quotedApk setsid app_process /system/bin --nice-name=${CollectorHelperProtocol.PROCESS_NAME} " +
            "${CollectorHelperProtocol.HELPER_CLASS} $appUid $quotedApk$modeArgument </dev/null >${CollectorHelperProtocol.LOG_PATH} 2>&1 & " +
            "for i in 1 2 3; do service list 2>/dev/null | grep -q ${CollectorHelperProtocol.SERVICE_NAME} && break; sleep 1; done"
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}

data class DirectBridgeResult(
    val ok: Boolean,
    val message: String
)
