package com.bydcollector.collector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.adb.AccessCheckMode
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.data.debug.DirectDebugDatabaseHelper
import com.bydcollector.collector.data.debug.DirectDebugDatabaseResolver
import com.bydcollector.collector.data.debug.DirectDebugParameterAsset
import com.bydcollector.collector.data.debug.DirectDebugRoundRobinPoller
import com.bydcollector.collector.data.debug.DirectDebugStore
import com.bydcollector.collector.data.debug.SecondaryReplayCoordinator
import com.bydcollector.collector.data.direct.DirectHelperOwnerMode
import com.bydcollector.collector.data.direct.DirectStreamController
import com.bydcollector.collector.data.direct.DirectVehicleHelperClient
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.local.HealthSnapshotDetail
import com.bydcollector.collector.data.normalized.NormalizedWriteSummary
import com.bydcollector.collector.data.normalized.PollingErrorSummaries
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.polling.PollOrigin
import com.bydcollector.collector.data.polling.PollPersistenceCoordinator
import com.bydcollector.collector.data.polling.SuccessfulPollObserver
import com.bydcollector.collector.data.polling.TelemetryPoller
import com.bydcollector.collector.data.polling.TelemetryWorkerReplayCoordinator
import com.bydcollector.collector.data.polling.TelemetryWorkerReplayPollCycleRunner
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.normalized.NormalizedObservation
import com.bydcollector.collector.data.remote.DirectTelemetryClient
import com.bydcollector.collector.data.remote.DirectBridgeManager
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.diagnostics.diagnosticSafeText
import com.bydcollector.collector.keepalive.KeepAliveConfig
import com.bydcollector.collector.keepalive.KeepAliveSupervisor
import com.bydcollector.collector.influx.HttpInfluxClient
import com.bydcollector.collector.influx.InfluxActionResult
import com.bydcollector.collector.influx.InfluxExportCoordinator
import com.bydcollector.collector.influx.InfluxRuntimeDiagnosticsProcess
import com.bydcollector.collector.influx.safeInfluxDiagnosticHost
import com.bydcollector.collector.ha.HaEndpoint
import com.bydcollector.collector.ha.HaConnectionOwnership
import com.bydcollector.collector.ha.SocketHaEndpointProbe
import com.bydcollector.collector.ha.TailscaleActivator
import com.bydcollector.collector.ha.TailscaleActivationGate
import com.bydcollector.collector.maintenance.ArchiveStorageJobMode
import com.bydcollector.collector.maintenance.ArchiveStorageJobStatus
import com.bydcollector.collector.maintenance.ArchiveStorageManager
import com.bydcollector.collector.maintenance.ArchiveShareLeaseRegistry
import com.bydcollector.collector.maintenance.DbMaintenanceCoordinator
import com.bydcollector.collector.maintenance.DbMaintenanceOperation
import com.bydcollector.collector.mqtt.HaMqttConfig
import com.bydcollector.collector.mqtt.HaMqttMessageFactory
import com.bydcollector.collector.mqtt.HaMqttStatus
import com.bydcollector.collector.mqtt.MqttActionResult
import com.bydcollector.collector.mqtt.MqttClientFacade
import com.bydcollector.collector.mqtt.MqttPublishCoordinator
import com.bydcollector.collector.mqtt.PahoMqttClientFacade
import com.bydcollector.collector.system.CollectorAutoStart
import com.bydcollector.collector.telegram.TelegramCoordinator
import com.bydcollector.collector.telegram.TelegramSendResult
import com.bydcollector.collector.telegram.correlateTripDiagnostic
import java.time.Instant
import com.bydcollector.collector.ui.DashboardDebugPollState
import com.bydcollector.collector.ui.DashboardMainPollState
import com.bydcollector.collector.ui.DashboardRowCounts
import com.bydcollector.collector.ui.DashboardRuntimeFlags
import com.bydcollector.collector.ui.DashboardStateProvider
import com.bydcollector.collector.ui.DashboardUiStateStore
import com.bydcollector.collector.ui.DebugRuntimeStatus
import com.bydcollector.collector.ui.DisplayTimeFormatter
import com.bydcollector.collector.ui.RuntimeActionStatus
import com.bydcollector.collector.ui.VehicleKpiLanguage
import com.bydcollector.collector.ui.VehicleKpiMapper
import com.bydcollector.collector.ui.VehicleKpis
import com.bydcollector.collector.ui.compose.AppTab
import com.bydcollector.collector.util.namedSingleThreadExecutor
import com.bydcollector.collector.util.sqliteFootprintBytes
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

//owns the runtime lifecycle so collection, exports, and keep-alive can continue without an open activity
class CollectorService : Service() {
    private var historicalEnergyGeneration = 0L
    private lateinit var store: TelemetryStore
    private lateinit var settings: CollectorSettings
    private lateinit var poller: TelemetryPoller
    private lateinit var debugStore: DirectDebugStore
    private lateinit var keepAliveSupervisor: KeepAliveSupervisor
    private lateinit var tailscaleGate: TailscaleActivationGate
    private lateinit var vehicleStateNormalizer: VehicleStateNormalizer
    private lateinit var mqttCoordinator: MqttPublishCoordinator
    private lateinit var influxCoordinator: InfluxExportCoordinator
    private val telegramDeliveryRuntime get() = (applicationContext as BydCollectorApplication).telegramDeliveryRuntime
    private var telegramCoordinator: TelegramCoordinator?
        get() = telegramDeliveryRuntime.coordinator
        set(value) { telegramDeliveryRuntime.coordinator = value }
    private lateinit var tripRuntime: TripRuntimeCoordinator
    private lateinit var maintenanceCoordinator: DbMaintenanceCoordinator
    private lateinit var dashboardUiStateStore: DashboardUiStateStore
    private lateinit var dashboardStateProvider: DashboardStateProvider
    private var mainPollerOwnerMode = DirectHelperOwnerMode.APP_GAP_SPOOL
    private var debugStorageReady = false
    @Volatile private var mainRuntimeStatus = RuntimeActionStatus.STOPPED
    @Volatile private var debugRuntimeStatus = DebugRuntimeStatus.STOPPED
    @Volatile private var debugRuntimeError: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionId: Long? = null
    private var debugPoller: DirectDebugRoundRobinPoller? = null
    private val debugPollerLock = Any()
    private var normalizedStateChangedCallback: ((Set<String>) -> Unit)? = null
    private val debugStartExecutor = namedSingleThreadExecutor("byd-debug-start")
    private val maintenanceExecutor = namedSingleThreadExecutor("byd-db-maintenance")
    private val archiveStorageExecutor = namedSingleThreadExecutor("byd-archive-storage")
    private val tailscaleExecutor = namedSingleThreadExecutor("byd-tailscale")
    private val dashboardMetricsExecutor = namedSingleThreadExecutor("byd-dashboard-metrics")
    private val dashboardCountExecutor = namedSingleThreadExecutor("byd-dashboard-counts")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mqttExecutorLock = Any()
    private var mqttExecutor: ExecutorService = namedSingleThreadExecutor("byd-mqtt")
    private var mqttRetryScheduled = false
    private var mqttRetryAtElapsedMs: Long? = null
    private val influxExecutorLock = Any()
    private val influxQueueLock = Any()
    private var influxExecutor: ExecutorService = namedSingleThreadExecutor("byd-influx")
    private val influxRequestQueued = AtomicBoolean(false)
    private val influxCycleDemand = InfluxCycleDemand()
    private var influxRequestRevision = 0L
    private val influxWorkInFlight = AtomicInteger(0)
    @Volatile private var influxRetryScheduled = false
    private var influxRetryAtElapsedMs: Long? = null
    @Volatile private var influxWorkQueuedAtElapsedMs: Long? = null
    private val influxRuntimeDiagnostics = InfluxRuntimeDiagnosticsProcess.instance
    private val influxDiagnosticRuntimeId = java.util.UUID.randomUUID().toString()
    private val telegramExecutorLock = Any()
    private val telegramExecutor: ExecutorService get() = telegramDeliveryRuntime.executor
    private val telegramStorageRecoveryInFlight = AtomicBoolean(false)
    private val telegramRecoveryCoalescer = TelegramRecoveryCoalescer()
    private val telegramNetworkRecoveryEdge = TelegramNetworkRecoveryEdge()
    private val telegramNetworkRecoveryRevision = AtomicLong(0L)
    private var telegramNetworkManager: ConnectivityManager? = null
    private var telegramNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var telegramNetworkCallbackRegistered = false
    private val mqttWorkGeneration = AtomicLong(0L)
    private val influxWorkGeneration = AtomicLong(0L)
    private val telegramWorkGeneration = AtomicLong(0L)
    private val debugWorkGeneration = AtomicLong(0L)
    private val debugStartInProgress = AtomicBoolean(false)
    private val debugStartQueued = AtomicBoolean(false)
    private val secondaryArchiveFenced = AtomicBoolean(false)
    private val debugStoreCloseRequested = AtomicBoolean(false)
    private val debugPollerShutdownInProgress = AtomicBoolean(false)
    @Volatile private var mqttRuntimeStatus = RuntimeActionStatus.STOPPED
    @Volatile private var influxRuntimeStatus = RuntimeActionStatus.STOPPED
    private val mqttRuntimeActive = AtomicBoolean(false)
    private val mqttOfflineQueued = AtomicBoolean(false)
    private val maintenanceActive = AtomicBoolean(false)
    private val maintenanceRuntimeRestoreAllowed = AtomicBoolean(true)
    private val userShutdownFinalizationStarted = AtomicBoolean(false)
    private val keepAliveStopGeneration = AtomicLong(0L)
    @Volatile
    private var activeMaintenanceOperation: DbMaintenanceOperation? = null
    private val tailscaleSequenceActive = AtomicBoolean(false)
    private val restoringRuntime = AtomicBoolean(false)
    private var lastStatusHeartbeatAtMs: Long = -STATUS_HEARTBEAT_INTERVAL_MS
    private var lastNotificationText: String? = null
    private var accessSelfCheckScheduled = false
    @Volatile private var lastTelegramPollError: String? = null
    private var telegramTickScheduled = false
    private var telegramTickAtMs: Long? = null
    private val dashboardMetricsGeneration = AtomicLong(0L)
    private val databaseFootprintQueued = AtomicBoolean(false)
    private val integrationDashboardRefreshQueued = AtomicBoolean(false)
    private val integrationDashboardRefreshPending = AtomicBoolean(false)
    @Volatile private var lastDatabaseFootprintAtMs = Long.MIN_VALUE
    private var lastKpiPublishAtMs = Long.MIN_VALUE
    private var lastKpiObservationAtMs = Long.MIN_VALUE
    private var pendingVehicleKpis: LocalizedVehicleKpis? = null
    private var kpiPublishScheduled = false
    private val mqttRetryTask = object : Runnable {
        override fun run() {
            mqttRetryScheduled = false
            mqttRetryAtElapsedMs = null
            if (!running.get() || !settings.isMqttEnabled() || maintenanceBlocksRuntimeStart()) return
            flushPendingMqttAsync(force = false)
        }
    }
    private val influxRetryTask = object : Runnable {
        override fun run() {
            val scheduledAt = influxRetryAtElapsedMs
            recordInfluxDiagnostic(
                com.bydcollector.collector.influx.InfluxDiagnosticEvent(
                    "influx_retry_fired",
                    mapOf(
                        "retry_deadline_elapsed_ms" to (scheduledAt ?: -1L).toString(),
                        "generation" to influxWorkGeneration.get().toString(),
                        "reason" to "scheduled"
                    )
                )
            )
            influxRetryScheduled = false
            influxRetryAtElapsedMs = null
            recordInfluxDiagnostic(
                com.bydcollector.collector.influx.InfluxDiagnosticEvent(
                    "influx_runtime_state",
                    influxDiagnosticStateDetails(influxWorkGeneration.get(), queued = influxRequestQueued.get()) +
                        mapOf("retry_deadline_elapsed_ms" to "none", "retry_deadline" to "none")
                )
            )
            if (!running.get()) {
                recordInfluxGate("stopped")
                return
            }
            if (!settings.isInfluxEnabled()) {
                recordInfluxGate("disabled")
                return
            }
            if (maintenanceBlocksRuntimeStart()) {
                recordInfluxGate("maintenance")
                return
            }
            requestInfluxCycle()
        }
    }
    private val telegramTickTask = object : Runnable {
        override fun run() {
            telegramTickScheduled = false
            telegramTickAtMs = null
            if (!running.get() || !settings.isTelegramEnabled() || maintenanceBlocksRuntimeStart()) return
            val coordinator = telegramCoordinator ?: return
            scheduleTelegramTick()
            executeOrderedTelegram(
                "telegram_tick_error",
                coordinator = coordinator,
                onSuccess = ::postTelegramTickSchedule
            ) {
                coordinator.tick(
                    mainCollectionExpected = settings.isPollingEnabled() && !settings.isMainManuallyStopped(),
                    lastError = lastTelegramPollError
                )
            }
        }
    }
    private val accessSelfCheckTask = object : Runnable {
        override fun run() {
            accessSelfCheckScheduled = false
            if (!settings.hasActiveAccessWork()) return
            requestAccessSelfCheck("runtime_watchdog")
            scheduleAccessSelfCheck()
        }
    }
    private val dashboardHeartbeatTask = object : Runnable {
        override fun run() {
            if (!running.get()) return
            publishDashboardRuntimeFlags()
            scheduleDatabaseFootprintRefresh(force = false)
            mainHandler.postDelayed(this, DASHBOARD_RUNTIME_HEARTBEAT_MS)
        }
    }
    private val kpiPublishTask = Runnable {
        kpiPublishScheduled = false
        pendingVehicleKpis?.let { kpis ->
            pendingVehicleKpis = null
            publishVehicleKpisNow(kpis)
        }
    }
    private val kpiStaleTask = Runnable {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastKpiObservationAtMs >= KPI_STALE_AFTER_MS) clearDashboardVehicleKpis()
    }

    override fun onCreate() {
        super.onCreate()
        val application = applicationContext as BydCollectorApplication
        historicalEnergyGeneration = application.beginHistoricalEnergyBackfillOwner()
        application.updateRuntime.start("collector_service")
        influxRuntimeDiagnostics.attachJournal(applicationContext)
        running.set(true)
        mainRuntimeStatus = RuntimeActionStatus.STOPPED
        debugRuntimeStatus = DebugRuntimeStatus.STOPPED
        debugRuntimeError = null
        mqttRuntimeStatus = RuntimeActionStatus.STOPPED
        influxRuntimeStatus = RuntimeActionStatus.STOPPED
        mainRuntimeStatusRef.set(mainRuntimeStatus)
        debugRuntimeStatusRef.set(debugRuntimeStatus)
        mqttRuntimeStatusRef.set(mqttRuntimeStatus)
        influxRuntimeStatusRef.set(influxRuntimeStatus)
        recordInfluxDiagnostic(
            com.bydcollector.collector.influx.InfluxDiagnosticEvent(
                "influx_runtime_state",
                mapOf(
                    "running" to "true",
                    "generation" to influxWorkGeneration.get().toString(),
                    "queued" to "false",
                    "inflight" to "0",
                    "frozen" to "false",
                    "actual_route" to "none",
                    "endpoints" to "none",
                    "reason" to "service_started"
                )
            )
        )
        store = BydCollectorApplication.store(applicationContext)
        settings = CollectorSettings(applicationContext, store)
        configureDesiredStreams()
        reconcilePersistedHelperStateAsync()
        debugStorageReady = BydCollectorApplication.isDebugStorageReady(applicationContext)
        // Keep Main available when the secondary filename is ambiguous; readiness fails closed on start.
        debugStore = DirectDebugStore(applicationContext)
        dashboardUiStateStore = BydCollectorApplication.dashboardUiStateStore(applicationContext)
        dashboardStateProvider = DashboardStateProvider(applicationContext, { store }, settings)
        if (dashboardUiStateStore.currentChrome() == null) {
            dashboardUiStateStore.seed(dashboardStateProvider.loadInitial())
        }
        keepAliveSupervisor = KeepAliveSupervisor(applicationContext, store)
        val endpointProbe = SocketHaEndpointProbe()
        tailscaleGate = TailscaleActivationGate(
            isEnabled = { settings.isTailscaleActivationEnabled() },
            lastAttemptAtMs = { settings.tailscaleActivationLastAttemptAtMs() },
            setLastAttemptAtMs = { settings.setTailscaleActivationLastAttemptAtMs(it) },
            isReachable = endpointProbe::isReachable,
            processCheck = { TailscaleActivator.checkProcess(applicationContext) },
            activate = { scheduleTailscaleActivation() }
        )
        vehicleStateNormalizer = VehicleStateNormalizer()
        mqttCoordinator = createMqttCoordinator(processMqttClientFacade)
        influxCoordinator = createInfluxCoordinator()
        telegramCoordinator = createTelegramCoordinator()
        normalizedStateChangedCallback = { changedCategories -> publishChangedCategoriesAsync(changedCategories) }
        tripRuntime = createTripRuntimeCoordinator()
        poller = createTelemetryPoller()
        maintenanceCoordinator = DbMaintenanceCoordinator(
            context = applicationContext,
            settings = settings,
            application = applicationContext as BydCollectorApplication,
            stopRuntime = { stopRuntimeForMaintenance(it) },
            onStoreReopened = { newStore ->
                runOnRuntimeOwnerBlocking {
                    check(running.get()) { "Collector service stopped during database maintenance" }
                    rebuildStoreBackedRuntime(newStore)
                }
            },
            closeDebugStore = { debugStore.close() },
            onDebugStoreReopened = { newStore ->
                val storageReady = newStore.isCompactV2()
                runOnRuntimeOwnerBlocking {
                    check(running.get()) { "Collector service stopped during debug database maintenance" }
                    rebindDebugStoreAfterMaintenance(newStore, storageReady)
                }
            }
        )
        createNotificationChannel()
        publishDashboardRuntimeFlags()
        scheduleDashboardCountBootstrap(force = false)
        scheduleIntegrationDashboardRefresh()
        scheduleDatabaseFootprintRefresh(force = true)
        mainHandler.postDelayed(dashboardHeartbeatTask, DASHBOARD_RUNTIME_HEARTBEAT_MS)
        registerTelegramNetworkCallback()
        if (settings.isAutoStartEnabled() && settings.hasActiveAccessWork()) {
            requestAccessSelfCheck("runtime_supervisor_start")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stickyRestart = intent?.action == null
        val action = intent?.action ?: "sticky_restart"
        val forceKeepAliveStatusCheck = intent?.getBooleanExtra(EXTRA_FORCE_KEEP_ALIVE_STATUS_CHECK, false) == true
        if (action == CollectorAutoStart.ACTION_KEEP_ALIVE_STOP_RETRY) {
            val retryAttempt =
                intent?.getIntExtra(CollectorAutoStart.EXTRA_KEEP_ALIVE_STOP_RETRY_ATTEMPT, 0) ?: 0
            if (keepAliveStopRetryBlockedByMaintenance()) {
                CollectorAutoStart.deferKeepAliveStopRetry(applicationContext, retryAttempt)
            } else {
                reconcileKeepAliveStopRetry(retryAttempt)
            }
            return START_STICKY
        }
        if (action == ACTION_SHUTDOWN) {
            shutdownByUser()
            return START_NOT_STICKY
        }
        if (settings.isUserShutdownRequested()) {
            suppressStartAfterUserShutdown(action)
            return START_NOT_STICKY
        }
        recoverInterruptedMaintenanceIfNeeded(action)
        recoverInterruptedArchiveDeleteIfNeeded(action)
        if (
            maintenanceActive.get() &&
            activeMaintenanceOperation == DbMaintenanceOperation.ARCHIVE &&
            action != ACTION_CANCEL_DATABASE_MAINTENANCE
        ) {
            return START_STICKY
        }
        if (stickyRestart) reconcilePendingCutoverArchiveStorage(action)
        if (stickyRestart) {
            reconcilePersistedRuntime(resetDebugToAutoStartDemand = true)
        } else when (action) {
            ACTION_STOP -> {
                //MainActivity commits the desired/manual flags before dispatching this action.
                //Do not let a delayed stop intent overwrite a newer start intent.
                if (!settings.isPollingEnabled() && settings.isMainManuallyStopped()) {
                    settings.setMainManuallyStopped(true)
                    settings.setPollingEnabled(false)
                    stopMain("polling_disabled")
                }
                reconcileCollection()
            }
            ACTION_START_DEBUG -> {
                reconcileCollection(DEBUG_REASON_MANUAL)
            }
            ACTION_RECONCILE_DEBUG -> reconcileDebugRuntime()
            ACTION_STOP_DEBUG -> {
                if (!settings.isDebugPollingEnabled() && settings.isDebugManuallyStopped()) {
                    settings.setDebugManuallyStopped(true)
                    settings.setDebugPollingEnabled(false)
                    stopDebug("debug_disabled")
                }
                reconcileCollection()
            }
            ACTION_RECONCILE_KEEP_ALIVE -> reconcilePersistedRuntime(
                reconcileKeepAliveState = true,
                forceKeepAliveStatusCheck = forceKeepAliveStatusCheck
            )
            ACTION_START_MQTT_EXPORT -> startMqttExport(clearManualStop = true)
            ACTION_RECONCILE_MQTT_EXPORT -> reconcileMqttAutoStart()
            ACTION_STOP_MQTT_EXPORT -> stopMqttExport(manualStop = true)
            ACTION_START_INFLUX_EXPORT -> startInfluxExport(clearManualStop = true)
            ACTION_RECONCILE_INFLUX_EXPORT -> reconcileInfluxAutoStart()
            ACTION_STOP_INFLUX_EXPORT -> stopInfluxExport(manualStop = true)
            ACTION_RECONCILE_TELEGRAM -> reconcileTelegramRuntime(unblockBlocked = true)
            ACTION_TEST_TELEGRAM -> testTelegramConnection()
            ACTION_ARCHIVE_DATABASE -> startDatabaseMaintenance(DbMaintenanceOperation.ARCHIVE)
            ACTION_ARCHIVE_DEBUG_DATABASE -> startDatabaseMaintenance(DbMaintenanceOperation.DEBUG_ARCHIVE)
            ACTION_CANCEL_DATABASE_MAINTENANCE -> cancelDatabaseMaintenance()
            ACTION_RECONCILE_ARCHIVE_STORAGE -> {
                ensureForegroundForChannel("Archive storage")
                enqueueArchiveStorageMaintenance(null)
            }
            ACTION_DELETE_ARCHIVES -> {
                ensureForegroundForChannel("Archive storage")
                enqueueArchiveDelete(intent?.getStringArrayListExtra(EXTRA_ARCHIVE_IDS).orEmpty())
            }
            ACTION_START -> reconcileCollection(forceKeepAliveStatusCheck = forceKeepAliveStatusCheck)
        }
        if (!stickyRestart) reconcilePendingCutoverArchiveStorage(action)
        reconcileAccessSelfCheckSchedule()
        return START_STICKY
    }

    override fun onDestroy() {
        requireRuntimeOwner()
        running.set(false)
        debugStoreCloseRequested.set(true)
        (applicationContext as BydCollectorApplication).cancelHistoricalEnergyBackfill()
        maintenanceRuntimeRestoreAllowed.set(false)
        telegramWorkGeneration.incrementAndGet()
        telegramRecoveryCoalescer.invalidate()
        unregisterTelegramNetworkCallback()
        mainHandler.removeCallbacks(accessSelfCheckTask)
        mainHandler.removeCallbacks(dashboardHeartbeatTask)
        mainHandler.removeCallbacks(kpiPublishTask)
        mainHandler.removeCallbacks(kpiStaleTask)
        cancelMqttRetry()
        cancelInfluxRetry("service_destroyed")
        cancelTelegramTick()
        accessSelfCheckScheduled = false
        stopCollection("service_destroyed")
        DirectStreamController.releaseApp()
        if (::tripRuntime.isInitialized) tripRuntime.close()
        keepAliveSupervisor.shutdown()
        debugStartExecutor.shutdownNow()
        closeDebugStoreAfterDebugStartExecutorStops()
        maintenanceExecutor.shutdownNow()
        archiveStorageExecutor.shutdownNow()
        tailscaleExecutor.shutdownNow()
        dashboardMetricsExecutor.shutdownNow()
        dashboardCountExecutor.shutdownNow()
        shutdownMqttExecutor(interrupt = true)
        shutdownInfluxExecutor()
        shutdownTelegramExecutor()
        activeMaintenanceOperation = null
        maintenanceActive.set(false)
        maintenanceRunningInProcess.set(false)
        mainPollingRunning.set(false)
        mainRuntimeStatus = RuntimeActionStatus.STOPPED
        debugRuntimeStatus = DebugRuntimeStatus.STOPPED
        debugRuntimeError = null
        mqttRuntimeStatus = RuntimeActionStatus.STOPPED
        influxRuntimeStatus = RuntimeActionStatus.STOPPED
        mainRuntimeStatusRef.set(mainRuntimeStatus)
        debugRuntimeStatusRef.set(debugRuntimeStatus)
        mqttRuntimeStatusRef.set(mqttRuntimeStatus)
        influxRuntimeStatusRef.set(influxRuntimeStatus)
        recordInfluxDiagnostic(
            com.bydcollector.collector.influx.InfluxDiagnosticEvent(
                "influx_runtime_state",
                mapOf(
                    "running" to "false",
                    "generation" to influxWorkGeneration.get().toString(),
                    "queued" to "false",
                    "inflight" to influxWorkInFlight.get().toString(),
                    "frozen" to "false",
                    "actual_route" to "none",
                    "endpoints" to "none",
                    "reason" to "service_destroyed"
                )
            )
        )
        publishDashboardRuntimeFlags()
        mqttConnection.release()
        influxConnection.release()
        clearDashboardVehicleKpis()
        closeDebugStoreAfterLocalWorkers()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        store.recordEvent("task_removed", "Collector task removed from recents")
        CollectorAutoStart.scheduleRestartAfterTaskRemoved(applicationContext, settings, store)
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int) {
        handleForegroundServiceTimeout(startId, null)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleForegroundServiceTimeout(startId, fgsType)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createTelemetryPoller(
        ownerMode: DirectHelperOwnerMode = DirectHelperOwnerMode.APP_GAP_SPOOL
    ): TelemetryPoller {
        val helper = DirectVehicleHelperClient()
        val adbClient = AdbLocalClient(File(applicationContext.filesDir, "adb_keys"))
        val observer = createSuccessfulPollObserver()
        val liveClient = DirectTelemetryClient(
            context = applicationContext,
            adbClient = adbClient,
            helper = helper,
            expectedOwnerMode = ownerMode
        )
        val live = PollPersistenceCoordinator(
            store = store,
            client = liveClient,
            successfulPollObserver = observer
        )
        val replay = TelemetryWorkerReplayCoordinator(
            store = store,
            ensureHelper = {
                liveClient.ensureHelperReady(ownerMode)?.let { failure ->
                    "${failure.category}: ${failure.message}"
                }
            },
            pendingSamples = helper::pendingWorkerSamples,
            acknowledgeSample = helper::acknowledgeWorkerSample,
            successfulPollObserver = observer
        )
        val nextPoller = TelemetryPoller(
            TelemetryWorkerReplayPollCycleRunner(
                replay = replay,
                live = live
            ),
            onCycleResult = { result -> handlePollCycleResult(result) }
        )
        mainPollerOwnerMode = ownerMode
        return nextPoller
    }

    private fun createSuccessfulPollObserver(): SuccessfulPollObserver {
        val trips = BydCollectorApplication.trips(applicationContext)
        val energy = com.bydcollector.collector.data.energy.EnergySessionCoordinator(trips) {
            trips.loadOpenSession()?.let {
                com.bydcollector.collector.data.energy.EnergySessionSeed(it.tripId, it.startedAt)
            }
        }
        val fallbackSource = com.bydcollector.collector.data.polling.LivePollSource()
        var energyHydrated = false
        //normalizes only after raw poll persistence so raw telemetry remains the source of truth
        return object : SuccessfulPollObserver {
            private var energyFailure: Exception? = null
            private var lastEnergyFailureKey: String? = null

            private fun <T> energyAttempt(action: () -> T): T? = try {
                action()
            } catch (error: Exception) {
                if (error is InterruptedException) throw error
                energyFailure = error
                val detail = "${error::class.java.simpleName}: ${error.message.orEmpty()}"
                if (detail != lastEnergyFailureKey) {
                    try {
                        store.recordEvent("energy_processing_error", "Energy processing is unavailable; raw collection continues", detail)
                        lastEnergyFailureKey = detail
                    } catch (diagnosticError: Exception) {
                        if (diagnosticError is InterruptedException) throw diagnosticError
                    }
                }
                null
            }

            private fun finishEnergyAttempt(origin: PollOrigin) {
                if (energyFailure == null) lastEnergyFailureKey = null
                // Ordinary energy storage/projection faults are still retryable, not
                // permission to ACK an unfinished shell sample. Core consumers ran first.
                if (origin == PollOrigin.REPLAY) energyFailure?.let { throw it }
            }

            private fun prepareEnergyProjection() {
                if (!energyHydrated) {
                    energy.stageCurrentProjection()
                    energyHydrated = true
                }
                // Pending domain state is portable across Main archive; drain before any newer receipt.
                energy.pendingProjection()?.let(::persistEnergyProjection)
            }

            private fun persistEnergyProjection(
                pending: com.bydcollector.collector.data.energy.EnergyPendingProjection
            ) {
                persistLocationObservations(
                    com.bydcollector.collector.data.energy.EnergyTelemetryProjection.observations(pending.snapshot)
                )
                check(energy.confirmProjected(pending.snapshot.snapshotId)) {
                    "Energy projection receipt changed"
                }
            }

            override fun onSuccessfulPoll(
                sessionId: Long,
                pollId: Long,
                timestamp: String,
                readings: List<PollReading>,
                origin: PollOrigin
            ) {
                onSourcePoll(sessionId, pollId, timestamp, readings, origin,
                    fallbackSource.capture(android.os.SystemClock.elapsedRealtime()))
            }

            override fun onSourcePoll(
                sessionId: Long, pollId: Long, timestamp: String, readings: List<PollReading>,
                origin: PollOrigin, source: com.bydcollector.collector.data.polling.PollSampleSource
            ) {
                energyFailure = null
                if (origin == PollOrigin.LIVE) {
                    (applicationContext as BydCollectorApplication).scheduleHistoricalEnergyBackfill(historicalEnergyGeneration)
                }
                val observations = vehicleStateNormalizer.normalize(
                    pollId = pollId,
                    observedAt = timestamp,
                    readings = readings
                )
                val energyResult = energyAttempt {
                    prepareEnergyProjection()
                    energy.process(com.bydcollector.collector.data.energy.EnergyTelemetryProjection.receipt(
                        source, timestamp, readings, observations
                    ))
                }?.takeUnless { it.stale }
                val energySnapshot = energyResult?.snapshot
                // Inactive cursor-only receipts retain the final snapshot, but must not
                // republish it as fresh and oscillate an expired Main value between OK/STALE.
                val energyObservations = energyResult?.pendingProjection?.snapshot
                    ?.let(com.bydcollector.collector.data.energy.EnergyTelemetryProjection::observations).orEmpty()
                val summary = store.applyNormalizedObservations(observations + energyObservations)
                energyResult?.pendingProjection?.let {
                    energyAttempt {
                        check(energy.confirmProjected(it.snapshot.snapshotId)) { "Energy projection receipt changed" }
                    }
                }
                dashboardUiStateStore.incrementMainRowCounts(
                    normalizedCurrentRows = summary.currentInsertedCount.toLong(),
                    normalizedHistoryRows = summary.historyInsertedCount.toLong()
                )
                queueDashboardVehicleKpis(
                    LocalizedVehicleKpis(
                        uk = VehicleKpiMapper.fromObservations(observations, VehicleKpiLanguage.UK),
                        en = VehicleKpiMapper.fromObservations(observations, VehicleKpiLanguage.EN)
                    )
                )
                scheduleDatabaseFootprintRefresh(force = false)
                val diagnosticPowerSession = CompletableFuture<String?>()
                tripRuntime.onSuccessfulPoll(
                    timestamp,
                    readings,
                    observations,
                    liveTelemetry = origin == PollOrigin.LIVE,
                    diagnosticPowerSession = diagnosticPowerSession,
                    energySnapshot = energySnapshot,
                    beforeBoundary = { watermark ->
                        val coordinator = telegramCoordinator
                        if (coordinator != null) {
                            val generation = telegramWorkGeneration.get()
                            executeTelegram("telegram_event_error", onSuccess = ::postTelegramTickSchedule) {
                                if (telegramCoordinator !== coordinator) return@executeTelegram null
                                drainTripCompletions(coordinator, watermark)
                                coordinator.onSuccessfulPoll(observations, energySnapshot) { legId ->
                                    correlateTripDiagnostic(
                                        legId, diagnosticPowerSession,
                                        isCurrent = {
                                            running.get() && telegramCoordinator === coordinator &&
                                                telegramWorkGeneration.get() == generation
                                        },
                                        enqueue = { action -> executeTelegram("telegram_diagnostic_correlation_error") { action() } },
                                        bind = coordinator::bindTripDiagnosticParent
                                    )
                                }
                            }
                        }
                    }
                )
                if (summary.changedCategories.isNotEmpty()) {
                    normalizedStateChangedCallback?.invoke(summary.changedCategories)
                }
                exportInfluxAfterNormalizedWrite(summary)
                finishEnergyAttempt(origin)
            }

            override fun onSourceFailure(
                sessionId: Long,
                pollId: Long,
                timestamp: String,
                origin: PollOrigin,
                source: com.bydcollector.collector.data.polling.PollSampleSource
            ) {
                energyFailure = null
                if (origin == PollOrigin.LIVE) {
                    (applicationContext as BydCollectorApplication).scheduleHistoricalEnergyBackfill(historicalEnergyGeneration)
                }
                energyAttempt {
                    prepareEnergyProjection()
                    val energyResult = energy.process(
                        com.bydcollector.collector.data.energy.EnergyTelemetryProjection.receipt(
                            source = source,
                            observedAt = timestamp,
                            readings = emptyList(),
                            observations = emptyList()
                        )
                    )
                    if (!energyResult.stale) energyResult.pendingProjection?.let(::persistEnergyProjection)
                }
                finishEnergyAttempt(origin)
            }
        }
    }

    private fun createTripRuntimeCoordinator(): TripRuntimeCoordinator {
        return TripRuntimeCoordinator(
            context = applicationContext,
            tripStore = BydCollectorApplication.trips(applicationContext),
            historyEnabled = settings::isTripHistoryEnabled,
            locationCaptureEnabled = {
                settings.isTripHistoryEnabled() ||
                    settings.isTelegramSendLocationEnabled() ||
                    settings.mqttEnabledCategories().contains("location") ||
                    settings.effectiveInfluxCategories().contains("location")
            },
            persistLocation = ::persistLocationObservations,
            completionEnabled = settings::isTelegramEnabled,
            onCompletionReady = { watermark ->
                executeTelegram("telegram_power_off_error", onSuccess = ::postTelegramTickSchedule) {
                    telegramCoordinator?.let { drainTripCompletions(it, watermark) }
                }
            },
            recordEvent = store::recordEvent,
            onConfirmedPowerOn = { requestTelegramRecovery(RECOVERY_VEHICLE_ON) }
        )
    }

    private fun persistLocationObservations(observations: List<NormalizedObservation>) {
        val summary = store.applyNormalizedObservations(observations)
        dashboardUiStateStore.incrementMainRowCounts(
            normalizedCurrentRows = summary.currentInsertedCount.toLong(),
            normalizedHistoryRows = summary.historyInsertedCount.toLong()
        )
        scheduleDatabaseFootprintRefresh(force = false)
        if (summary.changedCategories.isNotEmpty()) {
            normalizedStateChangedCallback?.invoke(summary.changedCategories)
        }
        exportInfluxAfterNormalizedWrite(summary)
    }

    private fun drainTripCompletions(coordinator: TelegramCoordinator, watermark: Long): Long? {
        if (!settings.isTelegramEnabled()) return null
        val trips = BydCollectorApplication.trips(applicationContext)
        var deadline: Long? = null
        com.bydcollector.collector.telegram.handoffTripCompletions(
            frontier = watermark,
            pending = { trips.pendingCompletions(watermark) },
            accept = { intent -> coordinator.acceptTripCompletion(intent, intent.lastLocation?.let(::telegramLocationSnapshot)) },
            acknowledge = { intent -> trips.acknowledgeCompletion(intent.sequence, intent.identity) },
            afterAck = { preparation ->
                if (preparation != null) deadline = coordinator.deliverPreparedPowerOff(preparation)
            }
        )
        return deadline
    }

    private fun telegramLocationSnapshot(sample: com.bydcollector.collector.data.trips.TripCompletionLocation): TelegramLocationSnapshot {
        val latitude = sample.latitude
        val longitude = sample.longitude
        val capturedAtMs = Instant.parse(sample.capturedAt).toEpochMilli()
        val ageSeconds = ((System.currentTimeMillis() - capturedAtMs).coerceAtLeast(0L) / 1_000L)
        return TelegramLocationSnapshot(
            latitude = latitude,
            longitude = longitude,
            capturedAt = sample.capturedAt,
            ageSeconds = ageSeconds,
            osmUrl = "https://www.openstreetmap.org/?mlat=$latitude&mlon=$longitude#map=17/$latitude/$longitude",
            googleUrl = "https://www.google.com/maps/search/?api=1&query=$latitude,$longitude",
            appleUrl = "https://maps.apple.com/?ll=$latitude,$longitude",
            wazeUrl = "https://www.waze.com/ul?ll=$latitude%2C$longitude&navigate=yes"
        )
    }

    private fun reconcileDebugRuntime() {
        if (maintenanceBlocksRuntimeStart(debugRuntime = true)) return
        if (!settings.isDebugAutoStartEnabled() || settings.isDebugManuallyStopped()) {
            stopIfNoActiveRuntime()
            return
        }
        settings.setDebugPollingEnabled(true)
        ensureForegroundForChannel("All data collection running")
        startDebugIfNeeded(DEBUG_REASON_AUTOSTART)
        CollectorAutoStart.scheduleWatchdog(applicationContext, settings, store)
    }

    private fun reconcilePersistedRuntime(
        reconcileKeepAliveState: Boolean = false,
        forceKeepAliveStatusCheck: Boolean = false,
        resetDebugToAutoStartDemand: Boolean = false
    ) {
        val demand = settings.runtimeDemand(includeEnabledExports = true)
        // Sticky recovery follows auto-start demand, never a stale manual debug flag.
        if (resetDebugToAutoStartDemand) settings.setDebugPollingEnabled(demand.debug)
        demand.recoveryActions().forEach { recoveryAction ->
            when (recoveryAction) {
                RuntimeRecoveryAction.MAIN -> reconcileCollection(
                    forceKeepAliveStatusCheck = forceKeepAliveStatusCheck
                )
                RuntimeRecoveryAction.DEBUG -> reconcileDebugRuntime()
                RuntimeRecoveryAction.MQTT -> startMqttExport(clearManualStop = false)
                RuntimeRecoveryAction.INFLUX -> startInfluxExport(clearManualStop = false)
                RuntimeRecoveryAction.TELEGRAM -> reconcileTelegramRuntime(unblockBlocked = true)
                RuntimeRecoveryAction.KEEP_ALIVE -> reconcileKeepAliveOnly(forceKeepAliveStatusCheck)
            }
        }
        if (reconcileKeepAliveState && !demand.main && !demand.keepAlive) {
            reconcileKeepAliveOnly(forceKeepAliveStatusCheck)
        } else if (!demand.any) {
            stopIfNoActiveRuntime()
        }
    }

    private fun reconcileMqttAutoStart() {
        if (!settings.isMqttAutoStartEnabled() || settings.isMqttManuallyStopped()) {
            if (!mqttRuntimeActive.get() && !mqttConnection.stopping) mqttConnection.release()
            stopIfNoActiveRuntime()
            return
        }
        startMqttExport(clearManualStop = false)
        CollectorAutoStart.scheduleWatchdog(applicationContext, settings, store)
    }

    private fun reconcileInfluxAutoStart() {
        if (!settings.isInfluxAutoStartEnabled() || settings.isInfluxManuallyStopped()) {
            if (influxWorkInFlight.get() == 0 && !settings.isInfluxEnabled() && !influxConnection.stopping) influxConnection.release()
            stopIfNoActiveRuntime()
            return
        }
        startInfluxExport(clearManualStop = false)
        CollectorAutoStart.scheduleWatchdog(applicationContext, settings, store)
    }

    private fun reconcileCollection(
        debugStartReason: String = DEBUG_REASON_AUTOSTART,
        forceKeepAliveStatusCheck: Boolean = false
    ) {
        if (maintenanceBlocksRuntimeStart()) return
        try {
            configureDesiredStreams()
            val mainEnabled = settings.isPollingEnabled()
            val debugEnabled = settings.isDebugPollingEnabled()
            val mainAllowed = mainEnabled && !settings.isMainManuallyStopped()
            val debugAllowed = debugEnabled && !settings.isDebugManuallyStopped()
            val keepAliveConfig = settings.keepAliveConfig()
            val keepAliveEnabled = keepAliveConfig.anyEnabled
            val telegramEnabled = settings.isTelegramEnabled()
            val runtimeDemand = settings.runtimeDemand()
            //stops the foreground service only after keep-alive settings have been mirrored to the shell delegate
            if (!mainAllowed && !debugAllowed && !runtimeDemand.any) {
                store.recordEvent("service_start_skipped", "No runtime channel is enabled")
                stopMain("polling_disabled")
                stopDebug("debug_disabled")
                stopAfterKeepAliveReconcile(keepAliveConfig)
                return
            }
            val initialNotificationText = notificationText(mainAllowed, debugAllowed, keepAliveEnabled, telegramEnabled)
            lastNotificationText = initialNotificationText
            startForeground(NOTIFICATION_ID, buildNotification(initialNotificationText))
            acquireWakeLock()
            //keeps network/bluetooth policy independent from whether telemetry polling itself is active
            keepAliveSupervisor.reconcile(
                keepAliveConfig,
                forceStatusCheck = forceKeepAliveStatusCheck && keepAliveEnabled
            )

            if (mainAllowed) {
                startMainIfNeeded()
            } else {
                stopMain("polling_disabled")
            }

            if (debugAllowed) {
                startDebugIfNeeded(debugStartReason)
            } else {
                stopDebug("debug_disabled")
            }

            if (runtimeDemand.mqtt) startMqttExport(clearManualStop = false)
            if (
                (settings.isInfluxEnabled() || runtimeDemand.influx) &&
                !settings.isInfluxManuallyStopped()
            ) startInfluxExport(clearManualStop = false)
            if (telegramEnabled) reconcileTelegramRuntime()

            CollectorAutoStart.scheduleWatchdog(applicationContext, settings, store)
        } catch (error: RuntimeException) {
            handleStartFailure(error)
        }
    }

    private fun reconcileKeepAliveOnly(forceKeepAliveStatusCheck: Boolean = false) {
        try {
            val keepAliveConfig = settings.keepAliveConfig()
            val keepAliveEnabled = keepAliveConfig.anyEnabled
            val mainRunning = poller.isRunning()
            val debugRunning = isDebugPollerRunning()
            //allows the service to exist solely for keep-alive toggles when collection is intentionally stopped
            if (!mainRunning && !debugRunning && !keepAliveEnabled) {
                store.recordEvent("keep_alive_reconcile_stopped", "Keep-alive disabled and no active collector runtime")
                stopAfterKeepAliveReconcile(keepAliveConfig)
                return
            }

            val notification = notificationText(mainRunning, debugRunning, keepAliveEnabled, settings.isTelegramEnabled())
            lastNotificationText = notification
            startForeground(NOTIFICATION_ID, buildNotification(notification))
            acquireWakeLock()
            keepAliveSupervisor.reconcile(
                keepAliveConfig,
                forceStatusCheck = forceKeepAliveStatusCheck && keepAliveEnabled
            )
            CollectorAutoStart.scheduleWatchdog(applicationContext, settings, store)
        } catch (error: RuntimeException) {
            handleStartFailure(error)
        }
    }

    private fun stopAfterKeepAliveReconcile(keepAliveConfig: KeepAliveConfig) {
        val generation = keepAliveStopGeneration.incrementAndGet()
        val stoppingText = "Stopping keep-alive"
        lastNotificationText = stoppingText
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(stoppingText)) }
        keepAliveSupervisor.reconcileThen(keepAliveConfig) { reconciled ->
            mainHandler.post {
                if (generation != keepAliveStopGeneration.get()) return@post
                if (!settings.isUserShutdownRequested() && settings.keepAliveConfig().anyEnabled) {
                    CollectorAutoStart.cancelKeepAliveStopRetry(applicationContext)
                    reconcilePersistedRuntime(
                        reconcileKeepAliveState = true,
                        forceKeepAliveStatusCheck = true
                    )
                    return@post
                }
                if (reconciled) {
                    CollectorAutoStart.cancelKeepAliveStopRetry(applicationContext)
                    if (settings.isUserShutdownRequested()) {
                        lastNotificationText = null
                    } else {
                        reconcilePersistedRuntime(forceKeepAliveStatusCheck = true)
                        restoreNotificationAfterKeepAliveStop()
                    }
                    stopIfNoActiveRuntime()
                } else {
                    finishKeepAliveStopAfterFailure(retryAttempt = 0, "keep_alive_stop_deferred")
                }
            }
        }
    }

    private fun configureDesiredStreams() {
        val userShutdown = settings.isUserShutdownRequested()
        DirectStreamController.configureDesired(
            main = !userShutdown && settings.isPollingEnabled() && !settings.isMainManuallyStopped(),
            secondary = !userShutdown && settings.isDebugPollingEnabled() && !settings.isDebugManuallyStopped()
        )
    }

    private fun reconcilePersistedHelperStateAsync() {
        try {
            debugStartExecutor.execute {
                val helper = DirectVehicleHelperClient()
                if (helper.isAlive() && !DirectStreamController.ensureReady()) {
                    store.recordEvent(
                        "direct_stream_reconcile_failed",
                        "Persisted direct stream state could not be reconciled"
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            // Service teardown owns executor rejection; helper fallback retains desired collection.
        }
    }

    private fun reconcileKeepAliveStopRetry(retryAttempt: Int) {
        val keepAliveEnabled = settings.keepAliveConfig().anyEnabled
        if (!settings.isUserShutdownRequested() && keepAliveEnabled) {
            CollectorAutoStart.cancelKeepAliveStopRetry(applicationContext)
            reconcilePersistedRuntime(
                reconcileKeepAliveState = true,
                forceKeepAliveStatusCheck = true
            )
            return
        }

        val generation = keepAliveStopGeneration.incrementAndGet()
        if (!hasRuntimeOwner()) {
            lastNotificationText = "Stopping keep-alive"
            runCatching { startForeground(NOTIFICATION_ID, buildNotification(lastNotificationText!!)) }
            acquireWakeLock()
        }
        keepAliveSupervisor.reconcileThen(KeepAliveConfig(false, false, false, false)) { reconciled ->
            mainHandler.post {
                if (generation != keepAliveStopGeneration.get()) return@post
                if (!settings.isUserShutdownRequested() && settings.keepAliveConfig().anyEnabled) {
                    CollectorAutoStart.cancelKeepAliveStopRetry(applicationContext)
                    reconcilePersistedRuntime(
                        reconcileKeepAliveState = true,
                        forceKeepAliveStatusCheck = true
                    )
                    return@post
                }
                if (reconciled) {
                    CollectorAutoStart.cancelKeepAliveStopRetry(applicationContext)
                    if (settings.isUserShutdownRequested()) {
                        lastNotificationText = null
                        userShutdownFinalizationStarted.set(false)
                        releaseWakeLock()
                        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                        stopSelf()
                    } else {
                        reconcilePersistedRuntime(forceKeepAliveStatusCheck = true)
                        restoreNotificationAfterKeepAliveStop()
                        stopIfNoActiveRuntime()
                    }
                } else {
                    finishKeepAliveStopAfterFailure(retryAttempt, "keep_alive_stop_retry_failed")
                }
            }
        }
    }

    private fun finishKeepAliveStopAfterFailure(retryAttempt: Int, category: String) {
        runCatching {
            store.recordEvent(
                category,
                "Keep-alive shutdown was not confirmed",
                "attempt=$retryAttempt"
            )
        }
        runCatching {
            CollectorAutoStart.scheduleKeepAliveStopRetry(applicationContext, store, retryAttempt)
        }
        if (settings.isUserShutdownRequested()) {
            lastNotificationText = null
            releaseWakeLock()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            userShutdownFinalizationStarted.set(false)
            stopSelf()
            return
        }
        if (hasRuntimeOwner()) {
            reconcilePersistedRuntime(forceKeepAliveStatusCheck = true)
            restoreNotificationAfterKeepAliveStop()
            return
        }
        lastNotificationText = null
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun startMainIfNeeded() {
        if (maintenanceBlocksRuntimeStart()) return
        if (poller.isRunning()) {
            mainPollingRunning.set(true)
            setMainRuntime(RuntimeActionStatus.RUNNING)
            publishDashboardRuntimeFlags()
            return
        }
        setMainRuntime(RuntimeActionStatus.STARTING)
        val ownerMode = DirectHelperOwnerMode.APP_GAP_SPOOL
        if (mainPollerOwnerMode != ownerMode) poller = createTelemetryPoller(ownerMode)
        mqttRuntimeActive.set(false)
        val openedSessionId = store.openSession()
        sessionId = openedSessionId
        try {
            //imports the car energy database opportunistically; telemetry polling must survive import failure
            val importResult = store.importEcDatabaseAtSessionStart(openedSessionId)
            if (importResult.ok && importResult.insertedCount > 0) {
                dashboardUiStateStore.incrementMainRowCounts(ecRows = importResult.insertedCount.toLong())
            }
        } catch (error: RuntimeException) {
            store.recordEvent(
                "ec_import_error",
                "EC_database.db import failed before polling",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }
        tripRuntime.resume()
        //The activity owns the desired/manual flags. Recheck immediately before touching the poller
        //so a delayed start cannot revive a runtime the user just stopped.
        if (
            !settings.isPollingEnabled() ||
            settings.isMainManuallyStopped() ||
            maintenanceBlocksRuntimeStart()
        ) {
            runCatching { store.endSession(openedSessionId, "polling_start_cancelled") }
            sessionId = null
            tripRuntime.pause("polling_start_cancelled")
            setMainRuntime(RuntimeActionStatus.STOPPED)
            return
        }
        poller.start(openedSessionId)
        mainPollingRunning.set(true)
        setMainRuntime(RuntimeActionStatus.RUNNING)
        publishDashboardRuntimeFlags()
        flushPendingMqttAsync(force = false)
    }

    private fun startDebugIfNeeded(reason: String) {
        if (!settings.isDebugPollingEnabled() || settings.isDebugManuallyStopped()) {
            setDebugRuntime(DebugRuntimeStatus.STOPPED)
            return
        }
        if (maintenanceBlocksRuntimeStart(debugRuntime = true)) return
        if (isDebugPollerRunning()) {
            setDebugRuntime(DebugRuntimeStatus.RUNNING)
            return
        }
        val startGeneration = debugWorkGeneration.incrementAndGet()
        if (!debugStartInProgress.compareAndSet(false, true)) {
            //A start requested while an older worker is unwinding must run after that worker clears.
            debugStartQueued.set(true)
            setDebugRuntime(DebugRuntimeStatus.STARTING, generation = startGeneration)
            return
        }
        setDebugRuntime(DebugRuntimeStatus.STARTING, generation = startGeneration)
        try {
            debugStartExecutor.execute {
                var streamLeaseHandedToPoller = false
                try {
                    //Readiness failures are deliberately retried only when a start is requested.
                    debugStorageReady = BydCollectorApplication.ensureDebugStorageReady(applicationContext)
                    if (!debugStorageReady) {
                        val detail = settings.debugStorageCutoverError() ?: "Debug database readiness failed"
                        if (debugStartStillCurrent(startGeneration)) {
                            setDebugRuntime(DebugRuntimeStatus.ERROR, detail, generation = startGeneration)
                            store.recordEvent("debug_polling_start_error", "Debug database is not ready", detail)
                            updateNotification("Polling error: $detail")
                        }
                        return@execute
                    }
                    if (!debugStartStillCurrent(startGeneration)) return@execute
                    scheduleDashboardCountBootstrap(force = true)
                    val parameters = DirectDebugParameterAsset.load(applicationContext)
                    val helper = DirectVehicleHelperClient()
                    val launch = DirectBridgeManager.ensureRunning(
                        context = applicationContext,
                        adbClient = AdbLocalClient(File(applicationContext.filesDir, "adb_keys")),
                        helper = helper,
                        ownerMode = settings.mainHelperOwnerMode()
                    )
                    if (!launch.ok) {
                        if (debugStartStillCurrent(startGeneration)) {
                            setDebugRuntime(DebugRuntimeStatus.ERROR, launch.message, generation = startGeneration)
                            store.recordEvent("debug_polling_start_error", "Debug direct helper unavailable", launch.message)
                            updateNotification("Polling error: ${PollingErrorSummaries.summary(launch.message)}")
                        }
                        return@execute
                    }
                    configureDesiredStreams()
                    if (!DirectStreamController.ensureReady(CollectorHelperProtocol.STREAM_SECONDARY)) {
                        val detail = "Secondary stream claim/reconcile failed"
                        if (debugStartStillCurrent(startGeneration)) {
                            setDebugRuntime(DebugRuntimeStatus.ERROR, detail, generation = startGeneration)
                            store.recordEvent("debug_polling_start_error", "Debug direct helper unavailable", detail)
                        }
                        return@execute
                    }
                    if (!debugStartStillCurrent(startGeneration)) return@execute
                    val batchSize = DirectDebugParameterAsset.TOTAL_PARAMETER_COUNT
                    var lastDebugReadModeKey: String? = null
                    val nextPoller = DirectDebugRoundRobinPoller(
                        parameters = parameters,
                        helper = helper,
                        store = debugStore,
                        pauseSecondary = {
                            DirectStreamController.pauseAndFence(CollectorHelperProtocol.STREAM_SECONDARY)
                        },
                    drainSecondaryReplay = { openedSessionId ->
                        SecondaryReplayCoordinator(
                            fetchPage = helper::secondarySpoolPage,
                            acknowledge = helper::acknowledgeSecondarySpool,
                            quarantine = helper::quarantineSecondarySpool,
                            importRecord = { record, digest ->
                                debugStore.importSecondaryRecord(openedSessionId, record, digest)
                            }
                        ).drain()
                    },
                    resumeSecondary = {
                        DirectStreamController.resume(CollectorHelperProtocol.STREAM_SECONDARY)
                    },
                    onStarted = started@{ openedSessionId ->
                        mainHandler.post {
                            if (!debugStartStillCurrent(startGeneration)) return@post
                            setDebugRuntime(DebugRuntimeStatus.RUNNING, generation = startGeneration)
                            val previous = dashboardUiStateStore.currentTab(AppTab.ALL_PARAMETERS)
                            dashboardUiStateStore.publishDebugPollState(
                                DashboardDebugPollState(
                                    lastReadingAt = previous?.debugLastReadingAt,
                                    lastErrorAt = previous?.debugLastErrorAt,
                                    lastError = previous?.debugLastError,
                                    errorCount = previous?.debugErrorCount ?: 0L,
                                    lastSessionId = openedSessionId
                                )
                            )
                        }
                    },
                    onFailure = failure@{ detail ->
                        mainHandler.post {
                            if (startGeneration != debugWorkGeneration.get()) return@post
                            setDebugRuntime(DebugRuntimeStatus.ERROR, detail, generation = startGeneration)
                            store.recordEvent("debug_polling_runtime_error", "Secondary polling stopped", detail)
                            updateNotification("Polling error: secondary replay/live cycle failed")
                        }
                    },
                    onTerminalFailure = {
                        if (startGeneration == debugWorkGeneration.get()) {
                            DirectStreamController.releaseLease(CollectorHelperProtocol.STREAM_SECONDARY)
                        }
                    },
                    onStopped = ::onDebugPollerStopped,
                    onCycle = cycle@{ summary ->
                        if (!debugStartStillCurrent(startGeneration)) return@cycle
                        dashboardUiStateStore.incrementDebugReadingCount(summary.changedCount.toLong())
                        val previous = dashboardUiStateStore.currentTab(AppTab.ALL_PARAMETERS)
                        val completedAt = DisplayTimeFormatter.formatNullable(java.time.Instant.now().toString())
                        dashboardUiStateStore.publishDebugPollState(
                            DashboardDebugPollState(
                                lastReadingAt = if (summary.okCount > 0) completedAt else previous?.debugLastReadingAt,
                                lastErrorAt = if (summary.errorCount > 0) completedAt else previous?.debugLastErrorAt,
                                lastError = if (summary.errorCount > 0) {
                                    "Debug helper returned ${summary.errorCount} errors"
                                } else {
                                    previous?.debugLastError
                                },
                                errorCount = (previous?.debugErrorCount ?: 0L).coerceAtLeast(0L) + summary.errorCount,
                                lastSessionId = previous?.debugLastSessionId
                            )
                        )
                        publishDashboardRuntimeFlags()
                        scheduleDatabaseFootprintRefresh(force = false)
                        summary.batchDiagnostics?.let { diagnostics ->
                            if (diagnostics.stateKey != lastDebugReadModeKey) {
                                runCatching {
                                    store.recordEvent(
                                        "debug_direct_read_mode",
                                        "Debug telemetry read mode changed",
                                        diagnostics.summary()
                                    )
                                }.onSuccess { lastDebugReadModeKey = diagnostics.stateKey }
                            }
                        }
                        if (summary.errorCount > 0 && summary.okCount == 0) {
                            updateNotification("Polling error: debug helper returned errors")
                        } else {
                            updateNotification(
                                notificationText(
                                    settings.isPollingEnabled(),
                                    true,
                                    settings.keepAliveConfig().anyEnabled,
                                    settings.isTelegramEnabled()
                                )
                            )
                        }
                    }
                )
                    val started = runOnRuntimeOwnerBlocking {
                        requireRuntimeOwner()
                        synchronized(debugPollerLock) {
                            if (
                                startGeneration != debugWorkGeneration.get() ||
                                !settings.isDebugPollingEnabled() ||
                                settings.isDebugManuallyStopped() ||
                                maintenanceBlocksRuntimeStart(debugRuntime = true) ||
                                debugPoller?.isRunning() == true
                            ) {
                                false
                            } else {
                                nextPoller.start(batchSize)
                                debugPoller = nextPoller
                                true
                            }
                        }
                    }
                    if (!started) {
                        nextPoller.shutdown("debug_start_cancelled")
                        return@execute
                    }
                    streamLeaseHandedToPoller = true
                    if (debugStartStillCurrent(startGeneration)) {
                        store.recordEvent(
                            "debug_polling_started",
                            "Debug round-robin polling started",
                            "reason=$reason batch_size=$batchSize parameters=${parameters.size}"
                        )
                    }
                } catch (error: RuntimeException) {
                    val detail = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                    if (debugStartStillCurrent(startGeneration)) {
                        setDebugRuntime(DebugRuntimeStatus.ERROR, detail, generation = startGeneration)
                        store.recordEvent(
                            "debug_polling_start_error",
                            "Debug round-robin startup failed",
                            detail
                        )
                        updateNotification("Polling error: debug startup failed")
                    }
                } finally {
                    if (!streamLeaseHandedToPoller) {
                        DirectStreamController.releaseLease(CollectorHelperProtocol.STREAM_SECONDARY)
                    }
                    debugStartInProgress.set(false)
                    closeDebugStoreAfterLocalWorkers()
                    if (
                        debugStartQueued.getAndSet(false) &&
                        settings.isDebugPollingEnabled() &&
                        !settings.isDebugManuallyStopped()
                    ) {
                        mainHandler.post {
                            if (running.get()) startDebugIfNeeded(DEBUG_REASON_MANUAL)
                        }
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            debugStartInProgress.set(false)
            debugStartQueued.set(false)
            closeDebugStoreAfterLocalWorkers()
            val detail = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            if (startGeneration == debugWorkGeneration.get()) {
                setDebugRuntime(DebugRuntimeStatus.ERROR, detail, generation = startGeneration)
                store.recordEvent("debug_polling_start_error", "Debug startup worker rejected", detail)
                updateNotification("Polling error: debug startup rejected")
            }
        }
    }

    private fun handleStartFailure(error: RuntimeException) {
        val detail = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
        if (mainRuntimeStatus == RuntimeActionStatus.STARTING || mainRuntimeStatus == RuntimeActionStatus.STOPPING) {
            setMainRuntime(RuntimeActionStatus.ERROR)
        }
        Log.e(TAG, "Collector start failed", error)
        store.recordEvent("service_start_error", "Collector service start failed", detail)
        lastNotificationText = "Polling error: ${PollingErrorSummaries.summary("service_start_error")}"
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildNotification(lastNotificationText ?: "Polling error")
            )
        }
        sessionId?.let { openedSessionId ->
            runCatching { store.endSession(openedSessionId, "service_start_error") }
                .onFailure { endError ->
                    store.recordEvent(
                        "session_end_error",
                        "Failed to close session after service start error",
                        "${endError::class.java.simpleName}: ${endError.message ?: "no message"}"
                    )
                }
        }
        sessionId = null
        clearDashboardVehicleKpis()
        publishDashboardRuntimeFlags()
        releaseWakeLock()
        stopSelf()
    }

    private fun stopCollection(reason: String) {
        stopMain(reason)
        stopDebug(reason)
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun shutdownByUser() {
        settings.setUserShutdownRequested(true)
        if (deferStopForActiveMaintenance("user_shutdown")) return
        stopRuntimeForUserShutdown()
        finishUserShutdown()
    }

    private fun suppressStartAfterUserShutdown(action: String) {
        store.recordEvent(
            "user_shutdown_start_suppressed",
            "Service start suppressed after user shutdown",
            "action=$action"
        )
        if (deferStopForActiveMaintenance("suppressed_action=$action")) return
        stopRuntimeForUserShutdown()
        finishUserShutdown()
    }

    private fun deferStopForActiveMaintenance(reason: String): Boolean {
        if (!maintenanceActive.get()) return false
        CollectorAutoStart.cancelScheduled(applicationContext)
        settings.setPollingEnabled(false)
        settings.setDebugPollingEnabled(false)
        settings.setMqttEnabled(false)
        settings.setInfluxEnabled(false)
        configureDesiredStreams()
        cancelMqttRetry()
        cancelInfluxRetry("maintenance")
        cancelTelegramTick()
        store.recordEvent(
            "user_shutdown_deferred_for_maintenance",
            "User shutdown deferred until database maintenance completes",
            reason
        )
        return true
    }

    private fun stopRuntimeForUserShutdown() {
        CollectorAutoStart.cancelScheduled(applicationContext)
        settings.setPollingEnabled(false)
        settings.setDebugPollingEnabled(false)
        settings.setMqttEnabled(false)
        settings.setInfluxEnabled(false)
        configureDesiredStreams()
        if (DirectVehicleHelperClient().isAlive()) DirectStreamController.ensureReady()
        stopMain("user_shutdown")
        stopDebug("user_shutdown")
        cancelMqttRetry()
        disconnectOfflineAsync()
        cancelInfluxRetry("user_shutdown")
        cancelTelegramTick()
        telegramRecoveryCoalescer.invalidate()
    }

    private fun finishUserShutdown() {
        if (!userShutdownFinalizationStarted.compareAndSet(false, true)) return
        try {
            maintenanceExecutor.execute {
                val mqttWorker = shutdownMqttExecutor()
                val telegramStopped = quiesceTelegramForUserShutdown()
                val influxWorker = synchronized(influxExecutorLock) { influxExecutor }
                val influxStopped = awaitSerializedExecutorAction(
                    executor = influxWorker,
                    executorThreadName = "byd-influx",
                    timeoutMs = USER_SHUTDOWN_STOP_TIMEOUT_MS
                ) {
                    check(influxCoordinator.stopExport().ok) { "Influx stop failed" }
                }
                val mqttStopped = awaitMqttWorkerTermination(mqttWorker, USER_SHUTDOWN_STOP_TIMEOUT_MS)
                if (!mqttStopped || !influxStopped || !telegramStopped) {
                    userShutdownFinalizationStarted.set(false)
                    store.recordEvent(
                        "user_shutdown_worker_stop_timeout",
                        "User shutdown left the service stopped but alive",
                        "mqtt_stopped=$mqttStopped influx_stopped=$influxStopped telegram_stopped=$telegramStopped"
                    )
                    if (!mqttStopped && influxStopped && telegramStopped) {
                        mainHandler.post {
                            if (settings.isUserShutdownRequested()) finishUserShutdown()
                        }
                    }
                    return@execute
                }
                mainHandler.post { stopServiceAfterUserShutdown() }
            }
        } catch (error: RejectedExecutionException) {
            userShutdownFinalizationStarted.set(false)
            store.recordEvent(
                "user_shutdown_finalize_rejected",
                "User shutdown finalization was rejected",
                error::class.java.name
            )
        }
    }

    private fun debugStartStillCurrent(generation: Long): Boolean {
        return generation == debugWorkGeneration.get() &&
            settings.isDebugPollingEnabled() &&
            !settings.isDebugManuallyStopped() &&
            !maintenanceBlocksRuntimeStart(debugRuntime = true)
    }

    private fun stopServiceAfterUserShutdown() {
        val generation = keepAliveStopGeneration.incrementAndGet()
        keepAliveSupervisor.reconcileThen(KeepAliveConfig(false, false, false, false)) { reconciled ->
            mainHandler.post {
                if (generation != keepAliveStopGeneration.get()) return@post
                if (!settings.isUserShutdownRequested()) {
                    CollectorAutoStart.cancelKeepAliveStopRetry(applicationContext)
                    userShutdownFinalizationStarted.set(false)
                    return@post
                }
                if (!reconciled) {
                    userShutdownFinalizationStarted.set(false)
                    finishKeepAliveStopAfterFailure(retryAttempt = 0, "user_shutdown_keep_alive_failed")
                    return@post
                }
                CollectorAutoStart.cancelKeepAliveStopRetry(applicationContext)
                lastNotificationText = null
                releaseWakeLock()
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun stopMain(reason: String) {
        if (reason != "service_destroyed" && reason != "database_maintenance" &&
            (!settings.isPollingEnabled() || settings.isMainManuallyStopped() || reason == "user_shutdown")
        ) {
            DirectStreamController.setDesired(CollectorHelperProtocol.STREAM_MAIN, false)
        }
        val wasActive = mainRuntimeStatus != RuntimeActionStatus.STOPPED
        if (wasActive) setMainRuntime(RuntimeActionStatus.STOPPING)
        val wasPolling = poller.isRunning()
        if (wasPolling) poller.stop()
        if (::tripRuntime.isInitialized && reason != "service_destroyed") tripRuntime.pause(reason)
        if (reason == "user_shutdown") stopAppGapSpoolHelper(reason)
        mainPollingRunning.set(false)
        sessionId?.let { openedSessionId ->
            runCatching { store.endSession(openedSessionId, reason) }
                .onFailure { error ->
                    store.recordEvent(
                        "session_end_error",
                        "Failed to close collection session",
                        "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                    )
                }
        }
        sessionId = null
        if (wasPolling || reason == "service_destroyed") {
            //publishes retained offline only after there was a real live mqtt runtime to retire
            disconnectOfflineAsync()
        }
        clearDashboardVehicleKpis()
        if (!settings.isPollingEnabled() || settings.isMainManuallyStopped() || reason == "service_destroyed") {
            setMainRuntime(RuntimeActionStatus.STOPPED)
        } else if (poller.isRunning()) {
            setMainRuntime(RuntimeActionStatus.RUNNING)
        }
        publishDashboardRuntimeFlags()
    }

    private fun stopAppGapSpoolHelper(reason: String) {
        if (reason == "service_destroyed") return
        val helper = DirectVehicleHelperClient()
        if (!helper.isAlive()) return
        val result = helper.requestStop(DirectHelperOwnerMode.APP_GAP_SPOOL)
        store.recordEvent(
            if (result.ok) "telemetry_spool_helper_stop_requested" else "telemetry_spool_helper_stop_failed",
            if (result.ok) "App-gap spool helper stop requested" else "App-gap spool helper stop failed",
            "reason=$reason ${result.error.orEmpty()}".trim()
        )
    }

    private fun exportInfluxAfterNormalizedWrite(summary: NormalizedWriteSummary) {
        //exports history after normalized changes because influx is the long-term time-series channel
        if (summary.historyInsertedCount <= 0) return
        if (!settings.isInfluxEnabled()) return
        requestInfluxCycle(newData = true)
    }

    private fun requestInfluxCycle(newData: Boolean = false) {
        if (!settings.isInfluxEnabled()) {
            recordInfluxGate("disabled")
            return
        }
        if (influxConnection.stopping) {
            recordInfluxGate("stopped")
            return
        }
        if (maintenanceBlocksRuntimeStart()) {
            recordInfluxGate("maintenance")
            return
        }
        if (!::influxCoordinator.isInitialized) {
            recordInfluxGate("missing_coordinator")
            return
        }
        val request = synchronized(influxQueueLock) {
            if (newData) influxCycleDemand.signal()
            if (influxRetryScheduled || influxWorkInFlight.get() > 0 || influxRequestQueued.get()) {
                recordInfluxGate(
                    "singleflight_occupied",
                    influxDiagnosticStateDetails(
                        influxWorkGeneration.get(),
                        queued = influxRequestQueued.get()
                    )
                )
                return
            }
            influxRequestRevision += 1
            val acquired = checkNotNull(
                influxCycleDemand.tryAcquire(influxRequestRevision, influxWorkGeneration.get())
            )
            influxRequestQueued.set(true)
            influxWorkQueuedAtElapsedMs = SystemClock.elapsedRealtime()
            acquired
        }
        recordInfluxDiagnostic(
            com.bydcollector.collector.influx.InfluxDiagnosticEvent(
                "influx_work_queued",
                influxDiagnosticStateDetails(request.generation, queued = true)
            )
        )
        val coordinator = influxCoordinator
        val isCurrentRequest = {
            request.generation == influxWorkGeneration.get() && influxCoordinator === coordinator
        }
        val represented = AtomicBoolean(false)
        val accepted = executeInflux(
            errorCategory = "influx_cycle_error",
            expectedGeneration = request.generation,
            afterSettled = { settleInfluxCycle(request, represented.get()) }
        ) {
            represented.set(true)
            coordinator.runOneCycle(force = false, isCurrent = isCurrentRequest)
        }
        if (!accepted) {
            recordInfluxGate(
                "queue_rejected",
                influxDiagnosticStateDetails(
                    workGeneration = request.generation.takeUnless { it == influxWorkGeneration.get() },
                    queued = influxRequestQueued.get()
                )
            )
        }
    }

    private fun settleInfluxCycle(request: InfluxCycleRequest, represented: Boolean) {
        val settlement = synchronized(influxQueueLock) {
            val result = influxCycleDemand.settle(request, represented)
            if (result.current) {
                influxRequestQueued.set(false)
                influxWorkQueuedAtElapsedMs = null
            }
            result
        }
        recordInfluxDiagnostic(
            com.bydcollector.collector.influx.InfluxDiagnosticEvent(
                "influx_work_settled",
                influxDiagnosticStateDetails(
                    workGeneration = request.generation.takeUnless { settlement.current },
                    queued = influxRequestQueued.get()
                )
            )
        )
        if (settlement.current && represented) {
            postInfluxRetrySchedule(request.generation, request.revision)
        }
    }

    private fun influxDiagnosticStateDetails(workGeneration: Long?, queued: Boolean): Map<String, String> {
        val coordinator = if (::influxCoordinator.isInitialized) influxCoordinator else null
        return buildMap {
            put("running", running.get().toString())
            put("generation", influxWorkGeneration.get().toString())
            put("queued", queued.toString())
            put("inflight", influxWorkInFlight.get().toString())
            put("queued_at_elapsed_ms", influxWorkQueuedAtElapsedMs?.toString() ?: "none")
            put("work_generation", workGeneration?.toString() ?: "none")
            put("frozen", (coordinator?.sessionFrozen ?: false).toString())
            put("actual_route", coordinator?.activeRoute?.name ?: "none")
            put("endpoints", coordinator?.frozenEndpoints ?: "none")
        }
    }

    private fun recordInfluxDiagnostic(event: com.bydcollector.collector.influx.InfluxDiagnosticEvent) {
        influxRuntimeDiagnostics.record(event.copy(details = event.details + ("runtime_id" to influxDiagnosticRuntimeId)))
    }

    private fun recordInfluxGate(reason: String, details: Map<String, String> = emptyMap()) {
        influxRuntimeDiagnostics.gate(reason, details + ("runtime_id" to influxDiagnosticRuntimeId))
    }

    private fun postInfluxRetrySchedule(
        submittedGeneration: Long,
        requestRevision: Long
    ) {
        if (!isCurrentInfluxRequest(submittedGeneration, requestRevision)) {
            recordInfluxGate(
                "stale_generation",
                influxDiagnosticStateDetails(submittedGeneration, queued = influxRequestQueued.get())
            )
            return
        }
        val delayMs = runCatching { influxCoordinator.retryDelayMs() }.getOrNull()
        mainHandler.post {
            val applied = synchronized(influxQueueLock) {
                when (
                    influxFollowUpAction(
                        currentRequest = submittedGeneration == influxWorkGeneration.get() &&
                            requestRevision == influxRequestRevision,
                        retryDelayMs = delayMs,
                        followUpDemand = influxCycleDemand.pending
                    )
                ) {
                    InfluxFollowUpAction.IGNORE_STALE -> false
                    InfluxFollowUpAction.SCHEDULE_RETRY -> {
                        scheduleInfluxRetry(delayMs)
                        true
                    }
                    InfluxFollowUpAction.REQUEST_CYCLE -> {
                        requestInfluxCycle()
                        true
                    }
                    InfluxFollowUpAction.IDLE -> {
                        scheduleInfluxRetry(null)
                        true
                    }
                }
            }
            if (!applied) {
                recordInfluxGate(
                    "stale_generation",
                    influxDiagnosticStateDetails(submittedGeneration, queued = influxRequestQueued.get())
                )
                return@post
            }
            stopIfNoActiveRuntime()
        }
    }

    private fun isCurrentInfluxRequest(generation: Long, revision: Long): Boolean {
        return synchronized(influxQueueLock) {
            generation == influxWorkGeneration.get() && revision == influxRequestRevision
        }
    }

    private fun scheduleInfluxRetry(delayMs: Long?) {
        if (delayMs == null || !running.get() || !settings.isInfluxEnabled() || maintenanceBlocksRuntimeStart()) {
            cancelInfluxRetry("no_deadline_or_gate")
            return
        }
        val targetElapsedMs = SystemClock.elapsedRealtime() + delayMs
        if (influxRetryScheduled && influxRetryAtElapsedMs?.let { it <= targetElapsedMs } == true) return
        if (influxRetryScheduled) mainHandler.removeCallbacks(influxRetryTask)
        influxRetryScheduled = true
        influxRetryAtElapsedMs = targetElapsedMs
        recordInfluxDiagnostic(
            com.bydcollector.collector.influx.InfluxDiagnosticEvent(
                "influx_retry_scheduled",
                mapOf(
                    "retry_deadline_elapsed_ms" to targetElapsedMs.toString(),
                    "delay_ms" to delayMs.toString(),
                    "generation" to influxWorkGeneration.get().toString(),
                    "reason" to "pending_or_backoff"
                )
            )
        )
        mainHandler.postDelayed(influxRetryTask, delayMs)
    }

    private fun cancelInfluxRetry(reason: String = "cancelled") {
        val wasScheduled = influxRetryScheduled
        mainHandler.removeCallbacks(influxRetryTask)
        influxRetryScheduled = false
        influxRetryAtElapsedMs = null
        if (wasScheduled) {
            recordInfluxDiagnostic(
                com.bydcollector.collector.influx.InfluxDiagnosticEvent(
                    "influx_retry_cancelled",
                    mapOf(
                        "generation" to influxWorkGeneration.get().toString(),
                        "reason" to reason,
                        "retry_deadline_elapsed_ms" to "none",
                        "retry_deadline" to "none"
                    )
                )
            )
        }
    }

    private fun stopDebug(reason: String) {
        if (reason != "service_destroyed" && reason != "debug_database_maintenance" &&
            (!settings.isDebugPollingEnabled() || settings.isDebugManuallyStopped() || reason == "user_shutdown")
        ) {
            DirectStreamController.setDesired(CollectorHelperProtocol.STREAM_SECONDARY, false)
        }
        val stopGeneration = debugWorkGeneration.incrementAndGet()
        debugStartQueued.set(false)
        setDebugRuntime(DebugRuntimeStatus.STOPPING, generation = stopGeneration)
        val detached = detachDebugPoller()
        if (reason == "service_destroyed" && detached != null) {
            debugPollerShutdownInProgress.set(true)
            if (!detached.isRunning()) debugPollerShutdownInProgress.set(false)
        }
        detached?.shutdown(reason)
        if (!settings.isDebugPollingEnabled() || settings.isDebugManuallyStopped()) {
            setDebugRuntime(DebugRuntimeStatus.STOPPED)
        }
    }

    private fun setMainRuntime(status: RuntimeActionStatus) {
        mainRuntimeStatus = status
        mainRuntimeStatusRef.set(status)
        publishDashboardRuntimeFlags()
    }

    private fun setMqttRuntime(status: RuntimeActionStatus) {
        mqttRuntimeStatus = status
        mqttRuntimeStatusRef.set(status)
        publishDashboardRuntimeFlags()
    }

    private fun setInfluxRuntime(status: RuntimeActionStatus) {
        influxRuntimeStatus = status
        influxRuntimeStatusRef.set(status)
        publishDashboardRuntimeFlags()
    }

    private fun setDebugRuntime(
        status: DebugRuntimeStatus,
        error: String? = null,
        generation: Long? = null
    ) {
        if (generation != null && generation != debugWorkGeneration.get()) return
        debugRuntimeStatus = status
        debugRuntimeError = error
        debugRuntimeStatusRef.set(status)
        debugRunning.set(status == DebugRuntimeStatus.RUNNING)
        publishDashboardRuntimeFlags()
    }

    private fun isDebugPollerRunning(): Boolean {
        return synchronized(debugPollerLock) { debugPoller?.isRunning() == true }
    }

    private fun detachDebugPoller(): DirectDebugRoundRobinPoller? {
        return synchronized(debugPollerLock) {
            val current = debugPoller
            debugPoller = null
            current
        }
    }

    private data class RuntimeSnapshot(
        val mainEnabled: Boolean,
        val debugEnabled: Boolean,
        val mqttEnabled: Boolean,
        val influxEnabled: Boolean,
        val telegramEnabled: Boolean,
        val debugRunning: Boolean
    )

    private data class LocalizedVehicleKpis(
        val uk: VehicleKpis,
        val en: VehicleKpis
    )

    private fun runtimeSnapshot(): RuntimeSnapshot {
        return RuntimeSnapshot(
            mainEnabled = settings.isPollingEnabled(),
            debugEnabled = settings.isDebugPollingEnabled(),
            mqttEnabled = settings.isMqttEnabled(),
            influxEnabled = settings.isInfluxEnabled(),
            telegramEnabled = settings.isTelegramEnabled(),
            debugRunning = isDebugPollerRunning()
        )
    }

    private fun onDebugPollerStopped() {
        debugPollerShutdownInProgress.set(false)
        closeDebugStoreAfterLocalWorkers()
    }

    private fun closeDebugStoreAfterDebugStartExecutorStops() {
        CompletableFuture.runAsync {
            val terminated = try {
                debugStartExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (terminated) {
                debugStartInProgress.set(false)
                closeDebugStoreAfterLocalWorkers()
            }
        }
    }

    private fun closeDebugStoreAfterLocalWorkers() {
        if (!debugStoreCloseRequested.get() || debugStartInProgress.get() ||
            debugPollerShutdownInProgress.get() || isDebugPollerRunning()
        ) return
        if (debugStoreCloseRequested.compareAndSet(true, false) && ::debugStore.isInitialized) {
            debugStore.close()
        }
    }

    private fun publishDashboardRuntimeFlags() {
        if (!::dashboardUiStateStore.isInitialized || !::settings.isInitialized) return
        if (::mqttCoordinator.isInitialized) mqttConnection.publishRoute(mqttCoordinator.activeRoute)
        if (::influxCoordinator.isInitialized) influxConnection.publishRoute(influxCoordinator.activeRoute)
        val access = AdbAuthorizationManager.currentSnapshot()
        dashboardUiStateStore.publishRuntimeFlags(
            DashboardRuntimeFlags(
                serviceRunning = running.get(),
                mainPollingRunning = mainPollingRunning.get(),
                mainRuntimeStatus = mainRuntimeStatus,
                debugPollingRunning = debugRunning.get(),
                debugRuntimeStatus = debugRuntimeStatus,
                debugRuntimeError = debugRuntimeError,
                pollingEnabled = settings.isPollingEnabled(),
                debugPollingEnabled = settings.isDebugPollingEnabled(),
                mqttEnabled = settings.isMqttEnabled(),
                mqttRuntimeStatus = mqttRuntimeStatus,
                influxEnabled = settings.isInfluxEnabled(),
                influxRuntimeStatus = influxRuntimeStatus,
                permissionsGranted = access.permissionsGranted,
                adbAuthorized = access.adbAuthorized,
                dbMaintenanceStatus = settings.dbMaintenanceStatus(),
                archiveStorageJobStatus = settings.archiveStorageJobStatus()
            )
        )
    }

    private fun queueDashboardVehicleKpis(kpis: LocalizedVehicleKpis) {
        mainHandler.post {
            if (!running.get() || !mainPollingRunning.get()) return@post
            val nowMs = SystemClock.elapsedRealtime()
            lastKpiObservationAtMs = nowMs
            mainHandler.removeCallbacks(kpiStaleTask)
            mainHandler.postDelayed(kpiStaleTask, KPI_STALE_AFTER_MS)
            if (lastKpiPublishAtMs == Long.MIN_VALUE || nowMs - lastKpiPublishAtMs >= KPI_PUBLISH_INTERVAL_MS) {
                pendingVehicleKpis = null
                mainHandler.removeCallbacks(kpiPublishTask)
                kpiPublishScheduled = false
                publishVehicleKpisNow(kpis)
            } else {
                pendingVehicleKpis = kpis
                if (!kpiPublishScheduled) {
                    kpiPublishScheduled = true
                    mainHandler.postDelayed(
                        kpiPublishTask,
                        (KPI_PUBLISH_INTERVAL_MS - (nowMs - lastKpiPublishAtMs)).coerceAtLeast(0L)
                    )
                }
            }
        }
    }

    private fun publishVehicleKpisNow(kpis: LocalizedVehicleKpis) {
        lastKpiPublishAtMs = SystemClock.elapsedRealtime()
        dashboardUiStateStore.publishVehicleKpis(kpis.uk, kpis.en)
    }

    private fun clearDashboardVehicleKpis() {
        if (!::dashboardUiStateStore.isInitialized) return
        mainHandler.removeCallbacks(kpiPublishTask)
        mainHandler.removeCallbacks(kpiStaleTask)
        kpiPublishScheduled = false
        pendingVehicleKpis = null
        lastKpiPublishAtMs = Long.MIN_VALUE
        lastKpiObservationAtMs = Long.MIN_VALUE
        dashboardUiStateStore.clearVehicleKpis()
    }

    private fun scheduleDashboardCountBootstrap(force: Boolean) {
        val countGeneration = dashboardUiStateStore.beginCountBootstrap(force) ?: return
        val generation = dashboardMetricsGeneration.get()
        try {
            dashboardCountExecutor.execute {
                runCatching {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    (applicationContext as BydCollectorApplication).withDatabaseRead {
                        if (generation != dashboardMetricsGeneration.get()) return@withDatabaseRead null
                        val main = store.dashboardRowCounts()
                        val debug = if (debugStorageReady) {
                            debugStore.dashboardReadingCount()
                        } else {
                            0L
                        }
                        DashboardRowCounts(
                            pollCount = main.pollCount,
                            valueRowCount = main.valueRowCount,
                            ecRowCount = main.ecRowCount,
                            normalizedCurrentCount = main.normalizedCurrentCount,
                            normalizedHistoryCount = main.normalizedHistoryCount,
                            debugReadingCount = debug
                        )
                    }
                }.onSuccess { counts ->
                    if (counts != null && generation == dashboardMetricsGeneration.get()) {
                        dashboardUiStateStore.publishRowCountBaseline(countGeneration, counts)
                    }
                }.onFailure { error ->
                    if (generation == dashboardMetricsGeneration.get()) {
                        dashboardUiStateStore.failCountBootstrap(countGeneration)
                        Log.w(TAG, "Dashboard row-count bootstrap failed", error)
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            dashboardUiStateStore.failCountBootstrap(countGeneration)
            Log.w(TAG, "Dashboard row-count bootstrap rejected", error)
        }
    }

    private fun scheduleDatabaseFootprintRefresh(force: Boolean) {
        if (!::dashboardUiStateStore.isInitialized) return
        val nowMs = SystemClock.elapsedRealtime()
        if (!force && nowMs - lastDatabaseFootprintAtMs < DATABASE_FOOTPRINT_INTERVAL_MS) return
        if (!databaseFootprintQueued.compareAndSet(false, true)) return
        val generation = dashboardMetricsGeneration.get()
        try {
            dashboardMetricsExecutor.execute {
                try {
                    val mainBytes = sqliteFootprintBytes(applicationContext.getDatabasePath(com.bydcollector.collector.data.local.TelemetryDatabaseHelper.DATABASE_NAME))
                    val debugBytes = sqliteFootprintBytes(DirectDebugDatabaseResolver.databaseFile(applicationContext))
                    val tripsBytes = sqliteFootprintBytes(applicationContext.getDatabasePath(com.bydcollector.collector.data.trips.TripDatabaseHelper.DATABASE_NAME))
                    if (generation == dashboardMetricsGeneration.get()) {
                        dashboardUiStateStore.publishDatabaseFootprints(mainBytes, debugBytes, tripsBytes)
                        lastDatabaseFootprintAtMs = SystemClock.elapsedRealtime()
                    }
                } finally {
                    databaseFootprintQueued.set(false)
                }
            }
        } catch (error: RejectedExecutionException) {
            databaseFootprintQueued.set(false)
            Log.w(TAG, "Dashboard database-footprint refresh rejected", error)
        }
    }

    private fun scheduleIntegrationDashboardRefresh() {
        if (!::dashboardStateProvider.isInitialized) return
        if (!integrationDashboardRefreshQueued.compareAndSet(false, true)) {
            integrationDashboardRefreshPending.set(true)
            return
        }
        val generation = dashboardMetricsGeneration.get()
        try {
            dashboardMetricsExecutor.execute {
                try {
                    if (generation != dashboardMetricsGeneration.get()) return@execute
                    dashboardStateProvider.invalidateIntegrationRuntime()
                    val previous = dashboardUiStateStore.currentTab(AppTab.HA)
                        ?: dashboardUiStateStore.currentChrome()
                    val state = dashboardStateProvider.load(
                        profile = com.bydcollector.collector.ui.DashboardLoadProfile.HA,
                        previous = previous
                    )
                    if (generation == dashboardMetricsGeneration.get()) {
                        dashboardUiStateStore.publishIntegrationRuntime(state)
                    }
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Dashboard integration refresh failed", error)
                } finally {
                    integrationDashboardRefreshQueued.set(false)
                    if (integrationDashboardRefreshPending.getAndSet(false)) {
                        scheduleIntegrationDashboardRefresh()
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            integrationDashboardRefreshQueued.set(false)
            integrationDashboardRefreshPending.set(false)
            Log.w(TAG, "Dashboard integration refresh rejected", error)
        }
    }

    private fun requestAccessSelfCheck(source: String) {
        AdbAuthorizationManager.request(
            context = applicationContext,
            store = store,
            source = source,
            mode = AccessCheckMode.NORMAL,
            helperOwnerMode = settings.mainHelperOwnerMode()
        )
    }

    private fun reconcileAccessSelfCheckSchedule() {
        if (!settings.hasActiveAccessWork()) {
            mainHandler.removeCallbacks(accessSelfCheckTask)
            accessSelfCheckScheduled = false
            return
        }
        scheduleAccessSelfCheck()
    }

    private fun scheduleAccessSelfCheck() {
        if (accessSelfCheckScheduled) return
        accessSelfCheckScheduled = true
        mainHandler.postDelayed(accessSelfCheckTask, ACCESS_SELF_CHECK_INTERVAL_MS)
    }

    private data class DetachedMaintenanceRuntime(
        val mainPoller: TelemetryPoller? = null,
        val debugPoller: DirectDebugRoundRobinPoller? = null,
        val openedSessionId: Long? = null,
        val secondaryWasRunning: Boolean = false
    )

    private fun stopRuntimeForMaintenance(operation: DbMaintenanceOperation) {
        check(!isRuntimeOwner()) { "Database maintenance must not wait for workers on the main handler" }
        val detached = runOnRuntimeOwnerBlocking {
            prepareRuntimeStopForMaintenance(operation)
        }
        if (detached.mainPoller?.stopAndJoin(2_000L) == false) {
            maintenanceRuntimeRestoreAllowed.set(false)
            error("Main poller did not stop for database maintenance")
        }
        val debugStopReason = if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE) {
            "debug_database_maintenance"
        } else {
            "database_maintenance"
        }
        if (detached.debugPoller?.shutdownAndAwait(debugStopReason, 2_000L) == false) {
            maintenanceRuntimeRestoreAllowed.set(false)
            error("Debug poller did not stop for database maintenance")
        }
        if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE) {
            prepareSecondaryArchive(detached.secondaryWasRunning)
            return
        }

        if (!tripRuntime.pauseAndAwait("database_maintenance")) {
            maintenanceRuntimeRestoreAllowed.set(false)
            error("Trip runtime did not pause for database maintenance")
        }

        detached.openedSessionId?.let { openedSessionId ->
            runCatching { store.endSession(openedSessionId, "database_maintenance") }
        }
        resetMqttExecutorForMaintenance()
        resetInfluxExecutorForMaintenance()
        resetTelegramExecutorForMaintenance()
        resetKeepAliveSupervisorForMaintenance()
    }

    private fun resetKeepAliveSupervisorForMaintenance() {
        val previous = keepAliveSupervisor
        if (!previous.shutdownAndAwait(2_000L)) {
            maintenanceRuntimeRestoreAllowed.set(false)
            error("Keep-alive worker did not stop before database maintenance")
        }
        runOnRuntimeOwnerBlocking {
            requireRuntimeOwner()
            if (keepAliveSupervisor !== previous) {
                maintenanceRuntimeRestoreAllowed.set(false)
                error("Keep-alive supervisor changed during database maintenance")
            }
            keepAliveSupervisor = KeepAliveSupervisor(applicationContext, store)
        }
    }

    private fun prepareRuntimeStopForMaintenance(operation: DbMaintenanceOperation): DetachedMaintenanceRuntime {
        requireRuntimeOwner()
        if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE) {
            val stopGeneration = debugWorkGeneration.incrementAndGet()
            val detachedDebugPoller = detachDebugPoller()
            setDebugRuntime(DebugRuntimeStatus.STOPPING, generation = stopGeneration)
            setDebugRuntime(DebugRuntimeStatus.STOPPED)
            return DetachedMaintenanceRuntime(
                debugPoller = detachedDebugPoller,
                secondaryWasRunning = detachedDebugPoller?.isRunning() == true
            )
        }

        cancelMqttRetry()
        cancelInfluxRetry("maintenance")
        cancelTelegramTick()
        if (mainRuntimeStatus != RuntimeActionStatus.STOPPED) setMainRuntime(RuntimeActionStatus.STOPPING)
        poller.stop()
        val detachedDebugPoller = detachDebugPoller()
        val openedSessionId = sessionId
        sessionId = null
        mainPollingRunning.set(false)
        setMainRuntime(RuntimeActionStatus.STOPPED)
        val stopGeneration = debugWorkGeneration.incrementAndGet()
        setDebugRuntime(DebugRuntimeStatus.STOPPING, generation = stopGeneration)
        setDebugRuntime(DebugRuntimeStatus.STOPPED)
        mqttRuntimeActive.set(false)
        publishDashboardRuntimeFlags()
        return DetachedMaintenanceRuntime(
            mainPoller = poller,
            debugPoller = detachedDebugPoller,
            openedSessionId = openedSessionId
        )
    }

    private fun prepareSecondaryArchive(wasRunning: Boolean) {
        val helper = DirectVehicleHelperClient()
        if (!wasRunning) {
            check(!settings.isDebugPollingEnabled() || settings.isDebugManuallyStopped()) {
                "Secondary archive requires the enabled collector to be running; restart it before archiving"
            }
            check(DirectStreamController.setDesired(CollectorHelperProtocol.STREAM_SECONDARY, false)) {
                "Secondary archive could not quiesce the stopped helper stream"
            }
            val backlog = helper.secondarySpoolStatus()
            check(backlog.ok) {
                "Secondary archive cannot prove the stopped backlog is empty: ${backlog.error ?: backlog.status}"
            }
            check(!backlog.pending) {
                "Secondary archive blocked by retained backlog; Start secondary collection to drain it first"
            }
            return
        }

        check(DirectStreamController.pauseAndFence(CollectorHelperProtocol.STREAM_SECONDARY)) {
            "Secondary archive pause/fence failed"
        }
        secondaryArchiveFenced.set(true)
        val parameters = DirectDebugParameterAsset.load(applicationContext)
        val archiveSessionId = debugStore.openSession(parameters, DirectDebugParameterAsset.TOTAL_PARAMETER_COUNT)
        try {
            val replay = SecondaryReplayCoordinator(
                fetchPage = helper::secondarySpoolPage,
                acknowledge = helper::acknowledgeSecondarySpool,
                quarantine = helper::quarantineSecondarySpool,
                importRecord = { record, digest ->
                    debugStore.importSecondaryRecord(archiveSessionId, record, digest)
                }
            ).drain()
            check(replay.drained) { replay.blockedReason ?: "Secondary archive replay did not drain" }
        } finally {
            debugStore.endSession(archiveSessionId, "debug_database_maintenance")
        }
    }

    private fun restoreRuntimeAfterMaintenance(operation: DbMaintenanceOperation, snapshot: RuntimeSnapshot) {
        requireRuntimeOwner()
        restoringRuntime.set(true)
        try {
            if (settings.isUserShutdownRequested()) {
                settings.setPollingEnabled(false)
                settings.setDebugPollingEnabled(false)
                settings.setMqttEnabled(false)
                settings.setInfluxEnabled(false)
                stopRuntimeForUserShutdown()
                finishUserShutdown()
                return
            }
            if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE) {
                if (
                    snapshot.debugRunning &&
                    settings.isDebugPollingEnabled() &&
                    !settings.isDebugManuallyStopped()
                ) {
                    startDebugIfNeeded(DEBUG_REASON_AUTOSTART)
                }
                return
            }
            settings.setPollingEnabled(snapshot.mainEnabled)
            settings.setDebugPollingEnabled(snapshot.debugEnabled)
            settings.setMqttEnabled(snapshot.mqttEnabled)
            settings.setInfluxEnabled(snapshot.influxEnabled)
            settings.setTelegramEnabled(snapshot.telegramEnabled)
            val collectionRuntimeRestored = snapshot.mainEnabled || snapshot.debugEnabled ||
                settings.keepAliveConfig().anyEnabled || snapshot.telegramEnabled
            if (collectionRuntimeRestored) {
                reconcileCollection()
            }
            if (snapshot.mqttEnabled && !settings.isMqttManuallyStopped()) startMqttExport(clearManualStop = false)
            if (
                snapshot.influxEnabled &&
                !settings.isInfluxManuallyStopped() &&
                !collectionRuntimeRestored
            ) startInfluxExport(clearManualStop = false)
            if (!snapshot.mqttEnabled && !snapshot.influxEnabled) stopIfNoActiveRuntime()
        } finally {
            restoringRuntime.set(false)
        }
    }

    private fun rebuildStoreBackedRuntime(newStore: TelemetryStore) {
        requireRuntimeOwner()
        dashboardMetricsGeneration.incrementAndGet()
        dashboardUiStateStore.invalidateRowCounts()
        store = newStore
        settings = CollectorSettings(applicationContext, store)
        dashboardStateProvider = DashboardStateProvider(applicationContext, { store }, settings)
        keepAliveSupervisor.shutdown()
        keepAliveSupervisor = KeepAliveSupervisor(applicationContext, store)
        mqttCoordinator = createMqttCoordinator(processMqttClientFacade)
        influxCoordinator = createInfluxCoordinator()
        telegramCoordinator = createTelegramCoordinator()
        poller = createTelemetryPoller()
        lastDatabaseFootprintAtMs = Long.MIN_VALUE
        scheduleDashboardCountBootstrap(force = true)
        scheduleDatabaseFootprintRefresh(force = true)
        scheduleIntegrationDashboardRefresh()
    }

    private fun rebindDebugStoreAfterMaintenance(newStore: DirectDebugStore, storageReady: Boolean) {
        requireRuntimeOwner()
        debugStore = newStore
        debugStorageReady = storageReady
        (applicationContext as BydCollectorApplication).setDebugStorageReadyAfterMaintenance(debugStorageReady)
        dashboardMetricsGeneration.incrementAndGet()
        dashboardUiStateStore.invalidateRowCounts()
        lastDatabaseFootprintAtMs = Long.MIN_VALUE
        scheduleDashboardCountBootstrap(force = true)
        scheduleDatabaseFootprintRefresh(force = true)
    }

    private fun acquireWakeLock() {
        requireRuntimeOwner()
        val current = wakeLock
        if (current?.isHeld == true) return

        //uses a partial wake lock because dilink may keep the tablet alive while still idling app threads
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "bydcollector:collection"
        )
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire()
        store.recordEvent("wake_lock_acquired", "Collector partial wake lock acquired")
    }

    private fun releaseWakeLock() {
        requireRuntimeOwner()
        val current = wakeLock
        if (current?.isHeld == true) {
            current.release()
            store.recordEvent("wake_lock_released", "Collector partial wake lock released")
        }
        wakeLock = null
    }

    private fun handlePollCycleResult(result: com.bydcollector.collector.data.polling.PollCycleResult) {
        lastTelegramPollError = if (result.ok) null else result.category
        dashboardUiStateStore.incrementMainRowCounts(
            pollRows = result.pollRowsPersisted,
            valueRows = result.valueRowsPersisted
        )
        val completedAt = DisplayTimeFormatter.formatNullable(result.timestamp ?: java.time.Instant.now().toString())
        val errorSummary = if (result.ok) null else PollingErrorSummaries.summary(result.category, result.errorMessage)
        dashboardUiStateStore.publishMainPollState(
            DashboardMainPollState(
                activeSessionId = sessionId,
                lastSuccessAt = if (result.ok) completedAt else dashboardUiStateStore.currentChrome()?.lastSuccessAt,
                lastError = errorSummary,
                lastErrorAt = if (result.ok) null else completedAt,
                lastPollStatus = if (result.ok) {
                    completedAt?.let { "ok at $it" } ?: "ok"
                } else {
                    completedAt?.let { "Polling error: $errorSummary at $it" } ?: "Polling error: $errorSummary"
                },
                elapsedMs = result.elapsedMs,
                requestCount = result.requestCount
            )
        )
        if (!result.ok) clearDashboardVehicleKpis()
        if (result.pollRowsPersisted > 0L) scheduleDatabaseFootprintRefresh(force = false)
        val text = if (result.ok) {
            notificationText(
                mainEnabled = true,
                debugEnabled = isDebugPollerRunning(),
                keepAliveEnabled = settings.keepAliveConfig().anyEnabled,
                telegramEnabled = settings.isTelegramEnabled()
            )
        } else {
            "Polling error: ${PollingErrorSummaries.summary(result.category)}"
        }
        updateNotification(text)
        publishDashboardRuntimeFlags()
        publishStatusHeartbeat(result, force = !result.ok)
    }

    private fun publishChangedCategoriesAsync(categories: Set<String>) {
        if (categories.isEmpty()) return
        if (!settings.isMqttEnabled()) return
        mqttRuntimeActive.set(true)
        //queues latest state for mqtt so transient broker outages do not lose the newest ha value
        executeMqtt("mqtt_changed_publish_error") {
            mqttCoordinator.queueChangedCategoriesAndFlush(categories)
        }
    }

    private fun startMqttExport(clearManualStop: Boolean = true) {
        if (mqttConnection.stopping || mqttOfflineQueued.get()) return
        if (maintenanceBlocksRuntimeStart() ||
            (clearManualStop && (!settings.isMqttEnabled() || settings.isMqttManuallyStopped()))) {
            if (!mqttRuntimeActive.get()) mqttConnection.release()
            return
        }
        mqttConnection.reserve()
        mqttWorkGeneration.incrementAndGet()
        if (clearManualStop) settings.setMqttManuallyStopped(false)
        settings.setMqttEnabled(true)
        setMqttRuntime(RuntimeActionStatus.STARTING)
        publishDashboardRuntimeFlags()
        ensureForegroundForChannel("MQTT export running")
        mqttRuntimeActive.set(true)
        executeMqtt("mqtt_start_error") {
            mqttCoordinator.startLiveExport()
        }
    }

    private fun stopMqttExport(manualStop: Boolean = true) {
        if (manualStop && settings.isMqttEnabled() && !settings.isMqttManuallyStopped()) return
        mqttConnection.beginStop()
        mqttWorkGeneration.incrementAndGet()
        setMqttRuntime(RuntimeActionStatus.STOPPING)
        if (manualStop) settings.setMqttManuallyStopped(true)
        cancelMqttRetry()
        settings.setMqttEnabled(false)
        publishDashboardRuntimeFlags()
        scheduleIntegrationDashboardRefresh()
        disconnectOfflineAsync()
        mqttRuntimeActive.set(false)
        if (!mqttOfflineQueued.get()) {
            if (mqttRuntimeStatus == RuntimeActionStatus.STOPPING) {
                mqttConnection.release()
                setMqttRuntime(RuntimeActionStatus.STOPPED)
            }
            stopIfNoActiveRuntime()
        }
    }

    private fun startInfluxExport(clearManualStop: Boolean = true) {
        if (influxConnection.stopping) return
        if (maintenanceBlocksRuntimeStart() ||
            (clearManualStop && (!settings.isInfluxEnabled() || settings.isInfluxManuallyStopped()))) {
            if (influxWorkInFlight.get() == 0) influxConnection.release()
            return
        }
        influxConnection.reserve()
        settings.setInfluxEnabled(true)
        if (!clearManualStop) {
            val workActive = influxRequestQueued.get() || influxWorkInFlight.get() > 0
            when (
                influxRecoveryAction(
                    sessionInitialized = influxCoordinator.sessionFrozen,
                    workActive = workActive,
                    retryScheduled = influxRetryScheduled
                )
            ) {
                InfluxRecoveryAction.PRESERVE -> return
                InfluxRecoveryAction.REQUEST_CYCLE -> {
                    requestInfluxCycle()
                    return
                }
                InfluxRecoveryAction.INITIALIZE -> Unit
            }
        }
        advanceInfluxGeneration()
        if (clearManualStop) settings.setInfluxManuallyStopped(false)
        setInfluxRuntime(RuntimeActionStatus.STARTING)
        publishDashboardRuntimeFlags()
        ensureForegroundForChannel("Influx export running")
        val submittedGeneration = influxWorkGeneration.get()
        val requestRevision = synchronized(influxQueueLock) {
            influxRequestRevision += 1
            influxRequestRevision
        }
        val coordinator = influxCoordinator
        val isCurrentRequest = {
            submittedGeneration == influxWorkGeneration.get() && influxCoordinator === coordinator
        }
        val represented = AtomicBoolean(false)
        val accepted = executeInflux(
            errorCategory = "influx_start_error",
            expectedGeneration = submittedGeneration,
            afterSettled = {
                if (represented.get()) {
                    postInfluxRetrySchedule(submittedGeneration, requestRevision)
                }
            }
        ) {
            represented.set(true)
            if (clearManualStop) {
                coordinator.startExport(isCurrent = isCurrentRequest)
            } else {
                coordinator.resumeExport(isCurrent = isCurrentRequest)
            }
        }
        if (!accepted) stopIfNoActiveRuntime()
    }

    private fun maybeActivateTailscaleAfterHaFailure(channel: String) {
        val endpoint = when (channel) {
            "mqtt" -> settings.mqttConfig().let { HaEndpoint(channel, it.host, it.port) }
            "influx" -> settings.influxConfig().let { HaEndpoint(channel, it.host, it.port) }
            else -> return
        }
        val decision = tailscaleGate.maybeActivate(endpoint)
        if (decision.category == "tailscale_activation_disabled") return
        store.recordEvent(
            category = decision.category,
            message = decision.message,
            detail = "channel=$channel host=${endpoint.host} port=${endpoint.port}"
        )
    }

    private fun scheduleTailscaleActivation(): com.bydcollector.collector.ha.TailscaleActivationResult {
        if (!tailscaleSequenceActive.compareAndSet(false, true)) {
            return com.bydcollector.collector.ha.TailscaleActivationResult(false, "tailscale_sequence_already_running")
        }
        return try {
            tailscaleExecutor.execute {
                try {
                    TailscaleActivator.runDelayedSequence(
                        isEnabled = { settings.isTailscaleActivationEnabled() },
                        sleeper = { Thread.sleep(it) },
                        launch = { TailscaleActivator.reactivate(applicationContext) },
                        restoreForeground = { target ->
                            TailscaleActivator.restoreForeground(applicationContext, target)
                        },
                        onEvent = { category, message ->
                            runCatching { store.recordEvent(category, message) }
                        }
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    tailscaleSequenceActive.set(false)
                }
            }
            com.bydcollector.collector.ha.TailscaleActivationResult(true, "tailscale_activation_scheduled")
        } catch (error: RejectedExecutionException) {
            tailscaleSequenceActive.set(false)
            com.bydcollector.collector.ha.TailscaleActivationResult(
                false,
                "tailscale_sequence_rejected: ${error.message ?: "no message"}"
            )
        }
    }

    private fun ensureForegroundForChannel(text: String) {
        lastNotificationText = text
        startForeground(NOTIFICATION_ID, buildNotification(text))
        acquireWakeLock()
    }

    private fun stopInfluxExport(manualStop: Boolean = true) {
        if (manualStop && settings.isInfluxEnabled() && !settings.isInfluxManuallyStopped()) return
        recordInfluxGate("stopped")
        influxConnection.beginStop()
        advanceInfluxGeneration()
        setInfluxRuntime(RuntimeActionStatus.STOPPING)
        if (manualStop) settings.setInfluxManuallyStopped(true)
        settings.setInfluxEnabled(false)
        publishDashboardRuntimeFlags()
        scheduleIntegrationDashboardRefresh()
        queueInfluxStop(stopServiceWhenIdle = true)
    }

    private fun queueInfluxStop(stopServiceWhenIdle: Boolean = false) {
        cancelInfluxRetry()
        val accepted = executeInflux(
            errorCategory = "influx_stop_error",
            activateTailscaleOnFailure = false,
            isStop = true,
            afterComplete = {
                if (stopServiceWhenIdle) mainHandler.post {
                    stopIfNoActiveRuntime()
                }
            }
        ) {
            try {
                influxCoordinator.stopExport()
            } finally {
                mainHandler.post {
                    if (!settings.isInfluxEnabled()) influxConnection.release()
                    publishDashboardRuntimeFlags()
                }
            }
        }
        if (!accepted) influxConnection.stopSubmissionFailed()
    }

    private fun reconcileTelegramRuntime(unblockBlocked: Boolean = false) {
        if (maintenanceBlocksRuntimeStart()) return
        if (!settings.isTelegramEnabled()) {
            cancelTelegramTick()
            telegramRecoveryCoalescer.invalidate()
            telegramCoordinator?.let { coordinator ->
                executeTelegram("telegram_reset_error") {
                    coordinator.integrationDisabled()
                }
            }
            stopIfNoActiveRuntime()
            return
        }
        val coordinator = telegramCoordinator
        if (coordinator == null) {
            cancelTelegramTick()
            settings.setTelegramConnectionStatus(
                "storage_error",
                BydCollectorApplication.TELEGRAM_STORAGE_ERROR
            )
            // Reuse the existing owner/watchdog events; SQLite repair never runs on the UI thread.
            ensureForegroundForChannel("Recovering Telegram storage")
            CollectorAutoStart.scheduleWatchdog(applicationContext, settings, store)
            if (!telegramStorageRecoveryInFlight.compareAndSet(false, true)) return
            executeTelegram(
                errorCategory = "telegram_storage_recovery_error",
                onSuccess = { recovered, generation ->
                    mainHandler.post {
                        if (!telegramRecoveryRuntimeAvailable(generation)) return@post
                        if (recovered != null && telegramCoordinator == null) {
                            telegramCoordinator = recovered
                            reconcileTelegramRuntime(unblockBlocked)
                        }
                        scheduleIntegrationDashboardRefresh()
                    }
                },
                onSettled = { telegramStorageRecoveryInFlight.set(false) }
            ) {
                if (!settings.isTelegramEnabled() || settings.isUserShutdownRequested() ||
                    telegramDeliveryRuntime.hasInFlightDelivery) null
                else (applicationContext as BydCollectorApplication).reconcileTelegramStorage(store)
                    ?.let { createTelegramCoordinator() }
            }
            return
        }
        ensureForegroundForChannel("Telegram notifications enabled")
        requestTelegramRecovery(
            if (unblockBlocked) RECOVERY_STARTUP_CREDENTIALS else RECOVERY_STARTUP
        )
        scheduleTelegramTick()
        CollectorAutoStart.scheduleWatchdog(applicationContext, settings, store)
    }

    private fun testTelegramConnection() {
        val coordinator = telegramCoordinator
        if (coordinator == null) {
            settings.setTelegramConnectionStatus(
                "storage_error",
                BydCollectorApplication.TELEGRAM_STORAGE_ERROR
            )
            mainHandler.post { stopIfNoActiveRuntime() }
            return
        }
        ensureForegroundForChannel("Testing Telegram connection")
        executeTelegram(
            errorCategory = "telegram_test_error",
            onFailedAction = {
                settings.setTelegramConnectionStatus("failed", "telegram_test_error")
                mainHandler.post { stopIfNoActiveRuntime() }
            },
        ) {
            coordinator.testConnection { result ->
                if (result == TelegramSendResult.Success && settings.isTelegramEnabled()) {
                    requestTelegramRecovery(RECOVERY_MANUAL_TEST_SUCCESS)
                }
                mainHandler.post { stopIfNoActiveRuntime() }
            }
        }
    }

    /** Posts a nonblocking hint; all lifecycle/setting checks happen on the runtime owner. */
    private fun requestTelegramRecovery(
        trigger: String,
        expectedGeneration: Long? = null,
        key: String = trigger,
        replacePendingKey: String? = null
    ) {
        val generation = expectedGeneration ?: telegramWorkGeneration.get()
        if (!running.get() || generation != telegramWorkGeneration.get()) return
        mainHandler.post { enqueueTelegramRecovery(trigger, generation, key, replacePendingKey) }
    }

    private fun enqueueTelegramRecovery(
        trigger: String,
        generation: Long,
        key: String = trigger,
        replacePendingKey: String? = null
    ) {
        if (!telegramRecoveryRuntimeAvailable(generation)) return
        val request = telegramRecoveryCoalescer.request(trigger, key, replacePendingKey) ?: return
        submitTelegramRecovery(request, generation)
    }

    private fun telegramRecoveryRuntimeAvailable(expectedGeneration: Long): Boolean {
        if (
            !running.get() ||
            expectedGeneration != telegramWorkGeneration.get() ||
            !::settings.isInitialized ||
            !settings.isTelegramEnabled() ||
            settings.isUserShutdownRequested() ||
            maintenanceActive.get()
        ) return false
        return !maintenanceBlocksRuntimeStart()
    }

    private fun submitTelegramRecovery(request: TelegramRecoveryRequest, generation: Long) {
        val coordinator = telegramCoordinator
        if (coordinator == null) {
            telegramRecoveryCoalescer.invalidate()
            return
        }
        try {
            executeOrderedTelegram(
                errorCategory = "telegram_recovery_error",
                coordinator = coordinator,
                onSuccess = ::postTelegramTickSchedule,
                onSettled = { completeTelegramRecovery(request.token, generation) }
            ) {
                if (
                    !running.get() ||
                    generation != telegramWorkGeneration.get() ||
                    telegramCoordinator !== coordinator ||
                    !telegramRecoveryCoalescer.isCurrent(request.token) ||
                    !settings.isTelegramEnabled() ||
                    settings.isUserShutdownRequested() ||
                    maintenanceBlocksRuntimeStart()
                ) return@executeOrderedTelegram null
                if (RECOVERY_STARTUP_CREDENTIALS in request.reasons) coordinator.credentialsChanged()
                if (!telegramRecoveryCoalescer.isCurrent(request.token)) return@executeOrderedTelegram null
                coordinator.recoverPending(request.trigger)
            }
        } catch (_: RuntimeException) {
            telegramRecoveryCoalescer.invalidate(request.token)
        }
    }

    private fun completeTelegramRecovery(requestToken: Long, submittedGeneration: Long) {
        if (submittedGeneration != telegramWorkGeneration.get()) {
            telegramRecoveryCoalescer.invalidate(requestToken)
            return
        }
        val next = telegramRecoveryCoalescer.complete(requestToken) ?: return
        val generation = submittedGeneration
        mainHandler.post {
            if (!telegramRecoveryRuntimeAvailable(generation)) {
                telegramRecoveryCoalescer.invalidate(next.token)
                return@post
            }
            submitTelegramRecovery(next, generation)
        }
    }

    private fun registerTelegramNetworkCallback() {
        if (telegramNetworkCallbackRegistered) return
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val generation = telegramWorkGeneration.get()
                if (!running.get()) return
                if (telegramNetworkRecoveryEdge.onAvailable(network.toString())) {
                    postTelegramNetworkRecoveryHint(generation, telegramNetworkRecoveryRevision.incrementAndGet())
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val generation = telegramWorkGeneration.get()
                if (!running.get()) return
                if (telegramNetworkRecoveryEdge.onCapabilitiesChanged(network.toString(), telegramNetworkCapabilities(capabilities))) {
                    postTelegramNetworkRecoveryHint(generation, telegramNetworkRecoveryRevision.incrementAndGet())
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val generation = telegramWorkGeneration.get()
                if (!running.get()) return
                if (telegramNetworkRecoveryEdge.onLinkPropertiesChanged(network.toString(), telegramNetworkLinks(linkProperties))) {
                    postTelegramNetworkRecoveryHint(generation, telegramNetworkRecoveryRevision.incrementAndGet())
                }
            }

            override fun onLost(network: Network) {
                telegramNetworkRecoveryEdge.onLost(network.toString())
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            telegramNetworkManager = manager
            telegramNetworkCallback = callback
            telegramNetworkCallbackRegistered = true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Telegram network callback registration failed", error)
        }
    }

    private fun unregisterTelegramNetworkCallback() {
        val manager = telegramNetworkManager
        val callback = telegramNetworkCallback
        telegramNetworkCallbackRegistered = false
        telegramNetworkManager = null
        telegramNetworkCallback = null
        telegramNetworkRecoveryEdge.reset()
        if (manager != null && callback != null) {
            runCatching { manager.unregisterNetworkCallback(callback) }
                .onFailure { error -> Log.w(TAG, "Telegram network callback unregister failed", error) }
        }
    }

    private fun postTelegramNetworkRecoveryHint(generation: Long, revision: Long) {
        // Check before posting as well as inside the owner callback: teardown and
        // maintenance may advance the generation between those two points.
        if (!running.get() || generation != telegramWorkGeneration.get()) return
        mainHandler.post {
            if (!running.get() || generation != telegramWorkGeneration.get()) return@post
            enqueueTelegramRecovery(
                trigger = RECOVERY_NETWORK,
                generation = generation,
                key = "$RECOVERY_NETWORK:$revision",
                replacePendingKey = RECOVERY_NETWORK
            )
        }
    }

    private fun telegramNetworkCapabilities(capabilities: NetworkCapabilities): TelegramNetworkCapabilitiesFingerprint {
        return TelegramNetworkCapabilitiesFingerprint(
            hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            notRestricted = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
            vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        )
    }

    private fun telegramNetworkLinks(linkProperties: LinkProperties): TelegramNetworkLinkFingerprint {
        return TelegramNetworkLinkFingerprint(
            dnsServers = linkProperties.dnsServers.map { it.hostAddress.orEmpty() }.sorted().toList(),
            routes = linkProperties.routes.map { it.toString() }.sorted().toList(),
            linkAddresses = linkProperties.linkAddresses.map { it.toString() }.sorted().toList(),
            interfaceName = linkProperties.interfaceName,
            domains = linkProperties.domains,
            mtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) linkProperties.mtu else 0
        )
    }

    private fun scheduleTelegramTick(deadlineAtMs: Long? = null) {
        if (
            !running.get() ||
            !settings.isTelegramEnabled() ||
            telegramCoordinator == null ||
            maintenanceBlocksRuntimeStart()
        ) return
        val nowMs = System.currentTimeMillis()
        val regularAtMs = nowMs + TELEGRAM_TICK_INTERVAL_MS
        val targetAtMs = deadlineAtMs?.let { minOf(it, regularAtMs) } ?: regularAtMs
        if (telegramTickScheduled && telegramTickAtMs?.let { it <= targetAtMs } == true) return
        if (telegramTickScheduled) mainHandler.removeCallbacks(telegramTickTask)
        telegramTickScheduled = true
        telegramTickAtMs = targetAtMs
        mainHandler.postDelayed(telegramTickTask, (targetAtMs - nowMs).coerceAtLeast(0L))
    }

    private fun postTelegramTickSchedule(deadlineAtMs: Long?, submittedGeneration: Long) {
        mainHandler.post {
            if (submittedGeneration != telegramWorkGeneration.get()) return@post
            scheduleTelegramTick(deadlineAtMs)
        }
    }

    private fun cancelTelegramTick() {
        mainHandler.removeCallbacks(telegramTickTask)
        telegramTickScheduled = false
        telegramTickAtMs = null
    }

    private fun currentRuntimeLiveness(): RuntimeLiveness {
        return RuntimeLiveness(
            main = poller.isRunning(),
            debug = isDebugPollerRunning(),
            keepAlive = settings.keepAliveConfig().anyEnabled,
            mqtt = mqttRuntimeActive.get() || mqttConnection.owned,
            telegram = settings.isTelegramEnabled(),
            influxQueued = influxRequestQueued.get(),
            influxInFlight = influxWorkInFlight.get() > 0,
            influxRetryScheduled = influxRetryScheduled,
            influxOwned = influxConnection.owned,
            maintenance = maintenanceActive.get(),
            archiveStorage = archiveStorageActiveInProcess.get()
        )
    }

    private fun hasRuntimeOwner(): Boolean {
        return settings.runtimeDemand().any || currentRuntimeLiveness().active
    }

    private fun restoreNotificationAfterKeepAliveStop() {
        if (!hasRuntimeOwner()) return
        updateNotification(
            notificationText(
                mainEnabled = poller.isRunning(),
                debugEnabled = isDebugPollerRunning(),
                keepAliveEnabled = settings.keepAliveConfig().anyEnabled,
                telegramEnabled = settings.isTelegramEnabled()
            )
        )
    }

    private fun stopIfNoActiveRuntime() {
        if (settings.isUserShutdownRequested() || userShutdownFinalizationStarted.get()) return
        val liveness = currentRuntimeLiveness()
        if (liveness.active) return
        if (!settings.runtimeDemand().requiresPersistentOwner) {
            CollectorAutoStart.cancelScheduled(applicationContext)
        }
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun postMqttRetrySchedule(submittedGeneration: Long) {
        if (submittedGeneration != mqttWorkGeneration.get()) return
        val delayMs = runCatching { mqttCoordinator.retryDelayMs() }.getOrNull()
        mainHandler.post {
            if (submittedGeneration != mqttWorkGeneration.get()) return@post
            scheduleMqttRetry(delayMs)
        }
    }

    private fun scheduleMqttRetry(delayMs: Long?) {
        if (delayMs == null || !running.get() || !settings.isMqttEnabled() || maintenanceBlocksRuntimeStart()) {
            cancelMqttRetry()
            return
        }
        val targetElapsedMs = SystemClock.elapsedRealtime() + delayMs
        if (mqttRetryScheduled && mqttRetryAtElapsedMs?.let { it <= targetElapsedMs } == true) return
        if (mqttRetryScheduled) mainHandler.removeCallbacks(mqttRetryTask)
        mqttRetryScheduled = true
        mqttRetryAtElapsedMs = targetElapsedMs
        mainHandler.postDelayed(mqttRetryTask, delayMs)
    }

    private fun cancelMqttRetry() {
        mainHandler.removeCallbacks(mqttRetryTask)
        mqttRetryScheduled = false
        mqttRetryAtElapsedMs = null
    }

    private fun flushPendingMqttAsync(force: Boolean) {
        if (!settings.isMqttEnabled()) return
        mqttRuntimeActive.set(true)
        executeMqtt("mqtt_flush_error") {
            mqttCoordinator.flushPending(force = force)
        }
    }

    private fun publishStatusHeartbeat(
        result: com.bydcollector.collector.data.polling.PollCycleResult,
        force: Boolean
    ) {
        if (!settings.isMqttEnabled()) return
        val now = SystemClock.elapsedRealtime()
        //throttles status chatter while still forcing immediate error visibility
        if (!force && now - lastStatusHeartbeatAtMs < STATUS_HEARTBEAT_INTERVAL_MS) return
        lastStatusHeartbeatAtMs = now
        mqttRuntimeActive.set(true)
        executeMqtt("mqtt_status_publish_error") {
            mqttCoordinator.queueStatusAndFlush(statusHeartbeat(result), force = force)
        }
    }

    private fun statusHeartbeat(result: com.bydcollector.collector.data.polling.PollCycleResult): HaMqttStatus {
        val health = store.healthSnapshot(
            running = poller.isRunning(),
            detail = HealthSnapshotDetail.SUMMARY,
            includeCounts = false
        )
        return HaMqttStatus(
            availability = "online",
            polling = poller.isRunning(),
            collectorStatus = if (result.ok) "polling" else "polling_error",
            adb = if (AdbAuthorizationManager.currentSnapshot().adbAuthorized) "authorized" else "not_authorized",
            helper = DirectBridgeManager.status(),
            lastSuccessAt = health.lastSuccessAt,
            lastError = if (result.ok) health.lastError else health.lastError ?: result.category,
            categories = mqttCategoryStatus()
        )
    }

    private fun mqttCategoryStatus(): Map<String, String> {
        val enabled = settings.mqttConfig().enabledCategories
        return (HaMqttConfig.VISIBLE_CATEGORIES + enabled)
            .sorted()
            .associateWith { category -> if (enabled.contains(category)) "enabled" else "disabled" }
    }

    private fun disconnectOfflineAsync() {
        if (!mqttRuntimeActive.get() && !settings.isMqttEnabled() && !mqttConnection.owned) return
        if (!mqttOfflineQueued.compareAndSet(false, true)) return
        try {
            resetMqttExecutorForOffline { previous ->
                completeMqttOffline(previous)
            }
        } catch (error: RejectedExecutionException) {
            mqttOfflineQueued.set(false)
            mqttConnection.stopSubmissionFailed()
            store.recordEvent(
                "mqtt_offline_publish_error",
                "MQTT offline publish rejected",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
            if (!settings.isMqttEnabled() && mqttRuntimeStatus == RuntimeActionStatus.STOPPING) {
                setMqttRuntime(RuntimeActionStatus.ERROR)
            }
        }
    }

    private fun completeMqttOffline(previous: ExecutorService) {
        var completedOk = false
        try {
            if (!awaitMqttWorkerTermination(previous, MQTT_MAINTENANCE_STOP_TIMEOUT_MS)) {
                store.recordEvent(
                    "mqtt_offline_publish_error",
                    "MQTT previous worker stop timed out; closing its client session"
                )
            }
            runCatching { mqttCoordinator.disconnectOffline() }
                .onSuccess { result ->
                    completedOk = result.ok
                    if (!result.ok) {
                        store.recordEvent(
                            "mqtt_offline_publish_error",
                            "MQTT offline publish failed",
                            "${result.category}: ${result.message}"
                        )
                    }
                }
                .onFailure { error ->
                    store.recordEvent(
                        "mqtt_offline_publish_error",
                        "MQTT offline publish failed",
                        "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                    )
                }
        } finally {
            mqttOfflineQueued.set(false)
            mainHandler.post {
                if (!settings.isMqttEnabled()) mqttConnection.release()
                if (!settings.isMqttEnabled() && mqttRuntimeStatus == RuntimeActionStatus.STOPPING) {
                    setMqttRuntime(if (completedOk) RuntimeActionStatus.STOPPED else RuntimeActionStatus.ERROR)
                    stopIfNoActiveRuntime()
                }
                scheduleIntegrationDashboardRefresh()
            }
        }
    }

    private fun executeMqtt(
        errorCategory: String,
        activateTailscaleOnFailure: Boolean = true,
        action: () -> MqttActionResult
    ): Boolean {
        if (settings.isMqttEnabled() && !maintenanceBlocksRuntimeStart()) mqttConnection.reserve()
        return executeChannel(
            channelName = "MQTT",
            errorCategory = errorCategory,
            executorLock = mqttExecutorLock,
            executor = { mqttExecutor },
            generation = mqttWorkGeneration,
            canExecute = { settings.isMqttEnabled() && !mqttConnection.stopping && !maintenanceBlocksRuntimeStart() },
            action = action,
            onSuccess = { result, submittedGeneration ->
                if (submittedGeneration == mqttWorkGeneration.get()) {
                    if (result.ok && settings.isMqttEnabled()) {
                        setMqttRuntime(RuntimeActionStatus.RUNNING)
                    } else if (!result.ok && settings.isMqttEnabled()) {
                        setMqttRuntime(RuntimeActionStatus.ERROR)
                    }
                }
                postMqttRetrySchedule(submittedGeneration)
            },
            onComplete = ::scheduleIntegrationDashboardRefresh,
            onFailedAction = {
                if (settings.isMqttEnabled() && !mqttConnection.stopping) {
                    setMqttRuntime(RuntimeActionStatus.ERROR)
                    if (activateTailscaleOnFailure) maybeActivateTailscaleAfterHaFailure("mqtt")
                }
            }
        ) { result ->
            ChannelActionStatus(result.ok, result.category, result.message)
        }
    }

    private fun resetMqttExecutorForOffline(action: (ExecutorService) -> Unit) {
        mqttWorkGeneration.incrementAndGet()
        synchronized(mqttExecutorLock) {
            val previous = mqttExecutor.also { it.shutdownNow() }
            val replacement = namedSingleThreadExecutor("byd-mqtt")
            try {
                replacement.execute { action(previous) }
                mqttExecutor = replacement
            } catch (error: RejectedExecutionException) {
                replacement.shutdownNow()
                //Keep the next MQTT action on a fresh executor even when the first submission rejects.
                mqttExecutor = namedSingleThreadExecutor("byd-mqtt")
                throw error
            }
        }
    }

    private fun resetMqttExecutorForMaintenance() {
        val previous = runOnRuntimeOwnerBlocking {
            requireRuntimeOwner()
            mqttWorkGeneration.incrementAndGet()
            synchronized(mqttExecutorLock) {
                mqttExecutor.also {
                    if (mqttOfflineQueued.get()) it.shutdown() else it.shutdownNow()
                }
            }
        }
        val stopped = try {
            previous.awaitTermination(MQTT_MAINTENANCE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!stopped) maintenanceRuntimeRestoreAllowed.set(false)
        check(stopped) { "MQTT worker did not stop before database maintenance" }
        mqttCoordinator.disconnectForMaintenance()
        runOnRuntimeOwnerBlocking {
            requireRuntimeOwner()
            synchronized(mqttExecutorLock) {
                if (mqttExecutor !== previous) {
                    maintenanceRuntimeRestoreAllowed.set(false)
                    error("MQTT executor changed during database maintenance")
                }
                mqttExecutor = namedSingleThreadExecutor("byd-mqtt")
            }
        }
    }

    private fun shutdownMqttExecutor(interrupt: Boolean = false): ExecutorService {
        return synchronized(mqttExecutorLock) {
            mqttExecutor.also {
                if (interrupt) it.shutdownNow() else it.shutdown()
            }
        }
    }

    private fun awaitMqttWorkerTermination(executor: ExecutorService, timeoutMs: Long): Boolean {
        return try {
            executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun executeInflux(
        errorCategory: String,
        activateTailscaleOnFailure: Boolean = true,
        isStop: Boolean = false,
        afterComplete: (() -> Unit)? = null,
        afterSettled: (() -> Unit)? = null,
        expectedGeneration: Long? = null,
        action: () -> InfluxActionResult
    ): Boolean {
        if (!isStop && settings.isInfluxEnabled() && !maintenanceBlocksRuntimeStart()) influxConnection.reserve()
        influxWorkInFlight.incrementAndGet()
        return executeChannel(
            channelName = "Influx",
            errorCategory = errorCategory,
            executorLock = influxExecutorLock,
            executor = { influxExecutor },
            generation = influxWorkGeneration,
            lowPriority = true,
            canExecute = {
                isInfluxSubmissionCurrent(expectedGeneration, influxWorkGeneration.get()) &&
                    !maintenanceBlocksRuntimeStart() &&
                    (if (isStop) !settings.isInfluxEnabled() else settings.isInfluxEnabled() && !influxConnection.stopping)
            },
            action = action,
            onSuccess = { result, submittedGeneration ->
                if (submittedGeneration == influxWorkGeneration.get()) {
                    if (result.ok && settings.isInfluxEnabled()) {
                        setInfluxRuntime(RuntimeActionStatus.RUNNING)
                    } else if (result.ok && !settings.isInfluxEnabled()) {
                        setInfluxRuntime(RuntimeActionStatus.STOPPED)
                    } else if (!result.ok) {
                        setInfluxRuntime(RuntimeActionStatus.ERROR)
                    }
                }
            },
            onComplete = {
                scheduleIntegrationDashboardRefresh()
                afterComplete?.invoke()
            },
            onSettled = {
                try {
                    settleInfluxWork()
                } finally {
                    afterSettled?.invoke()
                }
            },
            onFailedAction = {
                if (isInfluxSubmissionCurrent(expectedGeneration, influxWorkGeneration.get()) &&
                    (isStop || (settings.isInfluxEnabled() && !influxConnection.stopping))
                ) {
                    setInfluxRuntime(RuntimeActionStatus.ERROR)
                    if (activateTailscaleOnFailure) maybeActivateTailscaleAfterHaFailure("influx")
                }
            }
        ) { result ->
            ChannelActionStatus(result.ok, result.category, result.message)
        }
    }

    private fun settleInfluxWork() {
        val remaining = influxWorkInFlight.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
        val queued = influxRequestQueued.get()
        if (remaining == 0 && !queued) influxWorkQueuedAtElapsedMs = null
        recordInfluxDiagnostic(
            com.bydcollector.collector.influx.InfluxDiagnosticEvent(
                "influx_work_settled",
                influxDiagnosticStateDetails(
                    workGeneration = influxWorkGeneration.get().takeUnless { remaining == 0 && !queued },
                    queued = queued
                )
            )
        )
        mainHandler.post {
            if (influxWorkInFlight.get() == 0 && influxConnection.stopping && !settings.isInfluxEnabled()) {
                // A queued Stop may have been superseded by maintenance; retain ownership but allow retry.
                influxConnection.stopSubmissionFailed()
                setInfluxRuntime(RuntimeActionStatus.ERROR)
            }
            if (running.get()) stopIfNoActiveRuntime()
        }
    }

    private fun advanceInfluxGeneration(): Long {
        val generation = synchronized(influxQueueLock) {
            influxRequestQueued.set(false)
            influxWorkQueuedAtElapsedMs = null
            influxCycleDemand.invalidate()
            influxRequestRevision += 1
            influxWorkGeneration.incrementAndGet()
        }
        if (::influxCoordinator.isInitialized) influxCoordinator.cancelInFlight()
        return generation
    }

    private fun <T> executeOrderedTelegram(
        errorCategory: String,
        coordinator: TelegramCoordinator,
        onSuccess: ((T, Long) -> Unit)? = null,
        onSettled: (() -> Unit)? = null,
        action: () -> T
    ) {
        tripRuntime.afterPendingTrips(onDropped = { onSettled?.invoke() }) { watermark ->
            executeTelegram(errorCategory, onSuccess = onSuccess, onSettled = onSettled) {
                check(telegramCoordinator === coordinator) { "Telegram coordinator changed before ordered input" }
                drainTripCompletions(coordinator, watermark)
                action()
            }
        }
    }

    private fun <T> executeTelegram(
        errorCategory: String,
        onSuccess: ((T, Long) -> Unit)? = null,
        onFailedAction: (() -> Unit)? = null,
        onSettled: (() -> Unit)? = null,
        action: () -> T
    ) = executeChannel(
        channelName = "Telegram",
        errorCategory = errorCategory,
        executorLock = telegramExecutorLock,
        executor = { telegramExecutor },
        generation = telegramWorkGeneration,
        canExecute = { !maintenanceBlocksRuntimeStart() },
        action = action,
        onFailedAction = onFailedAction,
        onException = ::handleTelegramExecutionFailure,
        onSuccess = onSuccess,
        onSettled = onSettled
    ) {
        ChannelActionStatus(true, "ok", "ok")
    }

    private fun <T> executeChannel(
        channelName: String,
        errorCategory: String,
        executorLock: Any,
        executor: () -> ExecutorService,
        generation: AtomicLong,
        lowPriority: Boolean = false,
        canExecute: () -> Boolean = { true },
        action: () -> T,
        onFailedAction: (() -> Unit)? = null,
        onException: ((Throwable) -> Unit)? = null,
        onSuccess: ((T, Long) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        onSettled: (() -> Unit)? = null,
        status: (T) -> ChannelActionStatus
    ): Boolean {
        if (!canExecute()) {
            if (channelName == "Influx") {
                val reason = when {
                    maintenanceBlocksRuntimeStart() -> "maintenance"
                    !settings.isInfluxEnabled() -> "disabled"
                    influxConnection.stopping -> "stopped"
                    else -> "runtime_gate"
                }
                recordInfluxGate(reason)
            }
            onFailedAction?.invoke()
            onComplete?.invoke()
            onSettled?.invoke()
            return false
        }
        val submittedGeneration = generation.get()
        val selectedExecutor = synchronized(executorLock) { executor() }
        try {
            selectedExecutor.execute {
                try {
                    if (lowPriority) Thread.currentThread().priority = Thread.MIN_PRIORITY
                    //drops stale work submitted before a channel executor reset
                    if (submittedGeneration != generation.get() || !canExecute()) {
                        if (channelName == "Influx") {
                            recordInfluxGate(
                                "stale_generation",
                                influxDiagnosticStateDetails(
                                    submittedGeneration,
                                    queued = influxRequestQueued.get()
                                )
                            )
                        }
                        return@execute
                    }
                    if (channelName == "Influx") {
                        recordInfluxDiagnostic(
                            com.bydcollector.collector.influx.InfluxDiagnosticEvent(
                                "influx_work_started",
                                influxDiagnosticStateDetails(submittedGeneration, queued = false) +
                                    mapOf(
                                        "age_ms" to (influxWorkQueuedAtElapsedMs?.let {
                                            (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)
                                        } ?: 0L).toString()
                                    )
                            )
                        )
                    }
                    try {
                        runCatching { action() }
                            .onSuccess { result ->
                                if (submittedGeneration != generation.get() || !canExecute()) return@onSuccess
                                val state = status(result)
                                if (!state.ok) {
                                    store.recordEvent(
                                        errorCategory,
                                        "$channelName async action failed",
                                        "${state.category}: ${state.message}"
                                    )
                                    onFailedAction?.invoke()
                                }
                                if (submittedGeneration == generation.get()) {
                                    onSuccess?.invoke(result, submittedGeneration)
                                }
                            }
                            .onFailure { error ->
                                if (submittedGeneration != generation.get() || !canExecute()) return@onFailure
                                store.recordEvent(
                                    errorCategory,
                                    "$channelName async action failed",
                                    "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                                )
                                onFailedAction?.invoke()
                                onException?.invoke(error)
                            }
                    } finally {
                        if (submittedGeneration == generation.get() && canExecute()) {
                            onComplete?.invoke()
                        }
                    }
                } finally {
                    onSettled?.invoke()
                }
            }
            return true
        } catch (error: RejectedExecutionException) {
            if (channelName == "Influx") {
                recordInfluxGate(
                    "queue_rejected",
                    influxDiagnosticStateDetails(
                        submittedGeneration,
                        queued = influxRequestQueued.get()
                    ) + mapOf("error_class" to error::class.java.simpleName)
                )
            }
            if (submittedGeneration == generation.get() && canExecute()) {
                store.recordEvent(
                    errorCategory,
                    "$channelName async action rejected",
                    "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                )
                onFailedAction?.invoke()
                onComplete?.invoke()
            }
            onSettled?.invoke()
            return false
        }
    }

    private data class ChannelActionStatus(
        val ok: Boolean,
        val category: String,
        val message: String
    )

    private fun handleTelegramExecutionFailure(error: Throwable) {
        if (error !is SQLiteException) return
        (applicationContext as BydCollectorApplication).markTelegramStorageUnavailable(store, error)
        telegramCoordinator = null
        telegramWorkGeneration.incrementAndGet()
        telegramRecoveryCoalescer.invalidate()
        mainHandler.post {
            cancelTelegramTick()
            scheduleIntegrationDashboardRefresh()
            stopIfNoActiveRuntime()
        }
    }

    private fun shutdownInfluxExecutor() {
        advanceInfluxGeneration()
        synchronized(influxExecutorLock) {
            influxExecutor.shutdownNow()
        }
        influxWorkInFlight.set(0)
    }

    private fun resetInfluxExecutorForMaintenance() {
        val previous = runOnRuntimeOwnerBlocking {
            requireRuntimeOwner()
            advanceInfluxGeneration()
            if (influxRuntimeStatus != RuntimeActionStatus.STOPPED) {
                setInfluxRuntime(RuntimeActionStatus.STOPPING)
            }
            synchronized(influxExecutorLock) {
                influxExecutor.also { it.shutdownNow() }
            }
        }
        val stopped = try {
            previous.awaitTermination(INFLUX_MAINTENANCE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!stopped) {
            maintenanceRuntimeRestoreAllowed.set(false)
            runOnRuntimeOwnerBlocking {
                requireRuntimeOwner()
                setInfluxRuntime(RuntimeActionStatus.ERROR)
            }
        }
        check(stopped) { "Influx worker did not stop before database maintenance" }
        influxWorkInFlight.set(0)
        runOnRuntimeOwnerBlocking {
            requireRuntimeOwner()
            synchronized(influxExecutorLock) {
                if (influxExecutor !== previous) {
                    maintenanceRuntimeRestoreAllowed.set(false)
                    error("Influx executor changed during database maintenance")
                }
                influxExecutor = namedSingleThreadExecutor("byd-influx")
                setInfluxRuntime(RuntimeActionStatus.STOPPED)
            }
        }
    }

    private fun startDatabaseMaintenance(operation: DbMaintenanceOperation) {
        requireRuntimeOwner()
        if (!maintenanceActive.compareAndSet(false, true)) return
        telegramRecoveryCoalescer.invalidate()
        activeMaintenanceOperation = operation
        maintenanceRuntimeRestoreAllowed.set(true)
        maintenanceRunningInProcess.set(true)
        if (operation == DbMaintenanceOperation.ARCHIVE) {
            CollectorAutoStart.cancelRuntimeRecovery(applicationContext)
        }
        val snapshot = runtimeSnapshot()
        ensureForegroundForChannel("Database maintenance")
        var restoreAfterMaintenance = false
        try {
            maintenanceExecutor.execute {
                try {
                    val result = maintenanceCoordinator.run(operation) {
                        if (maintenanceRuntimeRestoreAllowed.get()) restoreAfterMaintenance = true
                    }
                    if (result.ok && result.archivePath != null) {
                        enqueueArchiveStorageMaintenance(result.archivePath)
                    }
                } finally {
                    dispatchDatabaseMaintenanceCompletion(
                        operation = operation,
                        snapshot = snapshot,
                        restoreAfterMaintenance = restoreAfterMaintenance
                    )
                }
            }
        } catch (error: RejectedExecutionException) {
            finishDatabaseMaintenanceOnRuntimeOwner(
                operation = operation,
                snapshot = snapshot,
                restoreAfterMaintenance = false
            )
            store.recordEvent(
                "database_maintenance_rejected",
                "Database maintenance action rejected",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }
    }

    private fun dispatchDatabaseMaintenanceCompletion(
        operation: DbMaintenanceOperation,
        snapshot: RuntimeSnapshot,
        restoreAfterMaintenance: Boolean
    ) {
        if (isRuntimeOwner()) {
            finishDatabaseMaintenanceOnRuntimeOwner(operation, snapshot, restoreAfterMaintenance)
            return
        }
        check(mainHandler.post {
            finishDatabaseMaintenanceOnRuntimeOwner(operation, snapshot, restoreAfterMaintenance)
        }) { "Collector runtime owner is unavailable" }
    }

    private fun finishDatabaseMaintenanceOnRuntimeOwner(
        operation: DbMaintenanceOperation,
        snapshot: RuntimeSnapshot,
        restoreAfterMaintenance: Boolean
    ) {
        requireRuntimeOwner()
        if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE && secondaryArchiveFenced.getAndSet(false)) {
            if (!settings.isUserShutdownRequested() && settings.isDebugPollingEnabled() &&
                !settings.isDebugManuallyStopped() &&
                !DirectStreamController.resume(CollectorHelperProtocol.STREAM_SECONDARY)
            ) {
                store.recordEvent(
                    "secondary_archive_resume_failed",
                    "Secondary helper stream did not resume after archive"
                )
            }
        }
        activeMaintenanceOperation = null
        maintenanceActive.set(false)
        maintenanceRunningInProcess.set(false)
        if (
            running.get() &&
            restoreAfterMaintenance &&
            maintenanceRuntimeRestoreAllowed.get()
        ) {
            restoreRuntimeAfterMaintenance(operation, snapshot)
        }
    }

    private fun resetTelegramExecutorForMaintenance() {
        runOnRuntimeOwnerBlocking {
            requireRuntimeOwner()
            telegramWorkGeneration.incrementAndGet()
        }
        val stopped = telegramDeliveryRuntime.quiesceAndAwait(TELEGRAM_MAINTENANCE_STOP_TIMEOUT_MS)
        if (!stopped) maintenanceRuntimeRestoreAllowed.set(false)
        check(stopped) { "Telegram HTTP/receipt did not settle before database maintenance" }
        telegramCoordinator = null
    }

    private fun quiesceTelegramForUserShutdown(): Boolean {
        telegramWorkGeneration.incrementAndGet()
        return telegramDeliveryRuntime.quiesceAndAwait(TELEGRAM_MAINTENANCE_STOP_TIMEOUT_MS)
    }

    private fun shutdownTelegramExecutor() {
        telegramWorkGeneration.incrementAndGet()
        // Process ownership keeps a returned HTTP result commit-able across Service recreation.
        telegramDeliveryRuntime.detach(this)
    }

    private fun cancelDatabaseMaintenance() {
        maintenanceCoordinator.requestCancel()
    }

    private fun enqueueArchiveStorageMaintenance(preferredArchivePath: String?) {
        enqueueArchiveStorageWork("archive_storage_rejected", ArchiveStorageJobMode.RETENTION) {
            val manager = archiveStorageManager()
            val limitBytes = settings.archiveStorageLimitGb() * 1024L * 1024L * 1024L
            preferredArchivePath
                ?.let(::File)
                ?.takeIf { it.isDirectory }
                ?.let { manager.compressRawArchiveDirectory(it, ::publishArchiveStorageStatus) }
            manager.compressPendingRawArchives(::publishArchiveStorageStatus)
            val rawArchiveRemains = File(applicationContext.filesDir, "db_archive").listFiles().orEmpty().any { file ->
                file.isDirectory && (
                    file.name.startsWith("${File(store.databaseFile().name).nameWithoutExtension}_") ||
                        ArchiveStorageManager.isSecondaryArchiveName(file.name)
                    )
            }
            check(!rawArchiveRemains) { "Raw database archive compression remains pending" }
            manager.enforceRetention(limitBytes, ::publishArchiveStorageStatus)
            settings.setCutoverArchiveStoragePending(false)
        }
    }

    private fun reconcilePendingCutoverArchiveStorage(action: String) {
        if (!settings.isCutoverArchiveStoragePending()) return
        if (
            action in setOf(
                ACTION_ARCHIVE_DATABASE,
                ACTION_ARCHIVE_DEBUG_DATABASE,
                ACTION_RECONCILE_ARCHIVE_STORAGE,
                ACTION_DELETE_ARCHIVES
            )
        ) return
        ensureForegroundForChannel("Archive storage")
        enqueueArchiveStorageMaintenance(null)
    }

    private fun enqueueArchiveDelete(ids: List<String>) {
        val safeIds = ids.distinct()
        val successfulIds = linkedSetOf<String>()
        enqueueArchiveStorageWork(
            errorCategory = "archive_storage_delete_rejected",
            requestedMode = ArchiveStorageJobMode.DELETE,
            initialStatus = ArchiveStorageJobStatus(
                mode = ArchiveStorageJobMode.DELETE,
                running = true,
                stepIndex = 0,
                stepCount = safeIds.size,
                messageUk = "Готуємо видалення",
                messageEn = "Preparing archive deletion",
                updatedAtMs = System.currentTimeMillis()
            ),
            onRejected = {
                dashboardStateProvider.restoreRetiredArchiveStorageEntries(safeIds)
                dashboardStateProvider.completeArchiveStorageDeletion()
            },
            onFinished = {
                dashboardStateProvider.restoreRetiredArchiveStorageEntries(safeIds - successfulIds)
                dashboardStateProvider.completeArchiveStorageDeletion()
            }
        ) {
            val manager = archiveStorageManager()
            val failedIds = linkedSetOf<String>()
            store.recordEvent(
                "archive_delete_started",
                "Archive deletion started",
                "count=${safeIds.size}"
            )
            archiveShareLeaseRegistry.forceRelease(ids)
            manager.deleteArchiveIds(ids) { status ->
                publishArchiveStorageStatus(status)
                if (status.error != null && status.itemId != null) {
                    failedIds += status.itemId
                    store.recordEvent(
                        "archive_delete_item",
                        "Archive deletion failed",
                        "item=${diagnosticSafeText(status.itemId, 96)} step=${status.stepIndex}/${status.stepCount} " +
                            "reason=${diagnosticSafeText(status.error, 256)}"
                    )
                } else if (status.messageEn == "Archive deleted" && status.itemId != null) {
                    successfulIds += status.itemId
                    store.recordEvent(
                        "archive_delete_item",
                        "Archive deleted",
                        "item=${diagnosticSafeText(status.itemId, 96)} step=${status.stepIndex}/${status.stepCount}"
                    )
                }
            }
            if (failedIds.isNotEmpty()) {
                settings.setArchiveStorageJobStatus(
                    ArchiveStorageJobStatus(
                        mode = ArchiveStorageJobMode.DELETE,
                        running = false,
                        stepIndex = safeIds.size,
                        stepCount = safeIds.size,
                        messageUk = "Видалення завершено з помилками",
                        messageEn = "Archive deletion completed with failures",
                        error = summarizeArchiveDeleteFailures(failedIds),
                        updatedAtMs = System.currentTimeMillis()
                    ),
                    synchronous = true
                )
            }
        }
    }

    private fun enqueueArchiveStorageWork(
        errorCategory: String,
        requestedMode: ArchiveStorageJobMode,
        initialStatus: ArchiveStorageJobStatus? = null,
        onRejected: () -> Unit = {},
        onFinished: () -> Unit = {},
        work: () -> Unit
    ) {
        if (!archiveStorageActiveInProcess.compareAndSet(false, true)) {
            publishArchiveStorageTerminalError(requestedMode, "Archive storage is already active")
            onRejected()
            store.recordEvent(errorCategory, "Archive storage action rejected", "archive_storage_active")
            scheduleIntegrationDashboardRefresh()
            return
        }
        initialStatus?.let { settings.setArchiveStorageJobStatus(it, synchronous = true) }
        try {
            archiveStorageExecutor.execute {
                try {
                    val result = runCatching { work() }
                        .onFailure { error ->
                            settings.setArchiveStorageJobStatus(
                                ArchiveStorageJobStatus(
                                    mode = requestedMode,
                                    running = false,
                                    error = "${error::class.java.simpleName}: ${error.message ?: "no message"}",
                                    updatedAtMs = System.currentTimeMillis()
                                ),
                                synchronous = true
                            )
                            store.recordEvent(
                                "archive_storage_error",
                                "Archive storage action failed",
                                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                            )
                            store.recordEvent(
                                "archive_storage_terminal",
                                "Archive storage action completed",
                                "mode=${requestedMode.name.lowercase()} ok=false error=${error.message?.take(160) ?: error::class.java.simpleName}"
                            )
                        }
                    if (settings.archiveStorageJobStatus().error == null) {
                        if (requestedMode == ArchiveStorageJobMode.DELETE) {
                            settings.setArchiveStorageJobStatus(
                                ArchiveStorageJobStatus(
                                    mode = requestedMode,
                                    running = false,
                                    messageUk = "Видалення архівів завершено",
                                    messageEn = "Archive deletion completed",
                                    updatedAtMs = System.currentTimeMillis()
                                ),
                                synchronous = true
                            )
                        } else {
                            settings.clearArchiveStorageJobStatus()
                        }
                    }
                    try {
                        result.onSuccess {
                            val terminal = settings.archiveStorageJobStatus()
                            store.recordEvent(
                                "archive_storage_terminal",
                                "Archive storage action completed",
                                "mode=${requestedMode.name.lowercase()} ok=${terminal.error == null} error=${terminal.error?.take(160) ?: "none"}"
                            )
                        }
                    } finally {
                        onFinished()
                    }
                } finally {
                    archiveStorageActiveInProcess.set(false)
                    mainHandler.post { stopIfNoActiveRuntime() }
                }
            }
        } catch (error: RejectedExecutionException) {
            archiveStorageActiveInProcess.set(false)
            publishArchiveStorageTerminalError(
                requestedMode,
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
            onRejected()
            store.recordEvent(
                errorCategory,
                "Archive storage action rejected",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
            scheduleIntegrationDashboardRefresh()
        }
    }

    private fun publishArchiveStorageTerminalError(mode: ArchiveStorageJobMode, detail: String) {
        settings.setArchiveStorageJobStatus(
            ArchiveStorageJobStatus(
                mode = mode,
                running = false,
                error = detail,
                updatedAtMs = System.currentTimeMillis()
            ),
            synchronous = true
        )
    }

    private fun summarizeArchiveDeleteFailures(failedIds: Collection<String>): String {
        val cappedIds = failedIds
            .take(8)
            .joinToString(",") { it.take(96) }
        val suffix = if (failedIds.size > 8) ",..." else ""
        return "failed_count=${failedIds.size} failed_ids=$cappedIds$suffix"
    }

    private fun archiveStorageManager(): ArchiveStorageManager {
        return ArchiveStorageManager(
            archiveRoot = File(applicationContext.filesDir, "db_archive"),
            mainDatabaseFile = store.databaseFile(),
            debugDatabaseFile = applicationContext.getDatabasePath(DirectDebugDatabaseHelper.DATABASE_NAME),
            debugDatabaseFileProvider = { DirectDebugDatabaseResolver.databaseFile(applicationContext) },
            tripsDatabaseFile = applicationContext.getDatabasePath(com.bydcollector.collector.data.trips.TripDatabaseHelper.DATABASE_NAME),
            isRetentionProtected = archiveShareLeaseRegistry::isActive
        )
    }

    private fun publishArchiveStorageStatus(status: ArchiveStorageJobStatus) {
        settings.setArchiveStorageJobStatus(status, synchronous = true)
    }

    private fun maintenanceBlocksRuntimeStart(debugRuntime: Boolean = false): Boolean {
        if (restoringRuntime.get() && isRuntimeOwner()) return false
        if (maintenanceActive.get()) {
            return activeMaintenanceOperation != DbMaintenanceOperation.DEBUG_ARCHIVE || debugRuntime
        }
        if (settings.dbMaintenanceStatus().running) {
            settings.recoverInterruptedDbMaintenanceIfNeeded("runtime_start_guard")
            return false
        }
        return false
    }

    private fun keepAliveStopRetryBlockedByMaintenance(): Boolean {
        return maintenanceActive.get() || CollectorSettings.isDbMaintenanceRunning(applicationContext)
    }

    private fun isRuntimeOwner(): Boolean = Looper.myLooper() == mainHandler.looper

    private fun requireRuntimeOwner() {
        check(isRuntimeOwner()) { "Collector runtime state must be changed on the main handler" }
    }

    private fun <T> runOnRuntimeOwnerBlocking(action: () -> T): T {
        if (isRuntimeOwner()) return action()
        val task = FutureTask<T> { action() }
        check(mainHandler.post(task)) { "Collector runtime owner is unavailable" }
        return try {
            task.get(RUNTIME_OWNER_HANDOFF_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            task.cancel(false)
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for collector runtime owner", error)
        } catch (error: TimeoutException) {
            mainHandler.removeCallbacks(task)
            task.cancel(false)
            throw IllegalStateException("Timed out waiting for collector runtime owner", error)
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            if (cause is RuntimeException) throw cause
            throw IllegalStateException("Collector runtime owner action failed", cause)
        }
    }

    private fun recoverInterruptedMaintenanceIfNeeded(action: String) {
        if (action == ACTION_ARCHIVE_DATABASE || action == ACTION_ARCHIVE_DEBUG_DATABASE) return
        if (maintenanceActive.get()) return
        settings.recoverInterruptedDbMaintenanceIfNeeded("service_start:$action")
    }

    private fun recoverInterruptedArchiveDeleteIfNeeded(action: String) {
        val status = settings.archiveStorageJobStatus()
        if (status.mode != ArchiveStorageJobMode.DELETE || !status.running) return
        if (archiveStorageActiveInProcess.get()) return
        val detail = "archive_delete_interrupted: process_restart action=$action"
        settings.setArchiveStorageJobStatus(
            status.copy(
                running = false,
                messageUk = "Видалення перервано після перезапуску",
                messageEn = "Archive deletion interrupted by process restart",
                error = detail,
                updatedAtMs = System.currentTimeMillis()
            ),
            synchronous = true
        )
        runCatching {
            store.recordEvent(
                "archive_delete_interrupted",
                "Archive deletion interrupted by process restart",
                "action=$action step=${status.stepIndex}/${status.stepCount}"
            )
        }
        dashboardStateProvider.invalidateArchiveStorageSnapshot()
        scheduleIntegrationDashboardRefresh()
    }

    private fun createMqttCoordinator(client: MqttClientFacade): MqttPublishCoordinator {
        return MqttPublishCoordinator(
            client = client,
            outbox = store,
            retryStateStore = store,
            messageFactory = HaMqttMessageFactory(
                normalizedProvider = store,
                configProvider = { settings.mqttConfig() }
            ),
            configProvider = { settings.mqttConfig() }
        )
    }

    private fun createInfluxCoordinator(): InfluxExportCoordinator {
        return InfluxExportCoordinator(
            store = store,
            client = HttpInfluxClient(::recordInfluxDiagnostic),
            configProvider = { settings.influxConfig() },
            diagnostics = ::recordInfluxDiagnostic
        )
    }

    private fun notificationText(
        mainEnabled: Boolean,
        debugEnabled: Boolean,
        keepAliveEnabled: Boolean,
        telegramEnabled: Boolean
    ): String {
        val base = when {
            mainEnabled && debugEnabled && keepAliveEnabled -> "Collector running + debug + keep-alive"
            mainEnabled && keepAliveEnabled -> "Collector running + keep-alive"
            debugEnabled && keepAliveEnabled -> "Debug polling running + keep-alive"
            keepAliveEnabled -> "Keep-alive running"
            mainEnabled && debugEnabled -> "Collector running + debug"
            debugEnabled -> "Debug polling running"
            mainEnabled -> "Collector running"
            else -> "Collector service running"
        }
        return if (telegramEnabled) "$base + Telegram" else base
    }

    private fun createTelegramCoordinator(): TelegramCoordinator? {
        val application = applicationContext as BydCollectorApplication
        telegramDeliveryRuntime.attach(
            this,
            onReady = { postTelegramTickSchedule(it, telegramWorkGeneration.get()) },
            onFailure = ::handleTelegramExecutionFailure
        )
        telegramDeliveryRuntime.resume()
        telegramCoordinator?.let { return it }
        if (telegramDeliveryRuntime.hasInFlightDelivery) return null
        val telegramStore = application.telegramStoreOrNull() ?: run {
            settings.setTelegramConnectionStatus(
                "storage_error",
                BydCollectorApplication.TELEGRAM_STORAGE_ERROR
            )
            return null
        }
        return TelegramCoordinator(
            eventStore = store,
            telegramStore = telegramStore,
            settings = settings,
            dispatchSend = telegramDeliveryRuntime::dispatchSend,
            onDeliveryReady = telegramDeliveryRuntime::deliveryReady,
            currentEnergySnapshot = {
                BydCollectorApplication.trips(application).readEnergyRuntimeRow()?.let { row ->
                    com.bydcollector.collector.data.energy.EnergyStateCodec.decodeState(row.stateJson).currentSnapshot
                }
            }
        )
    }

    private fun updateNotification(text: String) {
        if (lastNotificationText == text) return
        lastNotificationText = text
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }.onFailure { error ->
            store.recordEvent(
                "notification_update_error",
                "Collector notification update failed",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }
    }

    private fun handleForegroundServiceTimeout(startId: Int, fgsType: Int?) {
        store.recordEvent(
            "foreground_service_timeout",
            "Foreground service timeout; stopping collection",
            "start_id=$startId fgs_type=${fgsType?.toString() ?: "unknown"}"
        )
        stopCollection("foreground_service_timeout")
        stopSelf(startId)
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(BuildConfig.COLLECTOR_DISPLAY_NAME)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            BuildConfig.COLLECTOR_DISPLAY_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        val ACTION_START: String = "${BuildConfig.ACTION_PREFIX}.action.START"
        val ACTION_SHUTDOWN: String = "${BuildConfig.ACTION_PREFIX}.action.SHUTDOWN"
        val ACTION_STOP: String = "${BuildConfig.ACTION_PREFIX}.action.STOP"
        val ACTION_START_DEBUG: String = "${BuildConfig.ACTION_PREFIX}.action.START_DEBUG"
        val ACTION_RECONCILE_DEBUG: String = "${BuildConfig.ACTION_PREFIX}.action.RECONCILE_DEBUG"
        val ACTION_STOP_DEBUG: String = "${BuildConfig.ACTION_PREFIX}.action.STOP_DEBUG"
        val ACTION_RECONCILE_KEEP_ALIVE: String = "${BuildConfig.ACTION_PREFIX}.action.RECONCILE_KEEP_ALIVE"
        val ACTION_START_MQTT_EXPORT: String = "${BuildConfig.ACTION_PREFIX}.action.START_MQTT_EXPORT"
        val ACTION_RECONCILE_MQTT_EXPORT: String = "${BuildConfig.ACTION_PREFIX}.action.RECONCILE_MQTT_EXPORT"
        val ACTION_STOP_MQTT_EXPORT: String = "${BuildConfig.ACTION_PREFIX}.action.STOP_MQTT_EXPORT"
        val ACTION_START_INFLUX_EXPORT: String = "${BuildConfig.ACTION_PREFIX}.action.START_INFLUX_EXPORT"
        val ACTION_RECONCILE_INFLUX_EXPORT: String = "${BuildConfig.ACTION_PREFIX}.action.RECONCILE_INFLUX_EXPORT"
        val ACTION_STOP_INFLUX_EXPORT: String = "${BuildConfig.ACTION_PREFIX}.action.STOP_INFLUX_EXPORT"
        val ACTION_RECONCILE_TELEGRAM: String = "${BuildConfig.ACTION_PREFIX}.action.RECONCILE_TELEGRAM"
        val ACTION_TEST_TELEGRAM: String = "${BuildConfig.ACTION_PREFIX}.action.TEST_TELEGRAM"
        val ACTION_ARCHIVE_DATABASE: String = "${BuildConfig.ACTION_PREFIX}.action.ARCHIVE_DATABASE"
        val ACTION_ARCHIVE_DEBUG_DATABASE: String = "${BuildConfig.ACTION_PREFIX}.action.ARCHIVE_DEBUG_DATABASE"
        val ACTION_CANCEL_DATABASE_MAINTENANCE: String = "${BuildConfig.ACTION_PREFIX}.action.CANCEL_DATABASE_MAINTENANCE"
        val ACTION_RECONCILE_ARCHIVE_STORAGE: String = "${BuildConfig.ACTION_PREFIX}.action.RECONCILE_ARCHIVE_STORAGE"
        val ACTION_DELETE_ARCHIVES: String = "${BuildConfig.ACTION_PREFIX}.action.DELETE_ARCHIVES"
        const val EXTRA_ARCHIVE_IDS = "archiveIds"
        private const val EXTRA_FORCE_KEEP_ALIVE_STATUS_CHECK = "forceKeepAliveStatusCheck"
        private const val CHANNEL_ID = "collector"
        private const val NOTIFICATION_ID = 1001
        private const val STATUS_HEARTBEAT_INTERVAL_MS = 30_000L
        private const val DASHBOARD_RUNTIME_HEARTBEAT_MS = 2_000L
        private const val DATABASE_FOOTPRINT_INTERVAL_MS = 10_000L
        private const val KPI_PUBLISH_INTERVAL_MS = 1_000L
        private const val KPI_STALE_AFTER_MS = 3_000L
        private const val ACCESS_SELF_CHECK_INTERVAL_MS = 5 * 60_000L
        private const val TELEGRAM_TICK_INTERVAL_MS = 15_000L
        private const val RECOVERY_STARTUP = "startup"
        private const val RECOVERY_STARTUP_CREDENTIALS = "startup_credentials"
        private const val RECOVERY_VEHICLE_ON = "vehicle_on"
        private const val RECOVERY_NETWORK = "network"
        private const val RECOVERY_MANUAL_TEST_SUCCESS = "manual_test_success"
        private const val MQTT_MAINTENANCE_STOP_TIMEOUT_MS = 16_000L
        private const val INFLUX_MAINTENANCE_STOP_TIMEOUT_MS = 16_000L
        private const val TELEGRAM_MAINTENANCE_STOP_TIMEOUT_MS = 16_000L
        private const val RUNTIME_OWNER_HANDOFF_TIMEOUT_MS = 30_000L
        private const val USER_SHUTDOWN_STOP_TIMEOUT_MS = 16_000L
        private const val TAG = "BYDCollectorService"
        private const val DEBUG_REASON_AUTOSTART = "autostart"
        private const val DEBUG_REASON_MANUAL = "manual"
        private val running = AtomicBoolean(false)
        private val mainPollingRunning = AtomicBoolean(false)
        private val debugRunning = AtomicBoolean(false)
        private val mainRuntimeStatusRef = AtomicReference(RuntimeActionStatus.STOPPED)
        private val debugRuntimeStatusRef = AtomicReference(DebugRuntimeStatus.STOPPED)
        private val mqttRuntimeStatusRef = AtomicReference(RuntimeActionStatus.STOPPED)
        internal val mqttConnection = HaConnectionOwnership()
        private val influxRuntimeStatusRef = AtomicReference(RuntimeActionStatus.STOPPED)
        internal val influxConnection = HaConnectionOwnership()
        internal val influxRuntimeDiagnostics = InfluxRuntimeDiagnosticsProcess.instance
        private val maintenanceRunningInProcess = AtomicBoolean(false)
        private val archiveStorageActiveInProcess = AtomicBoolean(false)
        private val processMqttClientFacade = PahoMqttClientFacade()
        val archiveShareLeaseRegistry = ArchiveShareLeaseRegistry(
            elapsedRealtimeMs = { SystemClock.elapsedRealtime() }
        )

        fun isRunning(): Boolean = running.get()
        fun isMainPollingRunning(): Boolean = mainPollingRunning.get()
        fun isDebugRunning(): Boolean = debugRunning.get()
        fun mainRuntimeStatus(): RuntimeActionStatus = mainRuntimeStatusRef.get()
        fun debugRuntimeStatus(): DebugRuntimeStatus = debugRuntimeStatusRef.get()
        fun mqttRuntimeStatus(): RuntimeActionStatus = mqttRuntimeStatusRef.get()
        fun influxRuntimeStatus(): RuntimeActionStatus = influxRuntimeStatusRef.get()
        fun isMaintenanceRunningInProcess(): Boolean = maintenanceRunningInProcess.get()
        fun isArchiveStorageActive(): Boolean = archiveStorageActiveInProcess.get()

        fun startIntent(context: Context, forceKeepAliveStatusCheck: Boolean = false): Intent =
            Intent(context, CollectorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FORCE_KEEP_ALIVE_STATUS_CHECK, forceKeepAliveStatusCheck)
            }

        fun shutdownIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_SHUTDOWN
        }

        fun stopIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_STOP
        }

        fun startDebugIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_START_DEBUG
        }

        fun reconcileDebugIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_RECONCILE_DEBUG
        }

        fun stopDebugIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_STOP_DEBUG
        }

        fun keepAliveIntent(context: Context, forceKeepAliveStatusCheck: Boolean = false): Intent =
            Intent(context, CollectorService::class.java).apply {
                action = ACTION_RECONCILE_KEEP_ALIVE
                putExtra(EXTRA_FORCE_KEEP_ALIVE_STATUS_CHECK, forceKeepAliveStatusCheck)
            }

        fun keepAliveStopRetryIntent(context: Context, retryAttempt: Int): Intent =
            Intent(context, CollectorService::class.java).apply {
                action = CollectorAutoStart.ACTION_KEEP_ALIVE_STOP_RETRY
                putExtra(CollectorAutoStart.EXTRA_KEEP_ALIVE_STOP_RETRY_ATTEMPT, retryAttempt)
            }

        fun startMqttExportIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_START_MQTT_EXPORT
        }

        fun reconcileMqttExportIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_RECONCILE_MQTT_EXPORT
        }

        fun stopMqttExportIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_STOP_MQTT_EXPORT
        }

        fun startInfluxExportIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_START_INFLUX_EXPORT
        }

        fun reconcileInfluxExportIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_RECONCILE_INFLUX_EXPORT
        }

        fun stopInfluxExportIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_STOP_INFLUX_EXPORT
        }

        fun reconcileTelegramIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_RECONCILE_TELEGRAM
        }

        fun testTelegramIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_TEST_TELEGRAM
        }

        fun archiveDatabaseIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_ARCHIVE_DATABASE
        }

        fun archiveDebugDatabaseIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_ARCHIVE_DEBUG_DATABASE
        }

        fun cancelDatabaseMaintenanceIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_CANCEL_DATABASE_MAINTENANCE
        }

        fun reconcileArchiveStorageIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_RECONCILE_ARCHIVE_STORAGE
        }

        fun deleteArchivesIntent(context: Context, ids: ArrayList<String>): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_DELETE_ARCHIVES
            putStringArrayListExtra(EXTRA_ARCHIVE_IDS, ids)
        }
    }
}

internal fun awaitSerializedExecutorAction(
    executor: ExecutorService,
    executorThreadName: String,
    timeoutMs: Long,
    action: () -> Unit
): Boolean {
    if (Thread.currentThread().name == executorThreadName) return runCatching(action).isSuccess
    val future = try {
        executor.submit(action)
    } catch (_: RejectedExecutionException) {
        return false
    }
    return try {
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
        true
    } catch (_: TimeoutException) {
        false
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    } catch (_: Exception) {
        false
    }
}

internal fun awaitExecutorTermination(
    executor: ExecutorService,
    executorThreadName: String,
    timeoutMs: Long
): Boolean {
    if (Thread.currentThread().name == executorThreadName) return false
    return try {
        executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
