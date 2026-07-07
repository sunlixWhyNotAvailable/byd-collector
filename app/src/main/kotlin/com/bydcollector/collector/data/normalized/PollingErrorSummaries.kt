package com.bydcollector.collector.data.normalized

object PollingErrorSummaries {
    fun summary(category: String?, message: String? = null): String {
        val base = when (category) {
            "network_error",
            "timeout",
            "bridge_launch_failed",
            "bridge_unavailable",
            "helper_launch_failed",
            "helper_unavailable",
            "helper_launch_backoff" -> "Direct telemetry unavailable"
            "http_error",
            "di_success_false",
            "parse_error",
            "autoservice_snapshot_empty",
            "autoservice_partial_failure" -> "Direct telemetry error"
            "adb_authorization_required" -> "ADB not authorized"
            "adb_authorization_unavailable" -> "ADB unavailable"
            "adb_authorization_timeout" -> "ADB authorization timeout"
            "service_start_error" -> "service start failed"
            else -> category ?: "unknown"
        }
        return base + message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    }
}
