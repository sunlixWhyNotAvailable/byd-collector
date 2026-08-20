package com.bydcollector.collector.ui.compose

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import com.bydcollector.collector.R
import com.bydcollector.collector.maintenance.ArchiveEntryStatus
import com.bydcollector.collector.maintenance.ArchiveStorageSnapshot
import com.bydcollector.collector.maintenance.ArchiveStorageEntry
import com.bydcollector.collector.maintenance.ArchiveStorageJobMode
import com.bydcollector.collector.maintenance.ArchiveStorageJobStatus
import com.bydcollector.collector.maintenance.DbMaintenanceOperation
import com.bydcollector.collector.maintenance.DbMaintenanceUiState
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.ui.DashboardState
import com.bydcollector.collector.ui.VehicleKpis
import com.bydcollector.collector.update.ReleaseNotesSelector
import com.bydcollector.collector.update.UpdateInfo
import com.bydcollector.collector.update.UpdateUiState
import java.util.Locale
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

@Composable
fun BydCollectorApp(
    state: DashboardState?,
    chromeState: DashboardState? = state,
    activeTab: AppTab,
    language: UiLanguage,
    darkTheme: Boolean,
    mqttDraft: MqttDraft,
    influxDraft: InfluxDraft,
    tripsUiState: TripsUiState = TripsUiState(),
    tripsUiActions: TripsUiActions = TripsUiActions(),
    mqttLocationEnabled: Boolean = false,
    influxLocationEnabled: Boolean = false,
    telegramUiState: TelegramUiState = TelegramUiState(),
    telegramActions: TelegramUiActions = TelegramUiActions(),
    appVersionName: String = "",
    updateAutoCheckEnabled: Boolean = true,
    updateUiState: UpdateUiState = UpdateUiState.Hidden,
    databaseMaintenanceUiState: DbMaintenanceUiState? = null,
    switchConfirmationVersion: Int = 0,
    actions: BydCollectorActions,
    backgroundSetupPromptVisible: Boolean = false,
    onOpenBackgroundSettingsFromPrompt: () -> Unit = {},
    onDismissBackgroundSetupPrompt: () -> Unit = {}
) {
    val s = strings(language)
    //renders the operational dashboard directly; this app intentionally has no landing/marketing screen
    BydCollectorTheme(darkTheme) {
        val p = LocalBydPalette.current
        var pendingArchiveDeleteIds by remember { mutableStateOf<List<String>>(emptyList()) }
        CompositionLocalProvider(LocalSwitchConfirmationVersion provides switchConfirmationVersion) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(p.background)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TopHeader(
                        state = chromeState,
                        language = language,
                        darkTheme = darkTheme,
                        appVersionName = appVersionName,
                        strings = s,
                        actions = actions
                    )
                    DashboardSurface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            //keeps tabs mounted from one state snapshot so service/runtime facts stay consistent
                            when (activeTab) {
                                AppTab.MAIN -> MainTab(state, s, actions)
                                AppTab.ALL_PARAMETERS -> AllParametersTab(state, s, language, actions)
                                AppTab.TRIPS -> TripsTab(tripsUiState, tripsUiActions, s)
                                AppTab.HA -> HaTab(
                                    state,
                                    s,
                                    mqttDraft,
                                    influxDraft,
                                    mqttLocationEnabled,
                                    influxLocationEnabled,
                                    actions
                                )
                                AppTab.TELEGRAM -> TelegramTab(s, telegramUiState, telegramActions)
                                AppTab.STORAGE -> StorageTab(state, s, actions) { ids ->
                                    pendingArchiveDeleteIds = ids
                                }
                                AppTab.EXTRA -> ExtraTab(state, s, updateAutoCheckEnabled, actions)
                                AppTab.LOGS -> LogsTab(state, s, actions)
                            }
                        }
                    }
                    BottomTabs(activeTab = activeTab, strings = s, actions = actions)
                }
                if (backgroundSetupPromptVisible) {
                    //blocks underlying controls while the dilink background-app instruction is visible
                    BackgroundAppsSetupPrompt(
                        strings = s,
                        onOpenSettings = onOpenBackgroundSettingsFromPrompt,
                        onDismiss = onDismissBackgroundSetupPrompt
                    )
                }
                if (updateUiState != UpdateUiState.Hidden) {
                    //uses the same modal layer as background setup so update flow cannot trigger other controls
                    UpdateCheckDialog(
                        strings = s,
                        appVersionName = appVersionName,
                        language = language,
                        state = updateUiState,
                        onDismiss = actions::onDismissUpdateDialog,
                        onUpdate = actions::onInstallUpdate
                    )
                }
                if (databaseMaintenanceUiState != null) {
                    DatabaseMaintenanceDialog(
                        strings = s,
                        state = databaseMaintenanceUiState,
                        onConfirm = actions::onConfirmDatabaseMaintenance,
                        onCancel = actions::onCancelDatabaseMaintenance,
                        onDismiss = actions::onDismissDatabaseMaintenance
                    )
                }
                if (pendingArchiveDeleteIds.isNotEmpty()) {
                    ArchiveDeleteDialog(
                        strings = s,
                        count = pendingArchiveDeleteIds.size,
                        onConfirm = {
                            val ids = pendingArchiveDeleteIds
                            pendingArchiveDeleteIds = emptyList()
                            actions.onDeleteArchives(ids)
                        },
                        onDismiss = {
                            pendingArchiveDeleteIds = emptyList()
                        }
                    )
                }
                val archiveJob = chromeState?.archiveStorageJobStatus
                if (archiveJob?.running == true && archiveJob.mode == ArchiveStorageJobMode.DELETE) {
                    ArchiveStorageProgressDialog(strings = s, status = archiveJob)
                }
            }
        }
    }
}

@Composable
private fun BackgroundAppsSetupPrompt(
    strings: UiStrings,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalBydPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background.copy(alpha = 0.82f))
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        //guard modal taps from reaching the dashboard behind the dialog
        ModalInputBlocker()
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 540.dp)
                .background(p.panel, Rounded8)
                .border(1.dp, p.borderStrong, Rounded8)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = strings.backgroundSetupTitle,
                color = p.text,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = strings.backgroundSetupMessage,
                color = p.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = strings.backgroundSetupBody,
                color = p.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionButton(strings.backgroundSetupOpen, onOpenSettings, primary = true, modifier = Modifier.weight(1f))
                ActionButton(strings.backgroundSetupDismiss, onDismiss, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TopHeader(
    state: DashboardState?,
    language: UiLanguage,
    darkTheme: Boolean,
    appVersionName: String,
    strings: UiStrings,
    actions: BydCollectorActions
) {
    val p = LocalBydPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .background(p.surface, Rounded8)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.collector_top_bar_icon),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(38.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "BYD Collector",
                color = p.text,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                //keep top bar copy tied to the actual build version
                text = "${strings.topBarSubtitle} | v$appVersionName",
                color = p.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.End
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    text = "${strings.collection}: ${if (state?.running == true) strings.running else strings.idle}",
                    kind = if (state?.running == true) StatusKind.OK else StatusKind.WAITING
                )
                StatusPill(
                    text = "${strings.adb}: ${if (state?.adbAuthorized == true) strings.ok else strings.missing}",
                    kind = if (state?.adbAuthorized == true) StatusKind.OK else StatusKind.ERROR
                )
                StatusPill(
                    text = "${strings.permissions}: ${if (state?.permissionsGranted == true) strings.ok else strings.missing}",
                    kind = if (state?.permissionsGranted == true) StatusKind.OK else StatusKind.ERROR
                )
                SegmentedControl(
                    left = strings.uk,
                    right = strings.en,
                    leftSelected = language == UiLanguage.UK,
                    onLeft = { actions.onLanguageSelected(UiLanguage.UK) },
                    onRight = { actions.onLanguageSelected(UiLanguage.EN) },
                    modifier = Modifier.width(138.dp)
                )
                SegmentedControl(
                    left = strings.dark,
                    right = strings.light,
                    leftSelected = darkTheme,
                    onLeft = { actions.onDarkThemeSelected(true) },
                    onRight = { actions.onDarkThemeSelected(false) },
                    modifier = Modifier.width(154.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    text = "${strings.success}: ${state?.lastSuccessAt ?: "-"}",
                    kind = if (state?.lastSuccessAt != null) StatusKind.OK else StatusKind.WAITING
                )
                StatusPill(
                    text = "${strings.error}: ${state?.lastErrorAt ?: "-"}",
                    kind = if (state?.lastErrorAt != null) StatusKind.WARNING else StatusKind.WAITING
                )
            }
        }
    }
}

private val Rounded8 = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)

private data class TelegramMessageDefinition(
    val type: TelegramMessageType,
    val variableKeys: List<String>
)

private val TelegramMessageDefinitions = listOf(
    TelegramMessageDefinition(
        TelegramMessageType.CHARGING_STARTED,
        listOf("soc", "battery_power_kw", "time")
    ),
    TelegramMessageDefinition(
        TelegramMessageType.CHARGING_PROGRESS,
        listOf(
            "soc",
            "charge_step_added_percent",
            "charge_step_added_kwh",
            "charge_added_percent",
            "charge_added_kwh",
            "battery_power_kw"
        )
    ),
    TelegramMessageDefinition(
        TelegramMessageType.CHARGED_TO_100,
        listOf("soc", "remaining_energy_kwh", "range_km", "time")
    ),
    TelegramMessageDefinition(
        TelegramMessageType.CHARGING_STOPPED,
        listOf("soc", "charge_duration", "charge_added_percent", "charge_added_kwh", "time")
    ),
    TelegramMessageDefinition(
        TelegramMessageType.CHARGE_GUN_CONNECTED,
        listOf("soc", "time")
    ),
    TelegramMessageDefinition(
        TelegramMessageType.CHARGE_GUN_DISCONNECTED,
        listOf("soc", "time")
    ),
    TelegramMessageDefinition(
        TelegramMessageType.LOW_12V_VOLTAGE,
        listOf("battery_12v", "time")
    ),
    TelegramMessageDefinition(
        TelegramMessageType.TELEMETRY_UNAVAILABLE,
        listOf("last_data_time", "error", "time")
    ),
    TelegramMessageDefinition(
        TelegramMessageType.TRIP_SUMMARY,
        listOf(
            "trip_distance_km",
            "trip_energy_kwh",
            "trip_duration",
            "soc_start",
            "soc_end",
            "total_distance_km",
            "total_energy_kwh",
            "total_duration",
            "time"
        )
    )
)
private val TelegramMessageDefinitionRows = TelegramMessageDefinitions.chunked(2)

private data class TelegramNumberSetting(
    val label: String,
    val value: Int,
    val range: IntRange,
    val unit: String,
    val step: Int = 1,
    val onValueChange: (Int) -> Unit
)

@Composable
private fun TabScrollColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
private fun MainTab(state: DashboardState?, strings: UiStrings, actions: BydCollectorActions) {
    TabScrollColumn {
        ScreenTitle(strings.mainTab, strings.mainSubtitle)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MainCollectionCard(
                state = state,
                strings = strings,
                actions = actions,
                modifier = Modifier.weight(1.15f)
            )
            MainStatusCard(
                state = state,
                strings = strings,
                actions = actions,
                modifier = Modifier.weight(1f)
            )
        }
        MainDatabaseCard(state, strings, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun MainCollectionCard(
    state: DashboardState?,
    strings: UiStrings,
    actions: BydCollectorActions,
    modifier: Modifier
) {
    SectionCard(title = strings.dataCollection, modifier = modifier.height(226.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(strings.start, actions::onStartMain, primary = true, enabled = state?.mainPollingRunning != true, modifier = Modifier.weight(1f))
            ActionButton(strings.stop, actions::onStopMain, enabled = state?.mainPollingRunning == true, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.permissions, color = LocalBydPalette.current.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.75f))
            ActionButton(strings.grantAdb, actions::onGrantAdb, primary = true, modifier = Modifier.weight(0.9f))
            Spacer(Modifier.width(10.dp))
            ActionButton(strings.grantLocation, actions::onRequestLocationPermission, modifier = Modifier.weight(0.9f))
            Spacer(Modifier.width(10.dp))
            ActionButton(strings.backgroundWork, actions::onOpenBackgroundApps, modifier = Modifier.weight(0.9f))
        }
        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.autoStart, color = LocalBydPalette.current.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            BydSwitch(state?.autoStartEnabled == true, actions::onToggleMainAutoStart)
        }
    }
}

@Composable
private fun MainStatusCard(state: DashboardState?, strings: UiStrings, actions: BydCollectorActions, modifier: Modifier) {
    val mainPollingStatus = MainPollStatusFormatter.format(
        running = state?.mainPollingRunning == true,
        lastPollStatus = state?.lastPollStatus,
        strings = strings
    )
    SectionCard(title = strings.status, modifier = modifier.height(226.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusRow(strings.mainPolling, mainPollingStatus.text, mainPollingStatus.kind, modifier = Modifier.weight(1f))
            StatusRow(
                strings.allParameters,
                if (state?.debugPollingRunning == true) strings.running else strings.waiting,
                if (state?.debugPollingRunning == true) StatusKind.OK else StatusKind.WAITING,
                modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusRow("MQTT", compactChannelStatusText(state?.mqttStatus, strings), channelStatusKind(state?.mqttStatus, state?.mqttEnabled == true), modifier = Modifier.weight(1f))
            StatusRow("InfluxDB", compactChannelStatusText(state?.influxStatus, strings), channelStatusKind(state?.influxStatus, state?.influxEnabled == true), modifier = Modifier.weight(1f))
        }
        ActionButton(strings.archiveDatabase, actions::onOpenArchiveDatabase, primary = true, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun MainDatabaseCard(state: DashboardState?, strings: UiStrings, modifier: Modifier) {
    SectionCard(title = strings.database, modifier = modifier.height(142.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoRow(strings.size, UiSizeFormatter.bytes(state?.databaseSizeBytes ?: 0L, strings), modifier = Modifier.weight(0.84f), divider = true)
            ReadOnlyPathField(state?.databasePath ?: "-", modifier = Modifier.weight(1.06f))
        }
    }
}

@Composable
private fun AllParametersTab(
    state: DashboardState?,
    strings: UiStrings,
    language: UiLanguage,
    actions: BydCollectorActions
) {
    TabScrollColumn {
        ScreenTitle(strings.allTab, strings.allSubtitle)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(
                    title = strings.controls,
                    trailing = {
                        StatusPill(
                            if (state?.debugPollingRunning == true) strings.running else strings.waiting,
                            if (state?.debugPollingRunning == true) StatusKind.OK else StatusKind.WAITING,
                            compact = true
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(262.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton(strings.start, actions::onStartDebug, primary = true, enabled = state?.debugPollingRunning != true, modifier = Modifier.weight(1f))
                        ActionButton(strings.stop, actions::onStopDebug, enabled = state?.debugPollingRunning == true, modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(strings.autoStart, color = LocalBydPalette.current.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        BydSwitch(state?.debugAutoStartEnabled == true, actions::onToggleDebugAutoStart, enabled = state?.autoStartEnabled == true)
                    }
                    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(strings.parametersPerCycle, color = LocalBydPalette.current.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        NumericInput(state?.debugParameterCount?.toString().orEmpty(), modifier = Modifier.width(60.dp))
                    }
                }
            VehicleKpiCard(state?.vehicleKpis, strings, Modifier.weight(2f).height(262.dp))
        }
        DebugDatabaseCard(state, strings, actions, Modifier.fillMaxWidth())
    }
}

@Composable
private fun VehicleKpiCard(kpi: VehicleKpis?, strings: UiStrings, modifier: Modifier) {
    val chargeLabel = if (kpi?.batteryPowerCharging == true) strings.kpiCharging else strings.kpiDischarging
    SectionCard(title = strings.currentVehicleState, modifier = modifier, bodyPadding = 14.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiTile(strings.kpiSoc, kpi?.socPercent ?: "-", modifier = Modifier.weight(1f))
            KpiTile(strings.kpiOdometer, kpi?.odometerKm ?: "-", modifier = Modifier.weight(1f))
            KpiTile(strings.kpiCabinTemp, kpi?.cabinTempC ?: "-", modifier = Modifier.weight(1f))
            KpiTile(strings.kpiSoh, kpi?.sohPercent ?: "-", modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiTile(chargeLabel ?: "-", kpi?.batteryPowerKw ?: "-", modifier = Modifier.weight(1f))
            KpiTile(strings.kpiRange, kpi?.remainingRangeKm ?: "-", modifier = Modifier.weight(1f))
            KpiTile(strings.kpiBatteryTemp, kpi?.batteryTempC ?: "-", modifier = Modifier.weight(1f))
            KpiTile(strings.kpiCellDelta, kpi?.cellVoltageDeltaMv ?: "-", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DebugDatabaseCard(state: DashboardState?, strings: UiStrings, actions: BydCollectorActions, modifier: Modifier) {
    SectionCard(title = strings.database, modifier = modifier.height(226.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoRow(strings.size, UiSizeFormatter.bytes(state?.debugDatabaseSizeBytes ?: 0L, strings), modifier = Modifier.weight(0.84f), divider = true)
            ReadOnlyPathField(state?.debugDatabasePath ?: "-", modifier = Modifier.weight(1.06f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoRow(strings.success, state?.debugLastReadingAt ?: "-", modifier = Modifier.weight(1f), divider = false)
            InfoRow(strings.error, state?.debugLastErrorAt ?: state?.debugLastError ?: "-", modifier = Modifier.weight(1f), divider = false)
        }
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1.15f))
            ActionButton(
                strings.archiveDatabase,
                actions::onOpenArchiveDebugDatabase,
                primary = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TripsTab(
    state: TripsUiState,
    actions: TripsUiActions,
    strings: UiStrings
) {
    var selectedTripId by remember { mutableStateOf<String?>(null) }
    val selectedTrip = state.years.asSequence()
        .flatMap { it.months.asSequence() }
        .flatMap { it.days.asSequence() }
        .flatMap { it.trips.asSequence() }
        .firstOrNull { it.id == selectedTripId }
    TabScrollColumn {
        ScreenTitle(strings.tripsTab, strings.tripsSubtitle)
        SectionCard(strings.routeColors, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(strings.speed, color = if (state.colorMetric == TripMapMetric.SPEED) LocalBydPalette.current.text else LocalBydPalette.current.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                BydSwitch(
                    checked = state.colorMetric == TripMapMetric.CONSUMPTION,
                    onCheckedChange = { actions.onColorMetricChanged(if (it) TripMapMetric.CONSUMPTION else TripMapMetric.SPEED) }
                )
                Text(strings.consumption, color = if (state.colorMetric == TripMapMetric.CONSUMPTION) LocalBydPalette.current.text else LocalBydPalette.current.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TripThresholdRow(
                    strings = strings,
                    metric = state.colorMetric,
                    green = if (state.colorMetric == TripMapMetric.SPEED) state.speedGreenThreshold else state.consumptionGreenThreshold,
                    yellow = if (state.colorMetric == TripMapMetric.SPEED) state.speedYellowThreshold else state.consumptionYellowThreshold,
                    onChange = { green, yellow ->
                        if (state.colorMetric == TripMapMetric.SPEED) actions.onSpeedThresholdsChanged(green, yellow)
                        else actions.onConsumptionThresholdsChanged(green, yellow)
                    }
                )
            }
        }
        SectionCard(strings.tripList, modifier = Modifier.fillMaxWidth()) {
            if (state.years.isEmpty()) {
                Text(strings.noTrips, color = LocalBydPalette.current.muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(18.dp), textAlign = TextAlign.Center)
            } else {
                state.years.forEach { year ->
                    TripGroupRow(strings, year.title, year.distanceKm, year.energyKwh, year.averageConsumptionKwhPer100Km, year.months.sumOf { it.days.sumOf { day -> day.trips.size } })
                    year.months.forEach { month ->
                        TripGroupRow(strings, month.title, month.distanceKm, month.energyKwh, month.averageConsumptionKwhPer100Km, month.days.sumOf { it.trips.size }, indent = 12.dp)
                        month.days.forEach { day ->
                            TripGroupRow(strings, day.title, day.distanceKm, day.energyKwh, day.averageConsumptionKwhPer100Km, day.trips.size, indent = 24.dp)
                            TripTableHeader(strings)
                            day.trips.forEach { trip ->
                                TripTableRow(strings, trip) {
                                    selectedTripId = trip.id
                                    actions.onRouteRequested(trip.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    selectedTrip?.let { trip ->
        TripRouteDialog(
            trip = trip,
            state = state,
            strings = strings,
            onDismiss = { selectedTripId = null }
        )
    }
}

@Composable
private fun TripThresholdRow(
    strings: UiStrings,
    metric: TripMapMetric,
    green: Int,
    yellow: Int,
    onChange: (Int, Int) -> Unit
) {
    var greenText by remember(metric, green) { mutableStateOf(green.toString()) }
    var yellowText by remember(metric, yellow) { mutableStateOf(yellow.toString()) }
    val speed = metric == TripMapMetric.SPEED
    val boundary = if (speed) ">=" else "<="
    val between = if (speed) ">" else "<"
    val p = LocalBydPalette.current
    Text(strings.green, color = p.green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Text(boundary, color = p.muted, fontSize = 12.sp)
    TextInput("", greenText, { value -> greenText = value; value.toIntOrNull()?.let { onChange(it, yellowText.toIntOrNull() ?: yellow) } }, Modifier.width(62.dp), keyboardType = KeyboardType.Number)
    Text(between, color = p.muted, fontSize = 12.sp)
    Text(strings.yellow, color = p.yellow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Text(boundary, color = p.muted, fontSize = 12.sp)
    TextInput("", yellowText, { value -> yellowText = value; value.toIntOrNull()?.let { onChange(greenText.toIntOrNull() ?: green, it) } }, Modifier.width(62.dp), keyboardType = KeyboardType.Number)
    Text(between, color = p.muted, fontSize = 12.sp)
    Text(strings.red, color = p.red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TripGroupRow(
    strings: UiStrings,
    title: String,
    distanceKm: Double?,
    energyKwh: Double?,
    averageConsumption: Double?,
    tripCount: Int,
    indent: Dp = 0.dp
) {
    val p = LocalBydPalette.current
    Column(Modifier.fillMaxWidth().padding(start = indent)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = p.text, fontSize = if (indent == 0.dp) 16.sp else 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${formatTripNumber(distanceKm)} ${if (strings.distance == "Відстань") "км" else "km"} • ${formatTripNumber(energyKwh)} ${if (strings.used == "Витрачено") "кВт·год" else "kWh"} • ${formatTripNumber(averageConsumption)} ${if (strings.used == "Витрачено") "кВт·год/100 км" else "kWh/100 km"} • $tripCount", color = p.muted, fontSize = 12.sp, textAlign = TextAlign.End)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
    }
}

@Composable
private fun TripTableHeader(strings: UiStrings) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        listOf(strings.startEnd, strings.duration, strings.distance, "SOC", strings.used, strings.averageConsumption, strings.route).forEach { label ->
            Text(label, color = LocalBydPalette.current.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 3.dp))
        }
    }
}

@Composable
private fun TripTableRow(strings: UiStrings, trip: TripSummaryUi, onRoute: () -> Unit) {
    val p = LocalBydPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(
            "${trip.startAt} → ${trip.endAt}",
            trip.duration,
            "${formatTripNumber(trip.distanceKm)} ${if (strings.distance == "Відстань") "км" else "km"}",
            "${formatTripNumber(trip.socStart)} → ${formatTripNumber(trip.socEnd)}%",
            "${formatTripNumber(trip.energyKwh)} ${if (strings.used == "Витрачено") "кВт·год" else "kWh"}",
            "${formatTripNumber(trip.averageConsumptionKwhPer100Km)} ${if (strings.used == "Витрачено") "кВт·год/100 км" else "kWh/100 km"}",
        ).forEachIndexed { index, value ->
            Text(value, color = if (index == 5) p.green else p.text, fontSize = 11.sp, fontWeight = if (index == 0 || index == 5) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 3.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            ActionButton(strings.route, onRoute, modifier = Modifier.width(92.dp))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.border.copy(alpha = 0.55f)))
}

private fun formatTripNumber(value: Double?): String = value?.takeIf { it.isFinite() }?.let {
    "%.1f".format(Locale.US, it).trimEnd('0').trimEnd('.')
} ?: "-"

@Composable
private fun TripRouteDialog(
    trip: TripSummaryUi,
    state: TripsUiState,
    strings: UiStrings,
    onDismiss: () -> Unit
) {
    var metric by rememberSaveable(trip.id) { mutableStateOf(state.colorMetric) }
    val p = LocalBydPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(p.background.copy(alpha = 0.82f)).padding(28.dp), contentAlignment = Alignment.Center) {
            ModalInputBlocker()
            Column(Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.86f).background(p.panel, Rounded8).border(1.dp, p.borderStrong, Rounded8).padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(strings.tripRoute, color = p.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text("${trip.startAt} → ${trip.endAt} • ${formatTripNumber(trip.distanceKm)} km", color = p.muted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                TripMapView(
                    points = trip.route,
                    metric = metric,
                    speedGreen = state.speedGreenThreshold,
                    speedYellow = state.speedYellowThreshold,
                    consumptionGreen = state.consumptionGreenThreshold,
                    consumptionYellow = state.consumptionYellowThreshold,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.colorBySpeed, color = if (metric == TripMapMetric.SPEED) p.text else p.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(10.dp))
                    BydSwitch(metric == TripMapMetric.CONSUMPTION, { metric = if (it) TripMapMetric.CONSUMPTION else TripMapMetric.SPEED })
                    Spacer(Modifier.width(10.dp))
                    Text(strings.colorByConsumption, color = if (metric == TripMapMetric.CONSUMPTION) p.text else p.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    ActionButton(strings.close, onDismiss, modifier = Modifier.width(140.dp))
                }
            }
        }
    }
}

private fun createTripMap(context: Context): MapView {
    val cache = java.io.File(context.cacheDir, "osmdroid").apply { mkdirs() }
    trimTripMapCache(cache)
    Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    Configuration.getInstance().userAgentValue = "BYD Collector/2.7.0"
    Configuration.getInstance().osmdroidTileCache = cache
    return MapView(context).apply {
        setTileSource(XYTileSource(
            "OpenStreetMap",
            0,
            19,
            256,
            ".png",
            arrayOf("https://tile.openstreetmap.org/")
        ))
        setMultiTouchControls(true)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

private fun trimTripMapCache(cache: java.io.File) {
    val files = cache.walkTopDown().filter { it.isFile }.toList()
    var bytes = files.sumOf { it.length() }
    if (bytes <= 64L * 1024L * 1024L) return
    files.sortedBy { it.lastModified() }.forEach { file ->
        if (bytes <= 64L * 1024L * 1024L) return@forEach
        bytes -= file.length()
        runCatching { file.delete() }
    }
}

@Composable
private fun TripMapView(
    points: List<TripRoutePointUi>,
    metric: TripMapMetric,
    speedGreen: Int,
    speedYellow: Int,
    consumptionGreen: Int,
    consumptionYellow: Int,
    modifier: Modifier = Modifier
) {
    val p = LocalBydPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(modifier.clip(Rounded8).background(p.pathField).border(1.dp, p.border, Rounded8)) {
        if (points.none { !it.gap }) {
            Text("© OpenStreetMap contributors", color = p.muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { createTripMap(context) },
                update = { map -> updateTripMap(map, points, metric, speedGreen, speedYellow, consumptionGreen, consumptionYellow) },
                onRelease = MapView::onDetach
            )
            Text("© OpenStreetMap contributors", color = p.muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).background(p.surface.copy(alpha = 0.86f), Rounded8).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

private fun updateTripMap(
    map: MapView,
    points: List<TripRoutePointUi>,
    metric: TripMapMetric,
    speedGreen: Int,
    speedYellow: Int,
    consumptionGreen: Int,
    consumptionYellow: Int
) {
    map.overlays.clear()
    val runs = buildList {
        var run = mutableListOf<TripRoutePointUi>()
        points.forEach { point ->
            if (point.gap || !point.latitude.isFinite() || !point.longitude.isFinite()) {
                if (run.size > 1) add(run)
                run = mutableListOf()
            } else {
                run += point
            }
        }
        if (run.size > 1) add(run)
    }
    val p = runs.flatten()
    if (p.isEmpty()) return
    runs.forEach { run ->
        run.zipWithNext().forEach { (from, to) ->
            val line = Polyline(map).apply {
                setPoints(listOf(GeoPoint(from.latitude, from.longitude), GeoPoint(to.latitude, to.longitude)))
                color = when (metric) {
                    TripMapMetric.SPEED -> routeColor(from.speedKmh, speedGreen.toDouble(), speedYellow.toDouble(), speed = true)
                    TripMapMetric.CONSUMPTION -> routeColor(from.consumptionKwhPer100Km, consumptionGreen.toDouble(), consumptionYellow.toDouble(), speed = false)
                }
                width = 8f
            }
            map.overlays += line
        }
    }
    map.post {
        if (p.size == 1) {
            map.controller.setCenter(GeoPoint(p.first().latitude, p.first().longitude))
            map.controller.setZoom(14.0)
        } else {
            map.zoomToBoundingBox(
                BoundingBox.fromGeoPoints(p.map { GeoPoint(it.latitude, it.longitude) }),
                true,
                64
            )
        }
    }
    map.invalidate()
}

private fun routeColor(value: Double?, green: Double, yellow: Double, speed: Boolean): Int = when {
    value == null -> AndroidColor.GRAY
    speed && value >= green -> AndroidColor.rgb(84, 216, 152)
    speed && value > yellow -> AndroidColor.rgb(242, 195, 78)
    !speed && value <= green -> AndroidColor.rgb(84, 216, 152)
    !speed && value <= yellow -> AndroidColor.rgb(242, 195, 78)
    else -> AndroidColor.rgb(255, 140, 140)
}

@Composable
private fun HaTab(
    state: DashboardState?,
    strings: UiStrings,
    mqttDraft: MqttDraft,
    influxDraft: InfluxDraft,
    mqttLocationEnabled: Boolean,
    influxLocationEnabled: Boolean,
    actions: BydCollectorActions
) {
    TabScrollColumn {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle(strings.haTab, strings.haSubtitle, modifier = Modifier.weight(1f))
            Text(strings.sharedCategories, color = LocalBydPalette.current.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            BydSwitch(state?.haSharedCategoriesEnabled == true, actions::onToggleSharedCategories, enabled = state?.influxEnabled != true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MqttCard(state, strings, mqttDraft, mqttLocationEnabled, actions, Modifier.weight(1f))
            InfluxCard(state, strings, influxDraft, influxLocationEnabled, actions, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MqttCard(
    state: DashboardState?,
    strings: UiStrings,
    draft: MqttDraft,
    locationEnabled: Boolean,
    actions: BydCollectorActions,
    modifier: Modifier
) {
    SectionCard(
        title = "MQTT",
        trailing = { StatusPill(compactChannelStatusText(state?.mqttStatus, strings), channelStatusKind(state?.mqttStatus, state?.mqttEnabled == true), compact = true) },
        modifier = modifier.height(686.dp)
    ) {
        ChannelButtons(strings, onStart = actions::onStartMqtt, onStop = actions::onStopMqtt, onTest = actions::onTestMqtt, running = state?.mqttEnabled == true)
        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.autoStart, color = LocalBydPalette.current.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            BydSwitch(state?.mqttAutoStartEnabled == true, actions::onToggleMqttAutoStart)
        }
        ChannelPrelude(strings, mqtt = true, pendingText = "${state?.mqttPendingCount ?: 0L} ${strings.messages}", actions = actions)
        CategoryGrid(strings.mqttCategories, state?.mqttEnabledCategories.orEmpty(), enabled = state?.mqttEnabled != true, strings = strings) { category ->
            actions.onToggleMqttCategory(category, !state?.mqttEnabledCategories.orEmpty().contains(category))
        }
        SwitchRow(strings.location, locationEnabled, actions::onToggleMqttLocation)
        CredentialGridMqtt(strings, draft, actions)
    }
}

@Composable
private fun InfluxCard(
    state: DashboardState?,
    strings: UiStrings,
    draft: InfluxDraft,
    locationEnabled: Boolean,
    actions: BydCollectorActions,
    modifier: Modifier
) {
    SectionCard(
        title = "InfluxDB",
        trailing = { StatusPill(compactChannelStatusText(state?.influxStatus, strings), channelStatusKind(state?.influxStatus, state?.influxEnabled == true), compact = true) },
        modifier = modifier.height(686.dp)
    ) {
        ChannelButtons(strings, onStart = actions::onStartInflux, onStop = actions::onStopInflux, onTest = actions::onTestInflux, running = state?.influxEnabled == true)
        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.autoStart, color = LocalBydPalette.current.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            BydSwitch(state?.influxAutoStartEnabled == true, actions::onToggleInfluxAutoStart)
        }
        ChannelPrelude(strings, mqtt = false, pendingText = "${state?.influxPendingRows ?: 0L} ${strings.points}", actions = actions)
        CategoryGrid(
            strings.influxCategories,
            if (state?.haSharedCategoriesEnabled == true) state.mqttEnabledCategories else state?.influxEnabledCategories.orEmpty(),
            enabled = state?.influxEnabled != true && state?.haSharedCategoriesEnabled != true,
            strings = strings
        ) { category ->
            actions.onToggleInfluxCategory(category, !state?.influxEnabledCategories.orEmpty().contains(category))
        }
        SwitchRow(strings.location, locationEnabled, actions::onToggleInfluxLocation)
        CredentialGridInflux(strings, draft, actions)
    }
}

@Composable
private fun ChannelPrelude(
    strings: UiStrings,
    mqtt: Boolean,
    pendingText: String,
    actions: BydCollectorActions,
    height: Dp = 78.dp
) {
    Column(
        modifier = Modifier.height(height),
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            InfoRow(strings.queued, pendingText, modifier = Modifier.weight(1f))
            if (mqtt) {
                Spacer(Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    ActionButton(strings.reExport, actions::onReExportInflux, modifier = Modifier.fillMaxWidth())
                    Text(
                        strings.reExportHint,
                        color = LocalBydPalette.current.muted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelButtons(strings: UiStrings, onStart: () -> Unit, onStop: () -> Unit, onTest: () -> Unit, running: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton(strings.start, onStart, primary = true, enabled = !running, modifier = Modifier.weight(1f))
        ActionButton(strings.stop, onStop, enabled = running, modifier = Modifier.weight(1f))
        ActionButton(strings.testConnection, onTest, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CategoryGrid(
    title: String,
    selected: Set<String>,
    enabled: Boolean,
    strings: UiStrings,
    onToggle: (String) -> Unit
) {
    Text(title, color = LocalBydPalette.current.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val categoryRows = remember(strings) {
        listOf(
            "battery" to strings.battery,
            "motion" to strings.motion,
            "body" to strings.body,
            "climate" to strings.climate,
            "safety" to strings.safety
        ).chunked(3)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        categoryRows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (key, label) ->
                    CategoryChip(label, selected.contains(key), enabled, { onToggle(key) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CredentialGridMqtt(strings: UiStrings, draft: MqttDraft, actions: BydCollectorActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(strings.host, draft.host, { actions.onMqttDraftChanged(draft.copy(host = it)) }, Modifier.weight(1f))
            TextInput(strings.port, draft.port, { actions.onMqttDraftChanged(draft.copy(port = it)) }, Modifier.weight(1f), keyboardType = KeyboardType.Number)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(strings.username, draft.username, { actions.onMqttDraftChanged(draft.copy(username = it)) }, Modifier.weight(1f))
            TextInput(
                strings.password,
                draft.password,
                { actions.onMqttDraftChanged(draft.copy(password = it)) },
                Modifier.weight(1f),
                password = true,
                showPasswordContentDescription = strings.showSecret,
                hidePasswordContentDescription = strings.hideSecret
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(strings.clientId, draft.clientId, { actions.onMqttDraftChanged(draft.copy(clientId = it)) }, Modifier.weight(1f))
            TextInput(strings.topicPrefix, draft.topicPrefix, { actions.onMqttDraftChanged(draft.copy(topicPrefix = it)) }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(strings.discoveryPrefix, draft.discoveryPrefix, { actions.onMqttDraftChanged(draft.copy(discoveryPrefix = it)) }, Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CredentialGridInflux(strings: UiStrings, draft: InfluxDraft, actions: BydCollectorActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(strings.host, draft.host, { actions.onInfluxDraftChanged(draft.copy(host = it)) }, Modifier.weight(1f))
            TextInput(strings.port, draft.port, { actions.onInfluxDraftChanged(draft.copy(port = it)) }, Modifier.weight(1f), keyboardType = KeyboardType.Number)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(strings.username, draft.username, { actions.onInfluxDraftChanged(draft.copy(username = it)) }, Modifier.weight(1f))
            TextInput(
                strings.password,
                draft.password,
                { actions.onInfluxDraftChanged(draft.copy(password = it)) },
                Modifier.weight(1f),
                password = true,
                showPasswordContentDescription = strings.showSecret,
                hidePasswordContentDescription = strings.hideSecret
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(strings.databaseField, draft.database, { actions.onInfluxDraftChanged(draft.copy(database = it)) }, Modifier.weight(1f))
            TextInput(strings.measurement, draft.measurement, { actions.onInfluxDraftChanged(draft.copy(measurement = it)) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TelegramTab(
    strings: UiStrings,
    uiState: TelegramUiState,
    actions: TelegramUiActions
) {
    var config by remember(uiState.config) { mutableStateOf(uiState.config) }
    val updateConfig: (TelegramConfig) -> Unit = { next ->
        config = next
        actions.onConfigChanged(next)
    }

    TabScrollColumn {
        ScreenTitle(strings.telegram.tab, strings.telegram.subtitle)
        TelegramConnectionCard(
            strings = strings,
            config = config,
            testStatus = uiState.testStatus,
            onConfigChanged = updateConfig,
            onClearBotToken = actions.onClearBotToken,
            onTestConnection = actions.onTestConnection
        )
        TelegramMessageDefinitionRows.forEach { messages ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                messages.forEach { message ->
                    TelegramMessageCard(
                        strings = strings,
                        definition = message,
                        config = config,
                        onConfigChanged = updateConfig,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (messages.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TelegramConnectionCard(
    strings: UiStrings,
    config: TelegramConfig,
    testStatus: TelegramTestStatus,
    onConfigChanged: (TelegramConfig) -> Unit,
    onClearBotToken: () -> Unit,
    onTestConnection: () -> Unit
) {
    SectionCard(title = strings.telegram.connection, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(42.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                strings.telegram.enable,
                color = LocalBydPalette.current.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            BydSwitch(config.enabled, { onConfigChanged(config.copy(enabled = it)) })
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextInput(
                label = strings.telegram.botToken,
                value = config.botToken,
                onValueChange = { onConfigChanged(config.copy(botToken = it)) },
                modifier = Modifier.weight(1f),
                password = true,
                placeholder = if (config.botTokenSet) "********" else "123456789:AA...",
                showPasswordContentDescription = strings.showSecret,
                hidePasswordContentDescription = strings.hideSecret,
                clearContentDescription = strings.telegram.clearBotToken,
                onClear = onClearBotToken,
                showClearWhenEmpty = config.botTokenSet
            )
            TextInput(
                label = strings.telegram.chatId,
                value = config.chatId,
                onValueChange = { onConfigChanged(config.copy(chatId = it)) },
                modifier = Modifier.weight(1f),
                placeholder = "-1001234567890"
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionButton(
                text = strings.testConnection,
                onClick = onTestConnection,
                primary = true,
                enabled = config.botTokenSet && config.chatId.isNotBlank(),
                modifier = Modifier.width(180.dp)
            )
            StatusPill(
                text = telegramTestStatusText(testStatus, strings.telegram),
                kind = telegramTestStatusKind(testStatus),
                compact = true
            )
        }
    }
}

@Composable
private fun TelegramMessageCard(
    strings: UiStrings,
    definition: TelegramMessageDefinition,
    config: TelegramConfig,
    onConfigChanged: (TelegramConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val localized = strings.telegram.messages.getValue(definition.type)
    val messageConfig = config.messages[definition.type]
        ?: TelegramMessageConfig(template = localized.defaultTemplate)
    var template by remember(definition.type) {
        mutableStateOf(
            TextFieldValue(
                text = messageConfig.template,
                selection = TextRange(messageConfig.template.length)
            )
        )
    }
    var showVariablePicker by remember(definition.type) { mutableStateOf(false) }

    LaunchedEffect(messageConfig.template) {
        if (template.text != messageConfig.template) {
            template = TextFieldValue(
                text = messageConfig.template,
                selection = TextRange(messageConfig.template.length)
            )
        }
    }

    fun updateMessage(next: TelegramMessageConfig) {
        onConfigChanged(config.copy(messages = config.messages + (definition.type to next)))
    }

    SectionCard(
        title = localized.title,
        trailing = {
            BydSwitch(messageConfig.enabled, { updateMessage(messageConfig.copy(enabled = it)) })
        },
        modifier = modifier
    ) {
        telegramNumberSetting(definition.type, config, strings.telegram, onConfigChanged)?.let { setting ->
            TelegramNumberStepper(
                setting = setting,
                sendLocationEnabled = definition.type == TelegramMessageType.TRIP_SUMMARY && config.sendLocation,
                onSendLocationChanged = { onConfigChanged(config.copy(sendLocation = it)) },
                sendLocationLabel = strings.sendLocation.takeIf {
                    definition.type == TelegramMessageType.TRIP_SUMMARY
                }.orEmpty()
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                strings.telegram.messageTemplate,
                color = LocalBydPalette.current.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            ActionButton(
                text = "{}",
                onClick = { showVariablePicker = true },
                modifier = Modifier.width(48.dp)
            )
        }
        TextValueInput(
            label = "",
            value = template,
            placeholder = strings.telegram.messagePlaceholder,
            onValueChange = { next ->
                template = next
                updateMessage(messageConfig.copy(template = next.text))
            },
            multiline = true,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showVariablePicker) {
        TelegramVariableDialog(
            strings = strings,
            variables = definition.variableKeys.map { key ->
                "{$key}" to strings.telegram.variableDescriptions.getValue(key)
            },
            onSelect = { token ->
                val next = insertTelegramTemplateVariable(template, token)
                template = next
                updateMessage(messageConfig.copy(template = next.text))
                showVariablePicker = false
            },
            onDismiss = { showVariablePicker = false }
        )
    }
}

@Composable
private fun TelegramNumberStepper(
    setting: TelegramNumberSetting,
    sendLocationEnabled: Boolean = false,
    onSendLocationChanged: (Boolean) -> Unit = {},
    sendLocationLabel: String = ""
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = setting.label,
            color = LocalBydPalette.current.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        ActionButton(
            text = "-",
            onClick = {
                setting.onValueChange((setting.value - setting.step).coerceAtLeast(setting.range.first))
            },
            enabled = setting.value > setting.range.first,
            modifier = Modifier.width(42.dp)
        )
        if (sendLocationLabel.isNotBlank()) {
            Text(sendLocationLabel, color = LocalBydPalette.current.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            BydSwitch(sendLocationEnabled, onSendLocationChanged)
        }
        NumericInput(
            value = if (setting.unit == "%") "${setting.value}%" else "${setting.value} ${setting.unit}",
            modifier = Modifier.width(72.dp)
        )
        ActionButton(
            text = "+",
            onClick = {
                setting.onValueChange((setting.value + setting.step).coerceAtMost(setting.range.last))
            },
            enabled = setting.value < setting.range.last,
            modifier = Modifier.width(42.dp)
        )
    }
}

private fun telegramNumberSetting(
    type: TelegramMessageType,
    config: TelegramConfig,
    strings: TelegramStrings,
    onConfigChanged: (TelegramConfig) -> Unit
): TelegramNumberSetting? = when (type) {
    TelegramMessageType.CHARGING_PROGRESS -> TelegramNumberSetting(
        strings.chargeStep,
        config.chargeStepPercent,
        1..99,
        "%"
    ) { onConfigChanged(config.copy(chargeStepPercent = it)) }
    TelegramMessageType.LOW_12V_VOLTAGE -> TelegramNumberSetting(
        strings.low12vThreshold,
        config.low12vThresholdVolts,
        9..15,
        "V"
    ) { onConfigChanged(config.copy(low12vThresholdVolts = it)) }
    TelegramMessageType.TELEMETRY_UNAVAILABLE -> TelegramNumberSetting(
        strings.telemetryDelay,
        config.telemetryUnavailableMinutes,
        1..60,
        strings.minuteUnit
    ) { onConfigChanged(config.copy(telemetryUnavailableMinutes = it)) }
    TelegramMessageType.TRIP_SUMMARY -> TelegramNumberSetting(
        strings.tripDelay,
        config.tripSummaryDelaySeconds,
        5..300,
        strings.secondUnit,
        step = 5
    ) { onConfigChanged(config.copy(tripSummaryDelaySeconds = it)) }
    else -> null
}

internal fun insertTelegramTemplateVariable(value: TextFieldValue, token: String): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val end = maxOf(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
    val text = value.text.replaceRange(start, end, token)
    return TextFieldValue(text = text, selection = TextRange(start + token.length))
}

@Composable
private fun TelegramVariableDialog(
    strings: UiStrings,
    variables: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalBydPalette.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(p.background.copy(alpha = 0.82f))
                .padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            ModalInputBlocker()
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .background(p.panel, Rounded8)
                    .border(1.dp, p.borderStrong, Rounded8)
                    .padding(20.dp)
            ) {
                Text(
                    strings.telegram.insertVariable,
                    color = p.text,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                variables.forEachIndexed { index, variable ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onSelect(variable.first) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = variable.first,
                            color = p.accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(0.45f)
                        )
                        Text(
                            text = variable.second,
                            color = p.text,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(0.55f)
                        )
                    }
                    if (index != variables.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ActionButton(strings.close, onDismiss, modifier = Modifier.width(140.dp))
                }
            }
        }
    }
}

private fun telegramTestStatusText(status: TelegramTestStatus, strings: TelegramStrings): String = when (status) {
    TelegramTestStatus.NOT_TESTED -> strings.notTested
    TelegramTestStatus.TESTING -> strings.testing
    TelegramTestStatus.SUCCESS -> strings.connected
    TelegramTestStatus.STORAGE_ERROR -> strings.storageError
    TelegramTestStatus.FAILED -> strings.failed
}

private fun telegramTestStatusKind(status: TelegramTestStatus): StatusKind = when (status) {
    TelegramTestStatus.NOT_TESTED, TelegramTestStatus.TESTING -> StatusKind.WAITING
    TelegramTestStatus.SUCCESS -> StatusKind.OK
    TelegramTestStatus.STORAGE_ERROR, TelegramTestStatus.FAILED -> StatusKind.ERROR
}

@Composable
private fun ExtraTab(
    state: DashboardState?,
    strings: UiStrings,
    updateAutoCheckEnabled: Boolean,
    actions: BydCollectorActions
) {
    val optionsCardHeight = 312.dp
    TabScrollColumn {
        ScreenTitle(strings.extraTab, strings.extraSubtitle)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(strings.keepAlive, Modifier.weight(1f).height(optionsCardHeight)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchRow(
                        strings.keepWifi,
                        state?.keepWifiEnabled == true,
                        actions::onToggleKeepWifi
                    )
                    SwitchRow(
                        strings.keepMobile,
                        state?.keepMobileDataEnabled == true,
                        actions::onToggleKeepMobile
                    )
                    SwitchRow(
                        strings.keepBluetooth,
                        state?.keepBluetoothEnabled == true,
                        actions::onToggleKeepBluetooth
                    )
                    SwitchRow(
                        strings.restoreCollector,
                        state?.recoverCollectorServiceEnabled == true,
                        actions::onToggleKeepCollector,
                        divider = false
                    )
                }
            }
            SectionCard(strings.appRuntime, Modifier.weight(1f).height(optionsCardHeight)) {
                TailscaleRuntimeRow(strings, state?.tailscaleActivationEnabled == true, actions::onToggleTailscaleActivation)
                UpdateSettingsRow(
                    strings = strings,
                    updateAutoCheckEnabled = updateAutoCheckEnabled,
                    actions = actions
                )
                ShutdownSettingsRow(strings = strings, actions = actions)
            }
        }
    }
}

@Composable
private fun TailscaleRuntimeRow(strings: UiStrings, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalBydPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = strings.activateTailscale,
                    color = p.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = strings.activateTailscaleDescription,
                    color = p.muted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BydSwitch(enabled, onChange)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
    }
}

@Composable
private fun UpdateSettingsRow(
    strings: UiStrings,
    updateAutoCheckEnabled: Boolean,
    actions: BydCollectorActions
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = strings.checkUpdates,
                color = LocalBydPalette.current.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = strings.checkUpdatesDescription,
                color = LocalBydPalette.current.muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        ActionButton(strings.checkUpdates, actions::onCheckForUpdates, modifier = Modifier.width(210.dp))
        BydSwitch(updateAutoCheckEnabled, actions::onToggleUpdateAutoCheck)
    }
}

@Composable
private fun ShutdownSettingsRow(strings: UiStrings, actions: BydCollectorActions) {
    val p = LocalBydPalette.current
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(p.border)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 58.dp)
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = strings.shutdown,
                    color = p.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = strings.shutdownDescription,
                    color = p.muted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ShutdownIconButton(onClick = actions::onShutdownApp)
        }
    }
}

@Composable
private fun ShutdownIconButton(onClick: () -> Unit) {
    val p = LocalBydPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val press = rememberForcedPressClick(enabled = true, onClick = onClick)
    val visualPressed = pressed || press.visualPressed
    val buttonBackground = if (visualPressed) p.redSoft else p.redSoft.copy(alpha = 0.56f)
    Box(
        modifier = Modifier
            .size(42.dp)
            .pressScaleModifier(interactionSource, forcePressed = press.visualPressed)
            .background(buttonBackground, Rounded8)
            .border(1.dp, p.red.copy(alpha = 0.72f), Rounded8)
            .clickable(enabled = !press.locked, interactionSource = interactionSource, indication = null) {
                press.onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        ShutdownIcon(color = p.red, modifier = Modifier.size(23.dp))
    }
}

@Composable
private fun ArchiveShareIconButton(
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    val p = LocalBydPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val press = rememberForcedPressClick(enabled = enabled, onClick = onClick)
    val visualPressed = pressed || press.visualPressed
    val background = when {
        !enabled -> p.surface
        visualPressed -> p.accent.copy(alpha = 0.72f)
        else -> p.accent.copy(alpha = 0.12f)
    }
    Box(
        modifier = Modifier
            .size(42.dp)
            .pressScaleModifier(interactionSource, forcePressed = press.visualPressed)
            .background(background, Rounded8)
            .border(1.dp, if (enabled) p.accent.copy(alpha = 0.85f) else p.borderStrong, Rounded8)
            .clickable(
                enabled = enabled && !press.locked,
                interactionSource = interactionSource,
                indication = null
            ) { press.onClick() },
        contentAlignment = Alignment.Center
    ) {
        ShareIcon(
            contentDescription = contentDescription,
            color = when {
                !enabled -> p.muted.copy(alpha = 0.62f)
                visualPressed -> p.accentText
                else -> p.accent
            },
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    divider: Boolean = true
) {
    val p = LocalBydPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            BydSwitch(checked, onChange)
        }
        if (divider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        }
    }
}

@Composable
private fun ModalInputBlocker() {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {}
            )
    )
}

@Composable
private fun UpdateCheckDialog(
    strings: UiStrings,
    appVersionName: String,
    language: UiLanguage,
    state: UpdateUiState,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit
) {
    val p = LocalBydPalette.current
    val releaseNotesScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background.copy(alpha = 0.82f))
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        //guard modal taps from reaching the dashboard behind the dialog
        ModalInputBlocker()
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 540.dp)
                .height(430.dp)
                .background(p.panel, Rounded8)
                .border(1.dp, p.borderStrong, Rounded8)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(strings.updates, color = p.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text("${strings.currentVersion} v$appVersionName", color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(p.pathField, Rounded8)
                    .border(1.dp, p.border, Rounded8)
                    .padding(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(releaseNotesScroll)
                ) {
                    when (state) {
                        UpdateUiState.Checking -> Text(strings.checkingForUpdate, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        UpdateUiState.UpToDate -> Text(strings.latestVersion, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        is UpdateUiState.Available -> AvailableUpdateNotes(strings, state.info, language)
                        is UpdateUiState.Downloading -> {
                            DownloadingUpdateHeader(strings)
                            Spacer(Modifier.height(10.dp))
                            AvailableUpdateNotes(strings, state.info, language)
                        }
                        is UpdateUiState.Error -> Text("${strings.updateError}: ${localizedUpdateError(strings, state.message)}", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        UpdateUiState.Hidden -> Text(strings.checkingForUpdate, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (state is UpdateUiState.Downloading) {
                UpdateProgressBar(state.progress)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    text = strings.update,
                    onClick = onUpdate,
                    primary = true,
                    enabled = state is UpdateUiState.Available,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(strings.close, onDismiss, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DatabaseMaintenanceDialog(
    strings: UiStrings,
    state: DbMaintenanceUiState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalBydPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background.copy(alpha = 0.82f))
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        //guard modal taps from reaching the dashboard behind the dialog
        ModalInputBlocker()
        Column(
            modifier = Modifier
                .width(560.dp)
                .height(if (!state.running && !state.completed && state.error == null) 340.dp else 300.dp)
                .background(p.panel, Rounded8)
                .border(1.dp, p.borderStrong, Rounded8)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(operationTitle(strings, state.operation), color = p.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(p.pathField, Rounded8)
                    .border(1.dp, p.border, Rounded8)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    state.running -> DatabaseMaintenanceRunningBody(strings, state)
                    state.error != null -> Text("${strings.dbMaintenanceFailed}: ${localizedDbMaintenanceError(strings, state.error)}", color = p.red, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    state.completed -> {
                        Text(strings.dbMaintenanceComplete, color = p.green, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        state.archivePath?.let {
                            Text("${strings.dbMaintenanceArchivePath} $it", color = p.muted, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                    else -> DatabaseMaintenanceConfirmBody(strings, state)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    state.error != null || state.completed -> {
                        ActionButton(strings.ok, onDismiss, modifier = Modifier.weight(1f))
                    }
                    state.running -> {
                        ActionButton(strings.cancel, onCancel, enabled = state.cancelAvailable, modifier = Modifier.weight(1f))
                    }
                    else -> {
                        ActionButton(strings.yes, onConfirm, primary = true, modifier = Modifier.weight(1f))
                        ActionButton(strings.no, onDismiss, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DatabaseMaintenanceConfirmBody(
    strings: UiStrings,
    state: DbMaintenanceUiState
) {
    val p = LocalBydPalette.current
    val preflight = state.mainArchivePreflight
    val pending = (preflight?.telegramPending ?: 0L) +
        (preflight?.mqttPending ?: 0L) +
        (preflight?.influxPending ?: 0L)
    val debugArchive = state.operation == DbMaintenanceOperation.DEBUG_ARCHIVE
    val hasPendingWork = pending > 0L || preflight?.telegramDeferred == true
    Text(
        if (debugArchive) strings.dbMaintenanceDebugStopWarning else strings.dbMaintenanceStopWarning,
        color = p.text,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.SemiBold
    )
    if (!debugArchive && pending > 0L) {
        Text(
            String.format(
                strings.dbMaintenancePendingTemplate,
                preflight?.telegramPending ?: 0L,
                preflight?.mqttPending ?: 0L,
                preflight?.influxPending ?: 0L
            ),
            color = p.yellow,
            fontSize = 14.sp,
            lineHeight = 19.sp
        )
    }
    if (!debugArchive && preflight?.telegramDeferred == true) {
        Text(strings.dbMaintenanceTelegramDeferredWarning, color = p.yellow, fontSize = 14.sp, lineHeight = 19.sp)
    }
    if (!debugArchive && hasPendingWork) {
        Text(strings.dbMaintenanceArchivePendingWarning, color = p.yellow, fontSize = 14.sp, lineHeight = 19.sp)
    }
    Text(String.format(strings.dbMaintenanceConfirmTemplate, operationTitle(strings, state.operation)), color = p.text, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
    Text(strings.operationCannotBeStopped, color = p.text, fontSize = 14.sp, lineHeight = 19.sp)
    Text(strings.interruptionDataLossRisk, color = p.red, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun DatabaseMaintenanceRunningBody(strings: UiStrings, state: DbMaintenanceUiState) {
    val p = LocalBydPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = p.accent, strokeWidth = 3.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${strings.step} ${state.stepIndex}/${state.stepCount}", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(localizedMaintenanceMessage(strings, state), color = p.muted, fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}

private fun localizedMaintenanceMessage(strings: UiStrings, state: DbMaintenanceUiState): String {
    return if (strings.step == "Крок") state.messageUk.ifBlank { state.operation.stepsUk.getOrNull((state.stepIndex - 1).coerceAtLeast(0)) ?: "" }
    else state.messageEn.ifBlank { state.operation.stepsEn.getOrNull((state.stepIndex - 1).coerceAtLeast(0)) ?: "" }
}

private fun localizedUpdateError(strings: UiStrings, message: String): String {
    val normalized = message.lowercase()
    return when {
        "unable to resolve host" in normalized ||
            "failed to connect" in normalized ||
            "timeout" in normalized ||
            "network" in normalized ||
            "no address associated" in normalized -> strings.updateNetworkUnavailable
        "download" in normalized || "missing" in normalized -> strings.updateDownloadFailed
        "apk" in normalized ||
            "package" in normalized ||
            "versioncode" in normalized ||
            "certificate" in normalized ||
            "digest" in normalized ||
            "verification" in normalized -> strings.updateVerificationFailed
        "activitynotfound" in normalized || "installer" in normalized -> strings.updateInstallFailed
        else -> strings.updateGenericError
    }
}

@Composable
private fun ArchiveDeleteDialog(
    strings: UiStrings,
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalBydPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background.copy(alpha = 0.82f))
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        ModalInputBlocker()
        Column(
            modifier = Modifier
                .width(440.dp)
                .background(p.panel, Rounded8)
                .border(1.dp, p.borderStrong, Rounded8)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(strings.deleteSelected, color = p.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(String.format(strings.selectedArchivesTemplate, count), color = p.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(strings.deleteArchivesQuestion, color = p.text, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(strings.yes, onConfirm, primary = true, modifier = Modifier.weight(1f))
                ActionButton(strings.no, onDismiss, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ArchiveStorageProgressDialog(strings: UiStrings, status: ArchiveStorageJobStatus) {
    val p = LocalBydPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background.copy(alpha = 0.82f))
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        ModalInputBlocker()
        Column(
            modifier = Modifier
                .width(440.dp)
                .background(p.panel, Rounded8)
                .border(1.dp, p.borderStrong, Rounded8)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(strings.deleteSelected, color = p.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = p.accent, strokeWidth = 3.dp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${strings.step} ${status.stepIndex}/${status.stepCount}", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(localizedArchiveStorageMessage(strings, status), color = p.muted, fontSize = 14.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}

@Composable
private fun StorageTab(
    state: DashboardState?,
    strings: UiStrings,
    actions: BydCollectorActions,
    onRequestDelete: (List<String>) -> Unit
) {
    val snapshot = state?.archiveStorageSnapshot
    val entries = snapshot?.entries.orEmpty()
    val listKey = entries.joinToString("|") { it.id }
    var selectedIds by remember(listKey) { mutableStateOf(emptySet<String>()) }
    val limitGb = state?.archiveStorageLimitGb ?: 2
    var draftLimitGb by remember(limitGb) { mutableStateOf(limitGb) }
    var newestFirst by remember { mutableStateOf(true) }
    val sortedEntries = if (newestFirst) {
        entries.sortedWith(compareByDescending<ArchiveStorageEntry> { it.createdAtMs }.thenBy { it.id })
    } else {
        entries.sortedWith(compareBy<ArchiveStorageEntry> { it.createdAtMs }.thenBy { it.id })
    }
    val job = state?.archiveStorageJobStatus
    val selectedArchiveIds = sortedEntries.filter { selectedIds.contains(it.id) }.map { it.id }
    val selectedEntries = entries.filter { selectedIds.contains(it.id) }
    val shareEnabled = selectedIds.isNotEmpty() &&
        selectedEntries.size == selectedIds.size &&
        selectedEntries.all { it.status == ArchiveEntryStatus.COMPRESSED_ZIP } &&
        job?.running != true &&
        !CollectorService.isArchiveStorageActive()
    val topCardHeight = 156.dp

    TabScrollColumn {
        ScreenTitle(strings.storageTab, strings.storageSubtitle)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionCard(strings.activeDatabase, modifier = Modifier.weight(0.6f).height(topCardHeight)) {
                    Row(
                        Modifier.fillMaxWidth().height(34.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            strings.size,
                            color = LocalBydPalette.current.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            String.format(
                                strings.activeDatabaseSizeTemplate,
                                UiSizeFormatter.bytes(snapshot?.activeDatabaseSizeBytes ?: ((state?.databaseSizeBytes ?: 0L) + (state?.debugDatabaseSizeBytes ?: 0L)), strings),
                                UiSizeFormatter.bytes(snapshot?.mainDatabaseSizeBytes ?: state?.databaseSizeBytes ?: 0L, strings),
                                UiSizeFormatter.bytes(snapshot?.debugDatabaseSizeBytes ?: state?.debugDatabaseSizeBytes ?: 0L, strings)
                            ),
                            color = LocalBydPalette.current.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(2.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    state?.mainStorageCutoverDeferredReason?.let {
                        Text(
                            strings.mainStorageCutoverDeferred,
                            color = LocalBydPalette.current.yellow,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    state?.mainStorageCutoverError?.let { error ->
                        Text(
                            String.format(strings.mainStorageCutoverErrorTemplate, error),
                            color = LocalBydPalette.current.red,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    state?.debugStorageCutoverError?.let { error ->
                        Text(
                            String.format(strings.debugStorageCutoverErrorTemplate, error),
                            color = LocalBydPalette.current.red,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                SectionCard(strings.archiveStorageLimit, modifier = Modifier.weight(0.4f).height(topCardHeight)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionButton("-", { draftLimitGb = (draftLimitGb - 1).coerceAtLeast(1) }, enabled = draftLimitGb > 1, modifier = Modifier.width(42.dp))
                        ReadOnlyPathField(UiSizeFormatter.gigabytes(draftLimitGb, strings), modifier = Modifier.weight(0.7f))
                        ActionButton("+", { draftLimitGb = (draftLimitGb + 1).coerceAtMost(10) }, enabled = draftLimitGb < 10, modifier = Modifier.width(42.dp))
                        ActionButton(strings.ok, { actions.onSetArchiveStorageLimitGb(draftLimitGb) }, primary = true, enabled = draftLimitGb != limitGb, modifier = Modifier.weight(1f))
                    }
                    Text(strings.archiveStorageLimitHint, color = LocalBydPalette.current.muted, fontSize = 12.sp, lineHeight = 16.sp)
                }
        }
        SectionCard(
                strings.archiveStorage,
                modifier = Modifier.fillMaxWidth(),
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state?.archiveStorageScanPending == true) {
                            StatusPill(strings.archiveCalculating, StatusKind.WAITING, compact = true)
                        }
                        StatusPill(archiveUsageText(snapshot, strings), archiveUsageKind(snapshot), compact = true)
                        Text(
                            String.format(strings.archiveCountShortTemplate, entries.size),
                            color = LocalBydPalette.current.muted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ReadOnlyPathField(snapshot?.archiveRootPath ?: "-", modifier = Modifier.weight(1f))
                    ArchiveShareIconButton(
                        enabled = shareEnabled,
                        contentDescription = strings.shareSelectedArchives,
                        onClick = { actions.onShareArchives(selectedArchiveIds) }
                    )
                    val sortLabel = if (newestFirst) strings.archiveSortNewestFirst else strings.archiveSortOldestFirst
                    ActionButton(sortLabel, { newestFirst = !newestFirst }, modifier = Modifier.width(180.dp))
                }
                job?.takeIf { it.running }?.let {
                    ArchiveStorageInlineStatus(strings, it)
                }
                if (entries.isEmpty()) {
                    Text(
                        if (state?.archiveStorageScanPending == true) strings.archiveStorageScanning else strings.archiveStorageEmpty,
                        color = LocalBydPalette.current.muted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    sortedEntries.forEach { entry ->
                        ArchiveEntryRow(
                            entry = entry,
                            strings = strings,
                            selected = selectedIds.contains(entry.id),
                            onToggle = {
                                selectedIds = if (selectedIds.contains(entry.id)) {
                                    selectedIds - entry.id
                                } else {
                                    selectedIds + entry.id
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ActionButton(strings.deleteSelected,
                            { onRequestDelete(selectedIds.toList()) },
                            primary = true,
                            enabled = selectedIds.isNotEmpty(),
                            modifier = Modifier.width(320.dp)
                        )
                    }
                }
        }
    }
}

@Composable
private fun ArchiveStorageInlineStatus(strings: UiStrings, status: ArchiveStorageJobStatus) {
    val p = LocalBydPalette.current
    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = p.accent, strokeWidth = 3.dp)
        Text(
            text = localizedArchiveStorageMessage(strings, status),
            color = p.muted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        status.itemId?.let {
            Text(it, color = p.pathText, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ArchiveEntryRow(
    entry: ArchiveStorageEntry,
    strings: UiStrings,
    selected: Boolean,
    onToggle: () -> Unit
) {
    val p = LocalBydPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(if (selected) p.active else p.surface, Rounded8)
                .border(1.dp, if (selected) p.accent else p.borderStrong, Rounded8)
                .clickable(enabled = entry.deletable, onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Text("✓", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.displayName, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(UiSizeFormatter.bytes(entry.sizeBytes, strings), color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        StatusPill(archiveStatusLabel(strings, entry.status), archiveStatusKind(entry.status), compact = true)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
}

private fun archiveUsageText(snapshot: ArchiveStorageSnapshot?, strings: UiStrings): String {
    val archiveBytes = snapshot?.archiveBytes ?: 0L
    val limitBytes = snapshot?.archiveLimitBytes ?: 0L
    return "${UiSizeFormatter.bytes(archiveBytes, strings)}/${UiSizeFormatter.bytes(limitBytes, strings)}"
}

private fun archiveUsageKind(snapshot: ArchiveStorageSnapshot?): StatusKind {
    val limitBytes = snapshot?.archiveLimitBytes ?: 0L
    val ratio = if (limitBytes <= 0L) 0.0 else (snapshot?.archiveBytes ?: 0L).toDouble() / limitBytes.toDouble()
    return when {
        ratio <= 0.5 -> StatusKind.OK
        ratio <= 0.9 -> StatusKind.WARNING
        else -> StatusKind.ERROR
    }
}

private fun localizedDbMaintenanceError(strings: UiStrings, error: String): String {
    val normalized = error.lowercase()
    return when {
        "interrupted before completion" in normalized -> strings.dbMaintenanceInterrupted
        "already running" in normalized -> strings.dbMaintenanceAlreadyRunning
        "cancelled" in normalized -> strings.dbMaintenanceCancelled
        else -> strings.dbMaintenanceGenericError
    }
}

private fun operationTitle(strings: UiStrings, operation: DbMaintenanceOperation): String {
    return when (operation) {
        DbMaintenanceOperation.ARCHIVE -> strings.archiveDatabase
        DbMaintenanceOperation.DEBUG_ARCHIVE -> strings.archiveDebugDatabase
    }
}

private fun archiveStatusLabel(strings: UiStrings, status: ArchiveEntryStatus): String {
    return when (status) {
        ArchiveEntryStatus.RAW_DIRECTORY -> strings.archiveStatusRaw
        ArchiveEntryStatus.COMPRESSED_ZIP -> strings.archiveStatusZip
        ArchiveEntryStatus.TMP -> strings.archiveStatusTmp
    }
}

private fun archiveStatusKind(status: ArchiveEntryStatus): StatusKind {
    return when (status) {
        ArchiveEntryStatus.RAW_DIRECTORY -> StatusKind.WARNING
        ArchiveEntryStatus.COMPRESSED_ZIP -> StatusKind.OK
        ArchiveEntryStatus.TMP -> StatusKind.WAITING
    }
}

private fun localizedArchiveStorageMessage(strings: UiStrings, status: ArchiveStorageJobStatus): String {
    val fallback = when (status.mode) {
        ArchiveStorageJobMode.DELETE -> strings.deletingArchive
        ArchiveStorageJobMode.COMPRESS -> strings.archiveDatabase
        ArchiveStorageJobMode.RETENTION -> strings.archiveStorage
        null -> strings.archiveStorage
    }
    return if (strings.step == "Крок") status.messageUk.ifBlank { fallback } else status.messageEn.ifBlank { fallback }
}

@Composable
private fun AvailableUpdateNotes(strings: UiStrings, info: UpdateInfo, language: UiLanguage) {
    val selectedReleaseNotes = remember(info.releaseNotes, language) {
        ReleaseNotesSelector.select(info.releaseNotes, language == UiLanguage.UK)
    }
    Text(
        text = "${strings.availableVersion} ${info.version}",
        color = LocalBydPalette.current.text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(12.dp))
    MarkdownPatchNotesText(selectedReleaseNotes)
}

@Composable
private fun DownloadingUpdateHeader(strings: UiStrings) {
    Text(
        text = strings.downloadingUpdate,
        color = LocalBydPalette.current.text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun UpdateProgressBar(progress: Int) {
    val p = LocalBydPalette.current
    val percent = progress.coerceIn(0, 100)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .background(p.disabled, Rounded8),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth((percent / 100f).coerceIn(0.02f, 1f))
                .background(p.accent, Rounded8)
                .align(Alignment.CenterStart)
        )
        Text("$percent%", color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MarkdownPatchNotesText(text: String) {
    val p = LocalBydPalette.current
    val blocks = remember(text) { ReleaseNotesMarkdown.parse(text) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                ReleaseNotesMarkdownBlock.Blank -> Spacer(Modifier.height(6.dp))
                ReleaseNotesMarkdownBlock.Separator -> Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
                is ReleaseNotesMarkdownBlock.Heading -> Text(
                    text = block.spans.toAnnotatedString(),
                    color = p.text,
                    fontSize = when (block.level) {
                        1 -> 16.sp
                        2 -> 14.sp
                        else -> 13.sp
                    },
                    fontWeight = FontWeight.Bold,
                    lineHeight = 19.sp
                )
                is ReleaseNotesMarkdownBlock.Bullet -> MarkdownListRow("•", block.spans)
                is ReleaseNotesMarkdownBlock.Ordered -> MarkdownListRow("${block.number}.", block.spans)
                is ReleaseNotesMarkdownBlock.Paragraph -> Text(
                    text = block.spans.toAnnotatedString(),
                    color = p.text,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun MarkdownListRow(marker: String, spans: List<ReleaseNotesMarkdownSpan>) {
    val p = LocalBydPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = marker,
            modifier = Modifier.widthIn(min = 22.dp),
            color = p.text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.End
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = spans.toAnnotatedString(),
            modifier = Modifier.weight(1f),
            color = p.text,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun List<ReleaseNotesMarkdownSpan>.toAnnotatedString(): AnnotatedString {
    val p = LocalBydPalette.current
    return buildAnnotatedString {
        forEach { span ->
            when (span) {
                is ReleaseNotesMarkdownSpan.Text -> append(span.value)
                is ReleaseNotesMarkdownSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.value) }
                is ReleaseNotesMarkdownSpan.Code -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = p.disabled.copy(alpha = 0.72f)
                    )
                ) {
                    append(span.value)
                }
            }
        }
    }
}

@Composable
private fun LogsTab(state: DashboardState?, strings: UiStrings, actions: BydCollectorActions) {
    TabScrollColumn {
        ScreenTitle(strings.logsTab, strings.logsSubtitle)
        SectionCard(strings.controls, Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(strings.startJournal, actions::onStartJournal, primary = true, enabled = state?.logRecording != true, modifier = Modifier.weight(0.35f))
                ActionButton(strings.stopJournal, actions::onStopJournal, enabled = state?.logRecording == true, modifier = Modifier.weight(0.15f))
                ActionButton(strings.startLogcat, actions::onStartLogcat, primary = true, enabled = state?.logRecording != true, modifier = Modifier.weight(0.35f))
                ActionButton(strings.stopLogcat, actions::onStopLogcat, enabled = state?.logRecording == true, modifier = Modifier.weight(0.15f))
            }
        }
        LogsMetricsGrid(state, strings)
    }
}

@Composable
private fun LogsMetricsGrid(state: DashboardState?, strings: UiStrings) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LogCard(
                title = strings.mainPolling,
                status = if (state?.running == true) strings.running else strings.waiting,
                kind = if (state?.running == true) StatusKind.OK else StatusKind.WAITING,
                rows = listOf(
                    strings.size to UiSizeFormatter.bytes(state?.databaseSizeBytes ?: 0L, strings),
                    strings.rows to formatCount(state?.valueRowCount ?: 0L),
                    strings.lastSuccess to (state?.lastSuccessAt ?: "-"),
                    strings.lastError to (state?.lastErrorAt ?: "-"),
                    strings.sessionErrors to errorCount(state)
                ),
                modifier = Modifier.weight(1f)
            )
            LogCard(
                title = strings.allParameters,
                status = if (state?.debugPollingRunning == true) strings.running else strings.waiting,
                kind = if (state?.debugPollingRunning == true) StatusKind.OK else StatusKind.WAITING,
                rows = listOf(
                    strings.size to UiSizeFormatter.bytes(state?.debugDatabaseSizeBytes ?: 0L, strings),
                    strings.rows to formatCount(state?.debugReadingCount ?: 0L),
                    strings.lastSuccess to (state?.debugLastReadingAt ?: "-"),
                    strings.lastError to (state?.debugLastErrorAt ?: state?.debugLastError ?: "-"),
                    strings.sessionErrors to formatCount(state?.debugErrorCount ?: 0L)
                ),
                modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LogCard(
                title = "MQTT",
                status = compactChannelStatusText(state?.mqttStatus, strings),
                kind = channelStatusKind(state?.mqttStatus, state?.mqttEnabled == true),
                rows = listOf(
                    "" to "",
                    strings.queued to "${state?.mqttPendingCount ?: 0L} ${strings.messages}",
                    strings.lastSuccess to (state?.mqttLastPublishedAt ?: "-"),
                    strings.lastError to (state?.mqttRetryLastFailureAt ?: state?.mqttLastError ?: "-"),
                    strings.sessionErrors to (state?.mqttRetryFailureCount ?: 0).toString()
                ),
                modifier = Modifier.weight(1f)
            )
            LogCard(
                title = "InfluxDB",
                status = compactChannelStatusText(state?.influxStatus, strings),
                kind = channelStatusKind(state?.influxStatus, state?.influxEnabled == true),
                rows = listOf(
                    strings.exported to "${formatCount(state?.influxExportedRowsTotal ?: 0L)} ${strings.points}",
                    strings.queued to "${formatCount(state?.influxPendingRows ?: 0L)} ${strings.points}",
                    strings.lastSuccess to (state?.influxLastSuccessAt ?: "-"),
                    strings.lastError to (state?.influxLastErrorAt ?: state?.influxLastError ?: "-"),
                    strings.batch to "300"
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LogCard(title: String, status: String, kind: StatusKind, rows: List<Pair<String, String>>, modifier: Modifier) {
    SectionCard(title = title, trailing = { StatusPill(status, kind, compact = true) }, modifier = modifier.height(300.dp)) {
        rows.forEachIndexed { index, row ->
            InfoRow(row.first, row.second, divider = index != rows.lastIndex)
        }
    }
}

@Composable
private fun BottomTabs(activeTab: AppTab, strings: UiStrings, actions: BydCollectorActions) {
    val tabs = listOf(
        AppTab.MAIN to (strings.mainTab to BottomTabIcon.HOME),
        AppTab.ALL_PARAMETERS to (strings.allTab to BottomTabIcon.STORAGE),
        AppTab.TRIPS to (strings.tripsTab to BottomTabIcon.TRIPS),
        AppTab.HA to (strings.haTab to BottomTabIcon.HA),
        AppTab.TELEGRAM to (strings.telegram.tab to BottomTabIcon.TELEGRAM),
        AppTab.STORAGE to (strings.storageTab to BottomTabIcon.DATABASE),
        AppTab.EXTRA to (strings.extraTab to BottomTabIcon.GEAR),
        AppTab.LOGS to (strings.logsTab to BottomTabIcon.LOGS)
    )
    val p = LocalBydPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(p.surface, Rounded8)
            .padding(7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { (tab, pair) ->
            val selected = tab == activeTab
            val interactionSource = remember { MutableInteractionSource() }
            val press = rememberForcedPressClick(
                enabled = true,
                invokeImmediately = true
            ) { actions.onTabSelected(tab) }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pressScaleModifier(interactionSource, forcePressed = press.visualPressed)
                    .background(if (selected) p.active else p.surface, Rounded8)
                    .clickableNoRipple(interactionSource, press.visualPressed, enabled = !press.locked) { press.onClick() }
                    .borderSafe(if (selected) p.accent else p.surface)
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabIcon(pair.second, color = if (selected) p.text else p.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = pair.first,
                    color = if (selected) p.text else p.muted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun Modifier.clickableNoRipple(
    interactionSource: MutableInteractionSource,
    forcePressed: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier =
    this.then(
        Modifier.clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    )

@Composable
private fun Modifier.borderSafe(color: androidx.compose.ui.graphics.Color): Modifier =
    this.then(Modifier.border(1.dp, color, Rounded8))

private fun formatCount(value: Long): String {
    return if (value < 0L) "-" else "%,d".format(Locale.US, value).replace(",", " ")
}

private fun errorCount(state: DashboardState?): String {
    return if (state?.lastErrorAt != null || state?.lastError != null) "1" else "0"
}

private fun compactChannelStatusText(status: String?, strings: UiStrings): String {
    return ChannelStatusFormatter.compactText(status, strings)
}

private fun channelStatusKind(status: String?, enabled: Boolean): StatusKind {
    return ChannelStatusFormatter.kind(status, enabled)
}
