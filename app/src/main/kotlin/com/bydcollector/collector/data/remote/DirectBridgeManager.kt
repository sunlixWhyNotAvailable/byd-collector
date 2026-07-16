package com.bydcollector.collector.data.remote

import android.content.Context
import android.os.Process
import com.bydcollector.collector.adb.AdbCancellation
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.adb.AdbOperationCancelledException
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
            //rechecks under the launch lock so concurrent callers cannot kill a freshly started helper
            if (helper.isAlive()) return DirectBridgeResult(ok = true, message = "Direct helper already running")

            val launch = adbClient.execShell(launchCommand(context), timeoutMs = 15_000)
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
                    return DirectBridgeResult(ok = true, message = "Direct helper started")
                }
            }
            return DirectBridgeResult(ok = false, message = "Direct helper did not register Binder service after launch")
        } finally {
            launchLock.unlock()
        }
    }

    fun launchCommand(context: Context): String {
        return launchCommand(apkPath = context.applicationContext.applicationInfo.sourceDir, appUid = Process.myUid())
    }

    fun launchCommand(apkPath: String, appUid: Int): String {
        val quotedApk = shellQuote(apkPath)
        //clear stale shell helper from prior install before starting the current app-owned helper
        val cleanup = "for pid in ${'$'}(pidof ${CollectorHelperProtocol.PROCESS_NAME} 2>/dev/null); " +
            "do kill \"${'$'}pid\" 2>/dev/null || true; done; " +
            "rm -f ${CollectorHelperProtocol.LOCK_PATH}; "
        return cleanup +
            "CLASSPATH=$quotedApk setsid app_process /system/bin --nice-name=${CollectorHelperProtocol.PROCESS_NAME} " +
            "${CollectorHelperProtocol.HELPER_CLASS} $appUid $quotedApk </dev/null >${CollectorHelperProtocol.LOG_PATH} 2>&1 & " +
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
