package com.bydcollector.collector.ui.compose

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Paint
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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import com.bydcollector.collector.telegram.TelegramEventType
import com.bydcollector.collector.telegram.TelegramNavigatorMask
import com.bydcollector.collector.telegram.TelegramPayloadLimitState
import com.bydcollector.collector.telegram.TelegramTemplateErrorKind
import com.bydcollector.collector.telegram.TelegramTemplateRenderer
import com.bydcollector.collector.ui.DashboardState
import com.bydcollector.collector.ui.DebugRuntimeStatus
import com.bydcollector.collector.ui.RuntimeActionStatus
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
import org.osmdroid.views.overlay.Marker
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
    telegramUiState: TelegramUiState = TelegramUiState(),
    telegramActions: TelegramUiActions = TelegramUiActions(),
    appVersionName: String = "",
    updateAutoCheckEnabled: Boolean = true,
    updateUiState: UpdateUiState = UpdateUiState.Hidden,
    databaseMaintenanceUiState: DbMaintenanceUiState? = null,
    diagnosticsBusy: Boolean = false,
    actionUiState: BydCollectorActionUiState = BydCollectorActionUiState(),
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
        var showClearLogsDialog by rememberSaveable { mutableStateOf(false) }
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
                                AppTab.MAIN -> MainTab(state, s, actions, actionUiState)
                                AppTab.ALL_PARAMETERS -> AllParametersTab(state, s, language, actions)
                                AppTab.TRIPS -> TripsTab(tripsUiState, tripsUiActions, s, language)
                                 AppTab.HA -> HaTab(
                                     state,
                                     s,
                                     mqttDraft,
                                     influxDraft,
                                     actions,
                                     actionUiState
                                 )
                                AppTab.TELEGRAM -> TelegramTab(s, telegramUiState, telegramActions)
                                 AppTab.STORAGE -> StorageTab(state, s, actions, actionUiState) { ids ->
                                    pendingArchiveDeleteIds = ids
                                }
                                AppTab.EXTRA -> ExtraTab(
                                    state = state,
                                    strings = s,
                                    updateAutoCheckEnabled = updateAutoCheckEnabled,
                                    diagnosticsBusy = diagnosticsBusy,
                                    actions = actions,
                                    onRequestClearLogs = { showClearLogsDialog = true },
                                )
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
                if (showClearLogsDialog) {
                    ClearLogsDialog(
                        strings = s,
                        onConfirm = {
                            showClearLogsDialog = false
                            actions.onClearLogs()
                        },
                        onDismiss = { showClearLogsDialog = false },
                    )
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
            "charge_step_duration",
            "charge_added_percent",
            "charge_added_kwh",
            "charge_duration",
            "battery_power_kw"
        )
    ),
    TelegramMessageDefinition(
        TelegramMessageType.CHARGED_TO_100,
        listOf(
            "soc",
            "remaining_energy_kwh",
            "range_km",
            "time",
            "charge_added_percent",
            "charge_added_kwh",
            "charge_start_time",
            "charge_end_time",
            "charge_duration_hhmm"
        )
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
            "total_soc_start",
            "total_soc_end",
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
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val unit: String,
    val step: Float = 1f,
    val onValueChange: (Float) -> Unit
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
private fun MainTab(
    state: DashboardState?,
    strings: UiStrings,
    actions: BydCollectorActions,
    actionUiState: BydCollectorActionUiState
) {
    TabScrollColumn {
        ScreenTitle(strings.mainTab, strings.mainSubtitle)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MainCollectionCard(
                state = state,
                strings = strings,
                actions = actions,
                actionUiState = actionUiState,
                modifier = Modifier.weight(1.15f)
            )
            MainStatusCard(
                state = state,
                strings = strings,
                actions = actions,
                actionUiState = actionUiState,
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
    actionUiState: BydCollectorActionUiState,
    modifier: Modifier
) {
    SectionCard(title = strings.dataCollection, modifier = modifier.height(226.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val status = state?.mainRuntimeStatus ?: RuntimeActionStatus.STOPPED
            ActionButton(
                if (status == RuntimeActionStatus.STARTING) strings.starting else strings.start,
                actions::onStartMain,
                primary = true,
                enabled = status != RuntimeActionStatus.STARTING && status != RuntimeActionStatus.RUNNING,
                modifier = Modifier.weight(1f)
            )
            ActionButton(
                if (status == RuntimeActionStatus.STOPPING) strings.stopping else strings.stop,
                actions::onStopMain,
                enabled = status != RuntimeActionStatus.STOPPING && status != RuntimeActionStatus.STOPPED,
                modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.permissions, color = LocalBydPalette.current.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.75f))
            ActionButton(
                if (actionUiState.adbGrant) strings.loading else strings.grantAdb,
                actions::onGrantAdb,
                primary = true,
                enabled = !actionUiState.adbGrant,
                modifier = Modifier.weight(0.9f)
            )
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
private fun MainStatusCard(
    state: DashboardState?,
    strings: UiStrings,
    actions: BydCollectorActions,
    actionUiState: BydCollectorActionUiState,
    modifier: Modifier
) {
    val mainPollingStatus = when (state?.mainRuntimeStatus ?: RuntimeActionStatus.STOPPED) {
        RuntimeActionStatus.STARTING -> MainPollDisplayStatus(strings.starting, StatusKind.WAITING)
        RuntimeActionStatus.STOPPING -> MainPollDisplayStatus(strings.stopping, StatusKind.WAITING)
        RuntimeActionStatus.ERROR -> MainPollDisplayStatus(strings.error, StatusKind.ERROR)
        else -> MainPollStatusFormatter.format(
            running = state?.mainPollingRunning == true,
            lastPollStatus = state?.lastPollStatus,
            strings = strings
        )
    }
    SectionCard(title = strings.status, modifier = modifier.height(226.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusRow(strings.mainPolling, mainPollingStatus.text, mainPollingStatus.kind, modifier = Modifier.weight(1f))
            StatusRow(
                strings.allParameters,
                debugRuntimeDisplay(state, strings).first,
                debugRuntimeDisplay(state, strings).second,
                modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusRow("MQTT", compactChannelStatusText(state?.mqttStatus, strings, state?.mqttRuntimeStatus), channelStatusKind(state?.mqttStatus, state?.mqttEnabled == true, state?.mqttRuntimeStatus), modifier = Modifier.weight(1f))
            StatusRow("InfluxDB", compactChannelStatusText(state?.influxStatus, strings, state?.influxRuntimeStatus), channelStatusKind(state?.influxStatus, state?.influxEnabled == true, state?.influxRuntimeStatus), modifier = Modifier.weight(1f))
        }
        ActionButton(
            if (actionUiState.mainArchivePreflight) strings.loading else strings.archiveDatabase,
            actions::onOpenArchiveDatabase,
            primary = true,
            enabled = !actionUiState.mainArchivePreflight,
            modifier = Modifier.fillMaxWidth()
        )
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
                        val debugStatus = debugRuntimeDisplay(state, strings)
                        StatusPill(
                            debugStatus.first,
                            debugStatus.second,
                            compact = true
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(262.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val debugStatus = state?.debugRuntimeStatus ?: DebugRuntimeStatus.STOPPED
                        val startAllowed = debugStatus != DebugRuntimeStatus.STARTING && debugStatus != DebugRuntimeStatus.RUNNING
                        ActionButton(
                            if (debugStatus == DebugRuntimeStatus.STARTING) strings.starting else strings.start,
                            actions::onStartDebug,
                            primary = true,
                            enabled = startAllowed,
                            modifier = Modifier.weight(1f)
                        )
                        ActionButton(
                            if (debugStatus == DebugRuntimeStatus.STOPPING) strings.stopping else strings.stop,
                            actions::onStopDebug,
                            enabled = debugStatus != DebugRuntimeStatus.STOPPING && debugStatus != DebugRuntimeStatus.STOPPED,
                            modifier = Modifier.weight(1f)
                        )
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

private fun debugRuntimeDisplay(state: DashboardState?, strings: UiStrings): Pair<String, StatusKind> {
    return when (state?.debugRuntimeStatus ?: DebugRuntimeStatus.STOPPED) {
        DebugRuntimeStatus.STOPPED -> strings.stopped to StatusKind.WAITING
        DebugRuntimeStatus.STARTING -> strings.starting to StatusKind.WAITING
        DebugRuntimeStatus.RUNNING -> strings.running to StatusKind.OK
        DebugRuntimeStatus.STOPPING -> strings.stopping to StatusKind.WAITING
        DebugRuntimeStatus.ERROR -> strings.error to StatusKind.ERROR
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
            InfoRow(strings.error, state?.debugRuntimeError ?: state?.debugLastErrorAt ?: state?.debugLastError ?: "-", modifier = Modifier.weight(1f), divider = false)
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
    strings: UiStrings,
    language: UiLanguage
) {
    var selectedTripId by remember { mutableStateOf<String?>(null) }
    var expandedYears by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var expandedMonths by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var expandedDays by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var expansionInitialized by rememberSaveable { mutableStateOf(false) }
    val yearIds = state.years.map { it.id }
    LaunchedEffect(yearIds) {
        if (!expansionInitialized && state.years.isNotEmpty()) {
            val year = state.years.first()
            val month = year.months.firstOrNull()
            val day = month?.days?.firstOrNull()
            expandedYears = listOf(year.id)
            expandedMonths = month?.let { listOf(it.id) }.orEmpty()
            expandedDays = day?.let { listOf(it.id) }.orEmpty()
            expansionInitialized = true
        }
    }
    val selectedTrip = state.years.asSequence()
        .flatMap { it.months.asSequence() }
        .flatMap { it.days.asSequence() }
        .flatMap { it.trips.asSequence() }
        .firstOrNull { it.id == selectedTripId }
    TabScrollColumn {
        ScreenTitle(strings.tripsTab, strings.tripsSubtitle)
        SectionCard(
            title = strings.routeColors,
            modifier = Modifier.fillMaxWidth(),
            bodyPadding = 0.dp,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            strings.speed,
                            color = if (state.colorMetric == TripMapMetric.SPEED) LocalBydPalette.current.text else LocalBydPalette.current.muted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        BydSwitch(
                            checked = state.colorMetric == TripMapMetric.CONSUMPTION,
                            onCheckedChange = { actions.onColorMetricChanged(if (it) TripMapMetric.CONSUMPTION else TripMapMetric.SPEED) },
                            binary = true
                        )
                        Text(
                            strings.consumption,
                            color = if (state.colorMetric == TripMapMetric.CONSUMPTION) LocalBydPalette.current.text else LocalBydPalette.current.muted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
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
        ) {}
        SectionCard(strings.tripList, modifier = Modifier.fillMaxWidth(), bodyPadding = 0.dp) {
            if (state.years.isEmpty()) {
                Text(strings.noTrips, color = LocalBydPalette.current.muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(18.dp), textAlign = TextAlign.Center)
            } else {
                state.years.forEach { year ->
                    val yearExpanded = year.id in expandedYears
                    TripGroupRow(
                        strings, language, year.title, year.distanceKm, year.energyKwh,
                        year.averageConsumptionKwhPer100Km, year.months.sumOf { it.days.sumOf { day -> day.trips.size } },
                        level = 0, expanded = yearExpanded,
                        onClick = { expandedYears = toggleExpanded(expandedYears, year.id) }
                    )
                    if (yearExpanded) year.months.forEach { month ->
                        val monthExpanded = month.id in expandedMonths
                        TripGroupRow(
                            strings, language, month.title, month.distanceKm, month.energyKwh,
                            month.averageConsumptionKwhPer100Km, month.days.sumOf { it.trips.size },
                            level = 1, expanded = monthExpanded,
                            onClick = { expandedMonths = toggleExpanded(expandedMonths, month.id) }
                        )
                        if (monthExpanded) month.days.forEach { day ->
                            val dayExpanded = day.id in expandedDays
                            TripGroupRow(
                                strings, language, day.title, day.distanceKm, day.energyKwh,
                                day.averageConsumptionKwhPer100Km, day.trips.size,
                                level = 2, expanded = dayExpanded,
                                onClick = { expandedDays = toggleExpanded(expandedDays, day.id) }
                            )
                            if (dayExpanded) {
                                TripTableHeader(strings)
                                day.trips.forEach { trip ->
                                    TripTableRow(strings, language, trip, loading = state.routeLoadingId == trip.id) {
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
    }
    selectedTrip?.let { trip ->
        TripRouteDialog(
            trip = trip,
            state = state,
            strings = strings,
            language = language,
            onDismiss = { selectedTripId = null }
        )
    }
}

private fun toggleExpanded(ids: List<String>, id: String): List<String> =
    if (id in ids) ids - id else ids + id

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
    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        TripColorLabel(strings.green, LocalBydPalette.current.green, LocalBydPalette.current.greenSoft)
        TripRuleSymbol(boundary)
        TripThresholdField(greenText) { value ->
            greenText = value
            value.toIntOrNull()?.let { onChange(it, yellowText.toIntOrNull() ?: yellow) }
        }
        TripRuleSymbol(between)
        TripColorLabel(strings.yellow, LocalBydPalette.current.yellow, LocalBydPalette.current.yellowSoft)
        TripRuleSymbol(boundary)
        TripThresholdField(yellowText) { value ->
            yellowText = value
            value.toIntOrNull()?.let { onChange(greenText.toIntOrNull() ?: green, it) }
        }
        TripRuleSymbol(between)
        TripColorLabel(strings.red, LocalBydPalette.current.red, LocalBydPalette.current.redSoft)
    }
}

@Composable
private fun TripGroupRow(
    strings: UiStrings,
    language: UiLanguage,
    title: String,
    distanceKm: Double?,
    energyKwh: Double?,
    averageConsumption: Double?,
    tripCount: Int,
    level: Int,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val p = LocalBydPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(if (pressed) p.activeSoft else if (level == 0) p.panelAlt else Color.Transparent)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(start = (12 + level * 18).dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DisclosureMark(expanded)
                Spacer(Modifier.width(8.dp))
                Text(title, color = p.text, fontSize = if (level == 0) 17.sp else if (level == 1) 14.sp else 13.sp, fontWeight = if (level < 2) FontWeight.SemiBold else FontWeight.Medium)
            }
            Text(
                "${formatTripNumber(distanceKm, language)} ${distanceUnit(language)} • ${formatTripNumber(energyKwh, language)} ${energyUnit(language)} • ${formatTripNumber(averageConsumption, language)} ${consumptionUnit(language)} • ${tripCountLabel(tripCount, language)}",
                color = p.muted,
                fontSize = 12.sp,
                textAlign = TextAlign.End
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
    }
}

@Composable
private fun TripTableHeader(strings: UiStrings) {
    Row(Modifier.fillMaxWidth().height(34.dp).background(LocalBydPalette.current.pathField.copy(alpha = 0.48f)).padding(start = 48.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(strings.startEnd, strings.duration, strings.distance, "SOC", strings.used, strings.averageConsumption, strings.route).forEach { label ->
            TripTableCell(label, LocalBydPalette.current.muted, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TripTableRow(
    strings: UiStrings,
    language: UiLanguage,
    trip: TripSummaryUi,
    loading: Boolean,
    onRoute: () -> Unit
) {
    val p = LocalBydPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(start = 48.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TripTableCell("${trip.startAt} → ${trip.endAt}", p.text, Modifier.weight(1f), FontWeight.SemiBold)
        TripTableCell(trip.duration, p.text, Modifier.weight(1f))
        TripTableCell("${formatTripNumber(trip.distanceKm, language)} ${distanceUnit(language)}", p.text, Modifier.weight(1f))
        TripTableCell("${formatSoc(trip.socStart, language)} → ${formatSoc(trip.socEnd, language)}", p.text, Modifier.weight(1f))
        TripTableCell("${formatTripNumber(trip.energyKwh, language)} ${energyUnit(language)}", p.text, Modifier.weight(1f))
        TripTableCell("${formatTripNumber(trip.averageConsumptionKwhPer100Km, language)} ${consumptionUnit(language)}", p.green, Modifier.weight(1f), FontWeight.SemiBold)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            ActionButton(
                if (loading) strings.loading else strings.route,
                onRoute,
                primary = true,
                enabled = !loading,
                modifier = Modifier.width(108.dp)
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.border.copy(alpha = 0.55f)))
}

@Composable
private fun RowScope.TripTableCell(text: String, color: Color, modifier: Modifier, weight: FontWeight = FontWeight.Normal) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = weight, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier.padding(horizontal = 4.dp))
}

@Composable
private fun DisclosureMark(expanded: Boolean) {
    val p = LocalBydPalette.current
    Canvas(Modifier.size(16.dp)) {
        val stroke = 2.dp.toPx()
        if (expanded) {
            drawLine(p.accent, Offset(size.width * 0.22f, size.height * 0.38f), Offset(size.width * 0.50f, size.height * 0.66f), stroke, StrokeCap.Round)
            drawLine(p.accent, Offset(size.width * 0.50f, size.height * 0.66f), Offset(size.width * 0.78f, size.height * 0.38f), stroke, StrokeCap.Round)
        } else {
            drawLine(p.accent, Offset(size.width * 0.38f, size.height * 0.22f), Offset(size.width * 0.66f, size.height * 0.50f), stroke, StrokeCap.Round)
            drawLine(p.accent, Offset(size.width * 0.66f, size.height * 0.50f), Offset(size.width * 0.38f, size.height * 0.78f), stroke, StrokeCap.Round)
        }
    }
}

private fun formatTripNumber(value: Double?, language: UiLanguage): String = value?.takeIf { it.isFinite() }?.let {
    "%.1f".format(Locale.US, it).trimEnd('0').trimEnd('.').let { text -> if (language == UiLanguage.UK) text.replace('.', ',') else text }
} ?: "—"

private fun formatSoc(value: Double?, language: UiLanguage): String = formatTripNumber(value, language).let { if (it == "—") it else "$it%" }

private fun distanceUnit(language: UiLanguage) = if (language == UiLanguage.UK) "км" else "km"
private fun energyUnit(language: UiLanguage) = if (language == UiLanguage.UK) "кВт·год" else "kWh"
private fun consumptionUnit(language: UiLanguage) = if (language == UiLanguage.UK) "кВт·год/100 км" else "kWh/100 km"
private fun tripCountLabel(count: Int, language: UiLanguage): String = if (language == UiLanguage.EN) {
    "$count ${if (count == 1) "trip" else "trips"}"
} else {
    val word = when {
        count % 10 == 1 && count % 100 != 11 -> "поїздка"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "поїздки"
        else -> "поїздок"
    }
    "$count $word"
}

@Composable
private fun TripColorLabel(text: String, color: Color, background: Color) {
    Box(Modifier.height(34.dp).width(78.dp).clip(Rounded8).background(background).border(1.dp, color.copy(alpha = 0.58f), Rounded8), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TripRuleSymbol(symbol: String) {
    Text(symbol, color = LocalBydPalette.current.muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(28.dp))
}

@Composable
private fun TripThresholdField(value: String, onValueChange: (String) -> Unit) {
    val p = LocalBydPalette.current
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(3)) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
        modifier = Modifier.width(62.dp).height(38.dp).clip(Rounded8).background(p.pathField).border(1.dp, p.borderStrong, Rounded8).padding(horizontal = 8.dp, vertical = 9.dp)
    )
}

@Composable
private fun TripRouteDialog(
    trip: TripSummaryUi,
    state: TripsUiState,
    strings: UiStrings,
    language: UiLanguage,
    onDismiss: () -> Unit
) {
    var metric by rememberSaveable(trip.id) { mutableStateOf(TripMapMetric.SPEED) }
    val p = LocalBydPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(p.background.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
            ModalInputBlocker()
            Column(Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.86f).background(p.panel, Rounded8).border(1.dp, p.borderStrong, Rounded8).padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(strings.tripRoute, color = p.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text("${trip.startAt} → ${trip.endAt} • ${formatTripNumber(trip.distanceKm, language)} ${distanceUnit(language)}", color = p.muted, fontSize = 12.sp)
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
                    BydSwitch(metric == TripMapMetric.CONSUMPTION, { metric = if (it) TripMapMetric.CONSUMPTION else TripMapMetric.SPEED }, binary = true)
                    Spacer(Modifier.width(10.dp))
                    Text(strings.colorByConsumption, color = if (metric == TripMapMetric.CONSUMPTION) p.text else p.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TripEndpointLegend(R.drawable.ic_trip_start_marker, strings.tripStart)
                    Spacer(Modifier.width(12.dp))
                    TripEndpointLegend(R.drawable.ic_trip_finish_marker, strings.tripFinish)
                    Spacer(Modifier.width(12.dp))
                    TripNoDataLegend(strings.tripNoData)
                    Spacer(Modifier.width(18.dp))
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
                if (run.isNotEmpty()) add(run)
                run = mutableListOf()
            } else {
                run += point
            }
        }
        if (run.isNotEmpty()) add(run)
    }
    val p = runs.flatten()
    if (p.isEmpty()) return
    runs.filter { it.size > 1 }.forEach { run ->
        map.overlays += Polyline(map).apply {
            setPoints(run.map { GeoPoint(it.latitude, it.longitude) })
            color = AndroidColor.BLACK
            width = 12f
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
        }
    }
    runs.filter { it.size > 1 }.forEach { run ->
        run.zipWithNext().forEach { (from, to) ->
            val line = Polyline(map).apply {
                setPoints(listOf(GeoPoint(from.latitude, from.longitude), GeoPoint(to.latitude, to.longitude)))
                color = when (metric) {
                    TripMapMetric.SPEED -> routeColor(from.speedKmh, speedGreen.toDouble(), speedYellow.toDouble(), speed = true)
                    TripMapMetric.CONSUMPTION -> routeColor(from.consumptionKwhPer100Km, consumptionGreen.toDouble(), consumptionYellow.toDouble(), speed = false)
                }
                width = 8f
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
            }
            map.overlays += line
        }
    }
    map.overlays += tripMapMarker(map, p.first(), R.drawable.ic_trip_start_marker)
    if (p.size > 1) {
        map.overlays += tripMapMarker(map, p.last(), R.drawable.ic_trip_finish_marker)
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

private fun tripMapMarker(map: MapView, point: TripRoutePointUi, drawableRes: Int): Marker =
    Marker(map).apply {
        position = GeoPoint(point.latitude, point.longitude)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = map.context.getDrawable(drawableRes)
    }

@Composable
private fun TripEndpointLegend(iconRes: Int, label: String) {
    val p = LocalBydPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TripNoDataLegend(label: String) {
    val p = LocalBydPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(26.dp)
                .height(8.dp)
                .background(Color.Gray)
                .border(1.dp, Color.Black)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val TripRouteGreen = Color(0xFF147A55)

private fun routeColor(value: Double?, green: Double, yellow: Double, speed: Boolean): Int = when {
    value == null -> AndroidColor.GRAY
    speed && value >= green -> TripRouteGreen.toArgb()
    speed && value > yellow -> AndroidColor.rgb(242, 195, 78)
    !speed && value <= green -> TripRouteGreen.toArgb()
    !speed && value <= yellow -> AndroidColor.rgb(242, 195, 78)
    else -> AndroidColor.rgb(255, 140, 140)
}

@Composable
private fun HaTab(
    state: DashboardState?,
    strings: UiStrings,
    mqttDraft: MqttDraft,
    influxDraft: InfluxDraft,
    actions: BydCollectorActions,
    actionUiState: BydCollectorActionUiState
) {
    TabScrollColumn {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle(strings.haTab, strings.haSubtitle, modifier = Modifier.weight(1f))
            Text(strings.sharedCategories, color = LocalBydPalette.current.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            BydSwitch(state?.haSharedCategoriesEnabled == true, actions::onToggleSharedCategories, enabled = state?.influxEnabled != true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MqttCard(state, strings, mqttDraft, actions, actionUiState, Modifier.weight(1f))
            InfluxCard(state, strings, influxDraft, actions, actionUiState, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MqttCard(
    state: DashboardState?,
    strings: UiStrings,
    draft: MqttDraft,
    actions: BydCollectorActions,
    actionUiState: BydCollectorActionUiState,
    modifier: Modifier
) {
    SectionCard(
        title = "MQTT",
        trailing = { StatusPill(compactChannelStatusText(state?.mqttStatus, strings, state?.mqttRuntimeStatus), channelStatusKind(state?.mqttStatus, state?.mqttEnabled == true, state?.mqttRuntimeStatus), compact = true) },
        modifier = modifier.height(686.dp)
    ) {
        ChannelButtons(
            strings,
            onStart = actions::onStartMqtt,
            onStop = actions::onStopMqtt,
            onTest = actions::onTestMqtt,
            runtimeStatus = state?.mqttRuntimeStatus ?: RuntimeActionStatus.STOPPED,
            channelEnabled = state?.mqttEnabled == true,
            testInFlight = actionUiState.mqttTest
        )
        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.autoStart, color = LocalBydPalette.current.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            BydSwitch(state?.mqttAutoStartEnabled == true, actions::onToggleMqttAutoStart)
        }
        ChannelPrelude(strings, mqtt = true, pendingText = "${state?.mqttPendingCount ?: 0L} ${strings.messages}", actions = actions)
        CategoryGrid(strings.mqttCategories, state?.mqttEnabledCategories.orEmpty(), enabled = state?.mqttEnabled != true, strings = strings) { category ->
            actions.onToggleMqttCategory(category, !state?.mqttEnabledCategories.orEmpty().contains(category))
        }
        CredentialGridMqtt(strings, draft, actions)
    }
}

@Composable
private fun InfluxCard(
    state: DashboardState?,
    strings: UiStrings,
    draft: InfluxDraft,
    actions: BydCollectorActions,
    actionUiState: BydCollectorActionUiState,
    modifier: Modifier
) {
    SectionCard(
        title = "InfluxDB",
        trailing = { StatusPill(compactChannelStatusText(state?.influxStatus, strings, state?.influxRuntimeStatus), channelStatusKind(state?.influxStatus, state?.influxEnabled == true, state?.influxRuntimeStatus), compact = true) },
        modifier = modifier.height(686.dp)
    ) {
        ChannelButtons(
            strings,
            onStart = actions::onStartInflux,
            onStop = actions::onStopInflux,
            onTest = actions::onTestInflux,
            runtimeStatus = state?.influxRuntimeStatus ?: RuntimeActionStatus.STOPPED,
            channelEnabled = state?.influxEnabled == true,
            testInFlight = actionUiState.influxTest
        )
        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.autoStart, color = LocalBydPalette.current.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            BydSwitch(state?.influxAutoStartEnabled == true, actions::onToggleInfluxAutoStart)
        }
        ChannelPrelude(
            strings,
            mqtt = false,
            pendingText = "${state?.influxPendingRows ?: 0L} ${strings.points}",
            actions = actions,
            reExportInFlight = actionUiState.influxReExport
        )
        CategoryGrid(
            strings.influxCategories,
            if (state?.haSharedCategoriesEnabled == true) state.mqttEnabledCategories else state?.influxEnabledCategories.orEmpty(),
            enabled = state?.influxEnabled != true && state?.haSharedCategoriesEnabled != true,
            strings = strings
        ) { category ->
            actions.onToggleInfluxCategory(category, !state?.influxEnabledCategories.orEmpty().contains(category))
        }
        CredentialGridInflux(strings, draft, actions)
    }
}

@Composable
private fun ChannelPrelude(
    strings: UiStrings,
    mqtt: Boolean,
    pendingText: String,
    actions: BydCollectorActions,
    reExportInFlight: Boolean = false,
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
                    ActionButton(
                        if (reExportInFlight) strings.loading else strings.reExport,
                        actions::onReExportInflux,
                        enabled = !reExportInFlight,
                        modifier = Modifier.fillMaxWidth()
                    )
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
private fun ChannelButtons(
    strings: UiStrings,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTest: () -> Unit,
    runtimeStatus: RuntimeActionStatus,
    channelEnabled: Boolean,
    testInFlight: Boolean
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton(
            if (runtimeStatus == RuntimeActionStatus.STARTING) strings.starting else strings.start,
            onStart,
            primary = true,
            enabled = runtimeStatus != RuntimeActionStatus.STARTING && runtimeStatus != RuntimeActionStatus.RUNNING,
            modifier = Modifier.weight(1f)
        )
        ActionButton(
            if (runtimeStatus == RuntimeActionStatus.STOPPING) strings.stopping else strings.stop,
            onStop,
            enabled = runtimeStatus != RuntimeActionStatus.STOPPING &&
                (runtimeStatus != RuntimeActionStatus.STOPPED || channelEnabled),
            modifier = Modifier.weight(1f)
        )
        ActionButton(
            if (testInFlight) strings.loading else strings.testConnection,
            onTest,
            enabled = !testInFlight,
            modifier = Modifier.weight(1f)
        )
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
            "safety" to strings.safety,
            "location" to strings.location
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
                        tripTemplateLimitState = uiState.tripTemplateLimitState,
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
                enabled = config.botTokenSet && config.chatId.isNotBlank() && testStatus != TelegramTestStatus.TESTING,
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
    tripTemplateLimitState: TelegramPayloadLimitState,
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
    var showLocationSettings by remember(definition.type) { mutableStateOf(false) }

    LaunchedEffect(messageConfig.template) {
        if (template.text != messageConfig.template) {
            template = TextFieldValue(
                text = messageConfig.template,
                selection = TextRange(messageConfig.template.length)
            )
        }
    }

    val templateLimitWarning = if (definition.type == TelegramMessageType.TRIP_SUMMARY) {
        val limitState = if (TelegramTemplateRenderer.validate(TelegramEventType.TRIP_SUMMARY, template.text)
                .any { it.kind == TelegramTemplateErrorKind.TOO_LONG }
        ) {
            TelegramPayloadLimitState.TEMPLATE
        } else {
            tripTemplateLimitState
        }
        when (limitState) {
            TelegramPayloadLimitState.NONE -> null
            TelegramPayloadLimitState.TEMPLATE -> strings.telegram.templateLimitWarning
            TelegramPayloadLimitState.WITH_LOCATION -> strings.telegram.templateLimitWithLocation
        }
    } else {
        null
    }

    fun updateMessage(next: TelegramMessageConfig) {
        onConfigChanged(config.copy(messages = config.messages + (definition.type to next)))
    }

    SectionCard(
        title = localized.title,
        headerWarning = templateLimitWarning,
        trailing = {
            BydSwitch(messageConfig.enabled, { updateMessage(messageConfig.copy(enabled = it)) })
        },
        modifier = modifier
    ) {
        telegramNumberSetting(definition.type, config, strings.telegram, onConfigChanged)?.let { setting ->
            TelegramNumberStepper(
                setting = setting,
            )
        }
        if (definition.type == TelegramMessageType.TRIP_SUMMARY) {
            Row(
                modifier = Modifier.fillMaxWidth().height(42.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionButton(
                    text = strings.sendLocation,
                    onClick = { showLocationSettings = true },
                    modifier = Modifier.weight(1f)
                )
                NumericInput(
                    value = if (!config.sendLocation) strings.telegram.locationStatusNo
                    else String.format(strings.telegram.locationStatusYes, Integer.bitCount(config.navigatorMask and TelegramNavigatorMask.ALL)),
                    modifier = Modifier.width(132.dp),
                    emphasized = true,
                    textAlign = TextAlign.Center
                )
            }
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
                updateMessage(messageConfig.copy(template = next.text, usesDefaultTemplate = false))
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
                updateMessage(messageConfig.copy(template = next.text, usesDefaultTemplate = false))
                showVariablePicker = false
            },
            onDismiss = { showVariablePicker = false }
        )
    }

    if (showLocationSettings) {
        TelegramLocationDialog(
            strings = strings.telegram,
            enabled = config.sendLocation,
            navigatorMask = config.navigatorMask,
            onApply = { enabled, mask ->
                onConfigChanged(config.copy(sendLocation = enabled, navigatorMask = mask))
                showLocationSettings = false
            },
            onDismiss = { showLocationSettings = false }
        )
    }
}

@Composable
private fun TelegramNumberStepper(
    setting: TelegramNumberSetting
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
                setting.onValueChange((setting.value - setting.step).coerceAtLeast(setting.range.start))
            },
            enabled = setting.value > setting.range.start + 0.0001f,
            modifier = Modifier.width(42.dp)
        )
        NumericInput(
            value = formatTelegramNumber(setting.value, setting.unit),
            modifier = Modifier.width(72.dp)
        )
        ActionButton(
            text = "+",
            onClick = {
                setting.onValueChange((setting.value + setting.step).coerceAtMost(setting.range.endInclusive))
            },
            enabled = setting.value < setting.range.endInclusive - 0.0001f,
            modifier = Modifier.width(42.dp)
        )
    }
}

@Composable
private fun TelegramLocationDialog(
    strings: TelegramStrings,
    enabled: Boolean,
    navigatorMask: Int,
    onApply: (Boolean, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalBydPalette.current
    var selectedEnabled by remember(enabled) { mutableStateOf(enabled) }
    var selectedMask by remember(navigatorMask) { mutableStateOf(TelegramNavigatorMask.sanitize(navigatorMask)) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(p.background.copy(alpha = 0.82f)).padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            ModalInputBlocker()
            Column(
                modifier = Modifier.width(520.dp).background(p.panel, Rounded8)
                    .border(1.dp, p.borderStrong, Rounded8).padding(20.dp)
            ) {
                Text(strings.locationSettings, color = p.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.locationMaster, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    BydSwitch(selectedEnabled, { selectedEnabled = it })
                }
                listOf(
                    TelegramNavigatorMask.GOOGLE to strings.googleNavigator,
                    TelegramNavigatorMask.WAZE to strings.wazeNavigator,
                    TelegramNavigatorMask.APPLE to strings.appleNavigator,
                    TelegramNavigatorMask.OSM to strings.osmNavigator
                ).forEach { (bit, label) ->
                    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        BydSwitch((selectedMask and bit) != 0, { checked ->
                            selectedMask = if (checked) selectedMask or bit else selectedMask and bit.inv()
                        })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ActionButton(strings.locationClose, { onApply(selectedEnabled, TelegramNavigatorMask.sanitize(selectedMask)) }, modifier = Modifier.width(140.dp))
                }
            }
        }
    }
}

private fun formatTelegramNumber(value: Float, unit: String): String {
    return if (unit == "V") "%.1f V".format(Locale.US, value) else {
        val number = value.toInt()
        if (unit == "%") "$number%" else "$number $unit"
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
        config.chargeStepPercent.toFloat(),
        1f..99f,
        "%"
    ) { onConfigChanged(config.copy(chargeStepPercent = it.toInt())) }
    TelegramMessageType.LOW_12V_VOLTAGE -> TelegramNumberSetting(
        strings.low12vThreshold,
        config.low12vThresholdVolts,
        9f..15f,
        "V",
        step = 0.1f
    ) { onConfigChanged(config.copy(low12vThresholdVolts = (kotlin.math.round(it * 10f) / 10f).coerceIn(9f, 15f))) }
    TelegramMessageType.TELEMETRY_UNAVAILABLE -> TelegramNumberSetting(
        strings.telemetryDelay,
        config.telemetryUnavailableMinutes.toFloat(),
        1f..60f,
        strings.minuteUnit
    ) { onConfigChanged(config.copy(telemetryUnavailableMinutes = it.toInt())) }
    TelegramMessageType.TRIP_SUMMARY -> TelegramNumberSetting(
        strings.tripDelay,
        config.tripSummaryDelaySeconds.toFloat(),
        5f..300f,
        strings.secondUnit,
        step = 5f
    ) { onConfigChanged(config.copy(tripSummaryDelaySeconds = it.toInt())) }
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
    diagnosticsBusy: Boolean,
    actions: BydCollectorActions,
    onRequestClearLogs: () -> Unit,
) {
    val optionsCardHeight = 312.dp
    TabScrollColumn {
        ScreenTitle(strings.extraTab, strings.extraSubtitle)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(strings.keepAlive, Modifier.weight(1f).height(optionsCardHeight)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchRow(
                        strings.restoreConnectivity,
                        state?.keepWifiEnabled == true && state?.keepMobileDataEnabled == true,
                        actions::onToggleConnectivityRecovery
                    )
                    SwitchRow(
                        strings.keepBluetooth,
                        state?.keepBluetoothEnabled == true,
                        actions::onToggleKeepBluetooth
                    )
                    SwitchRow(
                        strings.restoreCollector,
                        state?.recoverCollectorServiceEnabled == true,
                        actions::onToggleKeepCollector
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton(
                            strings.startLogcat,
                            actions::onStartLogcat,
                            primary = true,
                            enabled = state?.logRecording != true && !diagnosticsBusy,
                            modifier = Modifier.weight(1f)
                        )
                        ActionButton(
                            strings.stopLogcat,
                            actions::onStopLogcat,
                            enabled = state?.logRecording == true && !diagnosticsBusy,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton(
                            strings.shareLogs,
                            actions::onShareLogs,
                            primary = true,
                            enabled = !diagnosticsBusy,
                            modifier = Modifier.weight(1f)
                        )
                        ActionButton(
                            strings.clearLogs,
                            onRequestClearLogs,
                            enabled = !diagnosticsBusy,
                            modifier = Modifier.weight(1f)
                        )
                    }
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
    loading: Boolean = false,
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
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = p.accent, strokeWidth = 2.dp)
        } else {
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
                        state.warning?.let {
                            Text(it, color = p.yellow, fontSize = 14.sp, lineHeight = 19.sp)
                        }
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
    val pending = (preflight?.mqttPending ?: 0L) +
        (preflight?.influxPending ?: 0L)
    val debugArchive = state.operation == DbMaintenanceOperation.DEBUG_ARCHIVE
    val inspectionWarning = preflight?.warning
    val hasPendingWork = inspectionWarning == null && pending > 0L
    Text(
        if (debugArchive) strings.dbMaintenanceDebugStopWarning else strings.dbMaintenanceStopWarning,
        color = p.text,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.SemiBold
    )
    inspectionWarning?.let {
        Text(it, color = p.yellow, fontSize = 14.sp, lineHeight = 19.sp)
    }
    preflight?.telegramStorageWarning?.let {
        Text(it, color = p.yellow, fontSize = 14.sp, lineHeight = 19.sp)
    }
    if (!debugArchive && inspectionWarning == null && pending > 0L) {
        Text(
            String.format(
                strings.dbMaintenancePendingTemplate,
                preflight?.mqttPending ?: 0L,
                preflight?.influxPending ?: 0L
            ),
            color = p.yellow,
            fontSize = 14.sp,
            lineHeight = 19.sp
        )
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
    actionUiState: BydCollectorActionUiState,
    onRequestDelete: (List<String>) -> Unit
) {
    val snapshot = state?.archiveStorageSnapshot
    val entries = snapshot?.entries.orEmpty()
    val listKey = entries.joinToString("|") { it.id }
    var selectedIds by remember(listKey, actionUiState.archiveShareHandoffGeneration) {
        mutableStateOf(emptySet<String>())
    }
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
                        enabled = shareEnabled && !actionUiState.archiveShare,
                        loading = actionUiState.archiveShare,
                        contentDescription = strings.shareSelectedArchives,
                        onClick = { actions.onShareArchives(selectedArchiveIds) }
                    )
                    val sortLabel = if (newestFirst) strings.archiveSortNewestFirst else strings.archiveSortOldestFirst
                    ActionButton(sortLabel, { newestFirst = !newestFirst }, modifier = Modifier.width(180.dp))
                }
                job?.takeIf { it.running }?.let {
                    ArchiveStorageInlineStatus(strings, it)
                }
                job?.takeIf { !it.running && it.error != null }?.let {
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
                        ActionButton(if (actionUiState.archiveDeleteDispatch) strings.loading else strings.deleteSelected,
                            { onRequestDelete(selectedIds.toList()) },
                            primary = true,
                            enabled = selectedIds.isNotEmpty() &&
                                !actionUiState.archiveDeleteDispatch &&
                                job?.running != true &&
                                !CollectorService.isArchiveStorageActive(),
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
        if (status.running) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = p.accent, strokeWidth = 3.dp)
        } else {
            StatusPill(strings.error, StatusKind.ERROR, compact = true)
        }
        Text(
            text = status.error?.let { "${localizedArchiveStorageMessage(strings, status)}: $it" }
                ?: localizedArchiveStorageMessage(strings, status),
            color = if (status.error != null) p.red else p.muted,
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
private fun ClearLogsDialog(
    strings: UiStrings,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalBydPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center,
    ) {
        ModalInputBlocker()
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(p.panel, Rounded8)
                .border(1.dp, p.borderStrong, Rounded8)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(strings.clearLogsTitle, color = p.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(strings.clearLogsScope, color = p.text, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(strings.clearLogsIrreversible, color = p.red, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(strings.clearLogsConfirm, onConfirm, primary = true, modifier = Modifier.weight(1f))
                ActionButton(strings.cancel, onDismiss, modifier = Modifier.weight(1f))
            }
        }
    }
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
private fun BottomTabs(activeTab: AppTab, strings: UiStrings, actions: BydCollectorActions) {
    val tabs = listOf(
        AppTab.MAIN to (strings.mainTab to BottomTabIcon.HOME),
        AppTab.ALL_PARAMETERS to (strings.allTab to BottomTabIcon.STORAGE),
        AppTab.TRIPS to (strings.tripsTab to BottomTabIcon.TRIPS),
        AppTab.HA to (strings.haTab to BottomTabIcon.HA),
        AppTab.TELEGRAM to (strings.telegram.tab to BottomTabIcon.TELEGRAM),
        AppTab.STORAGE to (strings.storageTab to BottomTabIcon.DATABASE),
        AppTab.EXTRA to (strings.extraTab to BottomTabIcon.GEAR)
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
                enabled = true
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

private fun compactChannelStatusText(
    status: String?,
    strings: UiStrings,
    runtimeStatus: RuntimeActionStatus? = null
): String {
    return ChannelStatusFormatter.compactText(status, strings, runtimeStatus)
}

private fun channelStatusKind(
    status: String?,
    enabled: Boolean,
    runtimeStatus: RuntimeActionStatus? = null
): StatusKind {
    return ChannelStatusFormatter.kind(status, enabled, runtimeStatus)
}
