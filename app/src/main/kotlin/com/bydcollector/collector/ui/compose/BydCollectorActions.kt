package com.bydcollector.collector.ui.compose

import com.bydcollector.collector.telegram.TelegramNavigatorMask

data class MqttDraft(
    val host: String = "",
    val port: String = "",
    val username: String = "",
    val password: String = "",
    val clientId: String = "",
    val topicPrefix: String = "",
    val discoveryPrefix: String = ""
)

data class InfluxDraft(
    val host: String = "",
    val port: String = "",
    val username: String = "",
    val password: String = "",
    val database: String = "",
    val measurement: String = ""
)

enum class TripMapMetric {
    SPEED,
    CONSUMPTION
}

data class TripRoutePointUi(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double? = null,
    val consumptionKwhPer100Km: Double? = null,
    val gap: Boolean = false
)

data class TripSummaryUi(
    val id: String,
    val startAt: String,
    val endAt: String,
    val duration: String,
    val distanceKm: Double?,
    val socStart: Double?,
    val socEnd: Double?,
    val energyKwh: Double?,
    val averageConsumptionKwhPer100Km: Double?,
    val route: List<TripRoutePointUi> = emptyList()
)

data class TripDayUi(
    val id: String,
    val title: String,
    val distanceKm: Double?,
    val energyKwh: Double?,
    val averageConsumptionKwhPer100Km: Double?,
    val trips: List<TripSummaryUi>
)

data class TripMonthUi(
    val id: String,
    val title: String,
    val distanceKm: Double?,
    val energyKwh: Double?,
    val averageConsumptionKwhPer100Km: Double?,
    val days: List<TripDayUi>
)

data class TripYearUi(
    val id: String,
    val title: String,
    val distanceKm: Double?,
    val energyKwh: Double?,
    val averageConsumptionKwhPer100Km: Double?,
    val months: List<TripMonthUi>
)

data class TripsUiState(
    val years: List<TripYearUi> = emptyList(),
    val colorMetric: TripMapMetric = TripMapMetric.SPEED,
    val speedGreenThreshold: Int = 90,
    val speedYellowThreshold: Int = 30,
    val consumptionGreenThreshold: Int = 15,
    val consumptionYellowThreshold: Int = 20,
    val routeLoadingId: String? = null
)

data class TripsUiActions(
    val onColorMetricChanged: (TripMapMetric) -> Unit = {},
    val onSpeedThresholdsChanged: (green: Int, yellow: Int) -> Unit = { _, _ -> },
    val onConsumptionThresholdsChanged: (green: Int, yellow: Int) -> Unit = { _, _ -> },
    val onRouteRequested: (String) -> Unit = {}
)

enum class TelegramMessageType {
    CHARGING_STARTED,
    CHARGING_PROGRESS,
    CHARGED_TO_100,
    CHARGING_STOPPED,
    CHARGE_GUN_CONNECTED,
    CHARGE_GUN_DISCONNECTED,
    LOW_12V_VOLTAGE,
    TELEMETRY_UNAVAILABLE,
    TRIP_SUMMARY
}

data class TelegramMessageConfig(
    val enabled: Boolean = false,
    val template: String = "",
    val usesDefaultTemplate: Boolean = true
)

data class TelegramConfig(
    val enabled: Boolean = false,
    val botToken: String = "",
    val botTokenSet: Boolean = false,
    val chatId: String = "",
    val chargeStepPercent: Int = 5,
    val low12vThresholdVolts: Float = 12.0f,
    val telemetryUnavailableMinutes: Int = 1,
    val tripSummaryDelaySeconds: Int = 10,
    val sendLocation: Boolean = false,
    val navigatorMask: Int = TelegramNavigatorMask.ALL,
    val messages: Map<TelegramMessageType, TelegramMessageConfig> = emptyMap()
)

enum class TelegramTestStatus {
    NOT_TESTED,
    TESTING,
    SUCCESS,
    STORAGE_ERROR,
    FAILED
}

data class TelegramUiState(
    val config: TelegramConfig = TelegramConfig(),
    val testStatus: TelegramTestStatus = TelegramTestStatus.NOT_TESTED
)

data class TelegramUiActions(
    val onConfigChanged: (TelegramConfig) -> Unit = {},
    val onClearBotToken: () -> Unit = {},
    val onTestConnection: () -> Unit = {}
)

interface BydCollectorActions {
    fun onTabSelected(tab: AppTab)
    fun onLanguageSelected(language: UiLanguage)
    fun onDarkThemeSelected(dark: Boolean)

    fun onStartMain()
    fun onStopMain()
    fun onToggleMainAutoStart(enabled: Boolean)
    fun onGrantAdb()
    fun onOpenBackgroundApps()
    fun onOpenArchiveDatabase()
    fun onOpenArchiveDebugDatabase()
    fun onConfirmDatabaseMaintenance()
    fun onCancelDatabaseMaintenance()
    fun onDismissDatabaseMaintenance()
    fun onSetArchiveStorageLimitGb(value: Int)
    fun onDeleteArchives(ids: List<String>)
    fun onShareArchives(ids: List<String>)

    fun onStartDebug()
    fun onStopDebug()
    fun onToggleDebugAutoStart(enabled: Boolean)

    fun onToggleSharedCategories(enabled: Boolean)
    fun onStartMqtt()
    fun onStopMqtt()
    fun onTestMqtt()
    fun onToggleMqttAutoStart(enabled: Boolean)
    fun onToggleMqttCategory(category: String, enabled: Boolean)
    fun onMqttDraftChanged(draft: MqttDraft)

    fun onStartInflux()
    fun onStopInflux()
    fun onTestInflux()
    fun onReExportInflux()
    fun onToggleInfluxAutoStart(enabled: Boolean)
    fun onToggleInfluxCategory(category: String, enabled: Boolean)
    fun onInfluxDraftChanged(draft: InfluxDraft)

    fun onToggleKeepWifi(enabled: Boolean)
    fun onToggleKeepMobile(enabled: Boolean)
    fun onToggleKeepBluetooth(enabled: Boolean)
    fun onToggleKeepCollector(enabled: Boolean)
    fun onToggleTailscaleActivation(enabled: Boolean)

    fun onToggleUpdateAutoCheck(enabled: Boolean)
    fun onCheckForUpdates()
    fun onDismissUpdateDialog()
    fun onInstallUpdate()
    fun onShutdownApp()

    fun onStartJournal()
    fun onStopJournal()
    fun onStartLogcat()
    fun onStopLogcat()
}
