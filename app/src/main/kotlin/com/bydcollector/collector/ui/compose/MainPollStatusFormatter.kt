package com.bydcollector.collector.ui.compose

import java.util.Locale
import com.bydcollector.collector.data.callback.CallbackQueuePhase
import com.bydcollector.collector.data.callback.CallbackQueueState

data class MainPollDisplayStatus(
    val text: String,
    val kind: StatusKind
)

//keeps main polling status tied to the current runtime instead of stale historical poll rows
object MainPollStatusFormatter {
    fun format(
        running: Boolean,
        lastPollStatus: String?,
        strings: UiStrings,
        queue: CallbackQueueState? = null
    ): MainPollDisplayStatus {
        if (!running) return MainPollDisplayStatus(strings.stopped, StatusKind.WAITING)

        val normalized = lastPollStatus.orEmpty().lowercase(Locale.US)
        return when {
            normalized.contains("error") || normalized.contains("failed") -> {
                MainPollDisplayStatus(strings.error.lowercase(Locale.getDefault()), StatusKind.ERROR)
            }
            queue != null -> queueStatus(queue, strings)!!
            normalized.startsWith("ok") -> MainPollDisplayStatus(strings.collectionRunning, StatusKind.OK)
            else -> MainPollDisplayStatus(strings.waiting, StatusKind.WAITING)
        }
    }

    fun queueStatus(queue: CallbackQueueState?, strings: UiStrings): MainPollDisplayStatus? = when (queue?.phase) {
        null -> null
        CallbackQueuePhase.HEALTHY -> MainPollDisplayStatus(strings.collectionRunning, StatusKind.OK)
        CallbackQueuePhase.CATCHING_UP -> MainPollDisplayStatus(strings.catchingUp, StatusKind.WARNING)
        CallbackQueuePhase.UNKNOWN, CallbackQueuePhase.WAITING -> MainPollDisplayStatus(strings.waiting, StatusKind.WAITING)
        CallbackQueuePhase.ERROR -> MainPollDisplayStatus(strings.error, StatusKind.ERROR)
    }
}
