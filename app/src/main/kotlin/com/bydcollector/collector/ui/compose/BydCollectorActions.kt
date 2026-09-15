package com.bydcollector.collector.ui.compose

import com.bydcollector.collector.telegram.TelegramNavigatorMask
import com.bydcollector.collector.telegram.TelegramPayloadLimitState
import com.bydcollector.collector.ha.HaEndpointProfile
import com.bydcollector.collector.update.UpdateHintAppearance

internal fun validEndpointDraft(host: String, port: String, optional: Boolean = false): Boolean {
    if (optional && host.isBlank() && port.isBlank()) return true
    return host.isNotBlank() && host.trim().none { it.isWhitespace() || it == '/' || it == '\\' } &&
        port.toIntOrNull()?.let { it in 1..65535 } == true
}

data class MqttDraft(
    val host: String = "",
    val port: String = "",
    val username: String = "",
    val password: String = "",
    val clientId: String = "",
    val topicPrefix: String = "",
    val discoveryPrefix: String = "",
    val alternativeHost: String = "",
    val alternativePort: String = "",
    val editingProfile: HaEndpointProfile = HaEndpointProfile.PRIMARY
)

data class InfluxDraft(
    val host: String = "",
    val port: String = "",
    val username: String = "",
    val password: String = "",
    val database: String = "",
    val measurement: String = "",
    val alternativeHost: String = "",
    val alternativePort: String = "",
    val editingProfile: HaEndpointProfile = HaEndpointProfile.PRIMARY
)

data class BydCollectorActionUiState(
    val adbGrant: Boolean = false,
    val mainArchivePreflight: Boolean = false,
    val archiveShare: Boolean = false,
    val archiveShareHandoffGeneration: Long = 0L,
    val archiveDeleteDispatch: Boolean = false,
    val mqttTest: Boolean = false,
    val influxTest: Boolean = false
)

enum class TripMapMetric {
    SPEED,
    CONSUMPTION
}

data class TripRoutePointUi(
    val sequence: Long,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double? = null,
    val consumptionKwhPer100Km: Double? = null,
    val gap: Boolean = false,
    val final: Boolean = false
)

enum class TripEnergyCompleteness {
    UNAVAILABLE,
    PARTIAL,
    COMPLETE
}

data class TripSummaryUi(
    val id: String,
    val startAt: String,
    val endAt: String,
    val duration: String,
    val distanceKm: Double?,
    val socStart: Double?,
    val socEnd: Double?,
    val energyKwh: Double?,
    val dischargedKwh: Double? = null,
    val regeneratedKwh: Double? = null,
    val netKwh: Double? = null,
    val averageConsumptionKwhPer100Km: Double?,
    val energyCompleteness: TripEnergyCompleteness = TripEnergyCompleteness.UNAVAILABLE,
    val netCompleteness: TripEnergyCompleteness = energyCompleteness,
    val energyCoveredMs: Long? = null,
    val energyUncoveredMs: Long? = null,
    val energyObservedAt: String? = null,
    val route: List<TripRoutePointUi> = emptyList(),
    val open: Boolean = false
)

data class TripDayUi(
    val id: String,
    val title: String,
    val distanceKm: Double?,
    val energyKwh: Double?,
    val dischargedKwh: Double?,
    val regeneratedKwh: Double?,
    val netKwh: Double?,
    val averageConsumptionKwhPer100Km: Double?,
    val energyCompleteness: TripEnergyCompleteness,
    val netCompleteness: TripEnergyCompleteness = energyCompleteness,
    val trips: List<TripSummaryUi>
)

data class TripMonthUi(
    val id: String,
    val title: String,
    val distanceKm: Double?,
    val energyKwh: Double?,
    val dischargedKwh: Double?,
    val regeneratedKwh: Double?,
    val netKwh: Double?,
    val averageConsumptionKwhPer100Km: Double?,
    val energyCompleteness: TripEnergyCompleteness,
    val netCompleteness: TripEnergyCompleteness = energyCompleteness,
    val days: List<TripDayUi>
)

data class TripYearUi(
    val id: String,
    val title: String,
    val distanceKm: Double?,
    val energyKwh: Double?,
    val dischargedKwh: Double?,
    val regeneratedKwh: Double?,
    val netKwh: Double?,
    val averageConsumptionKwhPer100Km: Double?,
    val energyCompleteness: TripEnergyCompleteness,
    val netCompleteness: TripEnergyCompleteness = energyCompleteness,
    val months: List<TripMonthUi>
)

data class CurrentTripUi(
    val trip: TripSummaryUi,
    val nextRouteSequence: Long = 0L
)

data class TripsUiState(
    val years: List<TripYearUi> = emptyList(),
    val colorMetric: TripMapMetric = TripMapMetric.SPEED,
    val speedGreenThreshold: Int = 90,
    val speedYellowThreshold: Int = 30,
    val consumptionGreenThreshold: Int = 15,
    val consumptionYellowThreshold: Int = 20,
    val routeLoadingId: String? = null,
    val databasePath: String = "",
    val databaseSizeBytes: Long = 0L,
    val currentTripAvailabilityKnown: Boolean = false,
    val availableCurrentTrip: CurrentTripUi? = null,
    val currentTripModal: CurrentTripUi? = null
)

data class TripsUiActions(
    val onColorMetricChanged: (TripMapMetric) -> Unit = {},
    val onSpeedThresholdsChanged: (green: Int, yellow: Int) -> Unit = { _, _ -> },
    val onConsumptionThresholdsChanged: (green: Int, yellow: Int) -> Unit = { _, _ -> },
    val onRouteRequested: (String) -> Unit = {},
    val onCurrentTripRequested: () -> Unit = {},
    val onCurrentTripDismissed: () -> Unit = {},
    val onCompressDatabase: () -> Unit = {},
    val onRefreshRequested: () -> Unit = {}
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
    val low12vThresholdVolts: Float = 12.5f,
    val telemetryUnavailableMinutes: Int = 1,
    val tripSummaryDelaySeconds: Int = 10,
    val sendLocation: Boolean = false,
    val navigatorMask: Int = TelegramNavigatorMask.NONE,
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
    val testStatus: TelegramTestStatus = TelegramTestStatus.NOT_TESTED,
    val tripTemplateLimitState: TelegramPayloadLimitState = TelegramPayloadLimitState.NONE
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
    fun onToggleInfluxAutoStart(enabled: Boolean)
    fun onToggleInfluxCategory(category: String, enabled: Boolean)
    fun onInfluxDraftChanged(draft: InfluxDraft)

    fun onToggleConnectivityRecovery(enabled: Boolean)
    fun onToggleKeepBluetooth(enabled: Boolean)
    fun onToggleKeepCollector(enabled: Boolean)
    fun onToggleTailscaleActivation(enabled: Boolean)

    fun onToggleUpdateAutoCheck(enabled: Boolean)
    fun onToggleUpdateHint(enabled: Boolean) {}
    fun onUpdateHintAppearanceChanged(appearance: UpdateHintAppearance) {}
    fun onCheckForUpdates()
    fun onDismissUpdateDialog()
    fun onInstallUpdate()
    fun onShutdownApp()

    fun onStartLogcat()
    fun onStopLogcat()
    fun onShareLogs()
    fun onClearLogs()
}
