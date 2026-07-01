package com.bydcollector.collector.ha

import android.content.Context
import android.content.Intent

object TailscaleActivator {
    fun packageCandidates(): List<String> = listOf("com.tailscale.ipn")

    fun activate(context: Context): TailscaleActivationResult {
        val launchIntent = packageCandidates()
            .asSequence()
            .mapNotNull { packageName -> context.packageManager.getLaunchIntentForPackage(packageName) }
            .firstOrNull()
            ?: return TailscaleActivationResult(false, "tailscale_not_installed")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            TailscaleActivationResult(true, "tailscale_launch_requested")
        }.getOrElse { error ->
            TailscaleActivationResult(false, "${error::class.java.simpleName}: ${error.message ?: "no message"}")
        }
    }
}

data class TailscaleActivationResult(
    val ok: Boolean,
    val message: String
)
