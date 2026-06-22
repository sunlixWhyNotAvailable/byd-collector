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
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.data.debug.DirectDebugDatabaseHelper
import com.bydcollector.collector.data.debug.DirectDebugParameterAsset
import com.bydcollector.collector.data.debug.DirectDebugRoundRobinPoller
import com.bydcollector.collector.data.debug.DirectDebugStore
import com.bydcollector.collector.data.direct.DirectVehicleHelperClient
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.normalized.NormalizedWriteSummary
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.polling.PollPersistenceCoordinator
import com.bydcollector.collector.data.polling.SuccessfulPollObserver
import com.bydcollector.collector.data.polling.TelemetryPoller
import com.bydcollector.collector.data.local.PollReading
import com.bydcollector.collector.data.remote.DiPlusClient
import com.bydcollector.collector.data.remote.DiPlusResult
import com.bydcollector.collector.data.remote.DirectTelemetryClient
import com.bydcollector.collector.data.remote.DirectBridgeManager
import com.bydcollector.collector.keepalive.KeepAliveConfig
import com.bydcollector.collector.keepalive.KeepAliveSupervisor
import com.bydcollector.collector.influx.HttpInfluxClient
import com.bydcollector.collector.influx.InfluxActionResult
import com.bydcollector.collector.influx.InfluxExportCoordinator
import com.bydcollector.collector.mqtt.HaMqttConfig
import com.bydcollector.collector.mqtt.HaMqttMessageFactory
import com.bydcollector.collector.mqtt.HaMqttStatus
import com.bydcollector.collector.mqtt.MqttActionResult
import com.bydcollector.collector.mqtt.MqttClientFacade
import com.bydcollector.collector.mqtt.MqttPublishCoordinator
import com.bydcollector.collector.mqtt.PahoMqttClientFacade
import com.bydcollector.collector.system.CollectorAutoStart
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CollectorService : Service() {
    private lateinit var store: TelemetryStore
    private lateinit var settings: CollectorSettings
    private lateinit var poller: TelemetryPoller
    private lateinit var debugStore: DirectDebugStore
    private lateinit var keepAliveSupervisor: KeepAliveSupervisor
    private lateinit var vehicleStateNormalizer: VehicleStateNormalizer
    private lateinit var mqttCoordinator: MqttPublishCoordinator
    private lateinit var influxCoordinator: InfluxExportCoordinator
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionId: Long? = null
    private var debugPoller: DirectDebugRoundRobinPoller? = null
    private var normalizedStateChangedCallback: ((Set<String>) -> Unit)? = null
    private val debugStartExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mqttExecutorLock = Any()
    private var mqttExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val influxExecutorLock = Any()
    private var influxExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mqttWorkGeneration = AtomicLong(0L)
    private val influxWorkGeneration = AtomicLong(0L)
    private val debugStartInProgress = AtomicBoolean(false)
    private val mqttRuntimeActive = AtomicBoolean(false)
    private val mqttOfflineQueued = AtomicBoolean(false)
    private var lastStatusHeartbeatAtMs: Long = -STATUS_HEARTBEAT_INTERVAL_MS
    private var lastNotificationText: String? = null

    override fun onCreate() {
        super.onCreate()
        running.set(true)
        val helper = TelemetryDatabaseHelper(applicationContext)
        store = TelemetryStore(applicationContext, helper)
        settings = CollectorSettings(applicationContext, store)
        debugStore = DirectDebugStore(applicationContext, DirectDebugDatabaseHelper(applicationContext))
        keepAliveSupervisor = KeepAliveSupervisor(applicationContext, store)
        vehicleStateNormalizer = VehicleStateNormalizer()
        mqttCoordinator = createMqttCoordinator(PahoMqttClientFacade())
        influxCoordinator = createInfluxCoordinator()
        normalizedStateChangedCallback = { changedCategories -> publishChangedCategoriesAsync(changedCategories) }
        poller = TelemetryPoller(
            PollPersistenceCoordinator(
                store = store,
                client = telemetryClient(),
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
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                settings.setPollingEnabled(false)
                reconcileCollection()
            }
            ACTION_START_DEBUG -> {
                settings.setDebugPollingEnabled(true)
                reconcileCollection(DEBUG_REASON_MANUAL)
            }
            ACTION_STOP_DEBUG -> {
                settings.setDebugPollingEnabled(false)
                settings.setDebugAutoStartEnabled(false)
                reconcileCollection()
            }
            ACTION_RECONCILE_KEEP_ALIVE -> reconcileKeepAliveOnly()
            ACTION_START_MQTT_EXPORT -> startMqttExport()
            ACTION_STOP_MQTT_EXPORT -> stopMqttExport()
            ACTION_START_INFLUX_EXPORT -> startInfluxExport()
            ACTION_STOP_INFLUX_EXPORT -> stopInfluxExport()
            ACTION_START -> reconcileCollection()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCollection("service_destroyed")
        keepAliveSupervisor.shutdown()
        debugStartExecutor.shutdownNow()
        shutdownMqttExecutor()
        shutdownInfluxExecutor()
        mainPollingRunning.set(false)
        running.set(false)
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

    private fun telemetryClient(): DiPlusClient {
        return DirectTelemetryClient(applicationContext)
    }

    private fun reconcileCollection(debugStartReason: String = DEBUG_REASON_AUTOSTART) {
        try {
            val mainEnabled = settings.isPollingEnabled()
            val debugEnabled = BuildConfig.ENABLE_DIRECT_DEBUG_ROUND_ROBIN && settings.isDebugPollingEnabled()
            val keepAliveConfig = settings.keepAliveConfig()
            val keepAliveEnabled = keepAliveConfig.anyEnabled
            if (!mainEnabled && !debugEnabled && !keepAliveEnabled) {
                store.recordEvent("service_start_skipped", "Polling and keep-alive disabled")
                stopMain("polling_disabled")
                stopDebug("debug_disabled")
                stopAfterKeepAliveReconcile(keepAliveConfig)
                return
            }
            val initialNotificationText = notificationText(mainEnabled, debugEnabled, keepAliveEnabled)
            lastNotificationText = initialNotificationText
            startForeground(NOTIFICATION_ID, buildNotification(initialNotificationText))
            acquireWakeLock()
            keepAliveSupervisor.reconcile(keepAliveConfig)

            if (mainEnabled) {
                startMainIfNeeded()
            } else {
                stopMain("polling_disabled")
            }

            if (debugEnabled) {
                startDebugIfNeeded(debugStartReason)
            } else {
                stopDebug("debug_disabled")
            }

            if (settings.isMqttAutoStartEnabled()) startMqttExport()
            if (settings.isInfluxAutoStartEnabled()) startInfluxExport()

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
            val debugRunning = debugPoller?.isRunning() == true
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
        if (poller.isRunning()) {
            mainPollingRunning.set(true)
            return
        }
        mqttRuntimeActive.set(false)
        mqttOfflineQueued.set(false)
        val openedSessionId = store.openSession()
        sessionId = openedSessionId
        try {
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
        if (debugPoller?.isRunning() == true) return
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
                    updateNotification("Polling error: ${pollingErrorSummary(launch.message)}")
                    return@execute
                }
                if (!settings.isDebugPollingEnabled()) return@execute
                val requestedBatchSize = settings.debugBatchSize()
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
                val nextPoller = DirectDebugRoundRobinPoller(
                    parameters = parameters,
                    helper = helper,
                    store = debugStore,
                    onCycle = { summary ->
                        debugRunning.set(true)
                        if (summary.errorCount > 0 && summary.okCount == 0) {
                            updateNotification("Polling error: debug helper returned errors")
                        } else {
                            updateNotification(notificationText(settings.isPollingEnabled(), true, settings.keepAliveConfig().anyEnabled))
                        }
                    }
                )
                debugPoller = nextPoller
                nextPoller.start(batchSize)
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
        lastNotificationText = "Polling error: service start failed"
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
            disconnectOfflineAsync()
        }
    }

    private fun exportInfluxAfterNormalizedWrite(summary: NormalizedWriteSummary) {
        if (summary.historyInsertedCount <= 0) return
        if (!settings.isInfluxEnabled()) return
        executeInflux("influx_cycle_error") {
            influxCoordinator.runOneCycle(force = false)
        }
    }

    private fun stopDebug(reason: String) {
        debugPoller?.shutdown(reason)
        debugPoller = null
        debugRunning.set(false)
    }

    private fun acquireWakeLock() {
        val current = wakeLock
        if (current?.isHeld == true) return

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
                debugEnabled = debugPoller?.isRunning() == true,
                keepAliveEnabled = settings.keepAliveConfig().anyEnabled
            )
        } else {
            "Polling error: ${pollingErrorSummary(result.category)}"
        }
        updateNotification(text)
        publishStatusHeartbeat(result, force = !result.ok)
    }

    private fun publishChangedCategoriesAsync(categories: Set<String>) {
        if (categories.isEmpty()) return
        if (!settings.isMqttEnabled()) return
        mqttRuntimeActive.set(true)
        executeMqtt("mqtt_changed_publish_error") {
            mqttCoordinator.queueChangedCategoriesAndFlush(categories)
        }
    }

    private fun startMqttExport() {
        settings.setMqttEnabled(true)
        ensureForegroundForChannel("MQTT export running")
        mqttRuntimeActive.set(true)
        executeMqtt("mqtt_start_error") {
            mqttCoordinator.startLiveExport()
        }
    }

    private fun stopMqttExport() {
        settings.setMqttEnabled(false)
        disconnectOfflineAsync()
        mqttRuntimeActive.set(false)
        stopIfNoActiveRuntime()
    }

    private fun startInfluxExport() {
        settings.setInfluxEnabled(true)
        ensureForegroundForChannel("Influx export running")
        executeInflux("influx_start_error") {
            influxCoordinator.startExport()
        }
    }

    private fun ensureForegroundForChannel(text: String) {
        lastNotificationText = text
        startForeground(NOTIFICATION_ID, buildNotification(text))
        acquireWakeLock()
    }

    private fun stopInfluxExport() {
        settings.setInfluxEnabled(false)
        executeInflux("influx_stop_error") {
            influxCoordinator.stopExport()
        }
        stopIfNoActiveRuntime()
    }

    private fun stopIfNoActiveRuntime() {
        val mainRunning = poller.isRunning()
        val debugRunningNow = debugPoller?.isRunning() == true
        val keepAliveEnabled = settings.keepAliveConfig().anyEnabled
        if (mainRunning || debugRunningNow || keepAliveEnabled || settings.isMqttEnabled() || settings.isInfluxEnabled()) {
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
            adb = if (AdbAuthorizationManager.isAdbGranted(applicationContext)) "authorized" else "not_authorized",
            helper = DirectBridgeManager.status(applicationContext) ?: "unknown",
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
        action: () -> MqttActionResult
    ) {
        val generation = mqttWorkGeneration.get()
        val executor = synchronized(mqttExecutorLock) { mqttExecutor }
        try {
            executor.execute {
                if (generation != mqttWorkGeneration.get()) return@execute
                runCatching { action() }
                    .onSuccess { result ->
                        if (!result.ok) {
                            store.recordEvent(
                                errorCategory,
                                "MQTT async action failed",
                                "${result.category}: ${result.message}"
                            )
                        }
                    }
                    .onFailure { error ->
                        store.recordEvent(
                            errorCategory,
                            "MQTT async action failed",
                            "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                        )
                    }
            }
        } catch (error: RejectedExecutionException) {
            store.recordEvent(
                errorCategory,
                "MQTT async action rejected",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }
    }

    private fun resetMqttExecutorForOffline(): ExecutorService {
        mqttWorkGeneration.incrementAndGet()
        return synchronized(mqttExecutorLock) {
            mqttExecutor.shutdownNow()
            Executors.newSingleThreadExecutor().also { replacement ->
                mqttExecutor = replacement
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
        action: () -> InfluxActionResult
    ) {
        val generation = influxWorkGeneration.get()
        val executor = synchronized(influxExecutorLock) { influxExecutor }
        try {
            executor.execute {
                Thread.currentThread().priority = Thread.MIN_PRIORITY
                if (generation != influxWorkGeneration.get()) return@execute
                runCatching { action() }
                    .onSuccess { result ->
                        if (!result.ok) {
                            store.recordEvent(
                                errorCategory,
                                "Influx async action failed",
                                "${result.category}: ${result.message}"
                            )
                        }
                    }
                    .onFailure { error ->
                        store.recordEvent(
                            errorCategory,
                            "Influx async action failed",
                            "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                        )
                    }
            }
        } catch (error: RejectedExecutionException) {
            store.recordEvent(
                errorCategory,
                "Influx async action rejected",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }
    }

    private fun shutdownInfluxExecutor() {
        synchronized(influxExecutorLock) {
            influxExecutor.shutdown()
        }
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

    private fun pollingErrorSummary(category: String?): String {
        return when (category) {
            DiPlusResult.NETWORK_ERROR, DiPlusResult.TIMEOUT -> "Direct telemetry unavailable"
            DiPlusResult.HTTP_ERROR, DiPlusResult.DI_SUCCESS_FALSE, DiPlusResult.PARSE_ERROR -> "Direct telemetry error"
            "adb_authorization_required" -> "ADB not authorized"
            "adb_authorization_unavailable" -> "ADB unavailable"
            "adb_authorization_timeout" -> "ADB authorization timeout"
            "bridge_launch_failed", "bridge_unavailable",
            "helper_launch_failed", "helper_unavailable", "helper_launch_backoff",
            "autoservice_snapshot_empty" -> "Direct helper unavailable"
            "service_start_error" -> "service start failed"
            else -> category ?: "unknown"
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
        val ACTION_STOP: String = "${BuildConfig.ACTION_PREFIX}.action.STOP"
        val ACTION_START_DEBUG: String = "${BuildConfig.ACTION_PREFIX}.action.START_DEBUG"
        val ACTION_STOP_DEBUG: String = "${BuildConfig.ACTION_PREFIX}.action.STOP_DEBUG"
        val ACTION_RECONCILE_KEEP_ALIVE: String = "${BuildConfig.ACTION_PREFIX}.action.RECONCILE_KEEP_ALIVE"
        val ACTION_START_MQTT_EXPORT: String = "${BuildConfig.ACTION_PREFIX}.action.START_MQTT_EXPORT"
        val ACTION_STOP_MQTT_EXPORT: String = "${BuildConfig.ACTION_PREFIX}.action.STOP_MQTT_EXPORT"
        val ACTION_START_INFLUX_EXPORT: String = "${BuildConfig.ACTION_PREFIX}.action.START_INFLUX_EXPORT"
        val ACTION_STOP_INFLUX_EXPORT: String = "${BuildConfig.ACTION_PREFIX}.action.STOP_INFLUX_EXPORT"
        private const val CHANNEL_ID = "collector"
        private const val NOTIFICATION_ID = 1001
        private const val STATUS_HEARTBEAT_INTERVAL_MS = 30_000L
        private const val TAG = "BYDCollectorService"
        private const val DEBUG_REASON_AUTOSTART = "autostart"
        private const val DEBUG_REASON_MANUAL = "manual"
        private val running = AtomicBoolean(false)
        private val mainPollingRunning = AtomicBoolean(false)
        private val debugRunning = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()
        fun isMainPollingRunning(): Boolean = mainPollingRunning.get()
        fun isDebugRunning(): Boolean = debugRunning.get()

        fun startIntent(context: Context): Intent = Intent(context, CollectorService::class.java).apply {
            action = ACTION_START
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
    }
}
