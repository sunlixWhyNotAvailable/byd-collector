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
import com.bydcollector.collector.util.namedSingleThreadExecutor
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
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
    private lateinit var maintenanceCoordinator: DbMaintenanceCoordinator
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionId: Long? = null
    private var debugPoller: DirectDebugRoundRobinPoller? = null
    private val debugPollerLock = Any()
    private var normalizedStateChangedCallback: ((Set<String>) -> Unit)? = null
    private val debugStartExecutor = namedSingleThreadExecutor("byd-debug-start")
    private val maintenanceExecutor = namedSingleThreadExecutor("byd-db-maintenance")
    private val archiveStorageExecutor = namedSingleThreadExecutor("byd-archive-storage")
    private val tailscaleExecutor = namedSingleThreadExecutor("byd-tailscale")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mqttExecutorLock = Any()
    private var mqttExecutor: ExecutorService = namedSingleThreadExecutor("byd-mqtt")
    private val influxExecutorLock = Any()
    private var influxExecutor: ExecutorService = namedSingleThreadExecutor("byd-influx")
    private val mqttWorkGeneration = AtomicLong(0L)
    private val influxWorkGeneration = AtomicLong(0L)
    private val debugStartInProgress = AtomicBoolean(false)
    private val mqttRuntimeActive = AtomicBoolean(false)
    private val mqttOfflineQueued = AtomicBoolean(false)
    private val maintenanceActive = AtomicBoolean(false)
    @Volatile
    private var activeMaintenanceOperation: DbMaintenanceOperation? = null
    private val archiveStorageActive = AtomicBoolean(false)
    private val tailscaleSequenceActive = AtomicBoolean(false)
    private val restoringRuntime = AtomicBoolean(false)
    private var lastStatusHeartbeatAtMs: Long = -STATUS_HEARTBEAT_INTERVAL_MS
    private var lastNotificationText: String? = null
    private var accessSelfCheckScheduled = false
    private val accessSelfCheckTask = object : Runnable {
        override fun run() {
            accessSelfCheckScheduled = false
            if (!settings.hasActiveAccessWork()) return
            requestAccessSelfCheck("runtime_watchdog")
            scheduleAccessSelfCheck()
        }
    }

    override fun onCreate() {
        super.onCreate()
        running.set(true)
        store = BydCollectorApplication.store(applicationContext)
        settings = CollectorSettings(applicationContext, store)
        debugStore = DirectDebugStore(applicationContext, DirectDebugDatabaseHelper(applicationContext))
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
        normalizedStateChangedCallback = { changedCategories -> publishChangedCategoriesAsync(changedCategories) }
        poller = createTelemetryPoller()
        maintenanceCoordinator = DbMaintenanceCoordinator(
            context = applicationContext,
            settings = settings,
            storeProvider = { store },
            debugStoreProvider = { debugStore },
            application = applicationContext as BydCollectorApplication,
            stopRuntime = { stopRuntimeForMaintenance(it) },
            onStoreReopened = { rebuildStoreBackedRuntime(it) },
            closeDebugStore = { debugStore.close() },
            onDebugStoreReopened = { debugStore = it }
        )
        createNotificationChannel()
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
        reconcileAccessSelfCheckSchedule()
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(accessSelfCheckTask)
        accessSelfCheckScheduled = false
        stopCollection("service_destroyed")
        keepAliveSupervisor.shutdown()
        debugStartExecutor.shutdownNow()
        maintenanceExecutor.shutdownNow()
        archiveStorageExecutor.shutdownNow()
        tailscaleExecutor.shutdownNow()
        shutdownMqttExecutor()
        shutdownInfluxExecutor()
        mainPollingRunning.set(false)
        running.set(false)
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
            //stops the foreground service only after keep-alive settings have been mirrored to the shell delegate
            if (!mainAllowed && !debugAllowed && !keepAliveEnabled) {
                store.recordEvent("service_start_skipped", "Polling and keep-alive disabled")
                stopMain("polling_disabled")
                stopDebug("debug_disabled")
                stopAfterKeepAliveReconcile(keepAliveConfig)
                return
            }
            val initialNotificationText = notificationText(mainAllowed, debugAllowed, keepAliveEnabled)
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
            if (settings.isInfluxAutoStartEnabled() && !settings.isInfluxManuallyStopped()) startInfluxExport(clearManualStop = false)

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

            val notification = notificationText(mainRunning, debugRunning, keepAliveEnabled)
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
            return
        }
        mqttRuntimeActive.set(false)
        mqttOfflineQueued.set(false)
        val openedSessionId = store.openSession()
        sessionId = openedSessionId
        try {
            //imports the car energy database opportunistically; telemetry polling must survive import failure
            store.importEcDatabaseAtSessionStart(openedSessionId)
        } catch (error: RuntimeException) {
            store.recordEvent(
                "ec_import_error",
                "EC_database.db import failed before polling",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }
        poller.start(openedSessionId)
        mainPollingRunning.set(true)
        flushPendingMqttAsync(force = false)
    }

    private fun startDebugIfNeeded(reason: String) {
        if (maintenanceBlocksRuntimeStart(debugRuntime = true)) return
        if (isDebugPollerRunning()) return
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
                val requestedBatchSize = settings.debugBatchSize()
                //caps automatic debug work so a reboot does not immediately start a huge round-robin load
                val batchSize = if (reason == DEBUG_REASON_MANUAL) {
                    requestedBatchSize
                } else {
                    settings.debugAutostartBatchSize()
                }
                if (batchSize != requestedBatchSize) {
                    store.recordEvent(
                        "debug_autostart_batch_clamped",
                        "Debug autostart batch clamped",
                        "reason=$reason requested=$requestedBatchSize effective=$batchSize"
                    )
                }
                var lastDebugReadModeKey: String? = null
                val nextPoller = DirectDebugRoundRobinPoller(
                    parameters = parameters,
                    helper = helper,
                    store = debugStore,
                    onCycle = { summary ->
                        debugRunning.set(true)
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
                            updateNotification(notificationText(settings.isPollingEnabled(), true, settings.keepAliveConfig().anyEnabled))
                        }
                    }
                )
                val started = synchronized(debugPollerLock) {
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
                        true
                    }
                }
                if (!started) {
                    nextPoller.shutdown("debug_start_cancelled")
                    return@execute
                }
                debugRunning.set(true)
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
        stopServiceAfterUserShutdown()
    }

    private fun suppressStartAfterUserShutdown(action: String) {
        store.recordEvent(
            "user_shutdown_start_suppressed",
            "Service start suppressed after user shutdown",
            "action=$action"
        )
        if (deferStopForActiveMaintenance("suppressed_action=$action")) return
        stopRuntimeForUserShutdown()
        stopServiceAfterUserShutdown()
    }

    private fun deferStopForActiveMaintenance(reason: String): Boolean {
        if (!maintenanceActive.get()) return false
        CollectorAutoStart.cancelScheduled(applicationContext)
        settings.setPollingEnabled(false)
        settings.setDebugPollingEnabled(false)
        settings.setMqttEnabled(false)
        settings.setInfluxEnabled(false)
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
        runCatching { influxCoordinator.stopExport() }
        resetInfluxExecutorForMaintenance()
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
    }

    private fun exportInfluxAfterNormalizedWrite(summary: NormalizedWriteSummary) {
        //exports history after normalized changes because influx is the long-term time-series channel
        if (summary.historyInsertedCount <= 0) return
        if (!settings.isInfluxEnabled()) return
        executeInflux("influx_cycle_error") {
            influxCoordinator.runOneCycle(force = false)
        }
    }

    private fun stopDebug(reason: String) {
        detachDebugPoller()?.shutdown(reason)
        debugRunning.set(false)
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
        val debugRunning: Boolean
    )

    private fun runtimeSnapshot(): RuntimeSnapshot {
        return RuntimeSnapshot(
            mainEnabled = settings.isPollingEnabled(),
            debugEnabled = settings.isDebugPollingEnabled(),
            mqttEnabled = settings.isMqttEnabled(),
            influxEnabled = settings.isInfluxEnabled(),
            debugRunning = isDebugPollerRunning()
        )
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

    private fun stopRuntimeForMaintenance(operation: DbMaintenanceOperation) {
        if (operation == DbMaintenanceOperation.DEBUG_ARCHIVE) {
            if (detachDebugPoller()?.shutdownAndAwait("debug_database_maintenance", 2_000L) == false) {
                error("Debug poller did not stop for database maintenance")
            }
            debugRunning.set(false)
            return
        }
        if (!poller.stopAndJoin(2_000L)) error("Main poller did not stop for database maintenance")
        if (detachDebugPoller()?.shutdownAndAwait("database_maintenance", 2_000L) == false) {
            error("Debug poller did not stop for database maintenance")
        }
        mainPollingRunning.set(false)
        debugRunning.set(false)
        sessionId?.let { openedSessionId ->
            runCatching { store.endSession(openedSessionId, "database_maintenance") }
        }
        sessionId = null
        mqttRuntimeActive.set(false)
        mqttOfflineQueued.set(false)
        mqttCoordinator.disconnectForMaintenance()
        resetMqttExecutorForMaintenance()
        resetInfluxExecutorForMaintenance()
    }

    private fun restoreRuntimeAfterMaintenance(operation: DbMaintenanceOperation, snapshot: RuntimeSnapshot) {
        restoringRuntime.set(true)
        try {
            if (settings.isUserShutdownRequested()) {
                settings.setPollingEnabled(false)
                settings.setDebugPollingEnabled(false)
                settings.setMqttEnabled(false)
                settings.setInfluxEnabled(false)
                stopServiceAfterUserShutdown()
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
            if (snapshot.mainEnabled || snapshot.debugEnabled || settings.keepAliveConfig().anyEnabled) {
                reconcileCollection()
            }
            if (snapshot.mqttEnabled && !settings.isMqttManuallyStopped()) startMqttExport(clearManualStop = false)
            if (snapshot.influxEnabled && !settings.isInfluxManuallyStopped()) startInfluxExport(clearManualStop = false)
            if (!snapshot.mqttEnabled && !snapshot.influxEnabled) stopIfNoActiveRuntime()
        } finally {
            restoringRuntime.set(false)
        }
    }

    private fun rebuildStoreBackedRuntime(newStore: TelemetryStore) {
        store = newStore
        settings = CollectorSettings(applicationContext, store)
        keepAliveSupervisor.shutdown()
        keepAliveSupervisor = KeepAliveSupervisor(applicationContext, store)
        mqttCoordinator = createMqttCoordinator(PahoMqttClientFacade())
        influxCoordinator = createInfluxCoordinator()
        poller = createTelemetryPoller()
    }

    private fun acquireWakeLock() {
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
        val current = wakeLock
        if (current?.isHeld == true) {
            current.release()
            store.recordEvent("wake_lock_released", "Collector partial wake lock released")
        }
        wakeLock = null
    }

    private fun handlePollCycleResult(result: com.bydcollector.collector.data.polling.PollCycleResult) {
        val text = if (result.ok) {
            notificationText(
                mainEnabled = true,
                debugEnabled = isDebugPollerRunning(),
                keepAliveEnabled = settings.keepAliveConfig().anyEnabled
            )
        } else {
            "Polling error: ${PollingErrorSummaries.summary(result.category)}"
        }
        updateNotification(text)
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
        ensureForegroundForChannel("MQTT export running")
        mqttRuntimeActive.set(true)
        executeMqtt("mqtt_start_error") {
            mqttCoordinator.startLiveExport()
        }
    }

    private fun stopMqttExport(manualStop: Boolean = true) {
        if (manualStop) settings.setMqttManuallyStopped(true)
        settings.setMqttEnabled(false)
        disconnectOfflineAsync()
        mqttRuntimeActive.set(false)
        stopIfNoActiveRuntime()
    }

    private fun startInfluxExport(clearManualStop: Boolean = true) {
        if (maintenanceBlocksRuntimeStart()) return
        if (clearManualStop) settings.setInfluxManuallyStopped(false)
        settings.setInfluxEnabled(true)
        ensureForegroundForChannel("Influx export running")
        executeInflux("influx_start_error") {
            influxCoordinator.startExport()
        }
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
                        launch = { TailscaleActivator.launchIfNeeded(applicationContext) },
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
        executeInflux("influx_stop_error", activateTailscaleOnFailure = false) {
            influxCoordinator.stopExport()
        }
        stopIfNoActiveRuntime()
    }

    private fun stopIfNoActiveRuntime() {
        val mainRunning = poller.isRunning()
        val debugRunningNow = isDebugPollerRunning()
        val keepAliveEnabled = settings.keepAliveConfig().anyEnabled
        if (mainRunning || debugRunningNow || keepAliveEnabled || settings.isMqttEnabled() || settings.isInfluxEnabled() || archiveStorageActive.get()) {
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
        val health = store.healthSnapshot(running = poller.isRunning())
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
            }
        } catch (error: RejectedExecutionException) {
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
        mqttWorkGeneration.incrementAndGet()
        synchronized(mqttExecutorLock) {
            mqttExecutor.shutdownNow()
            mqttExecutor = namedSingleThreadExecutor("byd-mqtt")
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
        action = action,
        onFailedAction = if (activateTailscaleOnFailure) {
            { maybeActivateTailscaleAfterHaFailure("influx") }
        } else {
            null
        }
    ) { result ->
        ChannelActionStatus(result.ok, result.category, result.message)
    }

    private fun <T> executeChannel(
        channelName: String,
        errorCategory: String,
        executorLock: Any,
        executor: () -> ExecutorService,
        generation: AtomicLong,
        lowPriority: Boolean = false,
        action: () -> T,
        onFailedAction: (() -> Unit)? = null,
        status: (T) -> ChannelActionStatus
    ) {
        val submittedGeneration = generation.get()
        val selectedExecutor = synchronized(executorLock) { executor() }
        try {
            selectedExecutor.execute {
                if (lowPriority) Thread.currentThread().priority = Thread.MIN_PRIORITY
                //drops stale work submitted before a channel executor reset
                if (submittedGeneration != generation.get()) return@execute
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
                    }
                    .onFailure { error ->
                        store.recordEvent(
                            errorCategory,
                            "$channelName async action failed",
                            "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                        )
                    }
            }
        } catch (error: RejectedExecutionException) {
            store.recordEvent(
                errorCategory,
                "$channelName async action rejected",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }
    }

    private data class ChannelActionStatus(
        val ok: Boolean,
        val category: String,
        val message: String
    )

    private fun shutdownInfluxExecutor() {
        synchronized(influxExecutorLock) {
            influxExecutor.shutdown()
        }
    }

    private fun resetInfluxExecutorForMaintenance() {
        influxWorkGeneration.incrementAndGet()
        synchronized(influxExecutorLock) {
            influxExecutor.shutdownNow()
            influxExecutor = namedSingleThreadExecutor("byd-influx")
        }
    }

    private fun startDatabaseMaintenance(operation: DbMaintenanceOperation) {
        if (!maintenanceActive.compareAndSet(false, true)) return
        activeMaintenanceOperation = operation
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
                        restoreAfterMaintenance = true
                    }
                    if (result.ok && result.archivePath != null) {
                        enqueueArchiveStorageMaintenance(result.archivePath)
                    }
                } finally {
                    activeMaintenanceOperation = null
                    maintenanceActive.set(false)
                    maintenanceRunningInProcess.set(false)
                    if (restoreAfterMaintenance) {
                        restoreRuntimeAfterMaintenance(operation, snapshot)
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            activeMaintenanceOperation = null
            maintenanceActive.set(false)
            maintenanceRunningInProcess.set(false)
            store.recordEvent(
                "database_maintenance_rejected",
                "Database maintenance action rejected",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
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
            manager.enforceRetention(limitBytes, ::publishArchiveStorageStatus)
        }
    }

    private fun enqueueArchiveDelete(ids: List<String>) {
        enqueueArchiveStorageWork("archive_storage_delete_rejected") {
            val manager = archiveStorageManager()
            val limitBytes = settings.archiveStorageLimitGb() * 1024L * 1024L * 1024L
            manager.deleteArchiveIds(ids, ::publishArchiveStorageStatus)
            manager.enforceRetention(limitBytes, ::publishArchiveStorageStatus)
        }
    }

    private fun enqueueArchiveStorageWork(errorCategory: String, work: () -> Unit) {
        if (!archiveStorageActive.compareAndSet(false, true)) return
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
                    archiveStorageActive.set(false)
                    mainHandler.post { stopIfNoActiveRuntime() }
                }
            }
        } catch (error: RejectedExecutionException) {
            archiveStorageActive.set(false)
            store.recordEvent(
                errorCategory,
                "Archive storage action rejected",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }
    }

    private fun archiveStorageManager(): ArchiveStorageManager {
        return ArchiveStorageManager(
            archiveRoot = File(applicationContext.filesDir, "db_archive"),
            mainDatabaseFile = store.databaseFile(),
            debugDatabaseFile = applicationContext.getDatabasePath(DirectDebugDatabaseHelper.DATABASE_NAME)
        )
    }

    private fun publishArchiveStorageStatus(status: ArchiveStorageJobStatus) {
        settings.setArchiveStorageJobStatus(status, synchronous = true)
    }

    private fun maintenanceBlocksRuntimeStart(debugRuntime: Boolean = false): Boolean {
        if (restoringRuntime.get()) return false
        if (maintenanceActive.get()) {
            return activeMaintenanceOperation != DbMaintenanceOperation.DEBUG_ARCHIVE || debugRuntime
        }
        if (settings.dbMaintenanceStatus().running) {
            settings.recoverInterruptedDbMaintenanceIfNeeded("runtime_start_guard")
            return false
        }
        return false
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

    private fun notificationText(mainEnabled: Boolean, debugEnabled: Boolean, keepAliveEnabled: Boolean): String {
        return when {
            mainEnabled && debugEnabled && keepAliveEnabled -> "Collector running + debug + keep-alive"
            mainEnabled && keepAliveEnabled -> "Collector running + keep-alive"
            debugEnabled && keepAliveEnabled -> "Debug polling running + keep-alive"
            keepAliveEnabled -> "Keep-alive running"
            mainEnabled && debugEnabled -> "Collector running + debug"
            debugEnabled -> "Debug polling running"
            else -> "Collector running"
        }
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
        val ACTION_ARCHIVE_DATABASE: String = "${BuildConfig.ACTION_PREFIX}.action.ARCHIVE_DATABASE"
        val ACTION_ARCHIVE_DEBUG_DATABASE: String = "${BuildConfig.ACTION_PREFIX}.action.ARCHIVE_DEBUG_DATABASE"
        val ACTION_CANCEL_DATABASE_MAINTENANCE: String = "${BuildConfig.ACTION_PREFIX}.action.CANCEL_DATABASE_MAINTENANCE"
        val ACTION_RECONCILE_ARCHIVE_STORAGE: String = "${BuildConfig.ACTION_PREFIX}.action.RECONCILE_ARCHIVE_STORAGE"
        val ACTION_DELETE_ARCHIVES: String = "${BuildConfig.ACTION_PREFIX}.action.DELETE_ARCHIVES"
        const val EXTRA_ARCHIVE_IDS = "archiveIds"
        private const val CHANNEL_ID = "collector"
        private const val NOTIFICATION_ID = 1001
        private const val STATUS_HEARTBEAT_INTERVAL_MS = 30_000L
        private const val ACCESS_SELF_CHECK_INTERVAL_MS = 5 * 60_000L
        private const val TAG = "BYDCollectorService"
        private const val DEBUG_REASON_AUTOSTART = "autostart"
        private const val DEBUG_REASON_MANUAL = "manual"
        private val running = AtomicBoolean(false)
        private val mainPollingRunning = AtomicBoolean(false)
        private val debugRunning = AtomicBoolean(false)
        private val maintenanceRunningInProcess = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()
        fun isMainPollingRunning(): Boolean = mainPollingRunning.get()
        fun isDebugRunning(): Boolean = debugRunning.get()
        fun isMaintenanceRunningInProcess(): Boolean = maintenanceRunningInProcess.get()

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
