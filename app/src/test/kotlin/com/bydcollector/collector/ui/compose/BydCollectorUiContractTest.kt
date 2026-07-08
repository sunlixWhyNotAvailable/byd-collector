package com.bydcollector.collector.ui.compose

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BydCollectorUiContractTest {
    @Test
    fun topBarSubtitleUsesTelemetryAndVersionCopy() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val strings = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorStrings.kt").readText()

        assertTrue(app.contains("appVersionName"))
        assertTrue(app.contains("text = \"\${strings.topBarSubtitle} | v\$appVersionName\""))
        assertTrue(strings.contains("topBarSubtitle = \"Збирання телеметрії авто\""))
        assertTrue(strings.contains("topBarSubtitle = \"Auto telemetry collection\""))
    }

    @Test
    fun tactileControlsKeepPressedAndPendingContracts() {
        val components = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorComponents.kt").readText()
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()

        assertTrue(components.contains("height(42.dp)"))
        assertTrue(components.contains("fontSize = 12.sp"))
        assertTrue(components.contains("val visualPressed = pressed || press.visualPressed"))
        assertTrue(components.contains("primary && visualPressed -> p.active"))
        assertTrue(components.contains("primary -> p.accent"))
        assertTrue(components.contains("primary -> p.accentText"))
        assertFalse(components.contains("primary -> p.activeSoft"))
        assertTrue(components.contains("visualPressed -> p.activeSoft"))
        assertTrue(components.contains("pending: Boolean = false"))
        assertTrue(components.contains("animateDpAsState("))
        assertTrue(components.contains("targetValue = when {"))
        assertTrue(components.contains("pending -> 12.dp"))
        assertTrue(components.contains(".size(width = 56.dp, height = 32.dp)"))
        assertTrue(components.contains(".background(if (visualChecked || visuallyPending) p.switchThumbOn else p.switchThumbOff)"))
        assertTrue(app.contains(".pressScaleModifier(interactionSource, forcePressed = press.visualPressed)"))
        assertTrue(app.contains(".background(if (selected) p.active else p.surface, Rounded8)"))
    }

    @Test
    fun mainTabUsesMergedStatusAndMaintenanceActions() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()

        assertFalse(app.contains("MainChannelsCard("))
        assertTrue(app.contains("ActionButton(strings.archiveDatabase, actions::onOpenArchiveDatabase, primary = true, modifier = Modifier.fillMaxWidth())"))
        assertFalse(app.contains("strings.compactDatabase"))
        assertFalse(app.contains("onOpenCompactDatabase"))
        assertInOrder(app, "StatusRow(strings.mainPolling", "strings.allParameters")
        assertInOrder(app, "StatusRow(\"MQTT\"", "StatusRow(\"InfluxDB\"")
    }

    @Test
    fun dialogButtonOrderAndMaintenanceCopyStayFixed() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()

        assertInOrder(app, "ActionButton(strings.backgroundSetupOpen", "ActionButton(strings.backgroundSetupDismiss")
        assertInOrder(app, "text = strings.update", "ActionButton(strings.close")
        assertTrue(app.contains("DatabaseMaintenanceDialog("))
        assertTrue(app.contains(".width(560.dp)"))
        assertTrue(app.contains("if (!state.running && !state.completed && state.error == null) 340.dp else 300.dp"))
        assertInOrder(app, "strings.dbMaintenanceStopWarning", "strings.dbMaintenancePendingTemplate")
        assertInOrder(app, "strings.dbMaintenancePendingTemplate", "strings.dbMaintenanceArchivePendingWarning")
        assertInOrder(app, "strings.dbMaintenanceArchivePendingWarning", "strings.dbMaintenanceConfirmTemplate")
        assertInOrder(app, "strings.dbMaintenanceConfirmTemplate", "strings.operationCannotBeStopped")
        assertInOrder(app, "strings.operationCannotBeStopped", "strings.interruptionDataLossRisk")
        assertTrue(app.contains("color = p.yellow, fontSize = 14.sp"))
        assertTrue(app.contains("strings.dbMaintenanceArchivePendingWarning, color = p.yellow"))
        assertTrue(app.contains("fontSize = 15.sp"))
        assertTrue(app.contains("CircularProgressIndicator("))
        assertTrue(app.contains("Modifier.size(28.dp)"))
        assertTrue(app.contains("\"\${strings.step} \${state.stepIndex}/\${state.stepCount}\""))
        assertTrue(app.contains("onCancel: () -> Unit"))
        assertTrue(app.contains("state.error != null || state.completed"))
        assertTrue(app.contains("ActionButton(strings.ok, onDismiss"))
        assertTrue(app.contains("ActionButton(strings.cancel, onCancel, enabled = state.cancelAvailable"))
        assertInOrder(app, "strings.yes", "strings.no")
    }

    @Test
    fun driverAssistCategoryIsHiddenAndBottomTabsUseVectorAssets() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val icons = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorIcons.kt").readText()

        assertFalse(app.contains("\"driver_assist\" to strings.driverAssist"))
        assertFalse(icons.contains("Canvas("))
        assertTrue(icons.contains("BottomTabIcon.HA -> R.drawable.ic_tab_ha_link"))
        assertTrue(icons.contains("BottomTabIcon.DATABASE -> R.drawable.ic_tab_all_data"))
        assertTrue(icons.contains("BottomTabIcon.STORAGE -> R.drawable.ic_tab_storage"))
        assertTrue(icons.contains("painterResource(id = icon.drawableRes())"))
        assertTrue(icons.contains("fun ShutdownIcon("))
        assertTrue(icons.contains("R.drawable.ic_shutdown"))
    }

    @Test
    fun storageTabExposesArchiveManagementWithoutCompact() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()
        val strings = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorStrings.kt").readText()

        assertTrue(strings.contains("STORAGE"))
        assertTrue(strings.contains("val storageTab: String"))
        assertTrue(strings.contains("storageTab = \"Сховище\""))
        assertTrue(strings.contains("storageTab = \"Storage\""))
        assertTrue(app.contains("AppTab.STORAGE -> StorageTab("))
        assertTrue(app.contains("AppTab.STORAGE to (strings.storageTab to BottomTabIcon.STORAGE)"))
        assertTrue(app.contains("ArchiveDeleteDialog("))
        assertTrue(app.contains("ArchiveStorageProgressDialog("))
        assertTrue(app.contains("val topCardHeight = 164.dp"))
        assertTrue(app.contains("Modifier.weight(0.6f).height(topCardHeight)"))
        assertTrue(app.contains("Modifier.weight(0.4f).height(topCardHeight)"))
        assertTrue(app.contains("var draftLimitGb by remember(limitGb)"))
        assertTrue(app.contains("ActionButton(strings.ok, { actions.onSetArchiveStorageLimitGb(draftLimitGb) }"))
        assertTrue(app.contains("ActionButton(\"+\", { draftLimitGb = (draftLimitGb + 1).coerceAtMost(10) }"))
        assertFalse(app.contains("ActionButton(\"+\", { actions.onSetArchiveStorageLimitGb"))
        assertTrue(app.contains("state?.archiveStorageScanPending == true"))
        assertTrue(app.contains("StatusPill(strings.archiveCalculating, StatusKind.WAITING"))
        assertTrue(app.contains("StatusPill(archiveUsageText(snapshot, strings), archiveUsageKind(snapshot), compact = true)"))
        assertTrue(app.contains("String.format(strings.archiveCountShortTemplate, entries.size)"))
        assertTrue(app.contains("ReadOnlyPathField(snapshot?.archiveRootPath ?: \"-\", modifier = Modifier.weight(0.5f))"))
        assertTrue(app.contains("ActionButton(sortLabel,"))
        assertTrue(app.contains("ActionButton(strings.deleteSelected"))
        assertFalse(app.contains("Text(entry.path"))
        assertTrue(app.contains("UiSizeFormatter.bytes("))
        assertTrue(actions.contains("fun onSetArchiveStorageLimitGb(value: Int)"))
        assertTrue(actions.contains("fun onDeleteArchives(ids: List<String>)"))
        assertTrue(actions.contains("fun onReconcileArchiveStorage()"))
        assertFalse(strings.contains("compactDatabase"))
    }

    @Test
    fun optionsTabUsesPreviewRuntimeLayoutAndShutdownAction() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()
        val strings = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorStrings.kt").readText()

        assertTrue(actions.contains("fun onShutdownApp()"))
        assertTrue(strings.contains("val keepAlive: String"))
        assertTrue(strings.contains("val appRuntime: String"))
        assertTrue(strings.contains("val shutdown: String"))
        assertTrue(strings.contains("val shutdownDescription: String"))
        assertTrue(strings.contains("val activateTailscale: String"))
        assertTrue(strings.contains("val activateTailscaleDescription: String"))
        assertTrue(strings.contains("Перевіряти наявність Tailscale та активувати VPN"))
        assertTrue(strings.contains("Check for Tailscale and activate VPN"))
        assertTrue(app.contains("val optionsCardHeight = 312.dp"))
        assertTrue(app.contains("SectionCard(strings.keepAlive, Modifier.weight(1f).height(optionsCardHeight))"))
        assertTrue(app.contains("SectionCard(strings.appRuntime, Modifier.weight(1f).height(optionsCardHeight))"))
        assertFalse(app.contains("AppRuntimeBottomSpacer()"))
        assertFalse(app.contains("Spacer(Modifier.height(92.dp))"))
        assertFalse(app.contains("SwitchRow(strings.activateTailscale"))
        assertTrue(app.contains("strings.restoreCollector"))
        assertFalse(app.contains("pending = KeepAlivePendingSwitch.COLLECTOR in keepAlivePendingSwitches"))
        assertTrue(app.contains("if (divider)"))
        assertTrue(app.contains(".background(p.border)"))
        assertInOrder(app, "strings.keepWifi", "strings.restoreCollector")
        assertInOrder(app, "SectionCard(strings.appRuntime", "TailscaleRuntimeRow(")
        assertInOrder(app, "TailscaleRuntimeRow(", "UpdateSettingsRow(")
        assertInOrder(app, "UpdateSettingsRow(", "ShutdownSettingsRow(")
        assertTrue(app.contains("ShutdownIcon(color = p.red"))
        assertTrue(app.contains("val buttonBackground = if (visualPressed) p.redSoft else p.redSoft.copy(alpha = 0.56f)"))
        assertTrue(app.contains(".background(buttonBackground, Rounded8)"))
        assertTrue(app.contains("actions::onShutdownApp"))
    }

    @Test
    fun allSwitchesUseUniversalPendingConfirmation() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()
        val components = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorComponents.kt").readText()
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()

        assertFalse(actions.contains("enum class KeepAlivePendingSwitch"))
        assertFalse(app.contains("keepAlivePendingSwitches"))
        assertFalse(activity.contains("keepAlivePendingTargets"))
        assertFalse(activity.contains("clearResolvedKeepAlivePending"))
        assertTrue(components.contains("LocalSwitchConfirmationVersion"))
        assertTrue(components.contains("SWITCH_CENTER_DELAY_MS"))
        assertTrue(components.contains("SWITCH_CONFIRM_TIMEOUT_MS"))
        assertTrue(components.contains("pending -> 12.dp"))
        assertTrue(components.contains("onCheckedChange(current.target)"))
        assertTrue(components.contains("if (checked != current.target) return@LaunchedEffect"))
        assertTrue(activity.contains("private var dashboardRefreshVersion by mutableStateOf(0)"))
        assertTrue(activity.contains("private var refreshAgainAfterCurrent = false"))
        assertTrue(app.contains("switchConfirmationVersion: Int = 0"))
        assertTrue(activity.contains("switchConfirmationVersion = dashboardRefreshVersion"))
    }

    @Test
    fun buttonLikeControlsForceVisualPressBeforeAction() {
        val components = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorComponents.kt").readText()
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()

        assertTrue(components.contains("FORCED_PRESS_DELAY_MS"))
        assertTrue(components.contains("rememberForcedPressClick"))
        assertTrue(components.contains("delay(FORCED_PRESS_DELAY_MS)"))
        assertTrue(components.contains("latestOnClick()"))
        assertTrue(components.contains("visualPressed"))
        assertTrue(components.contains(".clickable(enabled = enabled && !press.locked"))
        assertTrue(app.contains("pressScaleModifier(interactionSource, forcePressed = press.visualPressed"))
        assertTrue(app.contains("clickableNoRipple(interactionSource, press.visualPressed"))
        assertTrue(app.contains("ShutdownIconButton(onClick = actions::onShutdownApp)"))
    }

    private fun assertInOrder(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue(firstIndex >= 0, "Missing first token: $first")
        assertTrue(secondIndex >= 0, "Missing second token: $second")
        assertTrue(firstIndex < secondIndex, "Expected `$first` before `$second`")
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
