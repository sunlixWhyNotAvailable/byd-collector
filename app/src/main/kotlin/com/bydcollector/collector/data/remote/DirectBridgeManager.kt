package com.bydcollector.collector.data.remote

import android.content.Context
import android.os.Process
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.data.direct.DirectVehicleHelper
import com.bydcollector.collector.data.direct.DirectVehicleHelperClient
import com.bydcollector.collector.direct.CollectorHelperProtocol
import java.io.File

object DirectBridgeManager {
    fun status(context: Context): String? {
        if (DirectVehicleHelperClient().isAlive()) return "ready"

        val appContext = context.applicationContext
        val authorization = AdbLocalClient(File(appContext.filesDir, "adb_keys")).checkAuthorization()
        return when (authorization.category) {
            "adb_authorization_connected" -> "ready"
            "adb_authorization_required" -> "needs Grant ADB"
            "adb_authorization_unavailable",
            "adb_authorization_timeout",
            "adb_authorization_error" -> "unavailable"
            else -> "unavailable"
        }
    }

    fun ensureRunning(
        context: Context,
        adbClient: AdbLocalClient,
        helper: DirectVehicleHelper = DirectVehicleHelperClient()
    ): DirectBridgeResult {
        if (helper.isAlive()) return DirectBridgeResult(ok = true, message = "Direct helper already running")

        val launch = adbClient.execShell(launchCommand(context), timeoutMs = 15_000)
        if (!launch.ok) {
            return DirectBridgeResult(
                ok = false,
                message = launch.error ?: launch.output.ifBlank { "Direct helper launch failed" }
            )
        }

        repeat(12) {
            Thread.sleep(250)
            if (helper.isAlive()) {
                return DirectBridgeResult(ok = true, message = "Direct helper started")
            }
        }
        return DirectBridgeResult(ok = false, message = "Direct helper did not register Binder service after launch")
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
            "${CollectorHelperProtocol.HELPER_CLASS} $appUid </dev/null >${CollectorHelperProtocol.LOG_PATH} 2>&1 & " +
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
