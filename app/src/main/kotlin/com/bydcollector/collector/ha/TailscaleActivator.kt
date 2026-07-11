package com.bydcollector.collector.ha

import android.content.Context
import com.bydcollector.collector.adb.AdbLocalClient
import java.io.File

object TailscaleActivator {
    fun packageCandidates(): List<String> = listOf("com.tailscale.ipn")

    fun activate(context: Context): TailscaleActivationResult {
        val launchIntent = packageCandidates()
            .asSequence()
            .mapNotNull { packageName -> context.packageManager.getLaunchIntentForPackage(packageName) }
            .firstOrNull()
            ?: return TailscaleActivationResult(false, "tailscale_not_installed")
        val component = launchIntent.component
            ?: return TailscaleActivationResult(false, "tailscale_launcher_missing")

        val result = AdbLocalClient(File(context.filesDir, "adb_keys")).execShell(
            launchCommand(component.packageName, component.className)
        )
        return if (result.ok) {
            TailscaleActivationResult(true, "tailscale_launch_requested_via_adb")
        } else {
            TailscaleActivationResult(
                false,
                "tailscale_adb_launch_failed: ${result.error ?: result.output.trim().ifEmpty { "no detail" }}"
            )
        }
    }

    fun launchCommand(packageName: String, className: String): String =
        "am start -n $packageName/$className"
}

data class TailscaleActivationResult(
    val ok: Boolean,
    val message: String
)
