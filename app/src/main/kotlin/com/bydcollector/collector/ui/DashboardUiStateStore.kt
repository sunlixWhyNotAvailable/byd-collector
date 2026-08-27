package com.bydcollector.collector.ui

import android.os.SystemClock
import com.bydcollector.collector.maintenance.ArchiveStorageJobStatus
import com.bydcollector.collector.maintenance.DbMaintenanceRuntimeStatus
import com.bydcollector.collector.ui.compose.AppTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        if (influxEnabled) RuntimeActionStatus.RUNNING else RuntimeActionStatus.STOPPED
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

class DashboardUiStateStore(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private val lock = Any() //Serializes cache reads, writes, and generation checks.
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
    private var rowCounts: DashboardRowCounts? = null
    private var countBootstrapInFlight = false
    private var countBootstrapGeneration = 0L
    private var initialHydrationStarted = false
    private var runtimeFlags: DashboardRuntimeFlags? = null
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

            val seeded = applyRuntimeOverlays(initial)
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

    fun publishChrome(generation: Long, state: DashboardState): Boolean {
        return publish(null, generation, state)
    }

    fun publishTab(tab: AppTab, generation: Long, state: DashboardState): Boolean {
        return publish(tab, generation, state)
    }

    fun failChrome(generation: Long, error: String): Boolean {
        return fail(null, generation, error)
    }

    fun failTab(tab: AppTab, generation: Long, error: String): Boolean {
        return fail(tab, generation, error)
    }

    fun currentChrome(): DashboardState? = synchronized(lock) { chrome.value?.state }

    fun currentTab(tab: AppTab): DashboardState? = synchronized(lock) { tabs.getValue(tab).value?.state }

    fun markTabStale(tab: AppTab) {
        synchronized(lock) {
            val flow = tabs.getValue(tab)
            //Zero is the stale sentinel; the last non-null snapshot remains visible.
            flow.value = flow.value?.copy(loadedAtElapsedMs = 0L)
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
        }
    }

    fun beginInitialHydration(): Boolean = synchronized(lock) {
        if (initialHydrationStarted) return@synchronized false
        initialHydrationStarted = true
        true
    }

    fun publishRowCountBaseline(generation: Long, counts: DashboardRowCounts): Boolean {
        return synchronized(lock) {
            if (!countBootstrapInFlight || generation != countBootstrapGeneration) return@synchronized false
            rowCounts = counts
            countBootstrapInFlight = false
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

    fun incrementMainRowCounts(
        pollRows: Long = 0L,
        valueRows: Long = 0L,
        ecRows: Long = 0L,
        normalizedCurrentRows: Long = 0L,
        normalizedHistoryRows: Long = 0L
    ) {
        synchronized(lock) {
            val current = rowCounts ?: return
            rowCounts = current.copy(
                pollCount = incrementKnownCount(current.pollCount, pollRows),
                valueRowCount = incrementKnownCount(current.valueRowCount, valueRows),
                ecRowCount = incrementKnownCount(current.ecRowCount, ecRows),
                normalizedCurrentCount = incrementKnownCount(current.normalizedCurrentCount, normalizedCurrentRows),
                normalizedHistoryCount = incrementKnownCount(current.normalizedHistoryCount, normalizedHistoryRows)
            )
        }
    }

    fun incrementDebugReadingCount(rows: Long) {
        if (rows == 0L) return
        synchronized(lock) {
            val current = rowCounts ?: return
            rowCounts = current.copy(debugReadingCount = incrementKnownCount(current.debugReadingCount, rows))
        }
    }

    fun publishRuntimeFlags(flags: DashboardRuntimeFlags) {
        synchronized(lock) {
            runtimeFlags = flags
            updateAllLocked(::applyRuntimeOverlays)
        }
    }

    fun publishMainPollState(state: DashboardMainPollState) {
        synchronized(lock) {
            mainPollState = state
            updateTargetsLocked(setOf(AppTab.MAIN), includeChrome = true)
        }
    }

    fun publishDebugPollState(state: DashboardDebugPollState) {
        synchronized(lock) {
            debugPollState = state
            updateTargetsLocked(setOf(AppTab.ALL_PARAMETERS), includeChrome = false)
        }
    }

    fun publishIntegrationRuntime(state: DashboardState) {
        synchronized(lock) {
            integrationRuntimeState = state
            updateTargetsLocked(setOf(AppTab.MAIN, AppTab.HA), includeChrome = true)
        }
    }

    fun publishDatabaseFootprints(mainBytes: Long, debugBytes: Long, tripsBytes: Long) {
        synchronized(lock) {
            mainDatabaseSizeBytes = mainBytes
            debugDatabaseSizeBytes = debugBytes
            tripsDatabaseSizeBytes = tripsBytes
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

    private fun publish(tab: AppTab?, generation: Long, state: DashboardState): Boolean {
        return synchronized(lock) {
            val flow = mutableFlow(tab)
            val current = flow.value
            if (!isCurrentGeneration(tab, current, generation)) {
                false
            } else {
                flow.value = CachedDashboardState(
                    state = applyRuntimeOverlays(state),
                    loadedAtElapsedMs = clock(),
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
        updateFlowLocked(chrome, transform)
        tabs.values.forEach { flow -> updateFlowLocked(flow, transform) }
    }

    private fun updateTargetsLocked(targetTabs: Set<AppTab>, includeChrome: Boolean) {
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
        var state = source.copy(vehicleKpis = localizedVehicleKpis.getValue(selectedKpiLanguage))
        rowCounts?.let { counts ->
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
        //Settings/service flags are the newest producer-owned truth and must win over a slightly older
        //integration snapshot loaded from SQLite.
        runtimeFlags?.let { flags ->
            state = state.copy(
                running = flags.mainPollingRunning,
                serviceRunning = flags.serviceRunning,
                mainPollingRunning = flags.mainPollingRunning,
                pollingEnabled = flags.pollingEnabled,
                mainRuntimeStatus = flags.mainRuntimeStatus,
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
        mainDatabaseSizeBytes?.let { state = state.copy(databaseSizeBytes = it) }
        debugDatabaseSizeBytes?.let { state = state.copy(debugDatabaseSizeBytes = it) }
        state = state.copy(archiveStorageSnapshot = state.archiveStorageSnapshot.copy(
            mainDatabaseSizeBytes = mainDatabaseSizeBytes ?: state.archiveStorageSnapshot.mainDatabaseSizeBytes,
            debugDatabaseSizeBytes = debugDatabaseSizeBytes ?: state.archiveStorageSnapshot.debugDatabaseSizeBytes,
            tripsDatabaseSizeBytes = tripsDatabaseSizeBytes ?: state.archiveStorageSnapshot.tripsDatabaseSizeBytes
        ))
        return state
    }

    private fun nextGenerationLocked(): Long {
        check(nextGeneration < Long.MAX_VALUE) { "dashboard UI generation overflow" }
        nextGeneration += 1L
        return nextGeneration
    }

    private fun incrementKnownCount(current: Long, delta: Long): Long {
        return if (current == UNKNOWN_DASHBOARD_COUNT) current else current + delta
    }
}
