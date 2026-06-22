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
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.diagnostics.DiagnosticLogRecorder
import com.bydcollector.collector.influx.InfluxActionResult
import com.bydcollector.collector.influx.InfluxActions
import com.bydcollector.collector.mqtt.HaMqttActions
import com.bydcollector.collector.mqtt.MqttActionResult
import com.bydcollector.collector.service.CollectorServiceController
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.system.CollectorAutoStart
import com.bydcollector.collector.ui.DashboardState
import com.bydcollector.collector.ui.DashboardStateMerger
import com.bydcollector.collector.ui.DashboardStateProvider
import com.bydcollector.collector.ui.compose.AppTab
import com.bydcollector.collector.ui.compose.BydCollectorActions
import com.bydcollector.collector.ui.compose.BydCollectorApp
import com.bydcollector.collector.ui.compose.InfluxDraft
import com.bydcollector.collector.ui.compose.MqttDraft
import com.bydcollector.collector.ui.compose.UiLanguage
import com.bydcollector.collector.update.UpdateChecker
import com.bydcollector.collector.update.UpdateCheckResult
import com.bydcollector.collector.update.UpdateDownloader
import com.bydcollector.collector.update.UpdateInfo
import com.bydcollector.collector.update.UpdateUiState
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var store: TelemetryStore
    private lateinit var settings: CollectorSettings
    private lateinit var stateProvider: DashboardStateProvider
    private val handler = Handler(Looper.getMainLooper())
    private val dashboardExecutor = Executors.newSingleThreadExecutor()
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private val updateChecker by lazy { UpdateChecker(settings) }
    private val updateDownloader by lazy { UpdateDownloader(applicationContext) }
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
    private var backgroundSetupPromptVisible by mutableStateOf(false)
    private var backgroundSetupPromptAutoLaunch = false

    private val refreshTask = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, DASHBOARD_REFRESH_INTERVAL_MS)
        }
    }

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
            settings.setPollingEnabled(true)
            CollectorServiceController.start(this@MainActivity)
            refresh()
        }

        override fun onStopMain() {
            settings.setPollingEnabled(false)
            CollectorServiceController.stop(this@MainActivity)
            refresh()
        }

        override fun onToggleMainAutoStart(enabled: Boolean) {
            if (settings.isAutoStartEnabled() != enabled) {
                settings.setAutoStartEnabled(enabled)
                val message = if (enabled) {
                    CollectorSettings.AUTO_START_ENABLED_UK
                } else {
                    CollectorSettings.AUTO_START_DISABLED_UK
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                if (enabled) {
                    CollectorAutoStart.scheduleWatchdog(applicationContext, settings, store)
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

        override fun onStartDebug() {
            settings.setDebugBatchSize(debugBatchText.toIntOrNull()?.coerceAtLeast(1) ?: 1)
            settings.setDebugPollingEnabled(true)
            CollectorServiceController.startDebug(this@MainActivity)
            refresh()
        }

        override fun onStopDebug() {
            settings.setDebugPollingEnabled(false)
            CollectorServiceController.stopDebug(this@MainActivity)
            refresh()
        }

        override fun onToggleDebugAutoStart(enabled: Boolean) {
            if (settings.isDebugAutoStartEnabled() != enabled) {
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
            saveMqttDraft()
            settings.setMqttEnabled(true)
            CollectorServiceController.startMqttExport(this@MainActivity)
            refresh()
        }

        override fun onStopMqtt() {
            settings.setMqttEnabled(false)
            CollectorServiceController.stopMqttExport(this@MainActivity)
            refresh()
        }

        override fun onTestMqtt() {
            runMqttChannelAction("MQTT test") {
                HaMqttActions.testConnection(store, settings)
            }
        }

        override fun onToggleMqttAutoStart(enabled: Boolean) {
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
            saveInfluxDraft()
            settings.setInfluxEnabled(true)
            CollectorServiceController.startInfluxExport(this@MainActivity)
            refresh()
        }

        override fun onStopInflux() {
            settings.setInfluxEnabled(false)
            CollectorServiceController.stopInfluxExport(this@MainActivity)
            refresh()
        }

        override fun onTestInflux() {
            runInfluxChannelAction("Influx test") {
                InfluxActions.testConnection(store, settings)
            }
        }

        override fun onReExportInflux() {
            runInfluxChannelAction("Influx re-export") {
                InfluxActions.reExportNewCategories(store, settings)
            }
        }

        override fun onToggleInfluxAutoStart(enabled: Boolean) {
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
            settings.setKeepWifiEnabled(enabled)
            CollectorServiceController.reconcileKeepAlive(this@MainActivity)
            refresh()
        }

        override fun onToggleKeepMobile(enabled: Boolean) {
            settings.setKeepMobileDataEnabled(enabled)
            CollectorServiceController.reconcileKeepAlive(this@MainActivity)
            refresh()
        }

        override fun onToggleKeepBluetooth(enabled: Boolean) {
            settings.setKeepBluetoothEnabled(enabled)
            CollectorServiceController.reconcileKeepAlive(this@MainActivity)
            refresh()
        }

        override fun onToggleKeepCollector(enabled: Boolean) {
            settings.setRecoverCollectorServiceEnabled(enabled)
            CollectorServiceController.reconcileKeepAlive(this@MainActivity)
            refresh()
        }

        override fun onToggleUpdateAutoCheck(enabled: Boolean) {
            settings.setUpdateAutoCheckEnabled(enabled)
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
        val helper = TelemetryDatabaseHelper(applicationContext)
        store = TelemetryStore(applicationContext, helper)
        settings = CollectorSettings(applicationContext, store)
        stateProvider = DashboardStateProvider(applicationContext, store, settings)
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
        if (!setupHandled && BuildConfig.ENABLE_ADB_UI) {
            maybeRunStartupAdbSelfCheck("startup")
        }
        scheduleStartupUpdateCheck()
        handler.postDelayed(refreshTask, DASHBOARD_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        foreground = false
        handler.removeCallbacks(refreshTask)
        handler.removeCallbacks(::runAutomaticUpdateCheck)
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        if (::settings.isInitialized && ::store.isInitialized) {
            CollectorAutoStart.scheduleRestartAfterUiClosed(applicationContext, settings, store)
        }
        dashboardExecutor.shutdownNow()
        updateExecutor.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun refresh() {
        if (refreshInFlight || destroyed) return
        refreshInFlight = true
        val tab = activeTab
        val includeTelemetryDetails = tab == AppTab.MAIN || tab == AppTab.ALL_PARAMETERS || tab == AppTab.LOGS
        val includeDebugStatus = tab == AppTab.ALL_PARAMETERS || tab == AppTab.LOGS
        val includeVehicleKpis = tab == AppTab.ALL_PARAMETERS
        dashboardExecutor.execute {
            val result = runCatching {
                stateProvider.load(
                    includeTelemetryDetails = includeTelemetryDetails,
                    includeDebugStatus = includeDebugStatus,
                    includeVehicleKpis = includeVehicleKpis
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
                        dashboardState = DashboardStateMerger.merge(
                            previous = dashboardState,
                            next = state,
                            preserveDebugStatus = !includeDebugStatus,
                            preserveVehicleKpis = !includeVehicleKpis
                        )
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Dashboard refresh failed", error)
                        store.recordEvent(
                            "dashboard_refresh_failed",
                            "Dashboard refresh failed",
                            "${error::class.java.simpleName}: ${error.message ?: "no message"}"
                        )
                    }
            }
        }
    }

    private fun maybeRunStartupSetup(): Boolean {
        val prefs = getSharedPreferences(STARTUP_SETUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BACKGROUND_SETTINGS_PENDING_RETURN, false)) {
            prefs.edit()
                .putBoolean(KEY_BACKGROUND_SETTINGS_PENDING_RETURN, false)
                .putInt(KEY_BACKGROUND_SETTINGS_VERSION, BuildConfig.VERSION_CODE)
                .apply()
            store.recordEvent(
                "startup_background_settings_returned",
                "Returned from background settings",
                "version=${BuildConfig.VERSION_CODE}"
            )
            if (BuildConfig.ENABLE_ADB_UI) {
                store.recordEvent(
                    "adb_authorization_delayed_after_background",
                    "Scheduling delayed ADB self-check after background setup"
                )
                startupAdbSelfCheckPosted = true
                scheduleAdbSelfCheck(
                    source = "after_background",
                    delayMs = DELAYED_ADB_AUTH_AFTER_BACKGROUND_MS,
                    allowAutoPrompt = true
                )
            }
            return true
        }

        if (startupBackgroundLaunchPosted) return true
        val checkedVersion = prefs.getInt(KEY_BACKGROUND_SETTINGS_VERSION, -1)
        if (checkedVersion == BuildConfig.VERSION_CODE) return false

        startupBackgroundLaunchPosted = true
        store.recordEvent(
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
            if (BuildConfig.ENABLE_ADB_UI) {
                maybeRunStartupAdbSelfCheck("startup")
            }
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
            if (BuildConfig.ENABLE_ADB_UI) {
                maybeRunStartupAdbSelfCheck("startup")
            }
        }
    }

    private fun markStartupBackgroundSetupHandled(eventKey: String, message: String) {
        getSharedPreferences(STARTUP_SETUP_PREFS, MODE_PRIVATE)
            .edit()
            .putInt(KEY_BACKGROUND_SETTINGS_VERSION, BuildConfig.VERSION_CODE)
            .remove(KEY_BACKGROUND_SETTINGS_PENDING_RETURN)
            .apply()
        store.recordEvent(
            eventKey,
            message,
            "version=${BuildConfig.VERSION_CODE}"
        )
    }

    private fun openBackgroundSettings(autoLaunch: Boolean): Boolean {
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
                    store.recordEvent(
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
        store.recordEvent(
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
            store.recordEvent(
                "adb_authorization_delayed",
                "Starting delayed ADB authorization",
                "source=$source"
            )
            AdbAuthorizationManager.requestAuthorization(applicationContext, store)
        }, delayMs)
    }

    private fun scheduleAdbSelfCheck(source: String, delayMs: Long, allowAutoPrompt: Boolean) {
        handler.postDelayed({
            store.recordEvent(
                "startup_adb_self_check_started",
                "Starting startup ADB authorization self-check",
                "source=$source allow_auto_prompt=$allowAutoPrompt"
            )
            AdbAuthorizationManager.selfCheck(
                context = applicationContext,
                store = store,
                allowAutoPrompt = allowAutoPrompt,
                source = source
            )
        }, delayMs)
    }

    private fun scheduleStartupUpdateCheck() {
        //gate auto check to foreground so background service never opens update ui
        handler.removeCallbacks(::runAutomaticUpdateCheck)
        if (settings.isUpdateAutoCheckEnabled()) {
            handler.postDelayed(::runAutomaticUpdateCheck, UPDATE_AUTO_CHECK_DELAY_MS)
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
                while (progress < 100 && !destroyed) {
                    Thread.sleep(350L)
                    progress = updateDownloader.progress(downloadId)
                    if (progress < 0) error("Update download failed")
                    runOnUiThread {
                        if (!destroyed) updateUiState = UpdateUiState.Downloading(info, progress)
                    }
                }
                downloadId
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                result
                    .onSuccess { updateDownloader.install(info) }
                    .onFailure { error ->
                        updateUiState = UpdateUiState.Error(error.message ?: error::class.java.simpleName)
                    }
            }
        }
    }

    private fun saveMqttDraft() {
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

    private fun startDiagnostics(source: String) {
        try {
            val dir = DiagnosticLogRecorder.start(applicationContext)
            store.recordEvent("log_recording_started", "Diagnostic log recording started", "source=$source path=${dir.absolutePath}")
            Toast.makeText(this, "Запис логів почато", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            store.recordEvent("log_recording_error", "Diagnostic log recording failed", error.message)
            Toast.makeText(this, "Помилка запису логів: ${error.message}", Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private fun stopDiagnostics(source: String) {
        val dir = DiagnosticLogRecorder.stop()
        store.recordEvent("log_recording_stopped", "Diagnostic log recording stopped", "source=$source path=${dir?.absolutePath}")
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
        private const val UPDATE_AUTO_CHECK_DELAY_MS = 30_000L
        private const val DASHBOARD_REFRESH_INTERVAL_MS = 5_000L
    }
}
