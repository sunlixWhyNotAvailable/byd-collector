package com.bydcollector.collector.ui.compose

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
    val template: String = ""
)

data class TelegramConfig(
    val enabled: Boolean = false,
    val botToken: String = "",
    val botTokenSet: Boolean = false,
    val chatId: String = "",
    val chargeStepPercent: Int = 5,
    val low12vThresholdVolts: Int = 12,
    val telemetryUnavailableMinutes: Int = 1,
    val tripSummaryDelayMinutes: Int = 2,
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
