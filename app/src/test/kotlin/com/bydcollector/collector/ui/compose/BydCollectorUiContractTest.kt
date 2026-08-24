package com.bydcollector.collector.ui.compose

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun tactileControlsKeepPressedAndImmediateSwitchContracts() {
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
        assertFalse(components.contains("pending: Boolean = false"))
        assertFalse(components.contains("SwitchPendingState"))
        assertFalse(components.contains("LocalSwitchConfirmationVersion"))
        assertFalse(components.contains("SWITCH_CENTER_DELAY_MS"))
        assertFalse(components.contains("SWITCH_CONFIRM_TIMEOUT_MS"))
        assertTrue(components.contains("animateDpAsState("))
        assertTrue(components.contains("targetValue = when {"))
        assertTrue(components.contains("else -> 19.dp"))
        assertTrue(components.contains("tween(durationMillis = 120)"))
        assertTrue(components.contains(".size(width = 56.dp, height = 32.dp)"))
        assertTrue(components.contains(".size(thumbSize)"))
        assertTrue(components.contains(".background(if (binary || checked) p.switchThumbOn else p.switchThumbOff)"))
        assertTrue(components.contains("onCheckedChange(!checked)"))
        assertTrue(app.contains(".pressScaleModifier(interactionSource, forcePressed = press.visualPressed)"))
        assertTrue(app.contains(".background(if (selected) p.active else p.surface, Rounded8)"))
    }

    @Test
    fun mainTabUsesMergedStatusAndMaintenanceActions() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()

        assertFalse(app.contains("MainChannelsCard("))
        assertTrue(app.contains("actions::onOpenArchiveDatabase"))
        assertTrue(app.contains("actionUiState.mainArchivePreflight"))
        assertFalse(app.contains("strings.compactDatabase"))
        assertFalse(app.contains("onOpenCompactDatabase"))
        assertInOrder(app, "StatusRow(strings.mainPolling", "strings.allParameters")
        assertInOrder(app, "StatusRow(\"MQTT\"", "StatusRow(\"InfluxDB\"")
    }

    @Test
    fun mainAccessActionsKeepOnlyAdbAndBackgroundWork() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()
        val strings = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorStrings.kt").readText()

        assertFalse(app.contains("grantLocation"))
        assertFalse(actions.contains("onRequestLocationPermission"))
        assertFalse(strings.contains("grantLocation"))
        val mainCollection = app.substringAfter("private fun MainCollectionCard(").substringBefore("private fun MainStatusCard(")
        assertTrue(mainCollection.contains("actions::onGrantAdb"))
        assertTrue(mainCollection.contains("actionUiState.adbGrant"))
        assertTrue(mainCollection.contains("ActionButton(strings.backgroundWork, actions::onOpenBackgroundApps"))
        assertFalse(mainCollection.contains("ActionButton(strings.grantLocation"))
    }

    @Test
    fun roundRobinCountIsActualAndReadOnly() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val components = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorComponents.kt").readText()

        assertTrue(app.contains("NumericInput(state?.debugParameterCount?.toString().orEmpty()"))
        assertFalse(actions.contains("onDebugBatchChanged"))
        assertFalse(activity.contains("debugBatchText"))
        assertFalse(activity.contains("setDebugBatchSize"))
        val numericInput = components.substringAfter("fun NumericInput(").substringBefore("fun CategoryChip(")
        assertTrue(numericInput.contains("Text("))
        assertFalse(numericInput.contains("BasicTextField("))
    }

    @Test
    fun dialogButtonOrderAndMaintenanceCopyStayFixed() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()

        assertInOrder(app, "ActionButton(strings.backgroundSetupOpen", "ActionButton(strings.backgroundSetupDismiss")
        val updateDialog = app.substringAfter("private fun UpdateCheckDialog(")
            .substringBefore("private fun DatabaseMaintenanceDialog(")
        assertInOrder(updateDialog, "text = strings.update", "ActionButton(strings.close")
        assertTrue(app.contains("DatabaseMaintenanceDialog("))
        assertTrue(app.contains(".width(560.dp)"))
        assertTrue(app.contains("if (!state.running && !state.completed && state.error == null) 340.dp else 300.dp"))
        assertInOrder(app, "strings.dbMaintenanceStopWarning", "strings.dbMaintenancePendingTemplate")
        assertInOrder(app, "strings.dbMaintenancePendingTemplate", "strings.dbMaintenanceTelegramDeferredWarning")
        assertInOrder(app, "strings.dbMaintenanceTelegramDeferredWarning", "strings.dbMaintenanceArchivePendingWarning")
        assertInOrder(app, "strings.dbMaintenanceArchivePendingWarning", "strings.dbMaintenanceConfirmTemplate")
        assertInOrder(app, "strings.dbMaintenanceConfirmTemplate", "strings.operationCannotBeStopped")
        assertInOrder(app, "strings.operationCannotBeStopped", "strings.interruptionDataLossRisk")
        assertTrue(app.contains("color = p.yellow, fontSize = 14.sp"))
        assertTrue(app.contains("strings.dbMaintenanceArchivePendingWarning, color = p.yellow"))
        assertTrue(app.contains("inspectionWarning?.let"))
        assertTrue(app.contains("state.warning?.let"))
        assertTrue(app.contains("state.mainArchivePreflight"))
        assertFalse(app.contains("mqttPending = chromeState?.mqttPendingCount"))
        assertTrue(app.contains("fontSize = 15.sp"))
        assertTrue(app.contains("CircularProgressIndicator("))
        assertTrue(app.contains("Modifier.size(28.dp)"))
        assertTrue(app.contains("\"\${strings.step} \${state.stepIndex}/\${state.stepCount}\""))
        assertTrue(app.contains("onCancel: () -> Unit"))
        assertTrue(app.contains("state.error != null || state.completed"))
        assertTrue(app.contains("ActionButton(strings.ok, onDismiss"))
        assertTrue(app.contains("ActionButton(strings.cancel, onCancel, enabled = state.cancelAvailable"))
        val maintenanceDialog = app.substringAfter("private fun DatabaseMaintenanceDialog(")
            .substringBefore("private fun DatabaseMaintenanceConfirmBody(")
        assertInOrder(maintenanceDialog, "strings.yes", "strings.no")
    }

    @Test
    fun driverAssistCategoryIsHiddenAndBottomTabsUseVectorAssets() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val icons = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorIcons.kt").readText()

        assertFalse(app.contains("\"driver_assist\" to strings.driverAssist"))
        assertFalse(icons.contains("Canvas("))
        assertTrue(icons.contains("BottomTabIcon.HA -> R.drawable.ic_tab_ha_link"))
        assertTrue(icons.contains("BottomTabIcon.TELEGRAM -> R.drawable.ic_tab_telegram"))
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
        assertTrue(app.contains("AppTab.ALL_PARAMETERS to (strings.allTab to BottomTabIcon.STORAGE)"))
        assertTrue(app.contains("AppTab.STORAGE to (strings.storageTab to BottomTabIcon.DATABASE)"))
        assertTrue(app.contains("ArchiveDeleteDialog("))
        assertTrue(app.contains("ArchiveStorageProgressDialog("))
        assertTrue(app.contains("val topCardHeight = 156.dp"))
        assertTrue(app.contains("Modifier.weight(0.6f).height(topCardHeight)"))
        assertTrue(app.contains("Modifier.weight(0.4f).height(topCardHeight)"))
        val activeDatabaseSection = app.substringAfter("SectionCard(strings.activeDatabase")
            .substringBefore("SectionCard(strings.archiveStorageLimit")
        assertFalse(activeDatabaseSection.contains("Spacer(Modifier.height(10.dp))"))
        assertFalse(activeDatabaseSection.contains("activeDatabasePath"))
        assertTrue(activeDatabaseSection.contains("strings.activeDatabaseSizeTemplate"))
        assertTrue(activeDatabaseSection.contains("snapshot?.mainDatabaseSizeBytes"))
        assertTrue(activeDatabaseSection.contains("snapshot?.debugDatabaseSizeBytes"))
        assertTrue(activeDatabaseSection.contains("state?.mainStorageCutoverDeferredReason"))
        assertTrue(activeDatabaseSection.contains("strings.mainStorageCutoverDeferred"))
        assertTrue(activeDatabaseSection.contains("state?.mainStorageCutoverError"))
        assertTrue(activeDatabaseSection.contains("state?.debugStorageCutoverError"))
        assertTrue(app.contains("var draftLimitGb by remember(limitGb)"))
        assertTrue(app.contains("ActionButton(strings.ok, { actions.onSetArchiveStorageLimitGb(draftLimitGb) }"))
        assertTrue(app.contains("ActionButton(\"+\", { draftLimitGb = (draftLimitGb + 1).coerceAtMost(10) }"))
        assertFalse(app.contains("ActionButton(\"+\", { actions.onSetArchiveStorageLimitGb"))
        assertTrue(app.contains("state?.archiveStorageScanPending == true"))
        assertTrue(app.contains("StatusPill(strings.archiveCalculating, StatusKind.WAITING"))
        assertTrue(app.contains("strings.archiveStorageScanning else strings.archiveStorageEmpty"))
        assertTrue(strings.contains("archiveStorageScanning = \"Сховище сканується...\""))
        assertTrue(strings.contains("archiveStorageScanning = \"Scanning storage...\""))
        assertTrue(app.contains("StatusPill(archiveUsageText(snapshot, strings), archiveUsageKind(snapshot), compact = true)"))
        assertTrue(app.contains("String.format(strings.archiveCountShortTemplate, entries.size)"))
        assertTrue(app.contains("ReadOnlyPathField(snapshot?.archiveRootPath ?: \"-\", modifier = Modifier.weight(1f))"))
        assertTrue(app.contains("ArchiveShareIconButton("))
        assertTrue(app.contains(".size(42.dp)"))
        assertTrue(app.contains("selectedEntries.all { it.status == ArchiveEntryStatus.COMPRESSED_ZIP }"))
        assertTrue(app.contains("job?.running != true"))
        assertTrue(app.contains("!CollectorService.isArchiveStorageActive()"))
        assertTrue(app.contains("actions.onShareArchives(selectedArchiveIds)"))
        assertTrue(app.contains("ActionButton(sortLabel,"))
        assertTrue(app.contains("modifier = Modifier.width(180.dp)"))
        assertTrue(app.contains("actionUiState.archiveDeleteDispatch"))
        assertTrue(app.contains("strings.deleteSelected"))
        val archiveEntryRow = app.substringAfter("private fun ArchiveEntryRow(").substringBefore("private fun archiveUsageText")
        assertTrue(archiveEntryRow.contains(".height(48.dp)"))
        assertTrue(archiveEntryRow.contains(".size(32.dp)"))
        assertTrue(archiveEntryRow.contains(".height(1.dp)"))
        assertFalse(app.contains("Text(entry.path"))
        assertTrue(app.contains("UiSizeFormatter.bytes("))
        assertTrue(actions.contains("fun onSetArchiveStorageLimitGb(value: Int)"))
        assertTrue(actions.contains("fun onDeleteArchives(ids: List<String>)"))
        assertTrue(actions.contains("fun onShareArchives(ids: List<String>)"))
        assertFalse(actions.contains("fun onReconcileArchiveStorage()"))
        assertFalse(strings.contains("archiveStorageRefresh"))
        assertFalse(strings.contains("compactDatabase"))
        assertTrue(strings.contains("activeDatabase = \"Поточні бази\""))
        assertTrue(strings.contains("activeDatabase = \"Active databases\""))
        assertTrue(actions.contains("fun onOpenArchiveDebugDatabase()"))
        assertTrue(app.contains("actions::onOpenArchiveDebugDatabase"))
        assertTrue(app.contains("DbMaintenanceOperation.DEBUG_ARCHIVE -> strings.archiveDebugDatabase"))
    }

    @Test
    fun allTabsUseEagerScrollAndUpdateNotesKeepBoundedScroll() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()

        assertTrue(app.contains("private fun TabScrollColumn(content: @Composable ColumnScope.() -> Unit)"))
        assertEquals(7, Regex("TabScrollColumn \\{").findAll(app).count())
        assertFalse(app.contains("LazyColumn("))
        assertFalse(app.contains("LazyListScope"))
        assertEquals(3, Regex("\\.verticalScroll\\(").findAll(app).count())
        assertTrue(app.contains(".verticalScroll(releaseNotesScroll)"))
        assertTrue(app.contains(".verticalScroll(rememberScrollState()),"))
    }

    @Test
    fun tripsTabPrecedesHaAndExposesApprovedMapContract() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val strings = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorStrings.kt").readText()
        assertInOrder(app, "AppTab.TRIPS to (strings.tripsTab", "AppTab.HA to (strings.haTab")
        assertTrue(app.contains("© OpenStreetMap contributors"))
        assertTrue(app.contains("TripRouteDialog("))
        assertTrue(app.contains("strings.colorBySpeed"))
        assertTrue(app.contains("strings.colorByConsumption"))
        assertTrue(strings.contains("tripsTab = \"Поїздки\""))
        assertTrue(strings.contains("tripsTab = \"Trips\""))
        val map = app.substringAfter("private fun updateTripMap(").substringBefore("private fun tripMapMarker")
        assertInOrder(map, "color = AndroidColor.BLACK", "run.zipWithNext()")
        assertTrue(map.contains("width = 12f"))
        assertTrue(map.contains("width = 8f"))
        assertEquals(2, Regex("outlinePaint\\.strokeCap = Paint\\.Cap\\.ROUND").findAll(map).count())
        assertEquals(2, Regex("outlinePaint\\.strokeJoin = Paint\\.Join\\.ROUND").findAll(map).count())
        assertTrue(app.contains("private val TripRouteGreen = Color(0xFF147A55)"))
    }

    @Test
    fun optionsTabUsesApprovedUkrainianAndEnglishLabels() {
        val strings = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorStrings.kt").readText()

        assertTrue(strings.contains("extraTab = \"Опції\""))
        assertTrue(strings.contains("extraTab = \"Options\""))
        assertFalse(strings.contains("extraTab = \"Налаштування\""))
    }

    @Test
    fun tripsPreviewPortKeepsBinarySelectorsAndCompactHierarchyContract() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val components = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorComponents.kt").readText()
        assertEquals(2, Regex("binary = true").findAll(app).count())
        assertTrue(components.contains("binary: Boolean = false"))
        assertTrue(components.contains("onCheckedChange(!checked)"))
        assertTrue(app.contains("bodyPadding = 0.dp"))
        assertTrue(app.contains("rememberSaveable { mutableStateOf<List<String>>(emptyList()) }"))
        assertTrue(app.contains("year.id in expandedYears"))
        assertTrue(app.contains(".height(48.dp)"))
        assertTrue(app.contains(".height(34.dp)"))
        assertTrue(app.contains(".padding(start = 48.dp, end = 12.dp"))
        assertTrue(app.contains("heightIn(min = 54.dp)"))
        assertTrue(app.contains("Modifier.width(108.dp)"))
        assertTrue(app.contains("formatSoc(trip.socStart, language)"))
        assertTrue(app.contains("TripGroupRow("))
        assertTrue(app.contains("DisclosureMark(expanded)"))
    }

    @Test
    fun tripRouteAndTelegramControlsMatchApprovedInteractionContract() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val strings = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorStrings.kt").readText()
        val components = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorComponents.kt").readText()
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()
        assertTrue(app.contains("mutableStateOf(TripMapMetric.SPEED)"))
        assertTrue(app.contains("private fun TelegramLocationDialog("))
        assertTrue(app.contains("tripMapMarker(map, p.first(), R.drawable.ic_trip_start_marker)"))
        assertTrue(app.contains("tripMapMarker(map, p.last(), R.drawable.ic_trip_finish_marker)"))
        assertTrue(app.contains("TripEndpointLegend(R.drawable.ic_trip_start_marker, strings.tripStart)"))
        assertTrue(app.contains("TripEndpointLegend(R.drawable.ic_trip_finish_marker, strings.tripFinish)"))
        assertTrue(app.contains("TripNoDataLegend(strings.tripNoData)"))
        assertTrue(app.contains(".background(Color.Gray)"))
        assertTrue(strings.contains("tripNoData = \"Відсутні дані\""))
        assertTrue(strings.contains("tripNoData = \"No data\""))
        val locationRow = app.substringAfter("if (definition.type == TelegramMessageType.TRIP_SUMMARY)")
            .substringBefore("Row(\n            modifier = Modifier.fillMaxWidth(),")
        assertTrue(locationRow.contains("Modifier.fillMaxWidth().height(42.dp)"))
        assertTrue(locationRow.contains("horizontalArrangement = Arrangement.spacedBy(10.dp)"))
        assertTrue(locationRow.contains("modifier = Modifier.weight(1f)"))
        assertTrue(locationRow.contains("NumericInput("))
        assertTrue(locationRow.contains("modifier = Modifier.width(132.dp)"))
        assertTrue(locationRow.contains("emphasized = true"))
        assertTrue(locationRow.contains("textAlign = TextAlign.Center"))
        assertFalse(locationRow.contains("Spacer(Modifier.weight(1f))"))
        assertTrue(strings.contains("charge_start_time\" to \"Час початку заряджання, HH:mm\""))
        assertTrue(strings.contains("charge_end_time\" to \"Час завершення заряджання, HH:mm\""))
        assertTrue(strings.contains("charge_duration_hhmm\" to \"Тривалість заряджання, загальні години HH:mm\""))
        assertTrue(strings.contains("charge_start_time\" to \"Charging start time, HH:mm\""))
        assertTrue(strings.contains("charge_end_time\" to \"Charging end time, HH:mm\""))
        assertTrue(strings.contains("charge_duration_hhmm\" to \"Charging duration, total-hours HH:mm\""))
        val fullChargeDefinition = app.substringAfter("TelegramMessageType.CHARGED_TO_100,")
            .substringBefore("TelegramMessageType.CHARGING_STOPPED,")
        listOf(
            "charge_added_percent",
            "charge_added_kwh",
            "charge_start_time",
            "charge_end_time",
            "charge_duration_hhmm"
        ).forEach { assertTrue(fullChargeDefinition.contains("\"$it\"")) }
        val numericInput = components.substringAfter("fun NumericInput(").substringBefore("fun CategoryChip(")
        assertTrue(numericInput.contains("emphasized: Boolean = false"))
        assertTrue(numericInput.contains("textAlign: TextAlign = TextAlign.End"))
        assertTrue(numericInput.contains("color = if (emphasized) p.text else p.muted.copy(alpha = 0.6f)"))
        assertFalse(app.contains("AndroidColor.rgb(255, 140, 140))\n    }\n    map.post"))
        assertTrue(app.contains("Marker(map)"))
        assertTrue(strings.contains("tripDelay = \"Завершити після P\""))
        assertTrue(strings.contains("tripDelay = \"Finish after P\""))
        val stepper = app.substringAfter("private fun TelegramNumberStepper(").substringBefore("private fun telegramNumberSetting(")
        assertInOrder(stepper, "setting.label", "text = \"-\"")
        assertInOrder(stepper, "text = \"-\"", "NumericInput(")
        assertInOrder(stepper, "NumericInput(", "text = \"+\"")
        assertFalse(stepper.contains("sendLocationLabel"))
        assertFalse(stepper.contains("sendLocationEnabled"))
        assertInOrder(app, "text = strings.sendLocation", "String.format(strings.telegram.locationStatusYes")
        assertInOrder(app, "TelegramLocationDialog(", "TelegramNavigatorMask.GOOGLE")
        assertInOrder(app, "TelegramNavigatorMask.GOOGLE", "TelegramNavigatorMask.WAZE")
        assertInOrder(app, "TelegramNavigatorMask.WAZE", "TelegramNavigatorMask.APPLE")
        assertInOrder(app, "TelegramNavigatorMask.APPLE", "TelegramNavigatorMask.OSM")
        assertTrue(app.contains("dismissOnClickOutside = false"))
        assertTrue(app.contains("step = 0.1f"))
        assertTrue(app.contains("9f..15f"))
        assertTrue(actions.contains("low12vThresholdVolts: Float"))
        assertTrue(app.contains("\"charge_step_duration\""))
        assertTrue(app.contains("\"charge_duration\""))
        assertTrue(components.contains("binary -> 25.dp"))
        assertTrue(components.contains("binary && pressed -> p.accent.copy"))
        assertTrue(components.contains("if (binary || checked) p.switchThumbOn"))
    }

    @Test
    fun updateNotesSelectByLanguageForBothAvailableStates() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()

        assertEquals(2, Regex("AvailableUpdateNotes\\(strings, state.info, language\\)").findAll(app).count())
        assertTrue(app.contains("remember(info.releaseNotes, language)"))
        assertTrue(app.contains("remember(text) { ReleaseNotesMarkdown.parse(text) }"))
    }

    @Test
    fun secretFieldsLoadStoredValuesAndUseLocalVisibilityToggles() {
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val components = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorComponents.kt").readText()
        val icons = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorIcons.kt").readText()
        val strings = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorStrings.kt").readText()

        assertTrue(activity.contains("mqttPassword = source.mqttPassword()"))
        assertTrue(activity.contains("influxPassword = source.influxPassword()"))
        assertTrue(activity.contains("telegramBotToken = source.telegramBotToken()"))
        assertTrue(activity.indexOf("loadCredentialsAfterFirstFrame()") > activity.indexOf("setContent {"))
        assertTrue(activity.contains("if (credentialsLoaded || mqttDraft.username.isNotBlank())"))
        val clearToken = activity.substringAfter("private fun onClearTelegramBotToken()").substringBefore("private fun onTestTelegramConnection()")
        assertTrue(clearToken.contains("telegramCredentialRevision += 1L"))
        assertTrue(components.contains("var passwordVisible by remember { mutableStateOf(false) }"))
        assertFalse(components.contains("rememberSaveable"))
        assertTrue(components.contains("password && !passwordVisible"))
        assertTrue(components.contains("PasswordVisualTransformation()"))
        assertTrue(components.contains("SecretVisibilityIcon("))
        assertTrue(icons.contains("R.drawable.ic_visibility_off"))
        assertTrue(icons.contains("R.drawable.ic_visibility"))
        assertEquals(3, Regex("showPasswordContentDescription = strings.showSecret").findAll(app).count())
        assertEquals(3, Regex("hidePasswordContentDescription = strings.hideSecret").findAll(app).count())
        assertTrue(strings.contains("showSecret = \"Показати секрет\""))
        assertTrue(strings.contains("hideSecret = \"Hide secret\""))
    }

    @Test
    fun telegramTestRequiresPersistedTokenAndDistinguishesStorageFailure() {
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()
        val strings = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorStrings.kt").readText()

        assertTrue(app.contains("enabled = config.botTokenSet && config.chatId.isNotBlank()"))
        assertTrue(activity.contains("if (!settings.isTelegramBotTokenSet())"))
        assertInOrder(activity, "if (!settings.isTelegramBotTokenSet())", "CollectorServiceController.testTelegram(this)")
        assertTrue(activity.contains("setTelegramConnectionStatus(\"storage_error\""))
        assertTrue(activity.contains("else TelegramTestStatus.STORAGE_ERROR"))
        assertTrue(activity.contains("setTelegramConnectionStatus(\"storage_error\", \"clear_failed\")"))
        assertTrue(activity.contains("if (!cleared) settings.setTelegramEnabled(false)"))
        assertTrue(activity.contains("botToken = if (secretWriteFailed) settings.telegramBotToken()"))
        assertTrue(activity.contains("if (enabledChanged || secretWriteFailed)"))
        assertTrue(actions.contains("STORAGE_ERROR"))
        assertTrue(app.contains("TelegramTestStatus.STORAGE_ERROR -> strings.storageError"))
        assertTrue(strings.contains("storageError = \"помилка сховища\""))
        assertTrue(strings.contains("storageError = \"storage error\""))
    }

    @Test
    fun tripSummaryDelayUsesSecondsContract() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()
        val strings = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorStrings.kt").readText()

        assertTrue(actions.contains("tripSummaryDelaySeconds: Int = 10"))
        assertTrue(app.contains("config.tripSummaryDelaySeconds"))
        assertTrue(app.contains("5f..300f"))
        assertTrue(app.contains("strings.secondUnit"))
        assertTrue(app.contains("step = 5f"))
        assertTrue(strings.contains("secondUnit = \"с\""))
        assertTrue(strings.contains("secondUnit = \"sec\""))
    }

    @Test
    fun telegramDefaultsFollowLanguageWhileCustomTextStaysExact() {
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()
        val strings = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorStrings.kt").readText()

        assertTrue(actions.contains("usesDefaultTemplate: Boolean = true"))
        assertInOrder(activity, "uiLanguage = UiLanguage.fromCode(settings.uiLanguageCode())", "setContent {")
        assertInOrder(activity, "settings.setUiLanguageCode(language.code)", "loadTelegramUiState()")
        assertInOrder(activity, "newMessage.usesDefaultTemplate", "settings.clearTelegramTemplate(eventKey)")
        assertTrue(activity.contains("settings.setTelegramTemplate(eventKey, newMessage.template)"))
        assertTrue(strings.contains("TelegramTemplateCatalog.defaultTemplate"))
        assertTrue(strings.contains("locationSettings = \"Налаштування локації\""))
        assertTrue(strings.contains("locationSettings = \"Location settings\""))
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
        assertTrue(app.contains("strings.startLogcat"))
        assertTrue(app.contains("strings.stopLogcat"))
        assertTrue(app.contains("!diagnosticsBusy"))
        assertFalse(app.contains("private fun LogsTab("))
        assertFalse(actions.contains("onStartJournal"))
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
    fun allSwitchesDispatchImmediatelyWithoutPendingConfirmation() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()
        val components = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorComponents.kt").readText()
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()

        assertFalse(actions.contains("enum class KeepAlivePendingSwitch"))
        assertFalse(app.contains("keepAlivePendingSwitches"))
        assertFalse(activity.contains("keepAlivePendingTargets"))
        assertFalse(activity.contains("clearResolvedKeepAlivePending"))
        assertFalse(components.contains("LocalSwitchConfirmationVersion"))
        assertFalse(components.contains("SWITCH_CENTER_DELAY_MS"))
        assertFalse(components.contains("SWITCH_CONFIRM_TIMEOUT_MS"))
        assertFalse(components.contains("SwitchPendingState"))
        assertFalse(components.contains("visuallyPending"))
        assertFalse(components.contains("onCheckedChange(current.target)"))
        assertTrue(components.contains("onCheckedChange(!checked)"))
        assertFalse(activity.contains("dashboardRefreshVersion"))
        assertTrue(activity.contains("private var forcedRefreshPending = false"))
        assertFalse(app.contains("switchConfirmationVersion"))
        assertFalse(activity.contains("switchConfirmationVersion"))
    }

    @Test
    fun buttonLikeControlsKeepPressedFeedbackAndDispatchImmediately() {
        val components = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorComponents.kt").readText()
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val pressHelper = components
            .substringAfter("fun rememberForcedPressClick(")
            .substringBefore("fun Modifier.pressScaleModifier(")
        val bottomTabs = app
            .substringAfter("private fun BottomTabs(")
            .substringBefore("private fun Modifier.clickableNoRipple(")

        assertTrue(components.contains("PRESS_FEEDBACK_MS"))
        assertFalse(components.contains("FORCED_PRESS_DELAY_MS"))
        assertTrue(components.contains("rememberForcedPressClick"))
        assertTrue(components.contains("delay(PRESS_FEEDBACK_MS)"))
        assertTrue(components.contains("latestOnClick()"))
        assertFalse(pressHelper.contains("invokeImmediately"))
        assertInOrder(pressHelper, "clickToken += 1", "latestOnClick()")
        assertTrue(pressHelper.contains("visualPressed = false"))
        assertTrue(pressHelper.contains("locked = false"))
        assertTrue(components.contains("visualPressed"))
        assertTrue(components.contains(".clickable(enabled = enabled && !press.locked"))
        assertFalse(bottomTabs.contains("invokeImmediately"))
        assertFalse(app.contains("invokeImmediately"))
        assertFalse(components.contains("invokeImmediately = true"))
        assertTrue(app.contains("pressScaleModifier(interactionSource, forcePressed = press.visualPressed"))
        assertTrue(app.contains("clickableNoRipple(interactionSource, press.visualPressed"))
        assertTrue(app.contains("ShutdownIconButton(onClick = actions::onShutdownApp)"))
    }

    @Test
    fun asynchronousControlsUseNarrowRealOperationState() {
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()

        listOf(
            "adbGrant",
            "mainArchivePreflight",
            "archiveShare",
            "archiveDeleteDispatch",
            "mqttTest",
            "influxTest",
            "influxReExport"
        ).forEach { flag -> assertTrue(actions.contains("val $flag: Boolean = false"), "Missing scoped flag: $flag") }
        assertTrue(app.contains("state.routeLoadingId == trip.id"))
        assertTrue(app.contains("testStatus != TelegramTestStatus.TESTING"))
        assertTrue(app.contains("runtimeStatus != RuntimeActionStatus.STOPPING && runtimeStatus != RuntimeActionStatus.STOPPED"))
        assertTrue(app.contains("RuntimeActionStatus.ERROR -> MainPollDisplayStatus(strings.error, StatusKind.ERROR)"))
        assertTrue(app.contains("job?.running != true"))
        assertTrue(app.contains("job?.takeIf { !it.running && it.error != null }"))
        assertTrue(activity.contains("archiveJob.updatedAtMs >= dispatchedAtMs"))
        assertTrue(activity.contains("runCatching { diagnosticsExecutor.execute(task) }.onFailure"))
        assertTrue(activity.contains("updateExecutor.execute"))
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
