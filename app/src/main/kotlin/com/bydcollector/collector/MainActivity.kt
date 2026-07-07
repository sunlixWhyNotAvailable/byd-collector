package com.bydcollector.collector

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.diagnostics.DiagnosticLogRecorder
import com.bydcollector.collector.influx.InfluxActionResult
import com.bydcollector.collector.influx.InfluxActions
import com.bydcollector.collector.maintenance.DbMaintenanceOperation
import com.bydcollector.collector.maintenance.DbMaintenanceRuntimeStatus
import com.bydcollector.collector.maintenance.DbMaintenanceUiState
import com.bydcollector.collector.mqtt.HaMqttActions
import com.bydcollector.collector.mqtt.MqttActionResult
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorServiceController
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.system.CollectorAutoStart
import com.bydcollector.collector.ui.DashboardState
import com.bydcollector.collector.ui.DashboardStateMerger
import com.bydcollector.collector.ui.DashboardStateProvider
import com.bydcollector.collector.ui.VehicleKpiLanguage
import com.bydcollector.collector.ui.compose.AppTab
import com.bydcollector.collector.ui.compose.BydCollectorActions
import com.bydcollector.collector.ui.compose.BydCollectorApp
import com.bydcollector.collector.ui.compose.InfluxDraft
import com.bydcollector.collector.ui.compose.MqttDraft
import com.bydcollector.collector.ui.compose.UiLanguage
import com.bydcollector.collector.update.UpdateAutoCheckAction
import com.bydcollector.collector.update.UpdateAutoCheckRuntime
import com.bydcollector.collector.update.UpdateApkVerifier
import com.bydcollector.collector.update.UpdateChecker
import com.bydcollector.collector.update.UpdateCheckResult
import com.bydcollector.collector.update.UpdateDownloader
import com.bydcollector.collector.update.UpdateInfo
import com.bydcollector.collector.update.UpdateUiState
import com.bydcollector.collector.util.namedSingleThreadExecutor
import java.io.File

//coordinates the user-facing compose shell while CollectorService owns long-running vehicle work
class MainActivity : ComponentActivity() {
    private lateinit var store: TelemetryStore
    private lateinit var settings: CollectorSettings
    private lateinit var stateProvider: DashboardStateProvider
    private val handler = Handler(Looper.getMainLooper())
    private val dashboardExecutor = namedSingleThreadExecutor("byd-ui-dash")
    private val updateExecutor = namedSingleThreadExecutor("byd-update")
    private val updateChecker by lazy { UpdateChecker(settings) }
    private val updateDownloader by lazy { UpdateDownloader(applicationContext) }
    private val updateApkVerifier by lazy { UpdateApkVerifier(applicationContext) }
    private var startupBackgroundLaunchPosted = false
    private var startupAdbSelfCheckPosted = false
    @Volatile private var refreshInFlight = false
    @Volatile private var updateCheckInFlight = false
    @Volatile private var foreground = false
    @Volatile private var destroyed = false

    private var dashboardState by mutableStateOf<DashboardState?>(null)
    private var activeTab by mutableStateOf(AppTab.MAIN)
    private var uiLanguage by mutableStateOf(UiLanguage.UK)
    private var darkTheme by mutableStateOf(true)
    private var debugBatchText by mutableStateOf("")
    private var mqttDraft by mutableStateOf(MqttDraft())
    private var influxDraft by mutableStateOf(InfluxDraft())
    private var updateUiState by mutableStateOf<UpdateUiState>(UpdateUiState.Hidden)
    private var pendingMaintenanceOperation by mutableStateOf<DbMaintenanceOperation?>(null)
    private var maintenanceLaunchOperation by mutableStateOf<DbMaintenanceOperation?>(null)
    private var dashboardRefreshVersion by mutableStateOf(0)
    private var backgroundSetupPromptVisible by mutableStateOf(false)
    private var backgroundSetupPromptAutoLaunch = false
    @Volatile private var refreshAgainAfterCurrent = false

    private val refreshTask = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, DASHBOARD_REFRESH_INTERVAL_MS)
        }
    }
    private val updateAutoCheckTimerTask = Runnable { onUpdateAutoCheckTimerElapsed() }

    //maps every ui command to persisted settings plus service intents so process restarts keep the same intent
    private val uiActions = object : BydCollectorActions {
        override fun onTabSelected(tab: AppTab) {
            activeTab = tab
            refresh()
        }

        override fun onLanguageSelected(language: UiLanguage) {
            uiLanguage = language
        }

        override fun onDarkThemeSelected(dark: Boolean) {
            darkTheme = dark
        }

        override fun onStartMain() {
            refreshStoreBackedState()
            settings.setMainManuallyStopped(false)
            settings.setPollingEnabled(true)
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

        override fun onOpenCompactDatabase() {
            pendingMaintenanceOperation = DbMaintenanceOperation.COMPACT
            refresh()
        }

        override fun onOpenArchiveDatabase() {
            pendingMaintenanceOperation = DbMaintenanceOperation.ARCHIVE
            refresh()
        }

        override fun onConfirmDatabaseMaintenance() {
            val operation = pendingMaintenanceOperation ?: return
            maintenanceLaunchOperation = operation
            pendingMaintenanceOperation = null
            settings.setDbMaintenanceStatus(
                DbMaintenanceRuntimeStatus(
                    operation = operation,
                    running = true,
                    completed = false,
                    stepIndex = 1,
                    stepCount = operation.stepsUk.size,
                    messageUk = operation.stepsUk.first(),
                    messageEn = operation.stepsEn.first()
                ),
                synchronous = true
            )
            runCatching {
                when (operation) {
                DbMaintenanceOperation.COMPACT -> CollectorServiceController.compactDatabase(this@MainActivity)
                DbMaintenanceOperation.ARCHIVE -> CollectorServiceController.archiveDatabase(this@MainActivity)
                }
            }.onFailure { error ->
                settings.setDbMaintenanceStatus(
                    settings.dbMaintenanceStatus().copy(
                        running = false,
                        completed = false,
                        error = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                    )
                )
                maintenanceLaunchOperation = null
            }
            refresh()
        }

        override fun onCancelDatabaseMaintenance() {
            CollectorServiceController.cancelDatabaseMaintenance(this@MainActivity)
            refresh()
        }

        override fun onDismissDatabaseMaintenance() {
            if (dashboardState?.dbMaintenanceStatus?.running == true || maintenanceLaunchOperation != null) return
            pendingMaintenanceOperation = null
            settings.clearDbMaintenanceStatus()
            refresh()
        }

        override fun onStartDebug() {
            refreshStoreBackedState()
            settings.setDebugBatchSize(debugBatchText.toIntOrNull()?.coerceAtLeast(1) ?: 1)
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

        override fun onDebugBatchChanged(value: String) {
            debugBatchText = value
            value.toIntOrNull()?.let { settings.setDebugBatchSize(it.coerceAtLeast(1)) }
        }

        override fun onToggleSharedCategories(enabled: Boolean) {
            settings.setHaSharedCategoriesEnabled(enabled)
            refresh()
        }

        override fun onStartMqtt() {
            refreshStoreBackedState()
            saveMqttDraft()
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
            mqttDraft = draft
            saveMqttDraft()
        }

        override fun onStartInflux() {
            refreshStoreBackedState()
            saveInfluxDraft()
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
        store = currentStore()
        settings = CollectorSettings(applicationContext, store)
        if (!CollectorService.isMaintenanceRunningInProcess()) {
            settings.recoverInterruptedDbMaintenanceIfNeeded("activity_start")
        }
        val clearedUserShutdown = settings.clearUserShutdownRequestIfSet()
        if (clearedUserShutdown) {
            settings.clearRuntimeManualStops()
            CollectorAutoStart.recoverFromForeground(applicationContext, settings, currentStore())
        }
        stateProvider = DashboardStateProvider(applicationContext, { BydCollectorApplication.store(applicationContext) }, settings)
        debugBatchText = settings.debugBatchSize().toString()
        mqttDraft = MqttDraft(
            host = settings.mqttHost(),
            port = settings.mqttPort().toString(),
            username = settings.mqttUsername(),
            password = "",
            clientId = settings.mqttClientId(),
            topicPrefix = settings.mqttTopicPrefix(),
            discoveryPrefix = settings.mqttDiscoveryPrefix()
        )
        influxDraft = InfluxDraft(
            host = settings.influxHost(),
            port = settings.influxPort().toString(),
            username = settings.influxUsername(),
            password = "",
            database = settings.influxDatabase(),
            measurement = settings.influxMeasurement()
        )
        startRuntimeUpdateAutoCheck()
        setContent {
            BydCollectorApp(
                state = dashboardState,
                activeTab = activeTab,
                language = uiLanguage,
                darkTheme = darkTheme,
                debugBatchText = debugBatchText,
                mqttDraft = mqttDraft,
                influxDraft = influxDraft,
                appVersionName = BuildConfig.VERSION_NAME,
                updateAutoCheckEnabled = settings.isUpdateAutoCheckEnabled(),
                updateUiState = updateUiState,
                databaseMaintenanceUiState = currentMaintenanceUiState(),
                switchConfirmationVersion = dashboardRefreshVersion,
                actions = uiActions,
                backgroundSetupPromptVisible = backgroundSetupPromptVisible,
                onOpenBackgroundSettingsFromPrompt = ::onOpenBackgroundSettingsFromPrompt,
                onDismissBackgroundSetupPrompt = ::onDismissBackgroundSetupPrompt
            )
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        refresh()
        val setupHandled = maybeRunStartupSetup()
        if (!setupHandled) {
            maybeRunStartupAdbSelfCheck("startup")
        }
        runPendingStartupUpdateCheckIfReady()
        handler.postDelayed(refreshTask, DASHBOARD_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        foreground = false
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        //asks the watchdog path to recover service work if the user closes only the activity
        if (
            ::settings.isInitialized &&
            ::store.isInitialized &&
            !CollectorSettings.isDbMaintenanceRunning(applicationContext)
        ) {
            refreshStoreBackedState()
            CollectorAutoStart.scheduleRestartAfterUiClosed(applicationContext, settings, currentStore())
        }
        dashboardExecutor.shutdownNow()
        updateExecutor.shutdownNow()
        handler.removeCallbacks(updateAutoCheckTimerTask)
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun currentStore(): TelemetryStore = BydCollectorApplication.store(applicationContext)

    private fun refreshStoreBackedState() {
        store = currentStore()
        settings = CollectorSettings(applicationContext, store)
    }

    private fun refresh() {
        refreshStoreBackedState()
        if (destroyed) return
        if (refreshInFlight) {
            refreshAgainAfterCurrent = true
            return
        }
        refreshInFlight = true
        val tab = activeTab
        //loads only the heavy dashboard slices needed by the visible tab to keep ui refresh cheap
        val includeTelemetryDetails = tab == AppTab.MAIN || tab == AppTab.ALL_PARAMETERS || tab == AppTab.LOGS
        val includeDebugStatus = tab == AppTab.ALL_PARAMETERS || tab == AppTab.LOGS
        val includeVehicleKpis = foreground || tab == AppTab.ALL_PARAMETERS
        val kpiLanguage = if (uiLanguage == UiLanguage.UK) VehicleKpiLanguage.UK else VehicleKpiLanguage.EN
        dashboardExecutor.execute {
            val result = runCatching {
                stateProvider.load(
                    includeTelemetryDetails = includeTelemetryDetails,
                    includeDebugStatus = includeDebugStatus,
                    includeVehicleKpis = includeVehicleKpis,
                    vehicleKpiLanguage = kpiLanguage
                )
            }
            runOnUiThread {
                refreshInFlight = false
                if (destroyed) return@runOnUiThread
                result
                    .onSuccess { state ->
                        if (!state.autoStartEnabled && state.debugAutoStartEnabled) {
                            settings.setDebugAutoStartEnabled(false)
                        }
                        //preserves last known heavy-tab values so switching tabs does not blank telemetry until next poll
                        dashboardState = DashboardStateMerger.merge(
                            previous = dashboardState,
                            next = state,
                            preserveDebugStatus = !includeDebugStatus,
                            preserveVehicleKpis = !includeVehicleKpis
                        )
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Dashboard refresh failed", error)
                        currentStore().recordEvent(
                            "dashboard_refresh_failed",
                            "Dashboard refresh failed",
                            "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                        )
                    }
                dashboardRefreshVersion += 1
                if (refreshAgainAfterCurrent && !destroyed) {
                    refreshAgainAfterCurrent = false
                    refresh()
                }
            }
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
            currentStore().recordEvent(
                "startup_background_settings_returned",
                "Returned from background settings",
                "version=${BuildConfig.VERSION_CODE}"
            )
            currentStore().recordEvent(
                "adb_authorization_delayed_after_background",
                "Scheduling delayed ADB self-check after background setup"
            )
            startupAdbSelfCheckPosted = true
            scheduleAdbSelfCheck(
                source = "after_background",
                delayMs = DELAYED_ADB_AUTH_AFTER_BACKGROUND_MS,
                allowAutoPrompt = true
            )
            return true
        }

        if (startupBackgroundLaunchPosted) return true
        val checkedVersion = prefs.getInt(KEY_BACKGROUND_SETTINGS_VERSION, -1)
        if (checkedVersion == BuildConfig.VERSION_CODE) return false

        startupBackgroundLaunchPosted = true
        currentStore().recordEvent(
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
            maybeRunStartupAdbSelfCheck("startup")
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
            maybeRunStartupAdbSelfCheck("startup")
        }
    }

    private fun markStartupBackgroundSetupHandled(eventKey: String, message: String) {
        getSharedPreferences(STARTUP_SETUP_PREFS, MODE_PRIVATE)
            .edit()
            .putInt(KEY_BACKGROUND_SETTINGS_VERSION, BuildConfig.VERSION_CODE)
            .remove(KEY_BACKGROUND_SETTINGS_PENDING_RETURN)
            .apply()
        currentStore().recordEvent(
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
                    currentStore().recordEvent(
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
        currentStore().recordEvent(
            "adb_authorization_flow_started",
            "Starting local ADB RSA authorization request",
            "source=$source"
        )
        scheduleAdbAuthorization(source = source, delayMs = 0L)
    }

    private fun maybeRunStartupAdbSelfCheck(source: String) {
        if (startupAdbSelfCheckPosted) return
        startupAdbSelfCheckPosted = true
        scheduleAdbSelfCheck(
            source = source,
            delayMs = STARTUP_ADB_SELF_CHECK_DELAY_MS,
            allowAutoPrompt = true
        )
    }

    private fun scheduleAdbAuthorization(source: String, delayMs: Long) {
        handler.postDelayed({
            currentStore().recordEvent(
                "adb_authorization_delayed",
                "Starting delayed ADB authorization",
                "source=$source"
            )
            AdbAuthorizationManager.requestAuthorization(applicationContext, currentStore())
        }, delayMs)
    }

    private fun scheduleAdbSelfCheck(source: String, delayMs: Long, allowAutoPrompt: Boolean) {
        handler.postDelayed({
            currentStore().recordEvent(
                "startup_adb_self_check_started",
                "Starting startup ADB authorization self-check",
                "source=$source allow_auto_prompt=$allowAutoPrompt"
            )
            AdbAuthorizationManager.selfCheck(
                context = applicationContext,
                store = currentStore(),
                allowAutoPrompt = allowAutoPrompt,
                source = source
            )
        }, delayMs)
    }

    private fun startRuntimeUpdateAutoCheck() {
        //starts the 30s clock at activity runtime creation, even before the app is foregrounded
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
        if (force) {
            updateUiState = UpdateUiState.Checking
        }
        updateExecutor.execute {
            val result = updateChecker.check(force)
            runOnUiThread {
                updateCheckInFlight = false
                if (destroyed || !foreground) return@runOnUiThread
                updateUiState = when (result) {
                    is UpdateCheckResult.Available -> UpdateUiState.Available(result.info)
                    UpdateCheckResult.UpToDate -> if (force) UpdateUiState.UpToDate else UpdateUiState.Hidden
                    is UpdateCheckResult.Error -> if (force) UpdateUiState.Error(result.message) else UpdateUiState.Hidden
                }
            }
        }
    }

    private fun startUpdateDownload(info: UpdateInfo) {
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
                        if (!destroyed) updateUiState = UpdateUiState.Downloading(info, progress)
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
                            updateUiState = UpdateUiState.Error(
                                if (!finalValidation.ok) finalValidation.message else "APK digest changed before install"
                            )
                            return@onSuccess
                        }
                        updateDownloader.install(verified.info, verified.file)
                    }
                    .onFailure { error ->
                        updateUiState = UpdateUiState.Error(error.message ?: error::class.java.simpleName)
                    }
            }
        }
    }

    private fun saveMqttDraft() {
        //keeps saved passwords sticky while empty password fields mean "leave existing secret unchanged"
        settings.setMqttHost(mqttDraft.host)
        settings.setMqttPort(mqttDraft.port.toIntOrNull() ?: settings.mqttPort())
        settings.setMqttUsername(mqttDraft.username)
        if (mqttDraft.password.isNotBlank()) {
            settings.setMqttPassword(mqttDraft.password)
        }
        settings.setMqttClientId(mqttDraft.clientId)
        settings.setMqttTopicPrefix(mqttDraft.topicPrefix)
        settings.setMqttDiscoveryPrefix(mqttDraft.discoveryPrefix)
    }

    private fun saveInfluxDraft() {
        //mirrors mqtt draft semantics so editing non-secret influx fields never clears the stored password
        settings.setInfluxHost(influxDraft.host)
        settings.setInfluxPort(influxDraft.port.toIntOrNull() ?: settings.influxPort())
        settings.setInfluxUsername(influxDraft.username)
        if (influxDraft.password.isNotBlank()) {
            settings.setInfluxPassword(influxDraft.password)
        }
        settings.setInfluxDatabase(influxDraft.database)
        settings.setInfluxMeasurement(influxDraft.measurement)
    }

    private fun runMqttChannelAction(label: String, action: () -> MqttActionResult) {
        refreshStoreBackedState()
        saveMqttDraft()
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
        saveInfluxDraft()
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

    private fun currentMaintenanceUiState(): DbMaintenanceUiState? {
        val runtime = dashboardState?.dbMaintenanceStatus
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
        return pendingMaintenanceOperation?.let { DbMaintenanceUiState(operation = it) }
    }

    private fun startDiagnostics(source: String) {
        refreshStoreBackedState()
        try {
            val dir = DiagnosticLogRecorder.start(applicationContext)
            currentStore().recordEvent("log_recording_started", "Diagnostic log recording started", "source=$source path=${dir.absolutePath}")
            Toast.makeText(this, "Запис логів почато", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            currentStore().recordEvent("log_recording_error", "Diagnostic log recording failed", error.message)
            Toast.makeText(this, "Помилка запису логів: ${error.message}", Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private fun stopDiagnostics(source: String) {
        refreshStoreBackedState()
        val dir = DiagnosticLogRecorder.stop()
        currentStore().recordEvent("log_recording_stopped", "Diagnostic log recording stopped", "source=$source path=${dir?.absolutePath}")
        Toast.makeText(this, "Запис логів зупинено", Toast.LENGTH_SHORT).show()
        refresh()
    }

    companion object {
        private const val TAG = "BYDCollectorUI"
        private const val STARTUP_SETUP_PREFS = "startup_setup"
        private const val KEY_BACKGROUND_SETTINGS_VERSION = "background_settings_version"
        private const val KEY_BACKGROUND_SETTINGS_PENDING_RETURN = "background_settings_pending_return"
        private const val DELAYED_ADB_AUTH_AFTER_BACKGROUND_MS = 2_500L
        private const val STARTUP_ADB_SELF_CHECK_DELAY_MS = 900L
        private const val DASHBOARD_REFRESH_INTERVAL_MS = 5_000L
    }
}

private data class VerifiedUpdateDownload(
    val info: UpdateInfo,
    val file: File,
    val sha256: String
)
