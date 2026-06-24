package com.bydcollector.collector.ui.compose

import java.util.Locale

//maps verbose channel health strings into compact ui labels without letting enabled+retry look healthy
object ChannelStatusFormatter {
    fun compactText(status: String?, strings: UiStrings): String {
        val normalized = status.orEmpty().lowercase(Locale.US)
        return when {
            normalized.hasFailureSignal() -> strings.error.lowercase(Locale.getDefault())
            normalized.contains("catch") || normalized.contains("export") || normalized.contains("run") -> strings.exporting
            normalized.contains("enabled") -> strings.running
            normalized.contains("idle") || normalized.isBlank() -> strings.waiting
            else -> strings.waiting
        }
    }

    fun kind(status: String?, enabled: Boolean): StatusKind {
        val normalized = status.orEmpty().lowercase(Locale.US)
        return when {
            normalized.hasFailureSignal() -> StatusKind.ERROR
            enabled || normalized.contains("catch") || normalized.contains("export") || normalized.contains("run") -> StatusKind.OK
            else -> StatusKind.WAITING
        }
    }

    private fun String.hasFailureSignal(): Boolean {
        return contains("error") || contains("failed") || contains("retry") || contains("backoff")
    }
}
