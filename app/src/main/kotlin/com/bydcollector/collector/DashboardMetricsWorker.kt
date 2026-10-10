package com.bydcollector.collector

import android.content.SharedPreferences
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.bydcollector.collector.data.debug.DirectDebugRowCountState
import com.bydcollector.collector.data.debug.DirectDebugStore
import com.bydcollector.collector.data.local.TelemetryRowCounts
import com.bydcollector.collector.ha.HaIntegrationCategories
import com.bydcollector.collector.influx.InfluxConfig
import com.bydcollector.collector.influx.InfluxPendingSummary
import com.bydcollector.collector.influx.influxEffectiveFields
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.ui.DashboardRowCounts
import com.bydcollector.collector.ui.DashboardUiStateStore
import com.bydcollector.collector.ui.UNKNOWN_DASHBOARD_COUNT
import com.bydcollector.collector.runtime.RuntimePreferences
import com.bydcollector.collector.util.dispatchOperationalEvent
import com.bydcollector.collector.util.sharedOperationalEventExecutor
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal enum class DashboardMetricsPassDisposition { COMPLETE, INCOMPLETE, DEFERRED }

internal fun dashboardMetricsRetryDelayMs(disposition: DashboardMetricsPassDisposition): Long? = when (disposition) {
    DashboardMetricsPassDisposition.COMPLETE -> null
    DashboardMetricsPassDisposition.INCOMPLETE -> DASHBOARD_METRICS_CONTINUATION_MS
    DashboardMetricsPassDisposition.DEFERRED -> DASHBOARD_METRICS_DEFER_MS
}

/** Owner-side, cancellable count refresh. SQLite reads never run on the UI or control executor. */
internal class DashboardMetricsWorker(
    private val application: BydCollectorApplication
) : SharedPreferences.OnSharedPreferenceChangeListener, Closeable {
    private val state: DashboardUiStateStore get() = application.dashboardUiStateStore
    private val preferences = RuntimePreferences.get(application)
    private val control = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "byd-dashboard-metrics-control") }
    private val reads = Executors.newSingleThreadExecutor { task -> Thread(task, "byd-dashboard-metrics-read") }
    private val lock = Any()
    private val debugCountState = DirectDebugRowCountState()
    private var active = false
    private var closed = false
    private var lifecycleGeneration = 0L
    private var nextRunId = 0L
    private var activeRunId: Long? = null
    private var activeCancellation: CancellationSignal? = null
    private var timeoutTask: ScheduledFuture<*>? = null
    private var retryTask: ScheduledFuture<*>? = null
    private var periodicTask: ScheduledFuture<*>? = null
    private var requestPending = false
    private var listenerRegistered = false
    private var categoryRevision = 0L
    private var lastFieldSelection: Set<String>? = null
    private var lastStorageGeneration: Long? = null
    private var activeCountGeneration: Long? = null
    private var queueMetricsKnown = false
    private var lastFailureAtElapsedMs = Long.MIN_VALUE

    init {
        control.removeOnCancelPolicy = true
    }

    fun start() {
        if (preferences.getBoolean(CollectorSettings.KEY_USER_SHUTDOWN, false)) return
        val selectedFields = selectedInfluxFields()
        val invalidate = synchronized(lock) {
            if (closed || active || preferences.getBoolean(CollectorSettings.KEY_USER_SHUTDOWN, false)) return
            active = true
            lifecycleGeneration = nextRevision(lifecycleGeneration)
            val changed = lastFieldSelection != null && lastFieldSelection != selectedFields
            lastFieldSelection = selectedFields
            if (!listenerRegistered) {
                preferences.registerOnSharedPreferenceChangeListener(this)
                listenerRegistered = true
            }
            changed
        }
        if (invalidate) {
            synchronized(lock) { queueMetricsKnown = false }
            state.invalidateQueueMetrics()
        }
        requestDashboardMetricsRefresh()
        synchronized(lock) {
            if (active && periodicTask == null) {
                periodicTask = control.scheduleAtFixedRate(
                    { requestDashboardMetricsRefresh() },
                    DASHBOARD_METRICS_PERIOD_MS,
                    DASHBOARD_METRICS_PERIOD_MS,
                    TimeUnit.MILLISECONDS
                )
            }
        }
    }

    fun requestDashboardMetricsRefresh() {
        synchronized(lock) {
            if (!active || closed) return
            if (activeRunId != null) {
                requestPending = true
                return
            }
            retryTask?.cancel(false)
            retryTask = control.schedule({ launchPass() }, 0L, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        val cancellation: CancellationSignal?
        val countGeneration: Long?
        synchronized(lock) {
            if (!active) return
            active = false
            lifecycleGeneration = nextRevision(lifecycleGeneration)
            retryTask?.cancel(false)
            retryTask = null
            periodicTask?.cancel(false)
            periodicTask = null
            timeoutTask?.cancel(false)
            timeoutTask = null
            cancellation = activeCancellation
            activeCancellation = null
            activeRunId = null
            requestPending = false
            countGeneration = activeCountGeneration
            activeCountGeneration = null
            if (listenerRegistered) {
                preferences.unregisterOnSharedPreferenceChangeListener(this)
                listenerRegistered = false
            }
        }
        cancellation?.cancel()
        countGeneration?.let(state::failCountBootstrap)
    }

    override fun close() {
        synchronized(lock) { closed = true }
        stop()
        control.shutdownNow()
        reads.shutdownNow()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            CollectorSettings.KEY_USER_SHUTDOWN -> {
                if (preferences.getBoolean(CollectorSettings.KEY_USER_SHUTDOWN, false)) stop()
            }
            CollectorSettings.KEY_INFLUX_CATEGORIES,
            CollectorSettings.KEY_MQTT_CATEGORIES,
            CollectorSettings.KEY_HA_SHARED_CATEGORIES -> {
                val fields = selectedInfluxFields()
                val changed = synchronized(lock) {
                    categoryRevision = nextRevision(categoryRevision)
                    val differs = lastFieldSelection != null && lastFieldSelection != fields
                    lastFieldSelection = fields
                    if (differs) queueMetricsKnown = false
                    differs
                }
                if (changed) state.invalidateQueueMetrics()
                requestDashboardMetricsRefresh()
            }
        }
    }

    private fun launchPass() {
        val runId: Long
        val runGeneration: Long
        val cancellation = CancellationSignal()
        synchronized(lock) {
            retryTask = null
            if (!active || closed || activeRunId != null) return
            nextRunId = nextRevision(nextRunId)
            runId = nextRunId
            runGeneration = lifecycleGeneration
            activeRunId = runId
            activeCancellation = cancellation
            requestPending = false
        }
        val timeout = control.schedule({ cancellation.cancel() }, DASHBOARD_METRICS_QUERY_BUDGET_MS, TimeUnit.MILLISECONDS)
        synchronized(lock) {
            if (activeRunId == runId) timeoutTask = timeout else timeout.cancel(false)
        }
        try {
            reads.execute {
                val disposition = try {
                    refresh(runId, runGeneration, cancellation)
                } catch (error: Throwable) {
                    if (!isCancellation(error, cancellation)) {
                        invalidateQueueMetricsIfKnown(runId, runGeneration)
                        logFailure("Dashboard metrics refresh failed", error)
                    }
                    DashboardMetricsPassDisposition.DEFERRED
                }
                runCatching { control.execute { finishPass(runId, runGeneration, disposition) } }
            }
        } catch (error: Throwable) {
            logFailure("Dashboard metrics refresh was rejected", error)
            finishPass(runId, runGeneration, DashboardMetricsPassDisposition.DEFERRED)
        }
    }

    private fun refresh(
        runId: Long,
        runGeneration: Long,
        cancellation: CancellationSignal
    ): DashboardMetricsPassDisposition {
        if (!isCurrent(runId, runGeneration)) return DashboardMetricsPassDisposition.DEFERRED
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val measuredAt = SystemClock.elapsedRealtime()
        val expectedStorageGeneration = state.storageGenerationSnapshot()
        val categories = categorySnapshot()
        val expectedQueueRevision = state.queueMetricsRevisionSnapshot()
        if (lastStorageGeneration != expectedStorageGeneration) {
            debugCountState.reset()
            lastStorageGeneration = expectedStorageGeneration
            synchronized(lock) { queueMetricsKnown = false }
        }
        val countGeneration = state.beginCountBootstrap(force = true)
            ?: return DashboardMetricsPassDisposition.DEFERRED
        synchronized(lock) {
            if (!isCurrentLocked(runId, runGeneration)) {
                state.failCountBootstrap(countGeneration)
                return DashboardMetricsPassDisposition.DEFERRED
            }
            activeCountGeneration = countGeneration
        }

        var rowCountsPublished = false
        try {
            val reads = application.tryTelemetryStoreRead { store ->
                MainMetricsRead(
                    mqtt = runCatching { store.refreshMqttPendingCount(cancellation) },
                    influx = runCatching { store.refreshInfluxPendingSummary(categories.fields, cancellation) },
                    counts = runCatching { store.dashboardRowCounts(cancellation) }
                )
            } ?: return DashboardMetricsPassDisposition.DEFERRED

            if (cancellation.isCanceled) return DashboardMetricsPassDisposition.DEFERRED
            var deferred = false
            var incomplete = categories.revisionChanged
            val mqtt = reads.mqtt.getOrElse { error ->
                if (isCancellation(error, cancellation)) return DashboardMetricsPassDisposition.DEFERRED
                invalidateQueueMetricsIfKnown(runId, runGeneration, expectedStorageGeneration)
                logFailure("MQTT outbox count failed", error)
                deferred = true
                null
            }
            val influx = reads.influx.getOrElse { error ->
                if (isCancellation(error, cancellation)) return DashboardMetricsPassDisposition.DEFERRED
                invalidateQueueMetricsIfKnown(runId, runGeneration, expectedStorageGeneration)
                logFailure("Influx pending count failed", error)
                deferred = true
                null
            }
            val mainCounts = reads.counts.getOrElse { error ->
                if (isCancellation(error, cancellation)) return DashboardMetricsPassDisposition.DEFERRED
                logFailure("Dashboard row counts failed", error)
                deferred = true
                null
            }

            val debugReady = application.isDebugStorageReady()
            val debugCount = if (!debugReady) {
                UNKNOWN_DASHBOARD_COUNT
            } else {
                val secondary = runCatching {
                    application.trySecondaryDatabaseRead {
                        DirectDebugStore(application).use { it.dashboardReadingCount(debugCountState, cancellation) }
                    }
                }.getOrElse { error ->
                    if (isCancellation(error, cancellation)) return DashboardMetricsPassDisposition.DEFERRED
                    logFailure("Secondary row count failed", error)
                    deferred = true
                    null
                }
                if (secondary == null) deferred = true
                secondary ?: UNKNOWN_DASHBOARD_COUNT
            }

            if (mainCounts != null) {
                val mainComplete = mainCounts.pollCount >= 0L && mainCounts.valueRowCount >= 0L &&
                    mainCounts.ecRowCount >= 0L && mainCounts.normalizedCurrentCount >= 0L &&
                    mainCounts.normalizedHistoryCount >= 0L
                val debugComplete = !debugReady || debugCount >= 0L
                if (mainComplete && debugComplete) {
                    val counts = DashboardRowCounts(
                        mainCounts.pollCount,
                        mainCounts.valueRowCount,
                        mainCounts.ecRowCount,
                        mainCounts.normalizedCurrentCount,
                        mainCounts.normalizedHistoryCount,
                        debugCount
                    )
                    rowCountsPublished = publishRowCounts(
                        runId, runGeneration, countGeneration, counts, measuredAt, expectedStorageGeneration
                    )
                    if (!rowCountsPublished) incomplete = true
                } else {
                    incomplete = true
                }
            }

            val queueComplete = mqtt != null && mqtt >= 0L && influx != null && influx.complete && influx.rows >= 0L
            if (queueComplete && !categories.revisionChanged && isCurrent(runId, runGeneration)) {
                val published = publishQueueMetrics(
                    runId,
                    runGeneration,
                    categories,
                    mqtt,
                    requireNotNull(influx),
                    measuredAt,
                    expectedStorageGeneration,
                    expectedQueueRevision
                )
                if (!published) incomplete = true
            } else if (mqtt != null && influx != null && !queueComplete) {
                incomplete = true
            }

            if (cancellation.isCanceled) return DashboardMetricsPassDisposition.DEFERRED
            return when {
                deferred -> DashboardMetricsPassDisposition.DEFERRED
                incomplete -> DashboardMetricsPassDisposition.INCOMPLETE
                else -> DashboardMetricsPassDisposition.COMPLETE
            }
        } finally {
            if (!rowCountsPublished) state.failCountBootstrap(countGeneration)
            synchronized(lock) {
                if (activeCountGeneration == countGeneration) activeCountGeneration = null
            }
        }
    }

    private fun publishRowCounts(
        runId: Long,
        runGeneration: Long,
        countGeneration: Long,
        counts: DashboardRowCounts,
        measuredAt: Long,
        storageGeneration: Long
    ): Boolean = synchronized(lock) {
        if (!isCurrentLocked(runId, runGeneration)) return@synchronized false
        state.publishRowCountBaseline(countGeneration, counts, measuredAt, storageGeneration)
    }

    private fun publishQueueMetrics(
        runId: Long,
        runGeneration: Long,
        categories: CategorySnapshot,
        mqtt: Long,
        influx: InfluxPendingSummary,
        measuredAt: Long,
        storageGeneration: Long,
        queueRevision: Long
    ): Boolean = synchronized(lock) {
        if (!isCurrentLocked(runId, runGeneration) || categoryRevision != categories.revision ||
            selectedInfluxFields() != categories.fields
        ) return@synchronized false
        val published = state.publishQueueMetrics(
            mqtt,
            influx.rows,
            influx.oldestObservedAt,
            measuredAt,
            storageGeneration,
            queueRevision
        )
        if (published) queueMetricsKnown = true
        published
    }

    private fun finishPass(
        runId: Long,
        runGeneration: Long,
        disposition: DashboardMetricsPassDisposition
    ) {
        synchronized(lock) {
            if (activeRunId != runId) return
            activeRunId = null
            activeCancellation = null
            timeoutTask?.cancel(false)
            timeoutTask = null
            if (!active || lifecycleGeneration != runGeneration) return
            val delay = if (requestPending) 0L else dashboardMetricsRetryDelayMs(disposition)
            requestPending = false
            if (delay != null) retryTask = control.schedule({ launchPass() }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun categorySnapshot(): CategorySnapshot = synchronized(lock) {
        val fields = selectedInfluxFields()
        val changed = lastFieldSelection != null && lastFieldSelection != fields
        if (changed) {
            categoryRevision = nextRevision(categoryRevision)
            queueMetricsKnown = false
        }
        lastFieldSelection = fields
        if (changed) state.invalidateQueueMetrics()
        CategorySnapshot(fields, categoryRevision, revisionChanged = changed)
    }

    private fun selectedInfluxFields(): Set<String> {
        val shared = preferences.getBoolean(CollectorSettings.KEY_HA_SHARED_CATEGORIES, true)
        val categoryKey = if (shared) CollectorSettings.KEY_MQTT_CATEGORIES else CollectorSettings.KEY_INFLUX_CATEGORIES
        val categories = preferences.getStringSet(categoryKey, null)?.toSet() ?: HaIntegrationCategories.defaults
        return influxEffectiveFields(
            InfluxConfig(
                enabled = false,
                host = "",
                port = InfluxConfig.DEFAULT_PORT,
                database = "",
                username = null,
                password = null,
                measurement = "",
                enabledCategories = categories
            )
        )
    }

    private fun isCurrent(runId: Long, runGeneration: Long): Boolean = synchronized(lock) {
        isCurrentLocked(runId, runGeneration)
    }

    private fun isCurrentLocked(runId: Long, runGeneration: Long): Boolean =
        active && !closed && activeRunId == runId && lifecycleGeneration == runGeneration

    private fun invalidateQueueMetricsIfKnown(
        runId: Long,
        runGeneration: Long,
        expectedStorageGeneration: Long? = null
    ) {
        synchronized(lock) {
            if (!isCurrentLocked(runId, runGeneration) || !queueMetricsKnown) return
            if (expectedStorageGeneration != null &&
                state.storageGenerationSnapshot() != expectedStorageGeneration
            ) return
            queueMetricsKnown = false
            state.invalidateQueueMetrics()
        }
    }

    private fun logFailure(message: String, error: Throwable) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (lastFailureAtElapsedMs != Long.MIN_VALUE &&
                now - lastFailureAtElapsedMs < DASHBOARD_METRICS_ERROR_LOG_INTERVAL_MS
            ) return
            lastFailureAtElapsedMs = now
        }
        Log.w(TAG, message, error)
        dispatchOperationalEvent(sharedOperationalEventExecutor) {
            runCatching {
                application.operationalEventJournal.append(
                    Instant.now().toString(),
                    SystemClock.elapsedRealtime(),
                    "dashboard_metrics",
                    "refresh_error",
                    "${message.take(96)}: ${error.message ?: error::class.java.simpleName}".take(320)
                )
            }
        }
    }

    private fun isCancellation(error: Throwable, cancellation: CancellationSignal): Boolean =
        cancellation.isCanceled || error is OperationCanceledException || error is CancellationException

    private fun nextRevision(value: Long): Long {
        check(value < Long.MAX_VALUE) { "dashboard metrics revision overflow" }
        return value + 1L
    }

    private data class MainMetricsRead(
        val mqtt: Result<Long>,
        val influx: Result<InfluxPendingSummary>,
        val counts: Result<TelemetryRowCounts>
    )

    private data class CategorySnapshot(
        val fields: Set<String>,
        val revision: Long,
        val revisionChanged: Boolean
    )

    private companion object {
        const val TAG = "DashboardMetrics"
    }
}

internal const val DASHBOARD_METRICS_PERIOD_MS = 30_000L
internal const val DASHBOARD_METRICS_CONTINUATION_MS = 50L
internal const val DASHBOARD_METRICS_DEFER_MS = 1_000L
internal const val DASHBOARD_METRICS_QUERY_BUDGET_MS = 2_000L
internal const val DASHBOARD_METRICS_ERROR_LOG_INTERVAL_MS = 30_000L
