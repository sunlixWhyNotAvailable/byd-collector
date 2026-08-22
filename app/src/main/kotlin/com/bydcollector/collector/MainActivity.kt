package com.bydcollector.collector

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.adb.AccessCheckMode
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.debug.DirectDebugDatabaseHelper
import com.bydcollector.collector.data.debug.DirectDebugStore
import com.bydcollector.collector.diagnostics.DiagnosticLogRecorder
import com.bydcollector.collector.influx.InfluxActionResult
import com.bydcollector.collector.influx.InfluxActions
import com.bydcollector.collector.maintenance.DbMaintenanceOperation
import com.bydcollector.collector.maintenance.DbMaintenanceRuntimeStatus
import com.bydcollector.collector.maintenance.DbMaintenanceUiState
import com.bydcollector.collector.maintenance.MainArchivePreflight
import com.bydcollector.collector.maintenance.StorageFormatCutoverCoordinator
import com.bydcollector.collector.maintenance.ArchiveStorageManager
import com.bydcollector.collector.maintenance.ArchiveShareLeaseRegistry
import com.bydcollector.collector.mqtt.HaMqttActions
import com.bydcollector.collector.mqtt.MqttActionResult
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorServiceController
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.system.CollectorAutoStart
import com.bydcollector.collector.ui.DashboardState
import com.bydcollector.collector.ui.DashboardLoadProfile
import com.bydcollector.collector.ui.DashboardRowCounts
import com.bydcollector.collector.ui.DashboardStateProvider
import com.bydcollector.collector.ui.DashboardUiStateStore
import com.bydcollector.collector.ui.VehicleKpiLanguage
import com.bydcollector.collector.ui.compose.AppTab
import com.bydcollector.collector.ui.compose.BydCollectorActions
import com.bydcollector.collector.ui.compose.BydCollectorApp
import com.bydcollector.collector.ui.compose.InfluxDraft
import com.bydcollector.collector.ui.compose.MqttDraft
import com.bydcollector.collector.ui.compose.TelegramConfig
import com.bydcollector.collector.ui.compose.TelegramMessageConfig
import com.bydcollector.collector.ui.compose.TelegramMessageType
import com.bydcollector.collector.ui.compose.TelegramTestStatus
import com.bydcollector.collector.ui.compose.TelegramUiActions
import com.bydcollector.collector.ui.compose.TelegramUiState
import com.bydcollector.collector.ui.compose.TripsUiActions
import com.bydcollector.collector.ui.compose.TripsUiState
import com.bydcollector.collector.ui.compose.TripsUiMapper
import com.bydcollector.collector.ui.compose.UiLanguage
import com.bydcollector.collector.ui.compose.strings
import com.bydcollector.collector.update.UpdateAutoCheckAction
import com.bydcollector.collector.update.UpdateAutoCheckRuntime
import com.bydcollector.collector.update.UpdateApkVerifier
import com.bydcollector.collector.update.UpdateChecker
import com.bydcollector.collector.update.UpdateCheckResult
import com.bydcollector.collector.update.UpdateDownloader
import com.bydcollector.collector.update.UpdateInfo
import com.bydcollector.collector.update.UpdateUiState
import com.bydcollector.collector.util.dispatchOperationalEvent
import com.bydcollector.collector.util.namedSingleThreadExecutor
import java.io.File
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

//coordinates the user-facing compose shell while CollectorService owns long-running vehicle work
class MainActivity : ComponentActivity() {
    private lateinit var store: TelemetryStore
    private lateinit var settings: CollectorSettings
    private lateinit var settingsPreferences: SharedPreferences
    private lateinit var stateProvider: DashboardStateProvider
    private lateinit var dashboardUiStateStore: DashboardUiStateStore
    private val handler = Handler(Looper.getMainLooper())
    private val dashboardExecutor = namedSingleThreadExecutor("byd-ui-dash")
    private val dashboardCountExecutor = namedSingleThreadExecutor("byd-ui-counts")
    private val updateExecutor = namedSingleThreadExecutor("byd-update")
    private val updateChecker by lazy { UpdateChecker(settings) }
    private val updateDownloader by lazy { UpdateDownloader(applicationContext) }
    private val updateApkVerifier by lazy { UpdateApkVerifier(applicationContext) }
    private var startupBackgroundLaunchPosted = false
    private var startupAdbSelfCheckPosted = false
    private var startupAdbSelfCheckSource = "startup"
    private var startupAccessFlowCompleted = false
    private var mainWindowHasFocus = false
    private var runtimePermissionRequestInFlight = false
    @Volatile private var refreshInFlight = false
    @Volatile private var updateCheckInFlight = false
    @Volatile private var foreground = false
    @Volatile private var destroyed = false
    private val archiveShareInFlight = AtomicBoolean(false)

    private var activeTab by mutableStateOf(AppTab.MAIN)
    private var uiLanguage by mutableStateOf(UiLanguage.UK)
    private var darkTheme by mutableStateOf(true)
    private var mqttDraft by mutableStateOf(MqttDraft())
    private var influxDraft by mutableStateOf(InfluxDraft())
    private var telegramUiState by mutableStateOf(TelegramUiState())
    private var tripsUiState by mutableStateOf(TripsUiState())
    private var updateUiState by mutableStateOf<UpdateUiState>(UpdateUiState.Hidden)
    private var updateUiGeneration = 0L
    private var pendingMaintenanceOperation by mutableStateOf<DbMaintenanceOperation?>(null)
    private var pendingMainArchivePreflight by mutableStateOf<MainArchivePreflight?>(null)
    private var maintenanceLaunchOperation by mutableStateOf<DbMaintenanceOperation?>(null)
    private var maintenancePreflightInFlight = false
    private var dashboardRefreshVersion by mutableStateOf(0)
    private var backgroundSetupPromptVisible by mutableStateOf(false)
    private var backgroundSetupPromptAutoLaunch = false
    @Volatile private var forcedRefreshPending = false
    private var credentialsLoadStarted = false
    private var credentialsLoaded = false
    private var mqttCredentialRevision = 0L
    private var influxCredentialRevision = 0L
    private var telegramCredentialRevision = 0L
    private val tripsUiActions = TripsUiActions(
        onColorMetricChanged = { tripsUiState = tripsUiState.copy(colorMetric = it) },
        onSpeedThresholdsChanged = { green, yellow ->
            settings.setTripSpeedThresholds(green, yellow)
            tripsUiState = tripsUiState.copy(speedGreenThreshold = settings.tripSpeedGreenThreshold(), speedYellowThreshold = settings.tripSpeedYellowThreshold())
        },
        onConsumptionThresholdsChanged = { green, yellow ->
            settings.setTripConsumptionThresholds(green, yellow)
            tripsUiState = tripsUiState.copy(consumptionGreenThreshold = settings.tripConsumptionGreenThreshold(), consumptionYellowThreshold = settings.tripConsumptionYellowThreshold())
        },
        onRouteRequested = { tripId -> loadTripsUi(tripId) }
    )

    private val refreshTask = object : Runnable {
        override fun run() {
            refresh(force = false)
            handler.postDelayed(this, DASHBOARD_REFRESH_HEARTBEAT_MS)
        }
    }
    private val settingsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (
            key == CollectorSettings.KEY_TELEGRAM_CONNECTION_STATUS ||
            key == CollectorSettings.KEY_TELEGRAM_CONNECTION_MESSAGE
        ) {
            handler.post {
                if (!destroyed && ::settings.isInitialized) syncTelegramUiRuntimeState()
            }
        }
    }
    private val startupAdbSelfCheckTask = Runnable { runStartupAdbSelfCheckIfReady() }
    private val updateAutoCheckTimerTask = Runnable { onUpdateAutoCheckTimerElapsed() }
    private val telegramReconcileTask = Runnable {
        if (!destroyed && ::settings.isInitialized) {
            CollectorServiceController.reconcileTelegram(this@MainActivity)
        }
    }

    private val telegramActions = TelegramUiActions(
        onConfigChanged = ::onTelegramConfigChanged,
        onClearBotToken = ::onClearTelegramBotToken,
        onTestConnection = ::onTestTelegramConnection
    )

    //maps every ui command to persisted settings plus service intents so process restarts keep the same intent
    private val uiActions = object : BydCollectorActions {
        override fun onTabSelected(tab: AppTab) {
            activeTab = tab
            if (tab == AppTab.TELEGRAM) syncTelegramUiRuntimeState()
            if (tab == AppTab.TRIPS) loadTripsUi()
            refresh()
        }

        override fun onLanguageSelected(language: UiLanguage) {
            uiLanguage = language
            settings.setUiLanguageCode(language.code)
            dashboardUiStateStore.selectVehicleKpiLanguage(language.vehicleKpiLanguage())
            val previousTelegram = telegramUiState
            val localizedTelegram = loadTelegramUiState()
            telegramUiState = localizedTelegram.copy(
                config = localizedTelegram.config.copy(
                    botToken = previousTelegram.config.botToken,
                    botTokenSet = previousTelegram.config.botTokenSet,
                    chatId = previousTelegram.config.chatId
                ),
                testStatus = previousTelegram.testStatus
            )
            if (activeTab == AppTab.TRIPS) loadTripsUi()
        }

        override fun onDarkThemeSelected(dark: Boolean) {
            darkTheme = dark
        }

        override fun onStartMain() {
            refreshStoreBackedState()
            settings.setMainManuallyStopped(false)
            settings.setPollingEnabled(true)
            requestAccessCheck("start_main", AccessCheckMode.NORMAL)
            CollectorServiceController.start(this@MainActivity)
            refresh()
        }

        override fun onStopMain() {
            refreshStoreBackedState()
            settings.setMainManuallyStopped(true)
            settings.setPollingEnabled(false)
            CollectorServiceController.stop(this@MainActivity)
            refresh()
        }

        override fun onToggleMainAutoStart(enabled: Boolean) {
            refreshStoreBackedState()
            if (settings.isAutoStartEnabled() != enabled) {
                if (enabled) settings.setMainManuallyStopped(false)
                settings.setAutoStartEnabled(enabled)
                val message = if (enabled) {
                    CollectorSettings.AUTO_START_ENABLED_UK
                } else {
                    CollectorSettings.AUTO_START_DISABLED_UK
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                if (enabled) {
                    CollectorAutoStart.scheduleWatchdog(applicationContext, settings, currentStore())
                }
                refresh()
            }
        }

        override fun onGrantAdb() {
            requestAdbAuthorizationFlow("grant_button")
            refresh()
        }

        override fun onOpenBackgroundApps() {
            showBackgroundSetupPrompt(autoLaunch = false)
        }

        override fun onOpenArchiveDatabase() {
            openMainArchiveDialog()
        }

        override fun onOpenArchiveDebugDatabase() {
            pendingMainArchivePreflight = null
            pendingMaintenanceOperation = DbMaintenanceOperation.DEBUG_ARCHIVE
            refresh()
        }

        override fun onConfirmDatabaseMaintenance() {
            val operation = pendingMaintenanceOperation ?: return
            maintenanceLaunchOperation = operation
            pendingMaintenanceOperation = null
            pendingMainArchivePreflight = null
            stateProvider.invalidateArchiveStorageSnapshot()
            val runningStatus = DbMaintenanceRuntimeStatus(
                operation = operation,
                running = true,
                completed = false,
                stepIndex = 1,
                stepCount = operation.stepsUk.size,
                messageUk = operation.stepsUk.first(),
                messageEn = operation.stepsEn.first()
            )
            try {
                dashboardExecutor.execute {
                    val committed = runCatching {
                        settings.setDbMaintenanceStatus(runningStatus, synchronous = true)
                    }.getOrElse { error ->
                        postMaintenanceLaunchFailure(error)
                        return@execute
                    }
                    if (!committed) {
                        postMaintenanceLaunchFailure(
                            IllegalStateException("Could not persist database maintenance status")
                        )
                        return@execute
                    }
                    runCatching {
                        when (operation) {
                            DbMaintenanceOperation.ARCHIVE -> CollectorServiceController.archiveDatabase(applicationContext)
                            DbMaintenanceOperation.DEBUG_ARCHIVE -> CollectorServiceController.archiveDebugDatabase(applicationContext)
                        }
                    }.onSuccess {
                        runOnUiThread {
                            if (!destroyed) refresh()
                        }
                    }.onFailure { error ->
                        val failure = settings.dbMaintenanceStatus().copy(
                            running = false,
                            completed = false,
                            error = dashboardErrorDetail(error)
                        )
                        val persisted = runCatching {
                            settings.setDbMaintenanceStatus(failure, synchronous = true)
                        }.getOrElse { persistError ->
                            Log.e(TAG, "Database maintenance failure status could not be persisted", persistError)
                            false
                        }
                        if (!persisted) {
                            Log.e(TAG, "Database maintenance failure status commit returned false")
                        }
                        postMaintenanceLaunchFailure(error)
                    }
                }
            } catch (error: RuntimeException) {
                postMaintenanceLaunchFailure(error)
            }
        }

        override fun onCancelDatabaseMaintenance() {
            CollectorServiceController.cancelDatabaseMaintenance(this@MainActivity)
            refresh()
        }

        override fun onDismissDatabaseMaintenance() {
            if (dashboardUiStateStore.currentChrome()?.dbMaintenanceStatus?.running == true || maintenanceLaunchOperation != null) return
            pendingMaintenanceOperation = null
            pendingMainArchivePreflight = null
            settings.clearDbMaintenanceStatus()
            refresh()
        }

        override fun onSetArchiveStorageLimitGb(value: Int) {
            refreshStoreBackedState()
            settings.setArchiveStorageLimitGb(value)
            stateProvider.invalidateArchiveStorageSnapshot()
            CollectorServiceController.reconcileArchiveStorage(this@MainActivity)
            refresh()
        }

        override fun onDeleteArchives(ids: List<String>) {
            if (ids.isEmpty()) return
            stateProvider.invalidateArchiveStorageSnapshot()
            CollectorServiceController.deleteArchives(this@MainActivity, ids)
            refresh()
        }

        override fun onShareArchives(ids: List<String>) {
            shareArchives(ids)
        }

        override fun onStartDebug() {
            refreshStoreBackedState()
            requestAccessCheck("start_debug", AccessCheckMode.NORMAL)
            settings.setDebugManuallyStopped(false)
            settings.setDebugPollingEnabled(true)
            CollectorServiceController.startDebug(this@MainActivity)
            refresh()
        }

        override fun onStopDebug() {
            refreshStoreBackedState()
            settings.setDebugManuallyStopped(true)
            settings.setDebugPollingEnabled(false)
            CollectorServiceController.stopDebug(this@MainActivity)
            refresh()
        }

        override fun onToggleDebugAutoStart(enabled: Boolean) {
            if (settings.isDebugAutoStartEnabled() != enabled) {
                if (enabled) settings.setDebugManuallyStopped(false)
                settings.setDebugAutoStartEnabled(enabled && settings.isAutoStartEnabled())
                refresh()
            }
        }

        override fun onToggleSharedCategories(enabled: Boolean) {
            settings.setHaSharedCategoriesEnabled(enabled)
            refresh()
        }

        override fun onStartMqtt() {
            refreshStoreBackedState()
            requestAccessCheck("start_mqtt", AccessCheckMode.NORMAL)
            if (!saveMqttDraft()) {
                refresh()
                return
            }
            settings.setMqttManuallyStopped(false)
            settings.setMqttEnabled(true)
            CollectorServiceController.startMqttExport(this@MainActivity)
            refresh()
        }

        override fun onStopMqtt() {
            refreshStoreBackedState()
            settings.setMqttManuallyStopped(true)
            settings.setMqttEnabled(false)
            CollectorServiceController.stopMqttExport(this@MainActivity)
            refresh()
        }

        override fun onTestMqtt() {
            runMqttChannelAction("MQTT test") {
                HaMqttActions.testConnection(currentStore(), settings)
            }
        }

        override fun onToggleMqttAutoStart(enabled: Boolean) {
            if (enabled) settings.setMqttManuallyStopped(false)
            settings.setMqttAutoStartEnabled(enabled)
            refresh()
        }

        override fun onToggleMqttCategory(category: String, enabled: Boolean) {
            settings.setMqttCategoryEnabled(category, enabled)
            refresh()
        }

        override fun onMqttDraftChanged(draft: MqttDraft) {
            if (draft.username != mqttDraft.username || draft.password != mqttDraft.password) {
                mqttCredentialRevision += 1L
            }
            mqttDraft = draft
            saveMqttDraft()
        }

        override fun onStartInflux() {
            refreshStoreBackedState()
            requestAccessCheck("start_influx", AccessCheckMode.NORMAL)
            if (!saveInfluxDraft()) {
                refresh()
                return
            }
            settings.setInfluxManuallyStopped(false)
            settings.setInfluxEnabled(true)
            CollectorServiceController.startInfluxExport(this@MainActivity)
            refresh()
        }

        override fun onStopInflux() {
            refreshStoreBackedState()
            settings.setInfluxManuallyStopped(true)
            settings.setInfluxEnabled(false)
            CollectorServiceController.stopInfluxExport(this@MainActivity)
            refresh()
        }

        override fun onTestInflux() {
            runInfluxChannelAction("Influx test") {
                InfluxActions.testConnection(currentStore(), settings)
            }
        }

        override fun onReExportInflux() {
            runInfluxChannelAction("Influx re-export") {
                InfluxActions.reExportNewCategories(currentStore(), settings)
            }
        }

        override fun onToggleInfluxAutoStart(enabled: Boolean) {
            if (enabled) settings.setInfluxManuallyStopped(false)
            settings.setInfluxAutoStartEnabled(enabled)
            refresh()
        }

        override fun onToggleInfluxCategory(category: String, enabled: Boolean) {
            settings.setInfluxCategoryEnabled(category, enabled)
            refresh()
        }

        override fun onInfluxDraftChanged(draft: InfluxDraft) {
            if (draft.username != influxDraft.username || draft.password != influxDraft.password) {
                influxCredentialRevision += 1L
            }
            influxDraft = draft
            saveInfluxDraft()
        }

        override fun onToggleKeepWifi(enabled: Boolean) {
            updateKeepAliveSetting(enabled) { settings.setKeepWifiEnabled(it) }
        }

        override fun onToggleKeepMobile(enabled: Boolean) {
            updateKeepAliveSetting(enabled) { settings.setKeepMobileDataEnabled(it) }
        }

        override fun onToggleKeepBluetooth(enabled: Boolean) {
            updateKeepAliveSetting(enabled) { settings.setKeepBluetoothEnabled(it) }
        }

        override fun onToggleKeepCollector(enabled: Boolean) {
            updateKeepAliveSetting(enabled) { settings.setRecoverCollectorServiceEnabled(it) }
        }

        override fun onToggleTailscaleActivation(enabled: Boolean) {
            refreshStoreBackedState()
            settings.setTailscaleActivationEnabled(enabled)
            refresh()
        }

        override fun onToggleUpdateAutoCheck(enabled: Boolean) {
            settings.setUpdateAutoCheckEnabled(enabled)
            handleUpdateAutoCheckAction(UpdateAutoCheckRuntime.onAutoCheckEnabledChanged(enabled))
            refresh()
        }

        override fun onCheckForUpdates() {
            runUpdateCheck(force = true)
        }

        override fun onDismissUpdateDialog() {
            updateUiGeneration += 1L
            updateUiState = UpdateUiState.Hidden
        }

        override fun onInstallUpdate() {
            val available = updateUiState as? UpdateUiState.Available ?: return
            startUpdateDownload(available.info)
        }

        override fun onShutdownApp() {
            refreshStoreBackedState()
            settings.setUserShutdownRequested(true)
            CollectorAutoStart.cancelScheduled(applicationContext)
            CollectorServiceController.shutdown(this@MainActivity)
            finishAndRemoveTask()
        }

        override fun onStartJournal() {
            startDiagnostics("journal")
        }

        override fun onStopJournal() {
            stopDiagnostics("journal")
        }

        override fun onStartLogcat() {
            startDiagnostics("logcat")
        }

        override fun onStopLogcat() {
            stopDiagnostics("logcat")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = CollectorSettings(applicationContext)
        uiLanguage = UiLanguage.fromCode(settings.uiLanguageCode())
        settingsPreferences = getSharedPreferences(CollectorSettings.PREFS_NAME, MODE_PRIVATE)
        settingsPreferences.registerOnSharedPreferenceChangeListener(settingsChangeListener)
        if (!CollectorService.isMaintenanceRunningInProcess()) {
            settings.recoverInterruptedDbMaintenanceIfNeeded("activity_start")
        }
        val clearedUserShutdown = settings.clearUserShutdownRequestIfSet()
        if (clearedUserShutdown) {
            settings.clearRuntimeManualStops()
        }
        stateProvider = DashboardStateProvider(applicationContext, { BydCollectorApplication.store(applicationContext) }, settings)
        dashboardUiStateStore = BydCollectorApplication.dashboardUiStateStore(applicationContext)
        dashboardUiStateStore.selectVehicleKpiLanguage(uiLanguage.vehicleKpiLanguage())
        mqttDraft = MqttDraft(
            host = settings.mqttHost(),
            port = settings.mqttPort().toString(),
            clientId = settings.mqttClientId(),
            topicPrefix = settings.mqttTopicPrefix(),
            discoveryPrefix = settings.mqttDiscoveryPrefix()
        )
        influxDraft = InfluxDraft(
            host = settings.influxHost(),
            port = settings.influxPort().toString(),
            database = settings.influxDatabase(),
            measurement = settings.influxMeasurement()
        )
        telegramUiState = loadTelegramUiState()
        tripsUiState = TripsUiState(
            speedGreenThreshold = settings.tripSpeedGreenThreshold(),
            speedYellowThreshold = settings.tripSpeedYellowThreshold(),
            consumptionGreenThreshold = settings.tripConsumptionGreenThreshold(),
            consumptionYellowThreshold = settings.tripConsumptionYellowThreshold()
        )
        //An Activity recreation must render the process cache immediately; only a cold process
        //needs the lightweight initial snapshot before Compose starts collecting the flows.
        if (dashboardUiStateStore.currentChrome() == null) {
            dashboardUiStateStore.seed(stateProvider.loadInitial())
        }
        scheduleDashboardCountBootstrap(force = false)
        setContent {
            val chromeSnapshot by dashboardUiStateStore.chromeState.collectAsStateWithLifecycle()
            val tabSnapshot by dashboardUiStateStore.tabState(activeTab).collectAsStateWithLifecycle()
            val renderedChrome = chromeSnapshot?.state
            val renderedTab = tabSnapshot?.state ?: renderedChrome
            BydCollectorApp(
                state = renderedTab,
                chromeState = renderedChrome,
                activeTab = activeTab,
                language = uiLanguage,
                darkTheme = darkTheme,
                mqttDraft = mqttDraft,
                influxDraft = influxDraft,
                tripsUiState = tripsUiState,
                tripsUiActions = tripsUiActions,
                telegramUiState = telegramUiState,
                telegramActions = telegramActions,
                appVersionName = BuildConfig.VERSION_NAME,
                updateAutoCheckEnabled = settings.isUpdateAutoCheckEnabled(),
                updateUiState = updateUiState,
                databaseMaintenanceUiState = currentMaintenanceUiState(renderedChrome),
                switchConfirmationVersion = dashboardRefreshVersion,
                actions = uiActions,
                backgroundSetupPromptVisible = backgroundSetupPromptVisible,
                onOpenBackgroundSettingsFromPrompt = ::onOpenBackgroundSettingsFromPrompt,
                onDismissBackgroundSetupPrompt = ::onDismissBackgroundSetupPrompt
            )
        }
        loadCredentialsAfterFirstFrame()
        dashboardExecutor.execute {
            val runtimeStore = currentStore()
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                store = runtimeStore
                settings = CollectorSettings(
                    applicationContext,
                    runtimeStore,
                    eventExecutor = dashboardExecutor
                )
                if (clearedUserShutdown) {
                    CollectorAutoStart.recoverFromForeground(applicationContext, settings, runtimeStore)
                }
                reconcileCutoverArchiveStorageIfNeeded()
                startRuntimeUpdateAutoCheck()
                hydrateDashboardTabsOnce()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        scheduleDashboardCountBootstrap(force = false)
        reconcileCutoverArchiveStorageIfNeeded()
        syncTelegramUiRuntimeState()
        refresh()
        maybeContinueStartupAccessFlow()
        runPendingStartupUpdateCheckIfReady()
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, DASHBOARD_REFRESH_HEARTBEAT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        mainWindowHasFocus = hasFocus
        if (hasFocus && ::settings.isInitialized) maybeContinueStartupAccessFlow()
    }

    override fun onPause() {
        foreground = false
        mainWindowHasFocus = false
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        //asks the watchdog path to recover service work if the user closes only the activity
        if (
            ::settings.isInitialized &&
            !CollectorSettings.isDbMaintenanceRunning(applicationContext)
        ) {
            refreshStoreBackedState()
            CollectorAutoStart.scheduleRestartAfterUiClosed(applicationContext, settings, currentStore())
        }
        dashboardExecutor.shutdownNow()
        dashboardCountExecutor.shutdownNow()
        updateExecutor.shutdownNow()
        if (::stateProvider.isInitialized) {
            stateProvider.close()
        }
        if (::settingsPreferences.isInitialized) {
            settingsPreferences.unregisterOnSharedPreferenceChangeListener(settingsChangeListener)
        }
        handler.removeCallbacks(updateAutoCheckTimerTask)
        handler.removeCallbacks(startupAdbSelfCheckTask)
        handler.removeCallbacks(telegramReconcileTask)
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun currentStore(): TelemetryStore = BydCollectorApplication.store(applicationContext)

    private fun recordOperationalEvent(category: String, message: String, detail: String? = null) {
        dispatchOperationalEvent(dashboardExecutor) {
            val eventStore = currentStore()
            eventStore.recordEvent(category, message, detail)
        }
    }

    private fun postMaintenanceLaunchFailure(error: Throwable) {
        Log.e(TAG, "Database maintenance could not start", error)
        runOnUiThread {
            if (destroyed) return@runOnUiThread
            maintenanceLaunchOperation = null
            pendingMaintenanceOperation = null
            pendingMainArchivePreflight = null
            refresh()
        }
    }

    private fun reconcileCutoverArchiveStorageIfNeeded() {
        if (settings.isCutoverArchiveStoragePending() && !CollectorService.isArchiveStorageActive()) {
            CollectorServiceController.reconcileArchiveStorage(this)
        }
    }

    private fun shareArchives(ids: List<String>) {
        if (!archiveShareInFlight.compareAndSet(false, true)) return
        val requestedIds = ids.toList()
        if (CollectorService.isArchiveStorageActive()) {
            failArchiveShare(requestedIds, null, "archive_job_active")
            return
        }
        val lease = CollectorService.archiveShareLeaseRegistry.acquire(requestedIds)
        try {
            dashboardExecutor.execute {
                if (CollectorService.isArchiveStorageActive()) {
                    failArchiveShare(requestedIds, lease, "archive_job_started")
                    return@execute
                }
                val files = runCatching {
                    ArchiveStorageManager(
                        archiveRoot = File(filesDir, "db_archive"),
                        mainDatabaseFile = currentStore().databaseFile(),
                        debugDatabaseFile = getDatabasePath(DirectDebugDatabaseHelper.DATABASE_NAME)
                    ).resolveShareZipFiles(requestedIds)
                }.getOrNull()
                if (files == null) {
                    failArchiveShare(requestedIds, lease, "invalid_or_stale_selection")
                    return@execute
                }
                val uris = runCatching {
                    files.map { file ->
                        FileProvider.getUriForFile(
                            this@MainActivity,
                            "${BuildConfig.APPLICATION_ID}.fileprovider",
                            file
                        )
                    }
                }.getOrElse {
                    failArchiveShare(requestedIds, lease, "uri_resolution_failed:${it::class.java.simpleName}")
                    return@execute
                }
                handler.post {
                    if (destroyed) {
                        CollectorService.archiveShareLeaseRegistry.release(lease)
                        archiveShareInFlight.set(false)
                    } else {
                        openArchiveShareChooser(requestedIds, uris, lease)
                    }
                }
            }
        } catch (error: RuntimeException) {
            failArchiveShare(requestedIds, lease, "executor_rejected:${error::class.java.simpleName}")
        }
    }

    private fun openArchiveShareChooser(
        ids: List<String>,
        uris: List<Uri>,
        lease: ArchiveShareLeaseRegistry.Lease
    ) {
        try {
            check(uris.isNotEmpty())
            val sendIntent = Intent(
                if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            ).apply {
                type = "application/zip"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (uris.size == 1) {
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                } else {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
                clipData = ClipData.newUri(
                    contentResolver,
                    strings(uiLanguage).shareSelectedArchives,
                    uris.first()
                ).also { clip ->
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
            }
            startActivity(Intent.createChooser(sendIntent, strings(uiLanguage).shareSelectedArchives))
            recordOperationalEvent(
                "archive_share_chooser_opened",
                "Archive share chooser opened",
                "count=${ids.size}"
            )
        } catch (error: ActivityNotFoundException) {
            failArchiveShare(ids, lease, "no_share_target")
            return
        } catch (error: RuntimeException) {
            failArchiveShare(ids, lease, "chooser_failed:${error::class.java.simpleName}")
            return
        } finally {
            archiveShareInFlight.set(false)
        }
    }

    private fun failArchiveShare(
        ids: List<String>,
        lease: ArchiveShareLeaseRegistry.Lease?,
        reason: String
    ) {
        lease?.let(CollectorService.archiveShareLeaseRegistry::release)
        archiveShareInFlight.set(false)
        recordOperationalEvent(
            "archive_share_failed",
            "Archive share rejected",
            "reason=$reason count=${ids.size}"
        )
        if (!destroyed) {
            handler.post {
                if (!destroyed) {
                    Toast.makeText(
                        this@MainActivity,
                        strings(uiLanguage).archiveShareFailed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun refreshStoreBackedState() {
        store = currentStore()
        settings = CollectorSettings(
            applicationContext,
            store,
            eventExecutor = dashboardExecutor
        )
    }

    private fun scheduleDashboardCountBootstrap(force: Boolean) {
        val countGeneration = dashboardUiStateStore.beginCountBootstrap(force) ?: return
        runCatching {
            dashboardCountExecutor.execute {
                runCatching {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    val main = currentStore().dashboardRowCounts()
                    val debug = if (BydCollectorApplication.ensureDebugStorageReady(applicationContext)) {
                        DirectDebugStore(applicationContext).use { it.dashboardReadingCount() }
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
                    dashboardUiStateStore.publishRowCountBaseline(countGeneration, counts)
                }
                    .onFailure { error ->
                        dashboardUiStateStore.failCountBootstrap(countGeneration)
                        Log.w(TAG, "Dashboard row-count bootstrap failed", error)
                    }
            }
        }.onFailure { error ->
            dashboardUiStateStore.failCountBootstrap(countGeneration)
            Log.w(TAG, "Dashboard row-count bootstrap rejected", error)
        }
    }

    private fun hydrateDashboardTabsOnce() {
        if (!dashboardUiStateStore.beginInitialHydration()) return
        val kpiLanguage = uiLanguage.vehicleKpiLanguage()
        val targets = listOf(
            AppTab.MAIN to DashboardLoadProfile.MAIN,
            AppTab.ALL_PARAMETERS to DashboardLoadProfile.ALL_PARAMETERS,
            AppTab.HA to DashboardLoadProfile.HA,
            AppTab.EXTRA to DashboardLoadProfile.EXTRA,
            AppTab.LOGS to DashboardLoadProfile.LOGS
        )
        runCatching {
            dashboardExecutor.execute {
                targets.forEach { (tab, profile) ->
                    if (Thread.currentThread().isInterrupted) return@execute
                    val generation = dashboardUiStateStore.beginTabRefresh(tab)
                    runCatching {
                        stateProvider.load(
                            profile = profile,
                            previous = dashboardUiStateStore.currentTab(tab),
                            vehicleKpiLanguage = kpiLanguage
                        )
                    }.onSuccess { state ->
                        dashboardUiStateStore.publishTab(tab, generation, state)
                    }.onFailure { error ->
                        dashboardUiStateStore.failTab(tab, generation, dashboardErrorDetail(error))
                        Log.w(TAG, "Initial dashboard hydration failed for $tab", error)
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Initial dashboard hydration rejected", error)
        }
    }

    private fun openMainArchiveDialog() {
        if (maintenancePreflightInFlight || destroyed) return
        maintenancePreflightInFlight = true
        dashboardExecutor.execute {
            val result = runCatching {
                StorageFormatCutoverCoordinator.readMainPreflight(currentStore().databaseFile())
            }
            runOnUiThread {
                maintenancePreflightInFlight = false
                if (destroyed) return@runOnUiThread
                result
                    .onSuccess { preflight ->
                        pendingMainArchivePreflight = preflight
                        pendingMaintenanceOperation = DbMaintenanceOperation.ARCHIVE
                    }
                    .onFailure { error ->
                        recordDashboardRefreshFailure("maintenance_preflight", error)
                        Toast.makeText(
                            this@MainActivity,
                            strings(uiLanguage).archivePreflightFailed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
        }
    }

    private fun loadTripsUi(routeTripId: String? = null) {
        if (destroyed) return
        val requestedLanguage = uiLanguage
        tripsUiState = tripsUiState.copy(routeLoadingId = routeTripId)
        dashboardExecutor.execute {
            val result = runCatching {
                val trips = BydCollectorApplication.trips(applicationContext)
                val groups = trips.queryHierarchy()
                val routes = routeTripId?.let { id -> mapOf(id to trips.queryRoutePoints(id)) }.orEmpty()
                TripsUiMapper.years(groups, requestedLanguage, routes)
            }
            runOnUiThread {
                if (destroyed || uiLanguage != requestedLanguage) return@runOnUiThread
                result.onSuccess { years ->
                    tripsUiState = tripsUiState.copy(years = years, routeLoadingId = null)
                }.onFailure { error ->
                    tripsUiState = tripsUiState.copy(routeLoadingId = null)
                    recordDashboardRefreshFailure("trips", error)
                }
            }
        }
    }

    private fun refresh(force: Boolean = true) {
        if (destroyed || !foreground) return
        val nowMs = SystemClock.elapsedRealtime()
        val tab = activeTab
        val profile = dashboardProfile(tab)
        val tabSnapshot = dashboardUiStateStore.tabState(tab).value
        val tabIntervalMs = dashboardTabRefreshIntervalMs(
            tab = tab,
            storageRefreshPending = tabSnapshot?.state?.let { state ->
                state.archiveStorageJobStatus.running || state.archiveStorageScanPending
            } == true
        )
        val tabDue = profile != null && (
            force || dashboardSnapshotDue(tabSnapshot?.loadedAtElapsedMs, tabIntervalMs, nowMs)
        )
        val chromeSnapshot = dashboardUiStateStore.chromeState.value
        val chromeDue = force || dashboardSnapshotDue(
            loadedAtElapsedMs = chromeSnapshot?.loadedAtElapsedMs,
            intervalMs = DASHBOARD_CHROME_REFRESH_INTERVAL_MS,
            nowMs = nowMs
        )
        if (!tabDue && !chromeDue) return
        if (refreshInFlight) {
            if (force) forcedRefreshPending = true
            return
        }

        refreshInFlight = true
        val tabGeneration = if (tabDue) dashboardUiStateStore.beginTabRefresh(tab) else null
        val chromeGeneration = if (chromeDue) dashboardUiStateStore.beginChromeRefresh() else null
        val kpiLanguage = uiLanguage.vehicleKpiLanguage()
        dashboardExecutor.execute {
            val tabResult = if (tabGeneration != null && profile != null) {
                runCatching {
                    stateProvider.load(
                        profile = profile,
                        previous = dashboardUiStateStore.currentTab(tab),
                        vehicleKpiLanguage = kpiLanguage
                    )
                }
            } else {
                null
            }
            val chromeResult = if (chromeGeneration != null) {
                val reusableTabState = tabResult?.getOrNull()
                    ?.takeIf { profile?.healthDetail != null }
                reusableTabState?.let { Result.success(it) } ?: runCatching {
                    stateProvider.load(
                        profile = DashboardLoadProfile.CHROME,
                        previous = dashboardUiStateStore.currentChrome(),
                        vehicleKpiLanguage = kpiLanguage
                    )
                }
            } else {
                null
            }
            runOnUiThread {
                refreshInFlight = false
                if (destroyed) return@runOnUiThread
                var tabPublished = false
                if (tabGeneration != null && tabResult != null) {
                    tabResult
                        .onSuccess { state ->
                            if (!state.autoStartEnabled && state.debugAutoStartEnabled) {
                                settings.setDebugAutoStartEnabled(false)
                            }
                            tabPublished = dashboardUiStateStore.publishTab(tab, tabGeneration, state)
                        }
                        .onFailure { error ->
                            dashboardUiStateStore.failTab(tab, tabGeneration, dashboardErrorDetail(error))
                            recordDashboardRefreshFailure("tab=$tab", error)
                        }
                }
                if (chromeGeneration != null && chromeResult != null) {
                    chromeResult
                        .onSuccess { state -> dashboardUiStateStore.publishChrome(chromeGeneration, state) }
                        .onFailure { error ->
                            dashboardUiStateStore.failChrome(chromeGeneration, dashboardErrorDetail(error))
                            recordDashboardRefreshFailure("chrome", error)
                        }
                }
                reconcileCutoverArchiveStorageIfNeeded()
                if (tabPublished) dashboardRefreshVersion += 1
                if (forcedRefreshPending && !destroyed) {
                    forcedRefreshPending = false
                    refresh(force = true)
                }
            }
        }
    }

    private fun recordDashboardRefreshFailure(scope: String, error: Throwable) {
        val detail = dashboardErrorDetail(error)
        Log.e(TAG, "Dashboard refresh failed: $scope", error)
        runCatching {
            recordOperationalEvent(
                "dashboard_refresh_failed",
                "Dashboard refresh failed",
                "$scope $detail"
            )
        }.onFailure { eventError ->
            Log.e(TAG, "Dashboard refresh failure could not be recorded", eventError)
        }
    }

    private fun updateKeepAliveSetting(
        enabled: Boolean,
        applySetting: (Boolean) -> Unit
    ) {
        refreshStoreBackedState()
        applySetting(enabled)
        CollectorServiceController.reconcileKeepAlive(this@MainActivity)
        refresh()
    }

    private fun maybeRunStartupSetup(): Boolean {
        val prefs = getSharedPreferences(STARTUP_SETUP_PREFS, MODE_PRIVATE)
        //runs the byd background-app prompt once per app version because dilink may reset this after updates
        if (prefs.getBoolean(KEY_BACKGROUND_SETTINGS_PENDING_RETURN, false)) {
            prefs.edit()
                .putBoolean(KEY_BACKGROUND_SETTINGS_PENDING_RETURN, false)
                .putInt(KEY_BACKGROUND_SETTINGS_VERSION, BuildConfig.VERSION_CODE)
                .apply()
            recordOperationalEvent(
                "startup_background_settings_returned",
                "Returned from background settings",
                "version=${BuildConfig.VERSION_CODE}"
            )
            recordOperationalEvent(
                "adb_authorization_ready_after_background",
                "Background setup completed; ADB self-check may continue"
            )
            startupAdbSelfCheckSource = "after_background"
            return false
        }

        val checkedVersion = prefs.getInt(KEY_BACKGROUND_SETTINGS_VERSION, -1)
        if (checkedVersion == BuildConfig.VERSION_CODE) return false
        if (startupBackgroundLaunchPosted) return true

        startupBackgroundLaunchPosted = true
        recordOperationalEvent(
            "startup_background_check_required",
            "Showing BYD background settings prompt for this app version",
            "checked_version=$checkedVersion current_version=${BuildConfig.VERSION_CODE}"
        )
        showBackgroundSetupPrompt(autoLaunch = true)
        return true
    }

    private fun showBackgroundSetupPrompt(autoLaunch: Boolean) {
        backgroundSetupPromptAutoLaunch = autoLaunch
        backgroundSetupPromptVisible = true
    }

    private fun onOpenBackgroundSettingsFromPrompt() {
        val autoLaunch = backgroundSetupPromptAutoLaunch
        backgroundSetupPromptVisible = false
        backgroundSetupPromptAutoLaunch = false
        val opened = openBackgroundSettings(autoLaunch = autoLaunch)
        if (!opened && autoLaunch) {
            markStartupBackgroundSetupHandled(
                eventKey = "startup_background_settings_unavailable",
                message = "Background settings could not be opened from setup prompt"
            )
            maybeContinueStartupAccessFlow()
        }
    }

    private fun onDismissBackgroundSetupPrompt() {
        val autoLaunch = backgroundSetupPromptAutoLaunch
        backgroundSetupPromptVisible = false
        backgroundSetupPromptAutoLaunch = false
        if (autoLaunch) {
            markStartupBackgroundSetupHandled(
                eventKey = "startup_background_settings_prompt_dismissed",
                message = "Background settings setup prompt dismissed"
            )
        }
        maybeContinueStartupAccessFlow()
    }

    private fun markStartupBackgroundSetupHandled(eventKey: String, message: String) {
        getSharedPreferences(STARTUP_SETUP_PREFS, MODE_PRIVATE)
            .edit()
            .putInt(KEY_BACKGROUND_SETTINGS_VERSION, BuildConfig.VERSION_CODE)
            .remove(KEY_BACKGROUND_SETTINGS_PENDING_RETURN)
            .apply()
        recordOperationalEvent(
            eventKey,
            message,
            "version=${BuildConfig.VERSION_CODE}"
        )
    }

    private fun openBackgroundSettings(autoLaunch: Boolean): Boolean {
        //tries the byd-specific settings screen first, then falls back to generic android settings
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).apply {
                setClassName(
                    "com.byd.appstartmanagement",
                    "com.byd.appstartmanagement.frame.AppStartManagement"
                )
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            },
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in intents) {
            val opened = runCatching {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) {
                if (autoLaunch) {
                    getSharedPreferences(STARTUP_SETUP_PREFS, MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_BACKGROUND_SETTINGS_PENDING_RETURN, true)
                        .apply()
                    recordOperationalEvent(
                        "startup_background_settings_opened",
                        "Background settings opened automatically",
                        "version=${BuildConfig.VERSION_CODE}"
                    )
                }
                return true
            }
        }

        Toast.makeText(
            this,
            "Open DiLink Settings -> General -> Disable background Apps -> ${BuildConfig.COLLECTOR_DISPLAY_NAME} = OFF",
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    private fun requestAdbAuthorizationFlow(source: String) {
        recordOperationalEvent(
            "adb_authorization_flow_started",
            "Starting local ADB RSA authorization request",
            "source=$source"
        )
        requestAccessCheck(source, AccessCheckMode.FORCE, ::completeStartupAccessFlow)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_PERMISSION_REQUEST_CODE) return
        runtimePermissionRequestInFlight = false
        val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        recordOperationalEvent(
            "startup_location_permission_result",
            if (granted) "Startup location permission granted" else "Startup location permission denied"
        )
        refresh()
        maybeContinueStartupAccessFlow()
    }

    private fun maybeContinueStartupAccessFlow() {
        if (destroyed || startupAccessFlowCompleted) return
        if (startupAdbSelfCheckPosted) return
        if (startupHardFlowBlocked()) return
        if (maybeRunStartupSetup()) return
        if (maybeRunStartupLocationPermission()) return
        if (startupHardFlowBlocked()) return
        maybeRunStartupAdbSelfCheck(startupAdbSelfCheckSource)
    }

    private fun maybeRunStartupLocationPermission(): Boolean {
        val prefs = getSharedPreferences(STARTUP_SETUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LOCATION_PERMISSION_SETUP_CONSUMED, false)) return false
        if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean(KEY_LOCATION_PERMISSION_SETUP_CONSUMED, true).commit()
            recordOperationalEvent(
                "startup_location_permission_already_granted",
                "Startup location permission already granted"
            )
            return false
        }
        if (runtimePermissionRequestInFlight) return true
        prefs.edit().putBoolean(KEY_LOCATION_PERMISSION_SETUP_CONSUMED, true).commit()
        runtimePermissionRequestInFlight = true
        recordOperationalEvent(
            "startup_location_permission_requested",
            "Requesting startup fine and coarse location permissions"
        )
        runCatching {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }.onFailure { error ->
            runtimePermissionRequestInFlight = false
            recordOperationalEvent(
                "startup_location_permission_request_failed",
                "Startup location permission request failed",
                error::class.java.simpleName
            )
            refresh()
            maybeContinueStartupAccessFlow()
        }
        return true
    }

    private fun startupHardFlowBlocked(): Boolean {
        return startupHardFlowBlocked(
            foreground = foreground,
            windowFocused = mainWindowHasFocus,
            backgroundPromptVisible = backgroundSetupPromptVisible,
            runtimePermissionRequestInFlight = runtimePermissionRequestInFlight
        )
    }

    private fun maybeRunStartupAdbSelfCheck(source: String) {
        if (startupAdbSelfCheckPosted) return
        startupAdbSelfCheckPosted = true
        startupAdbSelfCheckSource = source
        handler.removeCallbacks(startupAdbSelfCheckTask)
        handler.postDelayed(startupAdbSelfCheckTask, STARTUP_ADB_SELF_CHECK_DELAY_MS)
    }

    private fun runStartupAdbSelfCheckIfReady() {
        if (destroyed || startupAccessFlowCompleted) return
        if (startupHardFlowBlocked()) {
            startupAdbSelfCheckPosted = false
            return
        }
        recordOperationalEvent(
            "startup_adb_self_check_started",
            "Starting startup ADB authorization self-check",
            "source=$startupAdbSelfCheckSource mode=${AccessCheckMode.COLD_START.name.lowercase()}"
        )
        val submitted = requestAccessCheck(
            source = startupAdbSelfCheckSource,
            mode = AccessCheckMode.COLD_START,
            afterComplete = ::completeStartupAccessFlow
        )
        if (!submitted) startupAdbSelfCheckPosted = false
    }

    private fun requestAccessCheck(
        source: String,
        mode: AccessCheckMode,
        afterComplete: (() -> Unit)? = null
    ): Boolean {
        return AdbAuthorizationManager.request(
            context = applicationContext,
            store = currentStore(),
            source = source,
            mode = mode,
            helperOwnerMode = settings.mainHelperOwnerMode(),
            onComplete = {
                handler.post {
                    if (!destroyed) {
                        refresh()
                        afterComplete?.invoke()
                    }
                }
            }
        )
    }

    private fun completeStartupAccessFlow() {
        if (startupAccessFlowCompleted) return
        startupAccessFlowCompleted = true
    }

    private fun startRuntimeUpdateAutoCheck() {
        //starts the process-aged 30s update clock independently from startup access prompts
        handleUpdateAutoCheckAction(
            UpdateAutoCheckRuntime.onRuntimeStarted(settings.isUpdateAutoCheckEnabled())
        )
    }

    private fun runPendingStartupUpdateCheckIfReady() {
        //runs a deferred startup check as soon as the user brings the already-running app forward
        handleUpdateAutoCheckAction(
            UpdateAutoCheckRuntime.onForeground(settings.isUpdateAutoCheckEnabled())
        )
    }

    private fun onUpdateAutoCheckTimerElapsed() {
        //records background expiry as pending while preserving the foreground-only popup rule
        handleUpdateAutoCheckAction(
            UpdateAutoCheckRuntime.onTimerElapsed(
                enabled = settings.isUpdateAutoCheckEnabled(),
                foreground = foreground
            )
        )
    }

    private fun handleUpdateAutoCheckAction(action: UpdateAutoCheckAction) {
        when (action) {
            UpdateAutoCheckAction.None -> Unit
            UpdateAutoCheckAction.Run -> runAutomaticUpdateCheck()
            is UpdateAutoCheckAction.Schedule -> {
                handler.removeCallbacks(updateAutoCheckTimerTask)
                handler.postDelayed(updateAutoCheckTimerTask, action.delayMs)
            }
        }
    }

    private fun runAutomaticUpdateCheck() {
        if (!foreground || destroyed || !settings.isUpdateAutoCheckEnabled()) return
        runUpdateCheck(force = false)
    }

    private fun runUpdateCheck(force: Boolean) {
        //guard duplicate update checks while github request is running
        if (updateCheckInFlight || destroyed) return
        updateCheckInFlight = true
        val uiGeneration = ++updateUiGeneration
        if (force) {
            updateUiState = UpdateUiState.Checking
        }
        updateExecutor.execute {
            val result = updateChecker.check(force)
            runOnUiThread {
                updateCheckInFlight = false
                if (destroyed || !foreground || uiGeneration != updateUiGeneration) return@runOnUiThread
                updateUiState = when (result) {
                    is UpdateCheckResult.Available -> UpdateUiState.Available(result.info)
                    UpdateCheckResult.UpToDate -> if (force) UpdateUiState.UpToDate else UpdateUiState.Hidden
                    is UpdateCheckResult.Error -> if (force) UpdateUiState.Error(result.message) else UpdateUiState.Hidden
                }
            }
        }
    }

    private fun startUpdateDownload(info: UpdateInfo) {
        val uiGeneration = ++updateUiGeneration
        updateUiState = UpdateUiState.Downloading(info, 0)
        updateExecutor.execute {
            val result = runCatching {
                val downloadId = updateDownloader.enqueue(info)
                var progress = 0
                //polls downloadmanager because install intent should be offered only after the apk is fully written
                while (progress < 100 && !destroyed) {
                    Thread.sleep(350L)
                    progress = updateDownloader.progress(downloadId)
                    if (progress < 0) error("Update download failed")
                    runOnUiThread {
                        if (!destroyed && uiGeneration == updateUiGeneration) {
                            updateUiState = UpdateUiState.Downloading(info, progress)
                        }
                    }
                }
                val verifiedFile = updateDownloader.copyDownloadedApkForInstall(info)
                val validation = updateApkVerifier.validate(verifiedFile)
                if (!validation.ok) error(validation.message)
                VerifiedUpdateDownload(
                    info = info,
                    file = verifiedFile,
                    sha256 = validation.sha256 ?: error("APK digest unavailable")
                )
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                result
                    .onSuccess { verified ->
                        val finalValidation = updateApkVerifier.validate(verified.file)
                        if (!finalValidation.ok || finalValidation.sha256 != verified.sha256) {
                            if (uiGeneration == updateUiGeneration) {
                                updateUiState = UpdateUiState.Error(
                                    if (!finalValidation.ok) finalValidation.message else "APK digest changed before install"
                                )
                            }
                            return@onSuccess
                        }
                        updateDownloader.install(verified.info, verified.file)
                    }
                    .onFailure { error ->
                        if (uiGeneration == updateUiGeneration) {
                            updateUiState = UpdateUiState.Error(error.message ?: error::class.java.simpleName)
                        }
                    }
            }
        }
    }

    private fun loadCredentialsAfterFirstFrame() {
        if (credentialsLoadStarted) return
        credentialsLoadStarted = true
        val mqttRevision = mqttCredentialRevision
        val influxRevision = influxCredentialRevision
        val telegramRevision = telegramCredentialRevision
        val source = settings
        updateExecutor.execute {
            val result = runCatching {
                LoadedCredentials(
                    mqttUsername = source.mqttUsername(),
                    mqttPassword = source.mqttPassword(),
                    influxUsername = source.influxUsername(),
                    influxPassword = source.influxPassword(),
                    telegramBotToken = source.telegramBotToken()
                )
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                result
                    .onSuccess { loaded ->
                        credentialsLoaded = true
                        if (mqttRevision == mqttCredentialRevision) {
                            mqttDraft = mqttDraft.copy(
                                username = loaded.mqttUsername,
                                password = loaded.mqttPassword
                            )
                        }
                        if (influxRevision == influxCredentialRevision) {
                            influxDraft = influxDraft.copy(
                                username = loaded.influxUsername,
                                password = loaded.influxPassword
                            )
                        }
                        if (telegramRevision == telegramCredentialRevision) {
                            telegramUiState = telegramUiState.copy(
                                config = telegramUiState.config.copy(
                                    botToken = loaded.telegramBotToken,
                                    botTokenSet = loaded.telegramBotToken.isNotEmpty()
                                )
                            )
                        }
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Credential UI hydration failed", error)
                    }
            }
        }
    }

    private fun saveMqttDraft(): Boolean {
        //keeps saved passwords sticky while empty password fields mean "leave existing secret unchanged"
        settings.setMqttHost(mqttDraft.host)
        settings.setMqttPort(mqttDraft.port.toIntOrNull() ?: settings.mqttPort())
        val usernameStored = if (credentialsLoaded || mqttDraft.username.isNotBlank()) {
            settings.setMqttUsername(mqttDraft.username)
        } else {
            true
        }
        val passwordStored = mqttDraft.password.isBlank() || settings.setMqttPassword(mqttDraft.password)
        settings.setMqttClientId(mqttDraft.clientId)
        settings.setMqttTopicPrefix(mqttDraft.topicPrefix)
        settings.setMqttDiscoveryPrefix(mqttDraft.discoveryPrefix)
        val stored = usernameStored && passwordStored
        if (!stored) {
            mqttDraft = mqttDraft.copy(
                username = settings.mqttUsername(),
                password = settings.mqttPassword()
            )
        }
        return stored
    }

    private fun saveInfluxDraft(): Boolean {
        //mirrors mqtt draft semantics so editing non-secret influx fields never clears the stored password
        settings.setInfluxHost(influxDraft.host)
        settings.setInfluxPort(influxDraft.port.toIntOrNull() ?: settings.influxPort())
        val usernameStored = if (credentialsLoaded || influxDraft.username.isNotBlank()) {
            settings.setInfluxUsername(influxDraft.username)
        } else {
            true
        }
        val passwordStored = influxDraft.password.isBlank() || settings.setInfluxPassword(influxDraft.password)
        settings.setInfluxDatabase(influxDraft.database)
        settings.setInfluxMeasurement(influxDraft.measurement)
        val stored = usernameStored && passwordStored
        if (!stored) {
            influxDraft = influxDraft.copy(
                username = settings.influxUsername(),
                password = settings.influxPassword()
            )
        }
        return stored
    }

    private fun loadTelegramUiState(): TelegramUiState {
        val localizedMessages = strings(uiLanguage).telegram.messages
        val botToken = ""
        val messages = TelegramMessageType.entries.associateWith { type ->
            val eventKey = type.eventKey()
            val savedTemplate = settings.telegramTemplate(eventKey)
            TelegramMessageConfig(
                enabled = settings.isTelegramEventEnabled(eventKey),
                template = savedTemplate ?: localizedMessages.getValue(type).defaultTemplate,
                usesDefaultTemplate = savedTemplate == null
            )
        }
        return TelegramUiState(
            config = TelegramConfig(
                enabled = settings.isTelegramEnabled(),
                botToken = botToken,
                botTokenSet = settings.isTelegramBotTokenSet(),
                chatId = settings.telegramChatId(),
                chargeStepPercent = settings.telegramChargeStepPercent(),
                low12vThresholdVolts = settings.telegramLowVoltageThreshold(),
                telemetryUnavailableMinutes = settings.telegramUnavailableDelayMinutes(),
                tripSummaryDelaySeconds = settings.telegramTripEndDelaySeconds(),
                sendLocation = settings.isTelegramSendLocationEnabled(),
                navigatorMask = settings.telegramNavigatorMask(),
                messages = messages
            ),
            testStatus = telegramTestStatus(settings.telegramConnectionStatus())
        )
    }

    private fun onTelegramConfigChanged(config: TelegramConfig) {
        refreshStoreBackedState()
        val previous = telegramUiState.config
        if (config.botToken != previous.botToken) telegramCredentialRevision += 1L
        var tokenSet = previous.botTokenSet
        var secretWriteFailed = false

        if (previous.enabled != config.enabled) settings.setTelegramEnabled(config.enabled)
        if (previous.chatId != config.chatId) settings.setTelegramChatId(config.chatId)
        if (config.botToken.isNotBlank() && config.botToken != previous.botToken) {
            tokenSet = settings.setTelegramBotToken(config.botToken)
            secretWriteFailed = !tokenSet
        }
        if (previous.chargeStepPercent != config.chargeStepPercent) {
            settings.setTelegramChargeStepPercent(config.chargeStepPercent)
        }
        if (previous.low12vThresholdVolts != config.low12vThresholdVolts) {
            settings.setTelegramLowVoltageThreshold(config.low12vThresholdVolts)
        }
        if (previous.telemetryUnavailableMinutes != config.telemetryUnavailableMinutes) {
            settings.setTelegramUnavailableDelayMinutes(config.telemetryUnavailableMinutes)
        }
        if (previous.tripSummaryDelaySeconds != config.tripSummaryDelaySeconds) {
            settings.setTelegramTripEndDelaySeconds(config.tripSummaryDelaySeconds)
        }
        if (previous.sendLocation != config.sendLocation) {
            settings.setTelegramSendLocationEnabled(config.sendLocation)
        }
        if (previous.navigatorMask != config.navigatorMask) {
            settings.setTelegramNavigatorMask(config.navigatorMask)
        }
        TelegramMessageType.entries.forEach { type ->
            val oldMessage = previous.messages[type]
            val newMessage = config.messages[type] ?: return@forEach
            val eventKey = type.eventKey()
            if (oldMessage?.enabled != newMessage.enabled) {
                settings.setTelegramEventEnabled(eventKey, newMessage.enabled)
            }
            if (newMessage.usesDefaultTemplate) {
                if (settings.telegramTemplate(eventKey) != null) settings.clearTelegramTemplate(eventKey)
            } else if (oldMessage?.template != newMessage.template || settings.telegramTemplate(eventKey) == null) {
                settings.setTelegramTemplate(eventKey, newMessage.template)
            }
        }

        val credentialsChanged = previous.chatId != config.chatId ||
            (config.botToken.isNotBlank() && config.botToken != previous.botToken)
        val nextTestStatus = when {
            secretWriteFailed -> TelegramTestStatus.STORAGE_ERROR
            credentialsChanged -> TelegramTestStatus.NOT_TESTED
            else -> telegramUiState.testStatus
        }
        if (secretWriteFailed) {
            settings.setTelegramConnectionStatus("storage_error", "keystore")
            settings.setTelegramEnabled(false)
        } else if (credentialsChanged) {
            settings.setTelegramConnectionStatus("not_tested", null)
        }
        val effectiveConfig = config.copy(
            enabled = if (secretWriteFailed) false else config.enabled,
            botToken = if (secretWriteFailed) settings.telegramBotToken() else config.botToken,
            botTokenSet = if (secretWriteFailed) settings.isTelegramBotTokenSet() else tokenSet
        )
        telegramUiState = telegramUiState.copy(
            config = effectiveConfig,
            testStatus = nextTestStatus
        )
        if (secretWriteFailed) {
            recordOperationalEvent(
                "telegram_secret_write_failed",
                "Telegram bot token could not be stored in Android Keystore"
            )
        }

        val enabledChanged = previous.enabled != effectiveConfig.enabled
        if (enabledChanged || secretWriteFailed) {
            handler.removeCallbacks(telegramReconcileTask)
            CollectorServiceController.reconcileTelegram(this)
        } else if (credentialsChanged && effectiveConfig.enabled) {
            handler.removeCallbacks(telegramReconcileTask)
            handler.postDelayed(telegramReconcileTask, TELEGRAM_RECONCILE_DELAY_MS)
        }
    }

    private fun onClearTelegramBotToken() {
        refreshStoreBackedState()
        telegramCredentialRevision += 1L
        val cleared = settings.clearTelegramBotToken()
        if (!cleared) settings.setTelegramEnabled(false)
        val persistedToken = settings.telegramBotToken()
        telegramUiState = telegramUiState.copy(
            config = telegramUiState.config.copy(
                enabled = settings.isTelegramEnabled(),
                botToken = persistedToken,
                botTokenSet = persistedToken.isNotEmpty()
            ),
            testStatus = if (cleared) TelegramTestStatus.NOT_TESTED else TelegramTestStatus.STORAGE_ERROR
        )
        if (cleared) {
            settings.setTelegramConnectionStatus("not_tested", null)
        } else {
            settings.setTelegramConnectionStatus("storage_error", "clear_failed")
            recordOperationalEvent(
                "telegram_secret_clear_failed",
                "Telegram bot token could not be cleared from Android Keystore"
            )
        }
        handler.removeCallbacks(telegramReconcileTask)
        CollectorServiceController.reconcileTelegram(this)
    }

    private fun onTestTelegramConnection() {
        refreshStoreBackedState()
        if (!settings.isTelegramBotTokenSet()) {
            settings.setTelegramConnectionStatus("storage_error", "missing_persisted_token")
            telegramUiState = telegramUiState.copy(testStatus = TelegramTestStatus.STORAGE_ERROR)
            return
        }
        telegramUiState = telegramUiState.copy(testStatus = TelegramTestStatus.TESTING)
        settings.setTelegramConnectionStatus("testing", null)
        CollectorServiceController.testTelegram(this)
    }

    private fun syncTelegramUiRuntimeState() {
        if (telegramUiState.config.messages.isEmpty()) return
        telegramUiState = telegramUiState.copy(
            config = telegramUiState.config.copy(
                enabled = settings.isTelegramEnabled()
            ),
            testStatus = telegramTestStatus(settings.telegramConnectionStatus())
        )
    }

    private fun runMqttChannelAction(label: String, action: () -> MqttActionResult) {
        refreshStoreBackedState()
        if (!saveMqttDraft()) {
            refresh()
            return
        }
        dashboardExecutor.execute {
            val result = runCatching { action() }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                result
                    .onSuccess { mqttResult ->
                        val message = if (mqttResult.ok) {
                            "$label: ${mqttResult.message}"
                        } else {
                            "$label failed: ${mqttResult.message}"
                        }
                        Toast.makeText(
                            this@MainActivity,
                            message,
                            if (mqttResult.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                        ).show()
                    }
                    .onFailure { error ->
                        Toast.makeText(
                            this@MainActivity,
                            "$label failed: ${error.message ?: error::class.java.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                refresh()
            }
        }
    }

    private fun runInfluxChannelAction(label: String, action: () -> InfluxActionResult) {
        refreshStoreBackedState()
        if (!saveInfluxDraft()) {
            refresh()
            return
        }
        dashboardExecutor.execute {
            val result = runCatching { action() }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                result
                    .onSuccess { influxResult ->
                        val message = if (influxResult.ok) {
                            "$label: ${influxResult.message}"
                        } else {
                            "$label failed: ${influxResult.message}"
                        }
                        Toast.makeText(
                            this@MainActivity,
                            message,
                            if (influxResult.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                        ).show()
                    }
                    .onFailure { error ->
                        Toast.makeText(
                            this@MainActivity,
                            "$label failed: ${error.message ?: error::class.java.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                refresh()
            }
        }
    }

    private fun currentMaintenanceUiState(state: DashboardState? = dashboardUiStateStore.currentChrome()): DbMaintenanceUiState? {
        val runtime = state?.dbMaintenanceStatus
        val runtimeOperation = runtime?.operation
        if (runtimeOperation != null && (runtime.running || runtime.completed || runtime.error != null)) {
            if (runtime.completed || runtime.error != null) {
                maintenanceLaunchOperation = null
            }
            return DbMaintenanceUiState(
                operation = runtimeOperation,
                running = runtime.running,
                completed = runtime.completed,
                stepIndex = runtime.stepIndex,
                stepCount = runtime.stepCount.takeIf { it > 0 } ?: runtimeOperation.stepsUk.size,
                messageUk = runtime.messageUk,
                messageEn = runtime.messageEn,
                error = runtime.error,
                archivePath = runtime.archivePath,
                cancelAvailable = runtime.cancelAvailable
            )
        }
        maintenanceLaunchOperation?.let { operation ->
            return DbMaintenanceUiState(
                operation = operation,
                running = true,
                completed = false,
                stepIndex = 1,
                stepCount = operation.stepsUk.size,
                messageUk = operation.stepsUk.first(),
                messageEn = operation.stepsEn.first()
            )
        }
        return pendingMaintenanceOperation?.let { operation ->
            DbMaintenanceUiState(
                operation = operation,
                mainArchivePreflight = pendingMainArchivePreflight.takeIf {
                    operation == DbMaintenanceOperation.ARCHIVE
                }
            )
        }
    }

    private fun startDiagnostics(source: String) {
        refreshStoreBackedState()
        requestAccessCheck("start_$source", AccessCheckMode.NORMAL)
        try {
            val dir = DiagnosticLogRecorder.start(applicationContext)
            recordOperationalEvent("log_recording_started", "Diagnostic log recording started", "source=$source path=${dir.absolutePath}")
            Toast.makeText(this, "Запис логів почато", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            recordOperationalEvent("log_recording_error", "Diagnostic log recording failed", error.message)
            Toast.makeText(this, "Помилка запису логів: ${error.message}", Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private fun stopDiagnostics(source: String) {
        refreshStoreBackedState()
        val dir = DiagnosticLogRecorder.stop()
        recordOperationalEvent("log_recording_stopped", "Diagnostic log recording stopped", "source=$source path=${dir?.absolutePath}")
        Toast.makeText(this, "Запис логів зупинено", Toast.LENGTH_SHORT).show()
        refresh()
    }

    companion object {
        private const val TAG = "BYDCollectorUI"
        private const val STARTUP_SETUP_PREFS = "startup_setup"
        private const val KEY_BACKGROUND_SETTINGS_VERSION = "background_settings_version"
        private const val KEY_BACKGROUND_SETTINGS_PENDING_RETURN = "background_settings_pending_return"
        private const val KEY_LOCATION_PERMISSION_SETUP_CONSUMED = "location_permission_setup_consumed"
        private const val STARTUP_ADB_SELF_CHECK_DELAY_MS = 600L
        private const val TELEGRAM_RECONCILE_DELAY_MS = 600L
        private const val LOCATION_PERMISSION_REQUEST_CODE = 4101
    }
}

internal const val DASHBOARD_REFRESH_HEARTBEAT_MS = 1_000L
//Chrome/status fields are producer-fed while the service runs. Activity entry, resume, tab changes,
//and explicit actions still force a reconciliation; the foreground heartbeat does not reread SQLite.
internal val DASHBOARD_CHROME_REFRESH_INTERVAL_MS: Long? = null

internal fun dashboardProfile(tab: AppTab): DashboardLoadProfile? = when (tab) {
    AppTab.MAIN -> DashboardLoadProfile.MAIN
    AppTab.ALL_PARAMETERS -> DashboardLoadProfile.ALL_PARAMETERS
    AppTab.TRIPS -> null
    AppTab.HA -> DashboardLoadProfile.HA
    AppTab.TELEGRAM -> null
    AppTab.STORAGE -> DashboardLoadProfile.STORAGE
    AppTab.EXTRA -> DashboardLoadProfile.EXTRA
    AppTab.LOGS -> DashboardLoadProfile.LOGS
}

internal fun dashboardTabRefreshIntervalMs(tab: AppTab, storageRefreshPending: Boolean): Long? = when (tab) {
    //Main/All/HA dynamic values are producer-fed into the process cache; entry/resume still force
    //one async reconciliation without polling SQLite on every foreground heartbeat.
    AppTab.MAIN -> null
    AppTab.ALL_PARAMETERS -> null
    AppTab.TRIPS -> null
    AppTab.HA -> null
    AppTab.TELEGRAM -> null
    AppTab.STORAGE -> if (storageRefreshPending) 1_000L else null
    AppTab.EXTRA -> null
    AppTab.LOGS -> 5_000L
}

internal fun dashboardSnapshotDue(
    loadedAtElapsedMs: Long?,
    intervalMs: Long?,
    nowMs: Long
): Boolean {
    if (intervalMs == null) return false
    if (loadedAtElapsedMs == null || loadedAtElapsedMs <= 0L || nowMs < loadedAtElapsedMs) return true
    return nowMs - loadedAtElapsedMs >= intervalMs
}

private fun dashboardErrorDetail(error: Throwable): String =
    "${error::class.java.simpleName}: ${error.message ?: "no message"}"

private fun UiLanguage.vehicleKpiLanguage(): VehicleKpiLanguage = when (this) {
    UiLanguage.UK -> VehicleKpiLanguage.UK
    UiLanguage.EN -> VehicleKpiLanguage.EN
}

private fun TelegramMessageType.eventKey(): String = when (this) {
    TelegramMessageType.CHARGING_STARTED -> "charging-started"
    TelegramMessageType.CHARGING_PROGRESS -> "charging-progress"
    TelegramMessageType.CHARGED_TO_100 -> "charged-full"
    TelegramMessageType.CHARGING_STOPPED -> "charging-stopped"
    TelegramMessageType.CHARGE_GUN_CONNECTED -> "charge-gun-connected"
    TelegramMessageType.CHARGE_GUN_DISCONNECTED -> "charge-gun-disconnected"
    TelegramMessageType.LOW_12V_VOLTAGE -> "low-12v"
    TelegramMessageType.TELEMETRY_UNAVAILABLE -> "telemetry-unavailable"
    TelegramMessageType.TRIP_SUMMARY -> "trip-summary"
}

private fun telegramTestStatus(value: String): TelegramTestStatus = when (value) {
    "testing" -> TelegramTestStatus.TESTING
    "success" -> TelegramTestStatus.SUCCESS
    "storage_error" -> TelegramTestStatus.STORAGE_ERROR
    "failed" -> TelegramTestStatus.FAILED
    else -> TelegramTestStatus.NOT_TESTED
}

private data class VerifiedUpdateDownload(
    val info: UpdateInfo,
    val file: File,
    val sha256: String
)

private data class LoadedCredentials(
    val mqttUsername: String,
    val mqttPassword: String,
    val influxUsername: String,
    val influxPassword: String,
    val telegramBotToken: String
)

internal fun startupHardFlowBlocked(
    foreground: Boolean,
    windowFocused: Boolean,
    backgroundPromptVisible: Boolean,
    runtimePermissionRequestInFlight: Boolean
): Boolean = !foreground ||
    !windowFocused ||
    backgroundPromptVisible ||
    runtimePermissionRequestInFlight
