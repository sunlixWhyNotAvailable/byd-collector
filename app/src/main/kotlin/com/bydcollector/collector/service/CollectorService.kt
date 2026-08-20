package com.bydcollector.collector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import com.bydcollector.collector.data.debug.DirectDebugParameterAsset
import com.bydcollector.collector.data.debug.DirectDebugRoundRobinPoller
import com.bydcollector.collector.data.debug.DirectDebugStore
import com.bydcollector.collector.data.direct.DirectVehicleHelperClient
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.local.HealthSnapshotDetail
import com.bydcollector.collector.data.normalized.NormalizedWriteSummary
import com.bydcollector.collector.data.normalized.PollingErrorSummaries
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.polling.PollPersistenceCoordinator
import com.bydcollector.collector.data.polling.SuccessfulPollObserver
import com.bydcollector.collector.data.polling.TelemetryPoller
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.remote.DirectTelemetryClient
import com.bydcollector.collector.data.remote.DirectBridgeManager
import com.bydcollector.collector.data.remote.TelemetryClient
import com.bydcollector.collector.keepalive.KeepAliveConfig
import com.bydcollector.collector.keepalive.KeepAliveSupervisor
import com.bydcollector.collector.influx.HttpInfluxClient
import com.bydcollector.collector.influx.InfluxActionResult
import com.bydcollector.collector.influx.InfluxExportCoordinator
import com.bydcollector.collector.ha.HaEndpoint
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
import com.bydcollector.collector.ui.DashboardDebugPollState
import com.bydcollector.collector.ui.DashboardMainPollState
import com.bydcollector.collector.ui.DashboardRowCounts
import com.bydcollector.collector.ui.DashboardRuntimeFlags
import com.bydcollector.collector.ui.DashboardStateProvider
import com.bydcollector.collector.ui.DashboardUiStateStore
import com.bydcollector.collector.ui.DisplayTimeFormatter
import com.bydcollector.collector.ui.VehicleKpiLanguage
import com.bydcollector.collector.ui.VehicleKpiMapper
import com.bydcollector.collector.ui.VehicleKpis
import com.bydcollector.collector.ui.compose.AppTab
import com.bydcollector.collector.util.namedSingleThreadExecutor
import com.bydcollector.collector.util.sqliteFootprintBytes
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

//owns the runtime lifecycle so collection, exports, and keep-alive can continue without an open activity
class CollectorService : Service() {
    private lateinit var store: TelemetryStore
    private lateinit var settings: CollectorSettings
    private lateinit var poller: TelemetryPoller
    private lateinit var debugStore: DirectDebugStore
    private lateinit var keepAliveSupervisor: KeepAliveSupervisor
    private lateinit var tailscaleGate: TailscaleActivationGate
    private lateinit var vehicleStateNormalizer: VehicleStateNormalizer
    private lateinit var mqttCoordinator: MqttPublishCoordinator
    private lateinit var influxCoordinator: InfluxExportCoordinator
    private lateinit var telegramCoordinator: TelegramCoordinator
    private lateinit var maintenanceCoordinator: DbMaintenanceCoordinator
    private lateinit var dashboardUiStateStore: DashboardUiStateStore
    private lateinit var dashboardStateProvider: DashboardStateProvider
    private var debugStorageReady = false
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
    private val influxExecutorLock = Any()
    private var influxExecutor: ExecutorService = namedSingleThreadExecutor("byd-influx")
    private val influxRequestQueued = AtomicBoolean(false)
    private var influxRetryScheduled = false
    private var influxRetryAtElapsedMs: Long? = null
    private val telegramExecutorLock = Any()
    private var telegramExecutor: ExecutorService = namedSingleThreadExecutor("byd-telegram")
    private val mqttWorkGeneration = AtomicLong(0L)
    private val influxWorkGeneration = AtomicLong(0L)
    private val telegramWorkGeneration = AtomicLong(0L)
    private val debugStartInProgress = AtomicBoolean(false)
    private val mqttRuntimeActive = AtomicBoolean(false)
    private val mqttOfflineQueued = AtomicBoolean(false)
    private val maintenanceActive = AtomicBoolean(false)
    private val maintenanceRuntimeRestoreAllowed = AtomicBoolean(true)
    private val userShutdownFinalizationStarted = AtomicBoolean(false)
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
    private val influxRetryTask = object : Runnable {
        override fun run() {
            influxRetryScheduled = false
            influxRetryAtElapsedMs = null
            if (!running.get() || !settings.isInfluxEnabled() || maintenanceBlocksRuntimeStart()) return
            requestInfluxCycle()
        }
    }
    private val telegramTickTask = object : Runnable {
        override fun run() {
            telegramTickScheduled = false
            telegramTickAtMs = null
            if (!running.get() || !settings.isTelegramEnabled() || maintenanceBlocksRuntimeStart()) return
            scheduleTelegramTick()
            executeTelegram(
                "telegram_tick_error",
                onSuccess = ::postTelegramTickSchedule
            ) {
                telegramCoordinator.tick(
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
        running.set(true)
        store = BydCollectorApplication.store(applicationContext)
        settings = CollectorSettings(applicationContext, store)
        debugStorageReady = BydCollectorApplication.ensureDebugStorageReady(applicationContext)
        debugStore = DirectDebugStore(applicationContext, DirectDebugDatabaseHelper(applicationContext))
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
        mqttCoordinator = createMqttCoordinator(PahoMqttClientFacade())
        influxCoordinator = createInfluxCoordinator()
        telegramCoordinator = createTelegramCoordinator()
        normalizedStateChangedCallback = { changedCategories -> publishChangedCategoriesAsync(changedCategories) }
        poller = createTelemetryPoller()
        maintenanceCoordinator = DbMaintenanceCoordinator(
            context = applicationContext,
            settings = settings,
            storeProvider = { store },
            debugStoreProvider = { debugStore },
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
        if (settings.isAutoStartEnabled() && settings.hasActiveAccessWork()) {
            requestAccessSelfCheck("runtime_supervisor_start")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_SHUTDOWN) {
            shutdownByUser()
            return START_NOT_STICKY
        }
        if (settings.isUserShutdownRequested()) {
            suppressStartAfterUserShutdown(action)
            return START_NOT_STICKY
        }
        recoverInterruptedMaintenanceIfNeeded(action)
        if (
            maintenanceActive.get() &&
            activeMaintenanceOperation == DbMaintenanceOperation.ARCHIVE &&
            action != ACTION_CANCEL_DATABASE_MAINTENANCE
        ) {
            return START_STICKY
        }
        when (action) {
            ACTION_STOP -> {
                settings.setMainManuallyStopped(true)
                settings.setPollingEnabled(false)
                stopMain("polling_disabled")
                reconcileCollection()
            }
            ACTION_START_DEBUG -> {
                settings.setDebugManuallyStopped(false)
                settings.setDebugPollingEnabled(true)
                reconcileCollection(DEBUG_REASON_MANUAL)
            }
            ACTION_STOP_DEBUG -> {
                settings.setDebugManuallyStopped(true)
                settings.setDebugPollingEnabled(false)
                reconcileCollection()
            }
            ACTION_RECONCILE_KEEP_ALIVE -> reconcileKeepAliveOnly()
            ACTION_START_MQTT_EXPORT -> startMqttExport(clearManualStop = true)
            ACTION_STOP_MQTT_EXPORT -> stopMqttExport(manualStop = true)
            ACTION_START_INFLUX_EXPORT -> startInfluxExport(clearManualStop = true)
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
            ACTION_START -> reconcileCollection()
        }
        reconcilePendingCutoverArchiveStorage(action)
        reconcileAccessSelfCheckSchedule()
        return START_STICKY
    }

    override fun onDestroy() {
        requireRuntimeOwner()
        maintenanceRuntimeRestoreAllowed.set(false)
        mainHandler.removeCallbacks(accessSelfCheckTask)
        mainHandler.removeCallbacks(dashboardHeartbeatTask)
        mainHandler.removeCallbacks(kpiPublishTask)
        mainHandler.removeCallbacks(kpiStaleTask)
        cancelInfluxRetry()
        cancelTelegramTick()
        accessSelfCheckScheduled = false
        stopCollection("service_destroyed")
        keepAliveSupervisor.shutdown()
        debugStartExecutor.shutdownNow()
        maintenanceExecutor.shutdownNow()
        archiveStorageExecutor.shutdownNow()
        tailscaleExecutor.shutdownNow()
        dashboardMetricsExecutor.shutdownNow()
        dashboardCountExecutor.shutdownNow()
        shutdownMqttExecutor()
        shutdownInfluxExecutor()
        shutdownTelegramExecutor()
        activeMaintenanceOperation = null
        maintenanceActive.set(false)
        maintenanceRunningInProcess.set(false)
        mainPollingRunning.set(false)
        running.set(false)
        publishDashboardRuntimeFlags()
        clearDashboardVehicleKpis()
        if (::dashboardStateProvider.isInitialized) dashboardStateProvider.close()
        if (::debugStore.isInitialized) {
            debugStore.close()
        }
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

    private fun telemetryClient(): TelemetryClient {
        return DirectTelemetryClient(applicationContext)
    }

    private fun createTelemetryPoller(): TelemetryPoller {
        return TelemetryPoller(
            PollPersistenceCoordinator(
                store = store,
                client = telemetryClient(),
                //normalizes only after raw poll persistence so raw telemetry remains the source of truth
                successfulPollObserver = object : SuccessfulPollObserver {
                    override fun onSuccessfulPoll(
                        sessionId: Long,
                        pollId: Long,
                        timestamp: String,
                        readings: List<PollReading>
                    ) {
                        val observations = vehicleStateNormalizer.normalize(
                            pollId = pollId,
                            observedAt = timestamp,
                            readings = readings
                        )
                        val summary = store.applyNormalizedObservations(observations)
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
                        if (settings.isTelegramEnabled()) {
                            executeTelegram(
                                "telegram_event_error",
                                onSuccess = ::postTelegramTickSchedule
                            ) {
                                telegramCoordinator.onSuccessfulPoll(observations)
                            }
                        }
                        if (summary.changedCategories.isNotEmpty()) {
                            normalizedStateChangedCallback?.invoke(summary.changedCategories)
                        }
                        exportInfluxAfterNormalizedWrite(summary)
                    }
                }
            ),
            onCycleResult = { result -> handlePollCycleResult(result) }
        )
    }

    private fun reconcileCollection(debugStartReason: String = DEBUG_REASON_AUTOSTART) {
        if (maintenanceBlocksRuntimeStart()) return
        try {
            val mainEnabled = settings.isPollingEnabled()
            val debugEnabled = settings.isDebugPollingEnabled()
            val mainAllowed = mainEnabled && !settings.isMainManuallyStopped()
            val debugAllowed = debugEnabled && !settings.isDebugManuallyStopped()
            val keepAliveConfig = settings.keepAliveConfig()
            val keepAliveEnabled = keepAliveConfig.anyEnabled
            val telegramEnabled = settings.isTelegramEnabled()
            //stops the foreground service only after keep-alive settings have been mirrored to the shell delegate
            if (!mainAllowed && !debugAllowed && !keepAliveEnabled && !telegramEnabled) {
                store.recordEvent("service_start_skipped", "Polling, keep-alive, and Telegram disabled")
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
            keepAliveSupervisor.reconcile(keepAliveConfig)

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

            if (settings.isMqttAutoStartEnabled() && !settings.isMqttManuallyStopped()) startMqttExport(clearManualStop = false)
            if (
                (settings.isInfluxEnabled() || settings.isInfluxAutoStartEnabled()) &&
                !settings.isInfluxManuallyStopped()
            ) startInfluxExport(clearManualStop = false)
            if (telegramEnabled) reconcileTelegramRuntime()

            CollectorAutoStart.scheduleWatchdog(applicationContext, settings, store)
        } catch (error: RuntimeException) {
            handleStartFailure(error)
        }
    }

    private fun reconcileKeepAliveOnly() {
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
            keepAliveSupervisor.reconcile(keepAliveConfig)
            CollectorAutoStart.scheduleWatchdog(applicationContext, settings, store)
        } catch (error: RuntimeException) {
            handleStartFailure(error)
        }
    }

    private fun stopAfterKeepAliveReconcile(keepAliveConfig: KeepAliveConfig) {
        val stoppingText = "Stopping keep-alive"
        lastNotificationText = stoppingText
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(stoppingText)) }
        keepAliveSupervisor.reconcileThen(keepAliveConfig) {
            mainHandler.post {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun startMainIfNeeded() {
        if (maintenanceBlocksRuntimeStart()) return
        if (poller.isRunning()) {
            mainPollingRunning.set(true)
            publishDashboardRuntimeFlags()
            return
        }
        mqttRuntimeActive.set(false)
        mqttOfflineQueued.set(false)
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
        poller.start(openedSessionId)
        mainPollingRunning.set(true)
        publishDashboardRuntimeFlags()
        flushPendingMqttAsync(force = false)
    }

    private fun startDebugIfNeeded(reason: String) {
        if (maintenanceBlocksRuntimeStart(debugRuntime = true)) return
        if (!debugStorageReady) {
            updateNotification("Polling error: debug database cutover required")
            return
        }
        if (isDebugPollerRunning()) {
            debugRunning.set(true)
            publishDashboardRuntimeFlags()
            return
        }
        if (!debugStartInProgress.compareAndSet(false, true)) return
        debugStartExecutor.execute {
            try {
                val parameters = DirectDebugParameterAsset.load(applicationContext)
                val helper = DirectVehicleHelperClient()
                val launch = DirectBridgeManager.ensureRunning(
                    context = applicationContext,
                    adbClient = AdbLocalClient(File(applicationContext.filesDir, "adb_keys")),
                    helper = helper
                )
                if (!launch.ok) {
                    store.recordEvent("debug_polling_start_error", "Debug direct helper unavailable", launch.message)
                    updateNotification("Polling error: ${PollingErrorSummaries.summary(launch.message)}")
                    return@execute
                }
                if (!settings.isDebugPollingEnabled() || settings.isDebugManuallyStopped()) return@execute
                val batchSize = DirectDebugParameterAsset.TOTAL_PARAMETER_COUNT
                var lastDebugReadModeKey: String? = null
                val nextPoller = DirectDebugRoundRobinPoller(
                    parameters = parameters,
                    helper = helper,
                    store = debugStore,
                    onCycle = { summary ->
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
                            !settings.isDebugPollingEnabled() ||
                            settings.isDebugManuallyStopped() ||
                            maintenanceBlocksRuntimeStart(debugRuntime = true) ||
                            debugPoller?.isRunning() == true
                        ) {
                            false
                        } else {
                            nextPoller.start(batchSize)
                            debugPoller = nextPoller
                            debugRunning.set(true)
                            publishDashboardRuntimeFlags()
                            true
                        }
                    }
                }
                if (!started) {
                    nextPoller.shutdown("debug_start_cancelled")
                    return@execute
                }
                store.recordEvent(
                    "debug_polling_started",
                    "Debug round-robin polling started",
                    "reason=$reason batch_size=$batchSize parameters=${parameters.size}"
                )
            } catch (error: RuntimeException) {
                store.recordEvent(
                    "debug_polling_start_error",
                    "Debug round-robin startup failed",
                    "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                )
                updateNotification("Polling error: debug startup failed")
            } finally {
                debugStartInProgress.set(false)
            }
        }
    }

    private fun handleStartFailure(error: RuntimeException) {
        val detail = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
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
        cancelInfluxRetry()
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
        stopMain("user_shutdown")
        stopDebug("user_shutdown")
        disconnectOfflineAsync()
        cancelInfluxRetry()
        cancelTelegramTick()
    }

    private fun finishUserShutdown() {
        if (!userShutdownFinalizationStarted.compareAndSet(false, true)) return
        try {
            maintenanceExecutor.execute {
                val telegramWorker = shutdownTelegramExecutorForUserShutdown()
                val influxWorker = synchronized(influxExecutorLock) { influxExecutor }
                val influxStopped = awaitSerializedExecutorAction(
                    executor = influxWorker,
                    executorThreadName = "byd-influx",
                    timeoutMs = USER_SHUTDOWN_STOP_TIMEOUT_MS
                ) {
                    check(influxCoordinator.stopExport().ok) { "Influx stop failed" }
                }
                val telegramStopped = awaitExecutorTermination(
                    executor = telegramWorker,
                    executorThreadName = "byd-telegram",
                    timeoutMs = USER_SHUTDOWN_STOP_TIMEOUT_MS
                )
                if (!influxStopped || !telegramStopped) {
                    userShutdownFinalizationStarted.set(false)
                    store.recordEvent(
                        "user_shutdown_worker_stop_timeout",
                        "User shutdown left the service stopped but alive",
                        "influx_stopped=$influxStopped telegram_stopped=$telegramStopped"
                    )
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

    private fun stopServiceAfterUserShutdown() {
        keepAliveSupervisor.reconcileThen(KeepAliveConfig(false, false, false, false)) {
            mainHandler.post {
                releaseWakeLock()
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf()
            }
        }
    }

    private fun stopMain(reason: String) {
        val wasPolling = poller.isRunning()
        if (wasPolling) poller.stop()
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
        publishDashboardRuntimeFlags()
    }

    private fun exportInfluxAfterNormalizedWrite(summary: NormalizedWriteSummary) {
        //exports history after normalized changes because influx is the long-term time-series channel
        if (summary.historyInsertedCount <= 0) return
        if (!settings.isInfluxEnabled()) return
        requestInfluxCycle()
    }

    private fun requestInfluxCycle() {
        if (!settings.isInfluxEnabled() || maintenanceBlocksRuntimeStart()) return
        if (!influxRequestQueued.compareAndSet(false, true)) return
        val submittedGeneration = influxWorkGeneration.get()
        val accepted = executeInflux("influx_cycle_error") {
            try {
                influxCoordinator.runOneCycle(force = false)
            } finally {
                influxRequestQueued.set(false)
                postInfluxRetrySchedule(submittedGeneration)
            }
        }
        if (!accepted) influxRequestQueued.set(false)
    }

    private fun postInfluxRetrySchedule(submittedGeneration: Long) {
        if (submittedGeneration != influxWorkGeneration.get()) return
        val delayMs = runCatching { influxCoordinator.retryDelayMs() }.getOrNull()
        mainHandler.post {
            if (submittedGeneration != influxWorkGeneration.get()) return@post
            scheduleInfluxRetry(delayMs)
            stopIfNoActiveRuntime()
        }
    }

    private fun scheduleInfluxRetry(delayMs: Long?) {
        if (delayMs == null || !running.get() || !settings.isInfluxEnabled() || maintenanceBlocksRuntimeStart()) {
            cancelInfluxRetry()
            return
        }
        val targetElapsedMs = SystemClock.elapsedRealtime() + delayMs
        if (influxRetryScheduled && influxRetryAtElapsedMs?.let { it <= targetElapsedMs } == true) return
        if (influxRetryScheduled) mainHandler.removeCallbacks(influxRetryTask)
        influxRetryScheduled = true
        influxRetryAtElapsedMs = targetElapsedMs
        mainHandler.postDelayed(influxRetryTask, delayMs)
    }

    private fun cancelInfluxRetry() {
        mainHandler.removeCallbacks(influxRetryTask)
        influxRetryScheduled = false
        influxRetryAtElapsedMs = null
    }

    private fun stopDebug(reason: String) {
        detachDebugPoller()?.shutdown(reason)
        debugRunning.set(false)
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

    private fun publishDashboardRuntimeFlags() {
        if (!::dashboardUiStateStore.isInitialized || !::settings.isInitialized) return
        val access = AdbAuthorizationManager.currentSnapshot()
        dashboardUiStateStore.publishRuntimeFlags(
            DashboardRuntimeFlags(
                serviceRunning = running.get(),
                mainPollingRunning = mainPollingRunning.get(),
                debugPollingRunning = debugRunning.get(),
                pollingEnabled = settings.isPollingEnabled(),
                debugPollingEnabled = settings.isDebugPollingEnabled(),
                mqttEnabled = settings.isMqttEnabled(),
                influxEnabled = settings.isInfluxEnabled(),
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
        val mainStore = store
        val roundRobinStore = debugStore
        try {
            dashboardCountExecutor.execute {
                runCatching {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    val main = mainStore.dashboardRowCounts()
                    val debug = if (debugStorageReady) {
                        roundRobinStore.dashboardReadingCount()
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
                }.onSuccess { counts ->
                    if (generation == dashboardMetricsGeneration.get()) {
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
                    val debugBytes = sqliteFootprintBytes(applicationContext.getDatabasePath(DirectDebugDatabaseHelper.DATABASE_NAME))
                    if (generation == dashboardMetricsGeneration.get()) {
                        dashboardUiStateStore.publishDatabaseFootprints(mainBytes, debugBytes)
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
            mode = AccessCheckMode.NORMAL
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
        val openedSessionId: Long? = null
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
        if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE) return

        detached.openedSessionId?.let { openedSessionId ->
            runCatching { store.endSession(openedSessionId, "database_maintenance") }
        }
        mqttCoordinator.disconnectForMaintenance()
        resetMqttExecutorForMaintenance()
        resetInfluxExecutorForMaintenance()
        resetTelegramExecutorForMaintenance()
    }

    private fun prepareRuntimeStopForMaintenance(operation: DbMaintenanceOperation): DetachedMaintenanceRuntime {
        requireRuntimeOwner()
        if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE) {
            val detachedDebugPoller = detachDebugPoller()
            debugRunning.set(false)
            publishDashboardRuntimeFlags()
            return DetachedMaintenanceRuntime(debugPoller = detachedDebugPoller)
        }

        cancelInfluxRetry()
        cancelTelegramTick()
        poller.stop()
        val detachedDebugPoller = detachDebugPoller()
        val openedSessionId = sessionId
        sessionId = null
        mainPollingRunning.set(false)
        debugRunning.set(false)
        mqttRuntimeActive.set(false)
        mqttOfflineQueued.set(false)
        publishDashboardRuntimeFlags()
        return DetachedMaintenanceRuntime(
            mainPoller = poller,
            debugPoller = detachedDebugPoller,
            openedSessionId = openedSessionId
        )
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
        dashboardStateProvider.close()
        store = newStore
        settings = CollectorSettings(applicationContext, store)
        dashboardStateProvider = DashboardStateProvider(applicationContext, { store }, settings)
        keepAliveSupervisor.shutdown()
        keepAliveSupervisor = KeepAliveSupervisor(applicationContext, store)
        mqttCoordinator = createMqttCoordinator(PahoMqttClientFacade())
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
        if (maintenanceBlocksRuntimeStart()) return
        if (clearManualStop) settings.setMqttManuallyStopped(false)
        settings.setMqttEnabled(true)
        publishDashboardRuntimeFlags()
        ensureForegroundForChannel("MQTT export running")
        mqttRuntimeActive.set(true)
        executeMqtt("mqtt_start_error") {
            mqttCoordinator.startLiveExport()
        }
    }

    private fun stopMqttExport(manualStop: Boolean = true) {
        if (manualStop) settings.setMqttManuallyStopped(true)
        settings.setMqttEnabled(false)
        publishDashboardRuntimeFlags()
        scheduleIntegrationDashboardRefresh()
        disconnectOfflineAsync()
        mqttRuntimeActive.set(false)
        stopIfNoActiveRuntime()
    }

    private fun startInfluxExport(clearManualStop: Boolean = true) {
        if (maintenanceBlocksRuntimeStart()) return
        if (clearManualStop) settings.setInfluxManuallyStopped(false)
        settings.setInfluxEnabled(true)
        publishDashboardRuntimeFlags()
        ensureForegroundForChannel("Influx export running")
        val submittedGeneration = influxWorkGeneration.get()
        val accepted = executeInflux("influx_start_error") {
            try {
                if (clearManualStop) influxCoordinator.startExport() else influxCoordinator.resumeExport()
            } finally {
                postInfluxRetrySchedule(submittedGeneration)
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
        if (manualStop) settings.setInfluxManuallyStopped(true)
        settings.setInfluxEnabled(false)
        publishDashboardRuntimeFlags()
        scheduleIntegrationDashboardRefresh()
        queueInfluxStop(stopServiceWhenIdle = true)
    }

    private fun queueInfluxStop(stopServiceWhenIdle: Boolean = false) {
        cancelInfluxRetry()
        val accepted = executeInflux("influx_stop_error", activateTailscaleOnFailure = false) {
            try {
                influxCoordinator.stopExport()
            } finally {
                if (stopServiceWhenIdle) mainHandler.post { stopIfNoActiveRuntime() }
            }
        }
        if (!accepted && stopServiceWhenIdle) stopIfNoActiveRuntime()
    }

    private fun reconcileTelegramRuntime(unblockBlocked: Boolean = false) {
        if (maintenanceBlocksRuntimeStart()) return
        if (!settings.isTelegramEnabled()) {
            cancelTelegramTick()
            executeTelegram("telegram_reset_error") {
                telegramCoordinator.integrationDisabled()
            }
            stopIfNoActiveRuntime()
            return
        }
        ensureForegroundForChannel("Telegram notifications enabled")
        executeTelegram(
            "telegram_start_error",
            onSuccess = ::postTelegramTickSchedule
        ) {
            if (unblockBlocked) telegramCoordinator.credentialsChanged()
            telegramCoordinator.tick(
                mainCollectionExpected = settings.isPollingEnabled() && !settings.isMainManuallyStopped(),
                lastError = lastTelegramPollError
            )
        }
        scheduleTelegramTick()
        CollectorAutoStart.scheduleWatchdog(applicationContext, settings, store)
    }

    private fun testTelegramConnection() {
        ensureForegroundForChannel("Testing Telegram connection")
        executeTelegram("telegram_test_error") {
            telegramCoordinator.testConnection()
            mainHandler.post { stopIfNoActiveRuntime() }
        }
    }

    private fun scheduleTelegramTick(deadlineAtMs: Long? = null) {
        if (!running.get() || !settings.isTelegramEnabled() || maintenanceBlocksRuntimeStart()) return
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

    private fun stopIfNoActiveRuntime() {
        val mainRunning = poller.isRunning()
        val debugRunningNow = isDebugPollerRunning()
        val keepAliveEnabled = settings.keepAliveConfig().anyEnabled
        if (mainRunning || debugRunningNow || keepAliveEnabled || settings.isMqttEnabled() || settings.isTelegramEnabled() || archiveStorageActiveInProcess.get()) {
            return
        }
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
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
        if (!mqttRuntimeActive.get() && !settings.isMqttEnabled()) return
        if (!mqttOfflineQueued.compareAndSet(false, true)) return
        //uses a fresh executor so queued live publishes cannot run after the retained offline message
        val executor = resetMqttExecutorForOffline()
        try {
            executor.execute {
                try {
                    runCatching { oneShotMqttCoordinator().disconnectOffline() }
                        .onSuccess { result ->
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
                    scheduleIntegrationDashboardRefresh()
                }
            }
        } catch (error: RejectedExecutionException) {
            mqttOfflineQueued.set(false)
            store.recordEvent(
                "mqtt_offline_publish_error",
                "MQTT offline publish rejected",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }
    }

    private fun executeMqtt(
        errorCategory: String,
        activateTailscaleOnFailure: Boolean = true,
        action: () -> MqttActionResult
    ) = executeChannel(
        channelName = "MQTT",
        errorCategory = errorCategory,
        executorLock = mqttExecutorLock,
        executor = { mqttExecutor },
        generation = mqttWorkGeneration,
        action = action,
        onComplete = ::scheduleIntegrationDashboardRefresh,
        onFailedAction = if (activateTailscaleOnFailure) {
            { maybeActivateTailscaleAfterHaFailure("mqtt") }
        } else {
            null
        }
    ) { result ->
        ChannelActionStatus(result.ok, result.category, result.message)
    }

    private fun resetMqttExecutorForOffline(): ExecutorService {
        mqttWorkGeneration.incrementAndGet()
        return synchronized(mqttExecutorLock) {
            mqttExecutor.shutdownNow()
            namedSingleThreadExecutor("byd-mqtt").also { replacement ->
                mqttExecutor = replacement
            }
        }
    }

    private fun resetMqttExecutorForMaintenance() {
        val previous = runOnRuntimeOwnerBlocking {
            requireRuntimeOwner()
            mqttWorkGeneration.incrementAndGet()
            synchronized(mqttExecutorLock) {
                mqttExecutor.also { it.shutdownNow() }
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

    private fun shutdownMqttExecutor() {
        synchronized(mqttExecutorLock) {
            mqttExecutor.shutdown()
        }
    }

    private fun executeInflux(
        errorCategory: String,
        activateTailscaleOnFailure: Boolean = true,
        action: () -> InfluxActionResult
    ) = executeChannel(
        channelName = "Influx",
        errorCategory = errorCategory,
        executorLock = influxExecutorLock,
        executor = { influxExecutor },
        generation = influxWorkGeneration,
        lowPriority = true,
        canExecute = { !maintenanceBlocksRuntimeStart() },
        action = action,
        onComplete = ::scheduleIntegrationDashboardRefresh,
        onFailedAction = if (activateTailscaleOnFailure) {
            { maybeActivateTailscaleAfterHaFailure("influx") }
        } else {
            null
        }
    ) { result ->
        ChannelActionStatus(result.ok, result.category, result.message)
    }

    private fun <T> executeTelegram(
        errorCategory: String,
        onSuccess: ((T, Long) -> Unit)? = null,
        action: () -> T
    ) = executeChannel(
        channelName = "Telegram",
        errorCategory = errorCategory,
        executorLock = telegramExecutorLock,
        executor = { telegramExecutor },
        generation = telegramWorkGeneration,
        canExecute = { !maintenanceBlocksRuntimeStart() },
        action = action,
        onSuccess = onSuccess
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
        onSuccess: ((T, Long) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        status: (T) -> ChannelActionStatus
    ): Boolean {
        val submittedGeneration = generation.get()
        val selectedExecutor = synchronized(executorLock) { executor() }
        try {
            selectedExecutor.execute {
                if (lowPriority) Thread.currentThread().priority = Thread.MIN_PRIORITY
                //drops stale work submitted before a channel executor reset
                if (submittedGeneration != generation.get() || !canExecute()) return@execute
                try {
                    runCatching { action() }
                        .onSuccess { result ->
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
                            store.recordEvent(
                                errorCategory,
                                "$channelName async action failed",
                                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                            )
                        }
                } finally {
                    if (submittedGeneration == generation.get()) {
                        onComplete?.invoke()
                    }
                }
            }
            return true
        } catch (error: RejectedExecutionException) {
            store.recordEvent(
                errorCategory,
                "$channelName async action rejected",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
            onComplete?.invoke()
            return false
        }
    }

    private data class ChannelActionStatus(
        val ok: Boolean,
        val category: String,
        val message: String
    )

    private fun shutdownInfluxExecutor() {
        influxWorkGeneration.incrementAndGet()
        influxRequestQueued.set(false)
        synchronized(influxExecutorLock) {
            influxExecutor.shutdownNow()
        }
    }

    private fun resetInfluxExecutorForMaintenance() {
        val previous = runOnRuntimeOwnerBlocking {
            requireRuntimeOwner()
            influxWorkGeneration.incrementAndGet()
            influxRequestQueued.set(false)
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
        if (!stopped) maintenanceRuntimeRestoreAllowed.set(false)
        check(stopped) { "Influx worker did not stop before database maintenance" }
        runOnRuntimeOwnerBlocking {
            requireRuntimeOwner()
            synchronized(influxExecutorLock) {
                if (influxExecutor !== previous) {
                    maintenanceRuntimeRestoreAllowed.set(false)
                    error("Influx executor changed during database maintenance")
                }
                influxExecutor = namedSingleThreadExecutor("byd-influx")
            }
        }
    }

    private fun startDatabaseMaintenance(operation: DbMaintenanceOperation) {
        requireRuntimeOwner()
        if (!maintenanceActive.compareAndSet(false, true)) return
        activeMaintenanceOperation = operation
        maintenanceRuntimeRestoreAllowed.set(true)
        maintenanceRunningInProcess.set(true)
        if (operation == DbMaintenanceOperation.ARCHIVE) {
            CollectorAutoStart.cancelScheduled(applicationContext)
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
        try {
            if (
                running.get() &&
                restoreAfterMaintenance &&
                maintenanceRuntimeRestoreAllowed.get()
            ) {
                restoreRuntimeAfterMaintenance(operation, snapshot)
            }
        } finally {
            activeMaintenanceOperation = null
            maintenanceActive.set(false)
            maintenanceRunningInProcess.set(false)
        }
    }

    private fun resetTelegramExecutorForMaintenance() {
        val previous = runOnRuntimeOwnerBlocking {
            requireRuntimeOwner()
            telegramWorkGeneration.incrementAndGet()
            synchronized(telegramExecutorLock) {
                telegramExecutor.also { it.shutdownNow() }
            }
        }
        val stopped = try {
            previous.awaitTermination(TELEGRAM_MAINTENANCE_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!stopped) maintenanceRuntimeRestoreAllowed.set(false)
        check(stopped) { "Telegram worker did not stop before database maintenance" }
        runOnRuntimeOwnerBlocking {
            requireRuntimeOwner()
            synchronized(telegramExecutorLock) {
                if (telegramExecutor !== previous) {
                    maintenanceRuntimeRestoreAllowed.set(false)
                    error("Telegram executor changed during database maintenance")
                }
                telegramExecutor = namedSingleThreadExecutor("byd-telegram")
            }
        }
    }

    private fun shutdownTelegramExecutorForUserShutdown(): ExecutorService {
        telegramWorkGeneration.incrementAndGet()
        return synchronized(telegramExecutorLock) {
            telegramExecutor.also { it.shutdownNow() }
        }
    }

    private fun shutdownTelegramExecutor() {
        synchronized(telegramExecutorLock) {
            telegramExecutor.shutdown()
        }
    }

    private fun cancelDatabaseMaintenance() {
        maintenanceCoordinator.requestCancel()
    }

    private fun enqueueArchiveStorageMaintenance(preferredArchivePath: String?) {
        enqueueArchiveStorageWork("archive_storage_rejected") {
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
                        file.name.startsWith("${File(DirectDebugDatabaseHelper.DATABASE_NAME).nameWithoutExtension}_")
                    )
            }
            check(!rawArchiveRemains) { "Raw database archive compression remains pending" }
            manager.enforceRetention(limitBytes, ::publishArchiveStorageStatus)
            settings.setCutoverArchiveStoragePending(false)
        }
    }

    private fun reconcilePendingCutoverArchiveStorage(action: String) {
        if (!settings.isCutoverArchiveStoragePending()) return
        if (action in setOf(ACTION_ARCHIVE_DATABASE, ACTION_ARCHIVE_DEBUG_DATABASE, ACTION_DELETE_ARCHIVES)) return
        ensureForegroundForChannel("Archive storage")
        enqueueArchiveStorageMaintenance(null)
    }

    private fun enqueueArchiveDelete(ids: List<String>) {
        enqueueArchiveStorageWork("archive_storage_delete_rejected") {
            val manager = archiveStorageManager()
            val limitBytes = settings.archiveStorageLimitGb() * 1024L * 1024L * 1024L
            archiveShareLeaseRegistry.forceRelease(ids)
            manager.deleteArchiveIds(ids, ::publishArchiveStorageStatus)
            manager.enforceRetention(limitBytes, ::publishArchiveStorageStatus)
        }
    }

    private fun enqueueArchiveStorageWork(errorCategory: String, work: () -> Unit) {
        if (!archiveStorageActiveInProcess.compareAndSet(false, true)) return
        try {
            archiveStorageExecutor.execute {
                try {
                    runCatching { work() }
                        .onFailure { error ->
                            settings.setArchiveStorageJobStatus(
                                ArchiveStorageJobStatus(
                                    mode = ArchiveStorageJobMode.RETENTION,
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
                        }
                    if (settings.archiveStorageJobStatus().error == null) {
                        settings.clearArchiveStorageJobStatus()
                    }
                } finally {
                    archiveStorageActiveInProcess.set(false)
                    mainHandler.post { stopIfNoActiveRuntime() }
                }
            }
        } catch (error: RejectedExecutionException) {
            archiveStorageActiveInProcess.set(false)
            store.recordEvent(
                errorCategory,
                "Archive storage action rejected",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
            scheduleIntegrationDashboardRefresh()
        }
    }

    private fun archiveStorageManager(): ArchiveStorageManager {
        return ArchiveStorageManager(
            archiveRoot = File(applicationContext.filesDir, "db_archive"),
            mainDatabaseFile = store.databaseFile(),
            debugDatabaseFile = applicationContext.getDatabasePath(DirectDebugDatabaseHelper.DATABASE_NAME),
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

    private fun oneShotMqttCoordinator(): MqttPublishCoordinator {
        return createMqttCoordinator(PahoMqttClientFacade())
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
            client = HttpInfluxClient(),
            configProvider = { settings.influxConfig() }
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

    private fun createTelegramCoordinator(): TelegramCoordinator {
        return TelegramCoordinator(
            store = store,
            settings = settings
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
        val ACTION_STOP_DEBUG: String = "${BuildConfig.ACTION_PREFIX}.action.STOP_DEBUG"
        val ACTION_RECONCILE_KEEP_ALIVE: String = "${BuildConfig.ACTION_PREFIX}.action.RECONCILE_KEEP_ALIVE"
        val ACTION_START_MQTT_EXPORT: String = "${BuildConfig.ACTION_PREFIX}.action.START_MQTT_EXPORT"
        val ACTION_STOP_MQTT_EXPORT: String = "${BuildConfig.ACTION_PREFIX}.action.STOP_MQTT_EXPORT"
        val ACTION_START_INFLUX_EXPORT: String = "${BuildConfig.ACTION_PREFIX}.action.START_INFLUX_EXPORT"
        val ACTION_STOP_INFLUX_EXPORT: String = "${BuildConfig.ACTION_PREFIX}.action.STOP_INFLUX_EXPORT"
        val ACTION_RECONCILE_TELEGRAM: String = "${BuildConfig.ACTION_PREFIX}.action.RECONCILE_TELEGRAM"
        val ACTION_TEST_TELEGRAM: String = "${BuildConfig.ACTION_PREFIX}.action.TEST_TELEGRAM"
        val ACTION_ARCHIVE_DATABASE: String = "${BuildConfig.ACTION_PREFIX}.action.ARCHIVE_DATABASE"
        val ACTION_ARCHIVE_DEBUG_DATABASE: String = "${BuildConfig.ACTION_PREFIX}.action.ARCHIVE_DEBUG_DATABASE"
        val ACTION_CANCEL_DATABASE_MAINTENANCE: String = "${BuildConfig.ACTION_PREFIX}.action.CANCEL_DATABASE_MAINTENANCE"
        val ACTION_RECONCILE_ARCHIVE_STORAGE: String = "${BuildConfig.ACTION_PREFIX}.action.RECONCILE_ARCHIVE_STORAGE"
        val ACTION_DELETE_ARCHIVES: String = "${BuildConfig.ACTION_PREFIX}.action.DELETE_ARCHIVES"
        const val EXTRA_ARCHIVE_IDS = "archiveIds"
        private const val CHANNEL_ID = "collector"
        private const val NOTIFICATION_ID = 1001
        private const val STATUS_HEARTBEAT_INTERVAL_MS = 30_000L
        private const val DASHBOARD_RUNTIME_HEARTBEAT_MS = 2_000L
        private const val DATABASE_FOOTPRINT_INTERVAL_MS = 10_000L
        private const val KPI_PUBLISH_INTERVAL_MS = 1_000L
        private const val KPI_STALE_AFTER_MS = 3_000L
        private const val ACCESS_SELF_CHECK_INTERVAL_MS = 5 * 60_000L
        private const val TELEGRAM_TICK_INTERVAL_MS = 15_000L
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
        private val maintenanceRunningInProcess = AtomicBoolean(false)
        private val archiveStorageActiveInProcess = AtomicBoolean(false)
        val archiveShareLeaseRegistry = ArchiveShareLeaseRegistry(
            elapsedRealtimeMs = { SystemClock.elapsedRealtime() }
        )

        fun isRunning(): Boolean = running.get()
        fun isMainPollingRunning(): Boolean = mainPollingRunning.get()
        fun isDebugRunning(): Boolean = debugRunning.get()
        fun isMaintenanceRunningInProcess(): Boolean = maintenanceRunningInProcess.get()
        fun isArchiveStorageActive(): Boolean = archiveStorageActiveInProcess.get()

        fun startIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_START
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

        fun stopDebugIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_STOP_DEBUG
        }

        fun keepAliveIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_RECONCILE_KEEP_ALIVE
        }

        fun startMqttExportIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_START_MQTT_EXPORT
        }

        fun stopMqttExportIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_STOP_MQTT_EXPORT
        }

        fun startInfluxExportIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_START_INFLUX_EXPORT
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
