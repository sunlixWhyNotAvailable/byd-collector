package com.bydcollector.collector

import android.os.SystemClock
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.runtime.RuntimePreferences
import com.bydcollector.collector.ui.DashboardRowCounts

/** Headless-emulator lifecycle check for the owner-side count worker. */
internal object DashboardMetricsNativeGate {
    fun cases(context: android.content.Context): List<Pair<String, () -> Unit>> = listOf(
        "dashboard_metrics_worker_start_refresh_stop_restart" to { startRefreshStopRestart(context) }
    )

    private fun startRefreshStopRestart(context: android.content.Context) {
        val app = context.applicationContext as BydCollectorApplication
        val settings = CollectorSettings(context)
        check(!settings.isUserShutdownRequested()) { "Dashboard metrics gate requires Shutdown to be off" }
        check(!CollectorService.isRunning()) { "Dashboard metrics gate requires collectors to be stopped" }

        // Opening Main first mirrors the mandatory owner-store-ready seam. No Service or sender
        // is started, and the worker only performs reads against the emulator's existing store.
        val mainStore = BydCollectorApplication.store(context)
        val preferences = RuntimePreferences.get(context)
        val oldSharedPresent = preferences.contains(CollectorSettings.KEY_HA_SHARED_CATEGORIES)
        val oldShared = preferences.getBoolean(CollectorSettings.KEY_HA_SHARED_CATEGORIES, true)
        val oldInfluxPresent = preferences.contains(CollectorSettings.KEY_INFLUX_CATEGORIES)
        val oldInflux = preferences.getStringSet(CollectorSettings.KEY_INFLUX_CATEGORIES, null)?.toSet()
        val state = app.dashboardUiStateStore
        val worker = DashboardMetricsWorker(app)

        try {
            // An empty selected set makes this gate independent of Influx configuration/networking.
            check(preferences.edit()
                .putBoolean(CollectorSettings.KEY_HA_SHARED_CATEGORIES, false)
                .putStringSet(CollectorSettings.KEY_INFLUX_CATEGORIES, emptySet())
                .commit()) { "Could not configure sender-off worker gate" }

            val firstQueueRevision = state.queueMetricsRevisionSnapshot()
            worker.start()
            await("cold start did not publish queue metrics") {
                state.queueMetricsRevisionSnapshot() > firstQueueRevision && mainCountsKnown(state.rowCountSnapshot())
            }
            val firstCounts = state.rowCountSnapshot()
            val countsOracle = mainStore.dashboardRowCounts()
            check(sameMainCounts(firstCounts, countsOracle)) {
                "Worker did not publish the current Main row counts: $firstCounts versus $countsOracle"
            }

            val beforeRefresh = state.queueMetricsRevisionSnapshot()
            worker.requestDashboardMetricsRefresh()
            await("manual refresh did not publish a newer queue measurement") {
                state.queueMetricsRevisionSnapshot() > beforeRefresh
            }

            val stoppedRevision = state.queueMetricsRevisionSnapshot()
            val stoppedCounts = state.rowCountSnapshot()
            worker.stop()
            worker.requestDashboardMetricsRefresh()
            Thread.sleep(STOP_QUIET_WINDOW_MS)
            check(state.queueMetricsRevisionSnapshot() == stoppedRevision) {
                "Stopped worker published a queue measurement"
            }
            check(state.rowCountSnapshot() == stoppedCounts) { "Stopped worker published row counts" }

            worker.start()
            await("worker did not resume after restart") {
                state.queueMetricsRevisionSnapshot() > stoppedRevision
            }
        } finally {
            worker.close()
            val editor = preferences.edit()
            if (oldInfluxPresent) editor.putStringSet(CollectorSettings.KEY_INFLUX_CATEGORIES, oldInflux.orEmpty())
            else editor.remove(CollectorSettings.KEY_INFLUX_CATEGORIES)
            if (oldSharedPresent) editor.putBoolean(CollectorSettings.KEY_HA_SHARED_CATEGORIES, oldShared)
            else editor.remove(CollectorSettings.KEY_HA_SHARED_CATEGORIES)
            check(editor.commit()) { "Could not restore worker gate preferences" }
            // The test selected a temporary category set; never leave its queue result as current.
            state.invalidateQueueMetrics()
        }
    }

    private fun mainCountsKnown(counts: DashboardRowCounts): Boolean =
        counts.pollCount >= 0L && counts.valueRowCount >= 0L && counts.ecRowCount >= 0L &&
            counts.normalizedCurrentCount >= 0L && counts.normalizedHistoryCount >= 0L

    private fun sameMainCounts(left: DashboardRowCounts, right: com.bydcollector.collector.data.local.TelemetryRowCounts): Boolean =
        left.pollCount == right.pollCount && left.valueRowCount == right.valueRowCount &&
            left.ecRowCount == right.ecRowCount && left.normalizedCurrentCount == right.normalizedCurrentCount &&
            left.normalizedHistoryCount == right.normalizedHistoryCount

    private fun await(message: String, timeoutMs: Long = WAIT_TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(WAIT_POLL_MS)
        }
        check(condition()) { message }
    }

    private const val STOP_QUIET_WINDOW_MS = 250L
    private const val WAIT_POLL_MS = 25L
    private const val WAIT_TIMEOUT_MS = 20_000L
}
