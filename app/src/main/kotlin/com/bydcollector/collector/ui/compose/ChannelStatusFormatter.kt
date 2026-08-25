package com.bydcollector.collector.ui.compose

import com.bydcollector.collector.ui.RuntimeActionStatus
import java.util.Locale

//maps verbose channel health strings into compact ui labels without treating a healthy next batch as a retry failure
object ChannelStatusFormatter {
    fun compactText(
        status: String?,
        strings: UiStrings,
        runtimeStatus: RuntimeActionStatus? = null
    ): String {
        val normalized = status.orEmpty().lowercase(Locale.US)
        return when {
            normalized.hasFailureSignal() -> strings.error.lowercase(Locale.getDefault())
            runtimeStatus == RuntimeActionStatus.STARTING -> strings.starting
            runtimeStatus == RuntimeActionStatus.STOPPING -> strings.stopping
            runtimeStatus == RuntimeActionStatus.ERROR -> strings.error.lowercase(Locale.getDefault())
            runtimeStatus == RuntimeActionStatus.STOPPED -> strings.waiting
            normalized.contains("catch") || normalized.contains("export") || normalized.contains("run") -> strings.exporting
            normalized.contains("scheduled") || normalized.contains("idle") -> strings.waiting
            normalized.contains("enabled") -> strings.running
            runtimeStatus == RuntimeActionStatus.RUNNING -> strings.running
            else -> strings.waiting
        }
    }

    fun kind(
        status: String?,
        enabled: Boolean,
        runtimeStatus: RuntimeActionStatus? = null
    ): StatusKind {
        val normalized = status.orEmpty().lowercase(Locale.US)
        return when {
            normalized.hasFailureSignal() -> StatusKind.ERROR
            runtimeStatus == RuntimeActionStatus.ERROR -> StatusKind.ERROR
            runtimeStatus == RuntimeActionStatus.STARTING ||
                runtimeStatus == RuntimeActionStatus.STOPPING ||
                runtimeStatus == RuntimeActionStatus.STOPPED -> StatusKind.WAITING
            normalized.contains("scheduled") || normalized.contains("idle") -> StatusKind.WAITING
            runtimeStatus == RuntimeActionStatus.RUNNING -> StatusKind.OK
            enabled || normalized.contains("catch") || normalized.contains("export") || normalized.contains("run") -> StatusKind.OK
            else -> StatusKind.WAITING
        }
    }

    private fun String.hasFailureSignal(): Boolean {
        return contains("error") || contains("failed") || contains("retry #") || contains("backoff") ||
            contains("unreachable") || contains("timed out") || contains("timeout")
    }
}
