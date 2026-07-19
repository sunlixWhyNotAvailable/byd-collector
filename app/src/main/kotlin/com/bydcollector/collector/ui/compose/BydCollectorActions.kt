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
    fun onReconcileArchiveStorage()

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
