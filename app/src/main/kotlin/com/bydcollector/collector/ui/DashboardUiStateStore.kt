package com.bydcollector.collector.ui

import android.os.SystemClock
import com.bydcollector.collector.data.callback.CallbackQueueState
import com.bydcollector.collector.maintenance.ArchiveStorageJobStatus
import com.bydcollector.collector.maintenance.DbMaintenanceRuntimeStatus
import com.bydcollector.collector.ui.compose.AppTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class CachedDashboardState(
    val state: DashboardState,
    val loadedAtElapsedMs: Long,
    val generation: Long,
    val inFlight: Boolean,
    val lastError: String?
)

data class DashboardRuntimeFlags(
    val serviceRunning: Boolean,
    val mainPollingRunning: Boolean,
    val debugPollingRunning: Boolean,
    val pollingEnabled: Boolean,
    val debugPollingEnabled: Boolean,
    val mqttEnabled: Boolean,
    val influxEnabled: Boolean,
    val permissionsGranted: Boolean,
    val adbAuthorized: Boolean,
    val dbMaintenanceStatus: DbMaintenanceRuntimeStatus,
    val archiveStorageJobStatus: ArchiveStorageJobStatus,
    val debugRuntimeStatus: DebugRuntimeStatus = if (debugPollingRunning) DebugRuntimeStatus.RUNNING else DebugRuntimeStatus.STOPPED,
    val debugRuntimeError: String? = null,
    val mainRuntimeStatus: RuntimeActionStatus =
        if (mainPollingRunning) RuntimeActionStatus.RUNNING else RuntimeActionStatus.STOPPED,
    val mqttRuntimeStatus: RuntimeActionStatus =
        if (mqttEnabled) RuntimeActionStatus.RUNNING else RuntimeActionStatus.STOPPED,
    val influxRuntimeStatus: RuntimeActionStatus =
        if (influxEnabled) RuntimeActionStatus.RUNNING else RuntimeActionStatus.STOPPED,
    val mainCallbackQueue: CallbackQueueState? = null,
    val secondaryCallbackQueue: CallbackQueueState? = null
)

data class DashboardMainPollState(
    val activeSessionId: Long?,
    val lastSuccessAt: String?,
    val lastError: String?,
    val lastErrorAt: String?,
    val lastPollStatus: String?,
    val elapsedMs: Long?,
    val requestCount: Int?
)

data class DashboardDebugPollState(
    val lastReadingAt: String?,
    val lastErrorAt: String?,
    val lastError: String?,
    val errorCount: Long,
    val lastSessionId: Long?
)

private data class DashboardQueueMetrics(
    val mqttPendingCount: Long,
    val influxPendingRows: Long,
    val influxOldestPendingAt: String?
)

class DashboardUiStateStore(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    internal var onRuntimeChanged: () -> Unit = {}
    private val lock = Any() //Serializes cache reads, writes, and generation checks.
    private val ownerStartedAtElapsedMs = clock()
    private val ownerIncarnationId = UUID.randomUUID().toString()
    private var nextGeneration = 0L
    private val chrome = MutableStateFlow<CachedDashboardState?>(null)
    private val tabs = AppTab.entries.associateWith {
        MutableStateFlow<CachedDashboardState?>(null)
    }
    private val pendingGenerations = mutableMapOf<AppTab?, Long>()
    private var selectedKpiLanguage = VehicleKpiLanguage.UK
    private var localizedVehicleKpis = mapOf(
        VehicleKpiLanguage.UK to VehicleKpis(),
        VehicleKpiLanguage.EN to VehicleKpis()
    )
    private var rowCounts = DashboardRowCounts(
        UNKNOWN_DASHBOARD_COUNT, UNKNOWN_DASHBOARD_COUNT, UNKNOWN_DASHBOARD_COUNT,
        UNKNOWN_DASHBOARD_COUNT, UNKNOWN_DASHBOARD_COUNT, UNKNOWN_DASHBOARD_COUNT
    )
    private var countBootstrapInFlight = false
    private var countBootstrapGeneration = 0L
    private var ownerStorageGeneration = 0L
    private var ownerRevision = 0L
    private var ownerMeasuredAtElapsedMs = 0L
    private var callbackQueueRevision = 0L
    private var callbackQueueMeasuredAtElapsedMs = 0L
    private var rowCountRevision = 0L
    private var rowCountMeasuredAtElapsedMs = 0L
    private var rowCountStorageGeneration = 0L
    private var queueMetricsRevision = 0L
    private var queueMetricsMeasuredAtElapsedMs = 0L
    private var queueMetrics = DashboardQueueMetrics(
        UNKNOWN_DASHBOARD_COUNT, UNKNOWN_DASHBOARD_COUNT, null
    )
    private var integrationRevision = 0L
    private var integrationMeasuredAtElapsedMs = 0L
    private var integrationStorageGeneration = 0L
    private var initialHydrationStarted = false
    private var runtimeFlags: DashboardRuntimeFlags? = null
    private var accessStatus: Pair<Boolean, Boolean>? = null
    private var mainPollState: DashboardMainPollState? = null
    private var debugPollState: DashboardDebugPollState? = null
    private var integrationRuntimeState: DashboardState? = null
    private var mainDatabaseSizeBytes: Long? = null
    private var debugDatabaseSizeBytes: Long? = null
    private var tripsDatabaseSizeBytes: Long? = null

    val chromeState: StateFlow<CachedDashboardState?> = chrome.asStateFlow()
    private val tabFlows = tabs.mapValues { (_, flow) -> flow.asStateFlow() }

    fun tabState(tab: AppTab): StateFlow<CachedDashboardState?> = tabFlows.getValue(tab)

    fun seed(initial: DashboardState) {
        synchronized(lock) {
            val emptyTabs = AppTab.entries.filter { tabs.getValue(it).value == null }
            if (chrome.value != null && emptyTabs.isEmpty()) return

            val seeded = mergeLatestQueueOverlayLocked(applyRuntimeOverlays(initial))
            val generation = nextGenerationLocked()
            val loadedAtElapsedMs = clock()
            if (chrome.value == null) {
                pendingGenerations.remove(null)
                chrome.value = CachedDashboardState(
                    state = seeded,
                    loadedAtElapsedMs = loadedAtElapsedMs,
                    generation = generation,
                    inFlight = false,
                    lastError = null
                )
            }
            emptyTabs.forEach { tab ->
                pendingGenerations.remove(tab)
                tabs.getValue(tab).value = CachedDashboardState(
                    state = seeded,
                    loadedAtElapsedMs = loadedAtElapsedMs,
                    generation = generation,
                    inFlight = false,
                    lastError = null
                )
            }
        }
    }

    fun beginChromeRefresh(): Long = beginRefresh(null)

    fun beginTabRefresh(tab: AppTab): Long = beginRefresh(tab)

    fun publishChrome(generation: Long, state: DashboardState, dataRefreshed: Boolean = true): Boolean {
        return publish(null, generation, state, dataRefreshed)
    }

    fun publishTab(tab: AppTab, generation: Long, state: DashboardState, dataRefreshed: Boolean = true): Boolean {
        return publish(tab, generation, state, dataRefreshed)
    }

    fun failChrome(generation: Long, error: String): Boolean {
        return fail(null, generation, error)
    }

    fun failTab(tab: AppTab, generation: Long, error: String): Boolean {
        return fail(tab, generation, error)
    }

    fun currentChrome(): DashboardState? = synchronized(lock) { chrome.value?.state }

    fun currentTab(tab: AppTab): DashboardState? = synchronized(lock) { tabs.getValue(tab).value?.state }

    internal fun rowCountSnapshot(): DashboardRowCounts = synchronized(lock) { rowCounts }

    /** Captured before a bounded count read and checked again before its result is published. */
    fun storageGenerationSnapshot(): Long = synchronized(lock) { ownerStorageGeneration }

    /** Category changes fence an in-flight queue read without invalidating unrelated storage metrics. */
    fun queueMetricsRevisionSnapshot(): Long = synchronized(lock) { queueMetricsRevision }

    fun markTabStale(tab: AppTab) {
        synchronized(lock) {
            val flow = tabs.getValue(tab)
            //Zero is the stale sentinel; the last non-null snapshot remains visible.
            // A load begun before this invalidation must not erase it with an older result.
            pendingGenerations.remove(tab)
            flow.value = flow.value?.copy(loadedAtElapsedMs = 0L, inFlight = false)
        }
    }

    fun selectVehicleKpiLanguage(language: VehicleKpiLanguage) {
        synchronized(lock) {
            if (selectedKpiLanguage == language) return
            selectedKpiLanguage = language
            updateTargetsLocked(setOf(AppTab.ALL_PARAMETERS), includeChrome = false)
        }
    }

    fun publishVehicleKpis(uk: VehicleKpis, en: VehicleKpis) {
        synchronized(lock) {
            if (localizedVehicleKpis[VehicleKpiLanguage.UK] == uk &&
                localizedVehicleKpis[VehicleKpiLanguage.EN] == en) return
            localizedVehicleKpis = mapOf(
                VehicleKpiLanguage.UK to uk,
                VehicleKpiLanguage.EN to en
            )
            updateTargetsLocked(setOf(AppTab.ALL_PARAMETERS), includeChrome = false)
        }
    }

    fun clearVehicleKpis() = publishVehicleKpis(VehicleKpis(), VehicleKpis())

    fun beginCountBootstrap(force: Boolean = false): Long? {
        return synchronized(lock) {
            val hasKnownBaseline = rowCounts?.let { counts ->
                counts.pollCount >= 0L &&
                    counts.valueRowCount >= 0L &&
                    counts.ecRowCount >= 0L &&
                    counts.normalizedCurrentCount >= 0L &&
                    counts.normalizedHistoryCount >= 0L &&
                    counts.debugReadingCount >= 0L
            } == true
            if (countBootstrapInFlight || (!force && hasKnownBaseline)) return@synchronized null
            check(countBootstrapGeneration < Long.MAX_VALUE) { "dashboard count generation overflow" }
            countBootstrapGeneration += 1L
            countBootstrapInFlight = true
            countBootstrapGeneration
        }
    }

    fun invalidateRowCounts() {
        synchronized(lock) {
            ownerStorageGeneration = nextRevision(ownerStorageGeneration, "dashboard storage generation")
            rowCounts = DashboardRowCounts(
                pollCount = UNKNOWN_DASHBOARD_COUNT,
                valueRowCount = UNKNOWN_DASHBOARD_COUNT,
                ecRowCount = UNKNOWN_DASHBOARD_COUNT,
                normalizedCurrentCount = UNKNOWN_DASHBOARD_COUNT,
                normalizedHistoryCount = UNKNOWN_DASHBOARD_COUNT,
                debugReadingCount = UNKNOWN_DASHBOARD_COUNT
            )
            countBootstrapInFlight = false
            check(countBootstrapGeneration < Long.MAX_VALUE) { "dashboard count generation overflow" }
            countBootstrapGeneration += 1L
            rowCountRevision = nextRevision(rowCountRevision, "dashboard row-count revision")
            rowCountMeasuredAtElapsedMs = clock()
            rowCountStorageGeneration = ownerStorageGeneration
            queueMetrics = DashboardQueueMetrics(
                mqttPendingCount = UNKNOWN_DASHBOARD_COUNT,
                influxPendingRows = UNKNOWN_DASHBOARD_COUNT,
                influxOldestPendingAt = null
            )
            queueMetricsRevision = nextRevision(queueMetricsRevision, "dashboard queue-metrics revision")
            queueMetricsMeasuredAtElapsedMs = rowCountMeasuredAtElapsedMs
            touchOwnerLocked(rowCountMeasuredAtElapsedMs)
            updateAllLocked(::applyRuntimeOverlays)
        }
    }

    fun beginInitialHydration(): Boolean = synchronized(lock) {
        if (initialHydrationStarted) return@synchronized false
        initialHydrationStarted = true
        true
    }

    fun publishRowCountBaseline(
        generation: Long,
        counts: DashboardRowCounts,
        measuredAtElapsedMs: Long = clock(),
        expectedStorageGeneration: Long = storageGenerationSnapshot()
    ): Boolean {
        return synchronized(lock) {
            if (expectedStorageGeneration != ownerStorageGeneration ||
                !countBootstrapInFlight || generation != countBootstrapGeneration
            ) return@synchronized false
            rowCounts = counts
            countBootstrapInFlight = false
            rowCountRevision = nextRevision(rowCountRevision, "dashboard row-count revision")
            rowCountMeasuredAtElapsedMs = measuredAtElapsedMs
            rowCountStorageGeneration = ownerStorageGeneration
            touchOwnerLocked(rowCountMeasuredAtElapsedMs)
            updateAllLocked(::applyRuntimeOverlays)
            true
        }
    }

    fun failCountBootstrap(generation: Long): Boolean {
        return synchronized(lock) {
            if (!countBootstrapInFlight || generation != countBootstrapGeneration) return@synchronized false
            countBootstrapInFlight = false
            true
        }
    }

    fun invalidateQueueMetrics() {
        synchronized(lock) {
            queueMetrics = DashboardQueueMetrics(
                mqttPendingCount = UNKNOWN_DASHBOARD_COUNT,
                influxPendingRows = UNKNOWN_DASHBOARD_COUNT,
                influxOldestPendingAt = null
            )
            queueMetricsRevision = nextRevision(queueMetricsRevision, "dashboard queue-metrics revision")
            queueMetricsMeasuredAtElapsedMs = clock()
            touchOwnerLocked(queueMetricsMeasuredAtElapsedMs)
            updateAllLocked(::applyRuntimeOverlays)
        }
    }

    fun publishRuntimeFlags(flags: DashboardRuntimeFlags) {
        synchronized(lock) {
            runtimeFlags = flags
            callbackQueueRevision = nextRevision(callbackQueueRevision, "dashboard callback-queue revision")
            callbackQueueMeasuredAtElapsedMs = clock()
            touchOwnerLocked(callbackQueueMeasuredAtElapsedMs)
            updateAllLocked(::applyRuntimeOverlays)
        }
    }

    /** The access checker remains authoritative even when the collection service is stopped. */
    fun publishAccessStatus(permissionsGranted: Boolean, adbAuthorized: Boolean) {
        synchronized(lock) {
            accessStatus = permissionsGranted to adbAuthorized
            touchOwnerLocked()
            updateAllLocked(::applyRuntimeOverlays)
        }
    }

    fun publishMainPollState(state: DashboardMainPollState) {
        synchronized(lock) {
            mainPollState = state
            touchOwnerLocked()
            updateTargetsLocked(setOf(AppTab.MAIN), includeChrome = true)
        }
    }

    internal fun mainPollSnapshot(): DashboardMainPollState? = synchronized(lock) { mainPollState }

    internal fun restoreMainPollState(state: DashboardMainPollState) {
        synchronized(lock) {
            if (mainPollState != null) return
            mainPollState = state
            touchOwnerLocked()
            updateTargetsLocked(setOf(AppTab.MAIN), includeChrome = true)
        }
    }

    fun publishDebugPollState(state: DashboardDebugPollState) {
        synchronized(lock) {
            debugPollState = state
            touchOwnerLocked()
            updateTargetsLocked(setOf(AppTab.ALL_PARAMETERS), includeChrome = false)
        }
    }

    fun publishIntegrationRuntime(
        state: DashboardState,
        measuredAtElapsedMs: Long = clock(),
        expectedStorageGeneration: Long = storageGenerationSnapshot()
    ): Boolean {
        return synchronized(lock) {
            if (expectedStorageGeneration != ownerStorageGeneration) return@synchronized false
            if (measuredAtElapsedMs < integrationMeasuredAtElapsedMs) return@synchronized false
            integrationRuntimeState = state
            integrationRevision = nextRevision(integrationRevision, "dashboard integration revision")
            integrationMeasuredAtElapsedMs = measuredAtElapsedMs
            integrationStorageGeneration = ownerStorageGeneration
            touchOwnerLocked(measuredAtElapsedMs)
            updateTargetsLocked(setOf(AppTab.MAIN, AppTab.HA), includeChrome = true)
            true
        }
    }

    /** Queue depth is measured independently from sender/export status snapshots. */
    fun publishQueueMetrics(
        mqttPendingCount: Long,
        influxPendingRows: Long,
        influxOldestPendingAt: String?,
        measuredAtElapsedMs: Long = clock(),
        expectedStorageGeneration: Long = storageGenerationSnapshot(),
        expectedQueueMetricsRevision: Long? = null
    ): Boolean {
        return synchronized(lock) {
            if (expectedStorageGeneration != ownerStorageGeneration) return@synchronized false
            if (expectedQueueMetricsRevision != null && expectedQueueMetricsRevision != queueMetricsRevision) return@synchronized false
            if (measuredAtElapsedMs < queueMetricsMeasuredAtElapsedMs) return@synchronized false
            queueMetrics = DashboardQueueMetrics(mqttPendingCount, influxPendingRows, influxOldestPendingAt)
            queueMetricsRevision = nextRevision(queueMetricsRevision, "dashboard queue-metrics revision")
            queueMetricsMeasuredAtElapsedMs = measuredAtElapsedMs
            touchOwnerLocked(measuredAtElapsedMs)
            updateTargetsLocked(setOf(AppTab.MAIN, AppTab.HA), includeChrome = true)
            true
        }
    }

    fun publishDatabaseFootprints(mainBytes: Long, debugBytes: Long, tripsBytes: Long) {
        synchronized(lock) {
            mainDatabaseSizeBytes = mainBytes
            debugDatabaseSizeBytes = debugBytes
            tripsDatabaseSizeBytes = tripsBytes
            touchOwnerLocked()
            updateTargetsLocked(
                setOf(AppTab.MAIN, AppTab.ALL_PARAMETERS, AppTab.STORAGE),
                includeChrome = true
            )
        }
    }

    private fun beginRefresh(tab: AppTab?): Long {
        return synchronized(lock) {
            val generation = nextGenerationLocked()
            pendingGenerations[tab] = generation
            val flow = mutableFlow(tab)
            flow.value = flow.value?.copy(
                generation = generation,
                inFlight = true
            )
            generation
        }
    }

    private fun publish(tab: AppTab?, generation: Long, state: DashboardState, dataRefreshed: Boolean): Boolean {
        return synchronized(lock) {
            val flow = mutableFlow(tab)
            val current = flow.value
            if (!isCurrentGeneration(tab, current, generation)) {
                false
            } else {
                val incoming = mergeLatestQueueOverlayLocked(applyRuntimeOverlays(state))
                flow.value = CachedDashboardState(
                    state = incoming,
                    // Re-reading a remote cache must not postpone the next real load.
                    loadedAtElapsedMs = if (dataRefreshed) clock() else current?.loadedAtElapsedMs ?: 0L,
                    generation = generation,
                    inFlight = false,
                    lastError = null
                )
                pendingGenerations.remove(tab)
                true
            }
        }
    }

    private fun fail(tab: AppTab?, generation: Long, error: String): Boolean {
        return synchronized(lock) {
            val flow = mutableFlow(tab)
            val current = flow.value
            if (!isCurrentGeneration(tab, current, generation)) {
                false
            } else {
                if (current != null) {
                    flow.value = current.copy(
                        inFlight = false,
                        lastError = error
                    )
                }
                pendingGenerations.remove(tab)
                true
            }
        }
    }

    private fun isCurrentGeneration(
        tab: AppTab?,
        current: CachedDashboardState?,
        generation: Long
    ): Boolean {
        return if (current == null) {
            pendingGenerations[tab] == generation
        } else {
            current.inFlight && current.generation == generation
        }
    }

    private fun mutableFlow(tab: AppTab?): MutableStateFlow<CachedDashboardState?> {
        return if (tab == null) chrome else tabs.getValue(tab)
    }

    private fun updateAllLocked(transform: (DashboardState) -> DashboardState) {
        onRuntimeChanged()
        updateFlowLocked(chrome, transform)
        tabs.values.forEach { flow -> updateFlowLocked(flow, transform) }
    }

    private fun updateTargetsLocked(targetTabs: Set<AppTab>, includeChrome: Boolean) {
        onRuntimeChanged()
        if (includeChrome) updateFlowLocked(chrome, ::applyRuntimeOverlays)
        targetTabs.forEach { tab -> updateFlowLocked(tabs.getValue(tab), ::applyRuntimeOverlays) }
    }

    private fun updateFlowLocked(
        flow: MutableStateFlow<CachedDashboardState?>,
        transform: (DashboardState) -> DashboardState
    ) {
        val current = flow.value ?: return
        val next = transform(current.state)
        if (next != current.state) flow.value = current.copy(state = next)
    }

    private fun applyRuntimeOverlays(source: DashboardState): DashboardState {
        if (com.bydcollector.collector.BuildConfig.RUNTIME_CLIENT) return source
        var state = source.copy(vehicleKpis = localizedVehicleKpis.getValue(selectedKpiLanguage))
        rowCounts.let { counts ->
            state = state.copy(
                pollCount = counts.pollCount,
                valueRowCount = counts.valueRowCount,
                ecRowCount = counts.ecRowCount,
                normalizedCurrentCount = counts.normalizedCurrentCount,
                normalizedHistoryCount = counts.normalizedHistoryCount,
                debugReadingCount = counts.debugReadingCount
            )
        }
        mainPollState?.let { poll ->
            state = state.copy(
                activeSessionId = poll.activeSessionId,
                lastSuccessAt = poll.lastSuccessAt,
                lastError = poll.lastError,
                lastErrorAt = poll.lastErrorAt,
                lastPollStatus = poll.lastPollStatus,
                elapsedMs = poll.elapsedMs,
                requestCount = poll.requestCount
            )
        }
        debugPollState?.let { debug ->
            state = state.copy(
                debugLastReadingAt = debug.lastReadingAt,
                debugLastErrorAt = debug.lastErrorAt,
                debugLastError = debug.lastError,
                debugErrorCount = debug.errorCount,
                debugLastSessionId = debug.lastSessionId
            )
        }
        integrationRuntimeState?.let { runtime ->
            state = state.copy(
                mqttEnabled = runtime.mqttEnabled,
                mqttStatus = runtime.mqttStatus,
                mqttLastError = runtime.mqttLastError,
                mqttLastPublishedAt = runtime.mqttLastPublishedAt,
                mqttPendingCount = runtime.mqttPendingCount,
                mqttRetryFailureCount = runtime.mqttRetryFailureCount,
                mqttNextRetryAt = runtime.mqttNextRetryAt,
                mqttRetryLastFailureAt = runtime.mqttRetryLastFailureAt,
                mqttRetryLastSuccessAt = runtime.mqttRetryLastSuccessAt,
                healthReadErrors = mergeHealthReadErrors(
                    state.healthReadErrors,
                    runtime.healthReadErrors,
                    INTEGRATION_HEALTH_READ_FIELDS
                ),
                influxEnabled = runtime.influxEnabled,
                influxStatus = runtime.influxStatus,
                influxPendingRows = runtime.influxPendingRows,
                influxOldestPendingAt = runtime.influxOldestPendingAt,
                influxNextRetryAt = runtime.influxNextRetryAt,
                influxLastSuccessAt = runtime.influxLastSuccessAt,
                influxLastErrorAt = runtime.influxLastErrorAt,
                influxLastError = runtime.influxLastError,
                influxExportedRowsTotal = runtime.influxExportedRowsTotal
            )
        }
        queueMetrics.let { metrics ->
            state = state.copy(
                mqttPendingCount = metrics.mqttPendingCount,
                influxPendingRows = metrics.influxPendingRows,
                influxOldestPendingAt = metrics.influxOldestPendingAt
            )
        }
        //Settings/service flags are the newest producer-owned truth and must win over a slightly older
        //integration snapshot loaded from SQLite.
        runtimeFlags?.let { flags ->
            state = state.copy(
                running = flags.mainPollingRunning,
                serviceRunning = flags.serviceRunning,
                mainPollingRunning = flags.mainPollingRunning,
                pollingEnabled = flags.pollingEnabled,
                mainRuntimeStatus = flags.mainRuntimeStatus,
                mainCallbackQueue = flags.mainCallbackQueue,
                secondaryCallbackQueue = flags.secondaryCallbackQueue,
                debugPollingEnabled = flags.debugPollingEnabled,
                debugPollingRunning = flags.debugPollingRunning,
                debugRuntimeStatus = flags.debugRuntimeStatus,
                debugRuntimeError = flags.debugRuntimeError,
                mqttEnabled = flags.mqttEnabled,
                mqttRuntimeStatus = flags.mqttRuntimeStatus,
                influxEnabled = flags.influxEnabled,
                influxRuntimeStatus = flags.influxRuntimeStatus,
                permissionsGranted = flags.permissionsGranted,
                adbAuthorized = flags.adbAuthorized,
                dbMaintenanceStatus = flags.dbMaintenanceStatus,
                archiveStorageJobStatus = flags.archiveStorageJobStatus
            )
        }
        accessStatus?.let { (permissionsGranted, adbAuthorized) ->
            state = state.copy(permissionsGranted = permissionsGranted, adbAuthorized = adbAuthorized)
        }
        mainDatabaseSizeBytes?.let { state = state.copy(databaseSizeBytes = it) }
        debugDatabaseSizeBytes?.let { state = state.copy(debugDatabaseSizeBytes = it) }
        state = state.copy(archiveStorageSnapshot = state.archiveStorageSnapshot.copy(
            mainDatabaseSizeBytes = mainDatabaseSizeBytes ?: state.archiveStorageSnapshot.mainDatabaseSizeBytes,
            debugDatabaseSizeBytes = debugDatabaseSizeBytes ?: state.archiveStorageSnapshot.debugDatabaseSizeBytes,
            tripsDatabaseSizeBytes = tripsDatabaseSizeBytes ?: state.archiveStorageSnapshot.tripsDatabaseSizeBytes
        ))
        return state.copy(freshness = DashboardSnapshotFreshness(
            ownerStartedAtElapsedMs = ownerStartedAtElapsedMs,
            ownerIncarnationId = ownerIncarnationId,
            ownerStorageGeneration = ownerStorageGeneration,
            ownerRevision = ownerRevision,
            ownerMeasuredAtElapsedMs = ownerMeasuredAtElapsedMs,
            callbackQueueRevision = callbackQueueRevision,
            callbackQueueMeasuredAtElapsedMs = callbackQueueMeasuredAtElapsedMs,
            rowCountRevision = rowCountRevision,
            rowCountMeasuredAtElapsedMs = rowCountMeasuredAtElapsedMs,
            rowCountStorageGeneration = rowCountStorageGeneration,
            queueMetricsRevision = queueMetricsRevision,
            queueMetricsMeasuredAtElapsedMs = queueMetricsMeasuredAtElapsedMs,
            integrationRevision = integrationRevision,
            integrationMeasuredAtElapsedMs = integrationMeasuredAtElapsedMs,
            integrationStorageGeneration = integrationStorageGeneration
        ))
    }

    internal fun snapshotForClient(source: DashboardState, language: VehicleKpiLanguage): DashboardState =
        synchronized(lock) {
            mergeLatestQueueOverlayLocked(applyRuntimeOverlays(source))
                .copy(vehicleKpis = localizedVehicleKpis.getValue(language))
        }

    private fun mergeLatestQueueOverlayLocked(incoming: DashboardState): DashboardState {
        val currentStates = buildList {
            chrome.value?.state?.let(::add)
            tabs.values.forEach { flow -> flow.value?.state?.let(::add) }
        }
        return currentStates.fold(incoming) { merged, current ->
            mergeDashboardFreshQueueOverlay(current, merged)
        }
    }

    private fun touchOwnerLocked(measuredAtElapsedMs: Long = clock()) {
        ownerRevision = nextRevision(ownerRevision, "dashboard owner revision")
        ownerMeasuredAtElapsedMs = maxOf(ownerMeasuredAtElapsedMs, measuredAtElapsedMs)
    }

    private fun nextRevision(value: Long, name: String): Long {
        check(value < Long.MAX_VALUE) { "$name overflow" }
        return value + 1L
    }

    private fun nextGenerationLocked(): Long {
        check(nextGeneration < Long.MAX_VALUE) { "dashboard UI generation overflow" }
        nextGeneration += 1L
        return nextGeneration
    }

}

/** Keep the freshest independently measured owner groups while accepting other fields from [incoming]. */
internal fun mergeDashboardFreshQueueOverlay(current: DashboardState, incoming: DashboardState): DashboardState {
    val currentFreshness = current.freshness
    val incomingFreshness = incoming.freshness
    val ownerOrder = compareOwnerIncarnation(currentFreshness, incomingFreshness)
    val sameOwner = ownerOrder == 0
    val generationOrder = compareValues(
        currentFreshness.ownerStorageGeneration,
        incomingFreshness.ownerStorageGeneration
    )
    val currentOwnerSnapshotIsNewer = ownerOrder > 0 || (sameOwner && generationOrder > 0)
    val mergedFreshness = incomingFreshness.copy(
        ownerStartedAtElapsedMs = if (currentOwnerSnapshotIsNewer) currentFreshness.ownerStartedAtElapsedMs else incomingFreshness.ownerStartedAtElapsedMs,
        ownerIncarnationId = if (currentOwnerSnapshotIsNewer) currentFreshness.ownerIncarnationId else incomingFreshness.ownerIncarnationId,
        ownerStorageGeneration = if (currentOwnerSnapshotIsNewer) currentFreshness.ownerStorageGeneration else incomingFreshness.ownerStorageGeneration,
        ownerRevision = when {
            !sameOwner -> if (currentOwnerSnapshotIsNewer) currentFreshness.ownerRevision else incomingFreshness.ownerRevision
            else -> maxOf(currentFreshness.ownerRevision, incomingFreshness.ownerRevision)
        },
        ownerMeasuredAtElapsedMs = when {
            !sameOwner -> if (currentOwnerSnapshotIsNewer) currentFreshness.ownerMeasuredAtElapsedMs else incomingFreshness.ownerMeasuredAtElapsedMs
            else -> maxOf(currentFreshness.ownerMeasuredAtElapsedMs, incomingFreshness.ownerMeasuredAtElapsedMs)
        }
    )
    var merged = incoming.copy(freshness = mergedFreshness)
    if (isFreshGroupNewer(
            currentFreshness, incomingFreshness,
            currentFreshness.rowCountStorageGeneration, incomingFreshness.rowCountStorageGeneration,
            currentFreshness.rowCountRevision, incomingFreshness.rowCountRevision,
            currentFreshness.rowCountMeasuredAtElapsedMs, incomingFreshness.rowCountMeasuredAtElapsedMs
        )) {
        merged = merged.copy(
            pollCount = current.pollCount,
            valueRowCount = current.valueRowCount,
            ecRowCount = current.ecRowCount,
            normalizedCurrentCount = current.normalizedCurrentCount,
            normalizedHistoryCount = current.normalizedHistoryCount,
            debugReadingCount = current.debugReadingCount,
            freshness = merged.freshness.copy(
                rowCountRevision = currentFreshness.rowCountRevision,
                rowCountMeasuredAtElapsedMs = currentFreshness.rowCountMeasuredAtElapsedMs,
                rowCountStorageGeneration = currentFreshness.rowCountStorageGeneration
            )
        )
    }
    if (isFreshGroupNewer(
            currentFreshness, incomingFreshness,
            currentFreshness.integrationStorageGeneration, incomingFreshness.integrationStorageGeneration,
            currentFreshness.integrationRevision, incomingFreshness.integrationRevision,
            currentFreshness.integrationMeasuredAtElapsedMs, incomingFreshness.integrationMeasuredAtElapsedMs
        )) {
        merged = merged.copy(
            mqttStatus = current.mqttStatus,
            mqttLastError = current.mqttLastError,
            mqttLastPublishedAt = current.mqttLastPublishedAt,
            mqttRetryFailureCount = current.mqttRetryFailureCount,
            mqttNextRetryAt = current.mqttNextRetryAt,
            mqttRetryLastFailureAt = current.mqttRetryLastFailureAt,
            mqttRetryLastSuccessAt = current.mqttRetryLastSuccessAt,
            healthReadErrors = mergeHealthReadErrors(
                merged.healthReadErrors,
                current.healthReadErrors,
                INTEGRATION_HEALTH_READ_FIELDS
            ),
            influxStatus = current.influxStatus,
            influxNextRetryAt = current.influxNextRetryAt,
            influxLastSuccessAt = current.influxLastSuccessAt,
            influxLastErrorAt = current.influxLastErrorAt,
            influxLastError = current.influxLastError,
            influxExportedRowsTotal = current.influxExportedRowsTotal,
            freshness = merged.freshness.copy(
                integrationRevision = currentFreshness.integrationRevision,
                integrationMeasuredAtElapsedMs = currentFreshness.integrationMeasuredAtElapsedMs,
                integrationStorageGeneration = currentFreshness.integrationStorageGeneration
            )
        )
    }
    if (isFreshGroupNewer(
            currentFreshness, incomingFreshness,
            currentFreshness.ownerStorageGeneration, incomingFreshness.ownerStorageGeneration,
            currentFreshness.callbackQueueRevision, incomingFreshness.callbackQueueRevision,
            currentFreshness.callbackQueueMeasuredAtElapsedMs, incomingFreshness.callbackQueueMeasuredAtElapsedMs
        )) {
        merged = merged.copy(
            mainCallbackQueue = current.mainCallbackQueue,
            secondaryCallbackQueue = current.secondaryCallbackQueue,
            freshness = merged.freshness.copy(
                callbackQueueRevision = currentFreshness.callbackQueueRevision,
                callbackQueueMeasuredAtElapsedMs = currentFreshness.callbackQueueMeasuredAtElapsedMs
            )
        )
    }
    if (isFreshGroupNewer(
            currentFreshness, incomingFreshness,
            currentFreshness.ownerStorageGeneration, incomingFreshness.ownerStorageGeneration,
            currentFreshness.queueMetricsRevision, incomingFreshness.queueMetricsRevision,
            currentFreshness.queueMetricsMeasuredAtElapsedMs, incomingFreshness.queueMetricsMeasuredAtElapsedMs
        )) {
        merged = merged.copy(
            mqttPendingCount = current.mqttPendingCount,
            influxPendingRows = current.influxPendingRows,
            influxOldestPendingAt = current.influxOldestPendingAt,
            freshness = merged.freshness.copy(
                queueMetricsRevision = currentFreshness.queueMetricsRevision,
                queueMetricsMeasuredAtElapsedMs = currentFreshness.queueMetricsMeasuredAtElapsedMs
            )
        )
    }
    return merged
}

private fun isFreshGroupNewer(
    current: DashboardSnapshotFreshness,
    incoming: DashboardSnapshotFreshness,
    currentStorageGeneration: Long,
    incomingStorageGeneration: Long,
    currentRevision: Long,
    incomingRevision: Long,
    currentMeasuredAtElapsedMs: Long,
    incomingMeasuredAtElapsedMs: Long
): Boolean {
    val ownerOrder = compareOwnerIncarnation(current, incoming)
    if (ownerOrder != 0) return ownerOrder > 0
    val generationOrder = compareValues(currentStorageGeneration, incomingStorageGeneration)
    if (generationOrder != 0) return generationOrder > 0
    val revisionOrder = compareValues(currentRevision, incomingRevision)
    if (revisionOrder != 0) return revisionOrder > 0
    if (currentMeasuredAtElapsedMs != incomingMeasuredAtElapsedMs) {
        return currentMeasuredAtElapsedMs > incomingMeasuredAtElapsedMs
    }
    // A non-empty equal stamp denotes one measurement; keep its already-published payload on ties.
    return currentRevision > 0L || currentMeasuredAtElapsedMs > 0L || currentStorageGeneration > 0L
}

private fun compareOwnerIncarnation(
    current: DashboardSnapshotFreshness,
    incoming: DashboardSnapshotFreshness
): Int = compareValues(current.ownerStartedAtElapsedMs, incoming.ownerStartedAtElapsedMs)
    .takeIf { it != 0 }
    ?: compareValues(current.ownerIncarnationId, incoming.ownerIncarnationId)
