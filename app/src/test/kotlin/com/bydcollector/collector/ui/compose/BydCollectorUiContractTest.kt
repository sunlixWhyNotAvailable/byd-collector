package com.bydcollector.collector.ui.compose

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BydCollectorUiContractTest {
    @Test
    fun endpointDraftRejectsInvalidHostsBeforeStartWhilePreservingSavedHostTrimming() {
        listOf("mqtt\\local", "influx\\local", "http://influx.local", "mqtt local", "").forEach { host ->
            assertFalse(validEndpointDraft(host, "1883"), host)
            assertFalse(validEndpointDraft(host, "8086", optional = true), host)
        }
        assertTrue(validEndpointDraft(" mqtt.local ", "1883"))
        assertTrue(validEndpointDraft(" influx.local ", "8086"))
        assertTrue(validEndpointDraft("", "", optional = true))
        assertFalse(validEndpointDraft("mqtt.local", ""))
        assertFalse(validEndpointDraft("mqtt.local", "65536"))
        assertFalse(validEndpointDraft("", "1883", optional = true))
    }

    @Test
    fun tripsCompressionUsesRealBackgroundProgressAndPreservesHistoryContract() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        val service = sourceFile("com/bydcollector/collector/service/TripCompressionService.kt").readText()
        val dialog = app.substringAfter("private fun TripsCompressionDialog(").substringBefore("private fun DatabaseMaintenanceDialog(")
        assertTrue(app.contains("TripCompressionService.state.collectAsStateWithLifecycle()"))
        assertTrue(activity.contains("TripCompressionService.start(applicationContext)"))
        assertTrue(activity.contains("sqliteFootprintBytes(trips.databaseFile)"))
        assertTrue(dialog.contains("ModalInputBlocker()"))
        assertTrue(dialog.contains("background(p.background.copy(alpha = 0.82f))"))
        assertTrue(dialog.contains("if (!state.running)"))
        assertFalse(dialog.contains("strings.cancel"))
        assertFalse(service.contains("DatabaseMaintenanceService"))
        val finish = service.substringAfter("private fun finishRun(").substringBefore("private fun clearInstanceIfOwned(")
        assertTrue(finish.contains("mainHandler.post"))
        assertInOrder(finish, "stopForeground(STOP_FOREGROUND_REMOVE)", "activeToken = null")
        UiLanguage.entries.forEach { language ->
            assertEquals(6, strings(language).tripsCompressionSteps.size)
            assertTrue(strings(language).tripsCompressionWarning.isNotBlank())
        }
    }

    @Test
    fun mqttConnectionEditingUsesRealOwnershipAndGuardsLateDraftCallbacks() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val activity = sourceFile("com/bydcollector/collector/MainActivity.kt").readText()
        assertTrue(app.contains("CollectorService.mqttConnection.state.collectAsStateWithLifecycle()"))
        assertTrue(app.contains("connection.activeRoute, !connection.owned && !actionUiState.mqttTest"))
        assertTrue(app.contains("if (connection.owned) Text(strings.stopChannelToEdit"))
        assertTrue(activity.contains("if (CollectorService.mqttConnection.owned || actionUiState.mqttTest) return"))
        assertTrue(activity.contains("if (CollectorService.mqttConnection.owned) return true"))
        assertTrue(activity.contains("HaMqttActions.testConnection(actionStore, settings, profile = selected)"))
        assertTrue(app.contains("CollectorService.influxConnection.state.collectAsStateWithLifecycle()"))
        assertTrue(app.contains("connection.activeRoute, !connection.owned && !actionUiState.influxTest"))
        assertTrue(activity.contains("if (CollectorService.influxConnection.owned || actionUiState.influxTest) return"))
        assertTrue(activity.contains("if (CollectorService.influxConnection.owned) return true"))
        assertTrue(activity.contains("InfluxActions.testConnection(actionStore, settings, profile = selected)"))
    }

    @Test
    fun haQueuesShareTheAutostartRowWithoutManualReExport() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val actions = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorActions.kt").readText()
        assertEquals(3, Regex("ChannelQueueRow\\(").findAll(app).count())
        assertTrue(app.contains("ReadOnlyPathField(\"\${strings.queued}:   \$pendingText\", Modifier.weight(1f).padding(horizontal = 24.dp))"))
        assertFalse(app.contains("ChannelPrelude"))
        assertFalse(actions.contains("ReExport"))
    }

    @Test
    fun nativeLaunchThemeMatchesCollectorDarkSurface() {
        val styles = listOf(
            File("src/main/res/values/styles.xml"),
            File("app/src/main/res/values/styles.xml")
        ).firstOrNull { it.isFile }?.readText() ?: error("Missing styles.xml")

        assertTrue(styles.contains("<item name=\"android:windowBackground\">#080D12</item>"))
        assertTrue(styles.contains("<item name=\"android:statusBarColor\">#080D12</item>"))
        assertTrue(styles.contains("<item name=\"android:navigationBarColor\">#080D12</item>"))
        assertTrue(styles.contains("<item name=\"android:windowLightStatusBar\">false</item>"))
    }

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
        assertTrue(components.contains("onValueChange = toggle.onChange"))
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
        assertInOrder(app, "strings.dbMaintenancePendingTemplate", "strings.dbMaintenanceArchivePendingWarning")
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
        assertTrue(activeDatabaseSection.contains("snapshot?.tripsDatabaseSizeBytes"))
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
        assertTrue(app.contains("ReadOnlyPathField(snapshot?.archiveRootPath ?: \"-\", modifier = Modifier.weight(0.6f))"))
        val shareAction = app.substringAfter("label = strings.shareArchives,").substringBefore("val sortLabel")
        assertTrue(shareAction.contains("modifier = Modifier.weight(0.4f)"))
        assertTrue(app.contains("ArchiveShareIconButton("))
        assertTrue(app.contains(".size(42.dp)"))
        assertTrue(app.contains("selectedEntries.all { it.status == ArchiveEntryStatus.COMPRESSED_ZIP }"))
        assertTrue(actions.contains("val archiveShareHandoffGeneration: Long = 0L"))
        assertTrue(app.contains("remember(listKey, actionUiState.archiveShareHandoffGeneration)"))
        assertTrue(app.contains("job?.running != true"))
        assertTrue(app.contains("!CollectorService.isArchiveStorageActive()"))
        assertTrue(app.contains("actions.onShareArchives(selectedArchiveIds)"))
        assertTrue(app.contains("ActionButton(sortLabel,"))
        assertTrue(app.contains("modifier = Modifier.width(180.dp)"))
        assertTrue(app.contains("actionUiState.archiveDeleteDispatch"))
        assertTrue(app.contains("strings.deleteSelected"))
        val inlineStatus = app.substringAfter("private fun ArchiveStorageInlineStatus(")
            .substringBefore("private fun ArchiveEntryRow(")
        assertTrue(inlineStatus.contains("status.stepCount.takeIf { it > 0 }"))
        assertTrue(inlineStatus.contains("\"${'$'}{status.stepIndex} / ${'$'}it · \""))
        assertTrue(inlineStatus.contains("text = progress +"))
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

        assertTrue(app.contains("private fun TabScrollColumn("))
        assertEquals(7, Regex("TabScrollColumn\\(").findAll(app).count() - 1)
        assertFalse(app.contains("LazyColumn("))
        assertFalse(app.contains("LazyListScope"))
        assertEquals(5, Regex("\\.verticalScroll\\(").findAll(app).count())
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
    fun tripsKeepSlidingMetricSelectorsAndHeaderUsesEqualWidthSlidingWithoutGeometryChanges() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val components = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorComponents.kt").readText()
        val tripsTab = app.substringAfter("private fun TripsTab(").substringBefore("private fun TripThresholdRow(")
        val tripRow = app.substringAfter("private fun TripTableRow(").substringBefore("private fun RowScope.TripTableCell(")
        val routeDialog = app.substringAfter("private fun TripRouteDialog(").substringBefore("private fun createTripMap(")
        val topHeader = app.substringAfter("private fun TopHeader(").substringBefore("private fun TabScrollColumn(")

        assertEquals(1, Regex("SegmentedControl\\(").findAll(tripsTab).count())
        assertTrue(tripsTab.contains("headerHeight = 50.dp"))
        assertTrue(tripsTab.contains("leftWidth = 150.dp"))
        assertTrue(tripsTab.contains("rightWidth = 210.dp"))
        assertTrue(tripsTab.contains("fontSize = 13.sp"))
        assertTrue(tripsTab.contains("animateSelection = true"))
        assertFalse(tripsTab.contains("BydSwitch("))
        assertEquals(1, Regex("SegmentedControl\\(").findAll(routeDialog).count())
        assertTrue(routeDialog.contains("leftWidth = 170.dp"))
        assertTrue(routeDialog.contains("rightWidth = 190.dp"))
        assertTrue(routeDialog.contains("animateSelection = true"))
        assertFalse(routeDialog.contains("BydSwitch("))
        assertInOrder(routeDialog, "TripNoDataLegend(strings.tripNoData)", "Spacer(Modifier.weight(1f))")
        assertInOrder(routeDialog, "Spacer(Modifier.weight(1f))", "ActionButton(strings.close")
        assertTrue(tripRow.contains("listAction = true"))
        assertTrue(tripRow.contains("fontSize = 12.sp"))
        assertFalse(tripRow.contains("primary = true"))
        assertEquals(2, Regex("SegmentedControl\\(").findAll(topHeader).count())
        assertEquals(2, Regex("animateSelection = true").findAll(topHeader).count())
        assertTrue(topHeader.contains("Modifier.width(138.dp)"))
        assertTrue(topHeader.contains("Modifier.width(154.dp)"))
        assertTrue(topHeader.contains(".height(96.dp)"))
        val equalSegments = components.substringAfter("// Match the existing weighted Row")
            .substringBefore("private fun SegmentButton(")
        assertTrue(equalSegments.contains("constraints.maxWidth / 2"))
        assertTrue(equalSegments.contains("targetValue = if (leftSelected) 0.dp else leftSize"))
        assertEquals(2, Regex("drawSelection = false, directClick = true").findAll(equalSegments).count())
        assertFalse(equalSegments.contains("rememberForcedPressClick"))
        assertTrue(components.contains("headerHeight: Dp = 42.dp"))
        assertTrue(components.contains(".height(headerHeight)"))
        assertTrue(components.contains("val animationDuration = if (animateSelection) 180 else 0"))
        assertTrue(components.contains("targetValue = if (leftSelected) leftWidth else rightWidth"))
        assertTrue(components.contains("targetValue = contentPadding + if (leftSelected) 0.dp else leftWidth + itemSpacing"))
        val segmentButton = components.substringAfter("private fun SegmentButton(").substringBefore("fun KpiTile(")
        assertTrue(components.contains("drawSelection = false, directClick = true"))
        assertTrue(segmentButton.contains("val press = if (directClick) null else rememberForcedPressClick"))
        assertTrue(segmentButton.contains("if (press == null) onClick() else press.onClick()"))
        assertTrue(components.contains("listAction && visualPressed -> p.accent.copy(alpha = if (p.dark) 0.24f else 0.14f)"))
        assertTrue(components.contains("listAction -> p.accent.copy(alpha = if (p.dark) 0.20f else 0.04f)"))
        assertTrue(components.contains("!enabled && listAction -> p.borderStrong.copy(alpha = 0.68f)"))
        assertTrue(components.contains("!enabled -> p.borderStrong"))
        assertTrue(components.contains("listAction || primary -> p.accent"))
        assertTrue(tripsTab.contains("bodyPadding = 0.dp"))
        assertTrue(app.contains("session.expandedTripYears"))
        assertTrue(app.contains("year.id in session.expandedTripYears"))
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
        val locationRow = app.substringAfter("val setting = telegramNumberSetting(")
            .substringBefore("TextValueInput(")
        assertTrue(locationRow.contains("Modifier.fillMaxWidth().height(42.dp)"))
        assertTrue(locationRow.contains("horizontalArrangement = Arrangement.spacedBy(10.dp)"))
        assertTrue(locationRow.contains("modifier = Modifier.weight(1f)"))
        assertTrue(locationRow.contains("NumericInput("))
        assertTrue(locationRow.contains("modifier = Modifier.width(132.dp)"))
        assertTrue(locationRow.contains("emphasized = true"))
        assertTrue(locationRow.contains("textAlign = TextAlign.Center"))
        assertFalse(locationRow.contains("Spacer(Modifier.weight(1f))"))
        assertTrue(locationRow.contains("Spacer(Modifier.height(42.dp))"))
        assertInOrder(locationRow, "NumericInput(", "text = \"{}\"")
        assertTrue(components.contains("minLines = if (multiline) 7 else 1"))
        assertTrue(components.contains("maxLines = if (multiline) 7 else 1"))
        assertFalse(components.contains("if (multiline) 104.dp"))
        val variableDialog = app.substringAfter("private fun TelegramVariableDialog(")
            .substringBefore("private fun telegramTestStatusText(")
        assertInOrder(variableDialog, "strings.telegram.insertVariable", "Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())")
        assertInOrder(variableDialog, "variables.forEachIndexed", "ActionButton(strings.close")
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
        assertTrue(strings.contains("templateLimitWarning = \"Перевищено обмеження шаблону 4 096 символів\""))
        assertTrue(strings.contains("templateLimitWarning = \"Template exceeds the 4,096-character limit\""))
        assertTrue(strings.contains("templateLimitWithLocation = \"Перевищено обмеження шаблону 4 096 символів із локацією\""))
        assertTrue(strings.contains("templateLimitWithLocation = \"Template exceeds the 4,096-character limit with location\""))
        assertTrue(components.contains("headerWarning: String? = null"))
        assertTrue(components.contains("private fun TemplateLimitWarning("))
        assertTrue(components.contains("color = p.yellow"))
        assertTrue(app.contains("tripTemplateLimitState = uiState.tripTemplateLimitState"))
        assertTrue(app.contains("when (limitState)"))
        assertTrue(actions.contains("val tripTemplateLimitState: TelegramPayloadLimitState"))
        assertTrue(app.contains("headerWarning = templateLimitWarning"))
        val stepper = app.substringAfter("private fun TelegramNumberStepper(").substringBefore("private fun telegramNumberSetting(")
        assertInOrder(stepper, "setting.label", "text = \"-\"")
        assertInOrder(stepper, "text = \"-\"", "NumericInput(")
        assertInOrder(stepper, "NumericInput(", "text = \"+\"")
        assertFalse(stepper.contains("sendLocationLabel"))
        assertFalse(stepper.contains("sendLocationEnabled"))
        assertInOrder(app, "text = strings.sendLocation", "String.format(strings.telegram.locationStatusYes")
        val locationDialog = app.substringAfter("private fun TelegramLocationDialog(")
            .substringBefore("private fun formatTelegramNumber(")
        assertInOrder(locationDialog, "TelegramNavigatorMask.GOOGLE", "TelegramNavigatorMask.WAZE")
        assertInOrder(locationDialog, "TelegramNavigatorMask.WAZE", "TelegramNavigatorMask.APPLE")
        assertInOrder(locationDialog, "TelegramNavigatorMask.APPLE", "TelegramNavigatorMask.OSM")
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
        assertTrue(strings.contains("Відновлювати Wi-Fi та моб. зв'язок"))
        assertTrue(strings.contains("Restore Wi-Fi and cellular"))
        assertTrue(strings.contains("Відновлювати Bluetooth"))
        assertTrue(strings.contains("Restore Bluetooth"))
        assertTrue(strings.contains("Поділитись логами"))
        assertTrue(strings.contains("Share logs"))
        assertTrue(strings.contains("Очистити логи"))
        assertTrue(strings.contains("Clear logs"))
        assertTrue(app.contains("val optionsCardHeight = 312.dp"))
        assertTrue(app.contains("SectionCard(strings.keepAlive, Modifier.weight(1f).height(optionsCardHeight))"))
        assertTrue(app.contains("SectionCard(strings.appRuntime, Modifier.weight(1f).height(optionsCardHeight))"))
        assertTrue(app.contains("strings.startLogcat"))
        assertTrue(app.contains("strings.stopLogcat"))
        assertTrue(app.contains("strings.shareLogs"))
        assertTrue(app.contains("strings.clearLogs"))
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
        assertTrue(actions.contains("fun onToggleConnectivityRecovery(enabled: Boolean)"))
        assertTrue(actions.contains("fun onShareLogs()"))
        assertTrue(actions.contains("fun onClearLogs()"))
        assertFalse(actions.contains("onToggleKeepWifi"))
        assertFalse(actions.contains("onToggleKeepMobile"))
        assertInOrder(app, "strings.restoreConnectivity", "strings.restoreCollector")
        assertInOrder(app, "strings.startLogcat", "strings.shareLogs")
        assertInOrder(app, "SectionCard(strings.appRuntime", "TailscaleRuntimeRow(")
        assertInOrder(app, "TailscaleRuntimeRow(", "UpdateSettingsRow(")
        assertInOrder(app, "UpdateSettingsRow(", "ShutdownSettingsRow(")
        assertTrue(app.contains("ShutdownIcon(color = p.red"))
        assertTrue(app.contains("val buttonBackground = if (visualPressed) p.redSoft else p.redSoft.copy(alpha = 0.56f)"))
        assertTrue(app.contains(".background(buttonBackground, Rounded8)"))
        assertTrue(app.contains("actions::onShutdownApp"))
    }

    @Test
    fun clearLogsUsesBlockingLocalizedConfirmationBeforeAction() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val strings = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorStrings.kt").readText()
        val extra = app.substringAfter("private fun ExtraTab(").substringBefore("private fun TailscaleRuntimeRow(")
        val confirm = app.substringAfter("if (showClearLogsDialog)").substringBefore("@Composable\nprivate fun BackgroundAppsSetupPrompt")
        val dialog = app.substringAfter("private fun ClearLogsDialog(").substringBefore("private fun DownloadingUpdateHeader(")

        assertTrue(app.contains("var showClearLogsDialog by remember"))
        assertTrue(app.contains("onRequestClearLogs = { showClearLogsDialog = true }"))
        assertTrue(extra.contains("onRequestClearLogs"))
        assertFalse(extra.contains("actions::onClearLogs"))
        assertFalse(extra.contains("actions.onClearLogs()"))
        assertInOrder(confirm, "showClearLogsDialog = false", "actions.onClearLogs()")
        assertTrue(dialog.contains(".background(p.background.copy(alpha = 0.82f))"))
        assertTrue(dialog.contains("ModalInputBlocker()"))
        assertTrue(dialog.contains(".width(520.dp)"))
        assertTrue(dialog.contains(".background(p.panel, Rounded8)"))
        assertTrue(dialog.contains(".border(1.dp, p.borderStrong, Rounded8)"))
        assertTrue(dialog.contains(".padding(20.dp)"))
        assertTrue(dialog.contains("verticalArrangement = Arrangement.spacedBy(14.dp)"))
        assertTrue(dialog.contains("val p = LocalBydPalette.current"))
        assertFalse(dialog.contains("if (language"))
        assertInOrder(dialog, "strings.clearLogsTitle", "strings.clearLogsScope")
        assertInOrder(dialog, "strings.clearLogsScope", "strings.clearLogsIrreversible")
        assertInOrder(dialog, "strings.clearLogsConfirm", "strings.cancel")
        assertTrue(dialog.contains("primary = true"))
        assertTrue(strings.contains("val clearLogsTitle: String"))
        assertTrue(strings.contains("val clearLogsScope: String"))
        assertTrue(strings.contains("val clearLogsIrreversible: String"))
        assertTrue(strings.contains("val clearLogsConfirm: String"))
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
        assertTrue(components.contains("onValueChange = toggle.onChange"))
        assertFalse(activity.contains("dashboardRefreshVersion"))
        assertTrue(activity.contains("private var forcedRefreshPending = false"))
        assertFalse(app.contains("switchConfirmationVersion"))
        assertFalse(activity.contains("switchConfirmationVersion"))
    }

    @Test
    fun switchRowsHaveOneSemanticOwnerAndShareTheOriginalPressedFeedback() {
        val components = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorComponents.kt").readText()
        val row = components.substringAfter("fun SwitchControlRow(").substringBefore("data class SwitchToggle(")
        val target = components.substringAfter("private fun Modifier.switchTarget(").substringBefore("fun BydSwitch(")
        val thumb = components.substringAfter("fun BydSwitch(").substringBefore("fun InfoRow(")

        assertTrue(row.contains("modifier.switchTarget(toggle, interactionSource)"))
        assertTrue(row.contains("BydSwitch(toggle.checked, null, enabled = toggle.enabled, interactionSource = interactionSource)"))
        assertTrue(target.contains("if (toggle == null) this else toggleable("))
        listOf("value = toggle.checked", "enabled = toggle.enabled", "role = Role.Switch", "onValueChange = toggle.onChange")
            .forEach { assertTrue(target.contains(it), it) }
        assertFalse(target.contains("delay("))
        assertFalse(target.contains("remember"))
        assertTrue(thumb.contains("onCheckedChange?.let { SwitchToggle(checked, it, enabled) }"))
        assertFalse(thumb.contains(".clickable("))
        assertTrue(thumb.contains("interactionSource.collectIsPressedAsState()"))
        assertTrue(thumb.contains(".size(width = 56.dp, height = 32.dp)"))
        assertTrue(thumb.contains("tween(durationMillis = 120)"))
    }

    @Test
    fun wholeRowTogglesPreserveRuntimeCallbacksGatesAndIndependentControls() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        assertFalse(app.contains("BydSwitch("), "Screen thumbs must belong to the semantic row/header")
        listOf(
            "SwitchToggle(state?.autoStartEnabled == true, actions::onToggleMainAutoStart)",
            "SwitchToggle(state?.debugAutoStartEnabled == true, actions::onToggleDebugAutoStart, enabled = state?.autoStartEnabled == true)",
            "SwitchToggle(state?.haSharedCategoriesEnabled == true, actions::onToggleSharedCategories, enabled = state?.influxEnabled != true)",
            "SwitchToggle(autoStart, onAutoStartChanged)",
            "SwitchToggle(config.enabled, { onConfigChanged(config.copy(enabled = it)) })",
            "SwitchToggle(selectedEnabled, { selectedEnabled = it })",
            "SwitchToggle((selectedMask and bit) != 0",
            "SwitchToggle(enabled, onChange)",
            "SwitchToggle(updateAutoCheckEnabled, actions::onToggleUpdateAutoCheck)",
            "SwitchToggle(checked, onChange)"
        ).forEach { assertTrue(app.contains(it), it) }
        val update = app.substringAfter("private fun UpdateSettingsRow(").substringBefore("private fun ShutdownSettingsRow(")
        assertTrue(update.contains("ActionButton(strings.checkUpdates, actions::onCheckForUpdates, modifier = Modifier.width(210.dp))"))
        assertFalse(update.contains("onCheckForUpdates()"))
        val queue = app.substringAfter("private fun ChannelQueueRow(").substringBefore("private fun ChannelButtons(")
        assertInOrder(queue, "SwitchControlRow(", "ReadOnlyPathField(")
        assertFalse(queue.substringAfter("ReadOnlyPathField(").contains("SwitchToggle("))
    }

    @Test
    fun messageToggleOwnsOnlyTheSectionHeaderNotItsBody() {
        val components = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorComponents.kt").readText()
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val section = components.substringAfter("fun SectionCard(").substringBefore("fun ActionButton(")
        assertInOrder(section, ".height(headerHeight)", ".switchTarget(headerToggle, headerInteraction)")
        assertTrue(section.contains("BydSwitch(it.checked, null, enabled = it.enabled, interactionSource = headerInteraction)"))
        assertFalse(section.substringAfter(".padding(bodyPadding)").contains("switchTarget"))
        assertTrue(app.contains("headerToggle = SwitchToggle(messageConfig.enabled, { updateMessage(messageConfig.copy(enabled = it)) })"))
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
    fun editableFieldsCommitOnImeDoneOrDismissWithoutChangingPersistenceContracts() {
        val components = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorComponents.kt").readText()
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()

        assertTrue(components.contains("internal fun rememberKeyboardCommit"))
        assertFalse(components.contains("WindowInsets.ime.getBottom"))
        assertTrue(components.contains("if (!focused)"))
        assertTrue(components.contains("keyboardController?.hide()"))
        assertTrue(components.contains("focusManager.clearFocus()"))
        assertFalse(components.substringAfter("internal fun rememberKeyboardCommit").substringBefore("data class ForcedPressClick").contains("onValueChange"))

        val textInput = components.substringAfter("fun TextInput(").substringBefore("fun TextValueInput(")
        assertTrue(textInput.contains("imeAction = ImeAction.Done"))
        assertTrue(textInput.contains("KeyboardActions(onDone = { keyboardCommit.onDone() })"))
        assertTrue(textInput.contains("modifier = keyboardCommit.modifier.weight(1f)"))

        val textValueInput = components.substringAfter("fun TextValueInput(").substringBefore("private fun PasswordVisibilityButton(")
        assertTrue(textValueInput.contains("singleLine = !multiline"))
        assertTrue(textValueInput.contains("imeAction = if (multiline) ImeAction.Default else ImeAction.Done"))
        assertTrue(textValueInput.contains("if (multiline) null"))
        assertTrue(textValueInput.contains("rememberKeyboardCommit()"))
        assertFalse(textValueInput.contains("onValueChange(value.copy"))

        val threshold = app.substringAfter("private fun TripThresholdField(").substringBefore("private fun TripRouteDialog(")
        assertTrue(threshold.contains("rememberKeyboardCommit()"))
        assertTrue(threshold.contains("ImeAction.Done"))
        assertTrue(threshold.contains("KeyboardActions(onDone = { keyboardCommit.onDone() })"))

        val storage = app.substringAfter("SectionCard(strings.archiveStorageLimit")
            .substringBefore("SectionCard(")
        assertTrue(storage.contains("ActionButton(strings.ok"))
        assertFalse(storage.contains("TextInput("))
    }

    @Test
    fun keyboardDismissalUsesFocusedRootInsetsWithoutPollingOrAnotherSavePath() {
        val components = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorComponents.kt").readText()
        val helper = components.substringAfter("internal fun rememberKeyboardCommit")
            .substringBefore("data class ForcedPressClick").replace("\r\n", "\n")
        assertTrue(helper.contains("DisposableEffect(view, focused)"))
        assertTrue(helper.contains("ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime())"))
        assertTrue(helper.contains("if (focused) {\n            listener.onGlobalLayout()\n            observer.addOnGlobalLayoutListener(listener)"))
        assertTrue(helper.contains("if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)"))
        assertTrue(helper.contains("else if (focused && imeWasVisible)"))
        assertTrue(helper.contains("imeWasVisible = false"))
        assertTrue(helper.contains("imeVisible = false"))
        listOf("delay(", "while (", "onValueChange", "setSoftInputMode", "setDecorFitsSystemWindows")
            .forEach { assertFalse(helper.contains(it), it) }
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
            "influxTest"
        ).forEach { flag -> assertTrue(actions.contains("val $flag: Boolean = false"), "Missing scoped flag: $flag") }
        assertTrue(app.contains("state.routeLoadingId == trip.id"))
        assertTrue(app.contains("testStatus != TelegramTestStatus.TESTING"))
        assertTrue(app.contains("runtimeStatus != RuntimeActionStatus.STOPPED || channelEnabled"))
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
