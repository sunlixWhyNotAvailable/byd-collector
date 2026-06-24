package com.bydcollector.collector.ui.compose

import java.util.Locale

data class MainPollDisplayStatus(
    val text: String,
    val kind: StatusKind
)

//keeps main polling status tied to the current runtime instead of stale historical poll rows
object MainPollStatusFormatter {
    fun format(
        running: Boolean,
        lastPollStatus: String?,
        strings: UiStrings
    ): MainPollDisplayStatus {
        if (!running) return MainPollDisplayStatus(strings.stopped, StatusKind.WAITING)

        val normalized = lastPollStatus.orEmpty().lowercase(Locale.US)
        return when {
            normalized.contains("error") || normalized.contains("failed") -> {
                MainPollDisplayStatus(strings.error.lowercase(Locale.getDefault()), StatusKind.ERROR)
            }
            normalized.startsWith("ok") -> MainPollDisplayStatus(strings.successful, StatusKind.OK)
            else -> MainPollDisplayStatus(strings.waiting, StatusKind.WAITING)
        }
    }
}
