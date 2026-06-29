package com.bydcollector.collector.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydcollector.collector.R
import com.bydcollector.collector.maintenance.DbMaintenanceOperation
import com.bydcollector.collector.maintenance.DbMaintenanceUiState
import com.bydcollector.collector.ui.DashboardState
import com.bydcollector.collector.update.UpdateInfo
import com.bydcollector.collector.update.UpdateUiState
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun BydCollectorApp(
    state: DashboardState?,
    activeTab: AppTab,
    language: UiLanguage,
    darkTheme: Boolean,
    debugBatchText: String,
    mqttDraft: MqttDraft,
    influxDraft: InfluxDraft,
    appVersionName: String = "",
    updateAutoCheckEnabled: Boolean = true,
    updateUiState: UpdateUiState = UpdateUiState.Hidden,
    databaseMaintenanceUiState: DbMaintenanceUiState? = null,
    actions: BydCollectorActions,
    backgroundSetupPromptVisible: Boolean = false,
    onOpenBackgroundSettingsFromPrompt: () -> Unit = {},
    onDismissBackgroundSetupPrompt: () -> Unit = {}
) {
    val s = strings(language)
    //renders the operational dashboard directly; this app intentionally has no landing/marketing screen
    BydCollectorTheme(darkTheme) {
        val p = LocalBydPalette.current
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
                    state = state,
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
                            AppTab.ALL_PARAMETERS -> AllParametersTab(state, s, language, debugBatchText, actions)
                            AppTab.HA -> HaTab(state, s, mqttDraft, influxDraft, actions)
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
                    state = updateUiState,
                    onDismiss = actions::onDismissUpdateDialog,
                    onUpdate = actions::onInstallUpdate
                )
            }
            if (databaseMaintenanceUiState != null) {
                DatabaseMaintenanceDialog(
                    strings = s,
                    state = databaseMaintenanceUiState,
                    mqttPending = state?.mqttPendingCount ?: 0L,
                    influxPending = state?.influxPendingRows ?: 0L,
                    onConfirm = actions::onConfirmDatabaseMaintenance,
                    onDismiss = actions::onDismissDatabaseMaintenance
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
                    text = "${strings.adb}: ${if (hasAccess(state, "adb")) strings.ok else strings.missing}",
                    kind = if (hasAccess(state, "adb")) StatusKind.OK else StatusKind.ERROR
                )
                StatusPill(
                    text = "${strings.permissions}: ${if (hasAllAccess(state)) strings.ok else strings.missing}",
                    kind = if (hasAllAccess(state)) StatusKind.OK else StatusKind.ERROR
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

@Composable
private fun MainTab(state: DashboardState?, strings: UiStrings, actions: BydCollectorActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(strings.compactDatabase, actions::onOpenCompactDatabase, modifier = Modifier.weight(1f))
            ActionButton(strings.archiveDatabase, actions::onOpenArchiveDatabase, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MainDatabaseCard(state: DashboardState?, strings: UiStrings, modifier: Modifier) {
    SectionCard(title = strings.database, modifier = modifier.height(142.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoRow(strings.size, formatBytes(state?.databaseSizeBytes ?: 0L), modifier = Modifier.weight(0.84f), divider = true)
            ReadOnlyPathField(state?.databasePath ?: "-", modifier = Modifier.weight(1.06f))
        }
    }
}

@Composable
private fun AllParametersTab(
    state: DashboardState?,
    strings: UiStrings,
    language: UiLanguage,
    debugBatchText: String,
    actions: BydCollectorActions
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
                    NumericInput(debugBatchText, actions::onDebugBatchChanged, modifier = Modifier.width(60.dp))
                }
            }
            VehicleKpiCard(state, strings, language, Modifier.weight(2f).height(262.dp))
        }
        DebugDatabaseCard(state, strings, Modifier.fillMaxWidth())
    }
}

@Composable
private fun VehicleKpiCard(state: DashboardState?, strings: UiStrings, language: UiLanguage, modifier: Modifier) {
    val kpi = state?.vehicleKpis
    val chargeLabel = if (language == UiLanguage.UK) kpi?.batteryPowerLabelUk else kpi?.batteryPowerLabelEn
    SectionCard(title = strings.currentVehicleState, modifier = modifier, bodyPadding = 14.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiTile("SOC", kpi?.socPercent ?: "-", modifier = Modifier.weight(1f))
            KpiTile(if (language == UiLanguage.UK) "Пробіг" else "Odometer", kpi?.odometerKm ?: "-", modifier = Modifier.weight(1f))
            KpiTile(if (language == UiLanguage.UK) "Темп. салону" else "Cabin temp", kpi?.cabinTempC ?: "-", modifier = Modifier.weight(1f))
            KpiTile("SOH", kpi?.sohPercent ?: "-", modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiTile(chargeLabel ?: "-", kpi?.batteryPowerKw ?: "-", modifier = Modifier.weight(1f))
            KpiTile(if (language == UiLanguage.UK) "Запас ходу" else "Range", kpi?.remainingRangeKm ?: "-", modifier = Modifier.weight(1f))
            KpiTile(if (language == UiLanguage.UK) "Темп. батареї" else "Battery temp", kpi?.batteryTempC ?: "-", modifier = Modifier.weight(1f))
            KpiTile(if (language == UiLanguage.UK) "Δ напруги комірки" else "Cell voltage Δ", kpi?.cellVoltageDeltaMv ?: "-", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DebugDatabaseCard(state: DashboardState?, strings: UiStrings, modifier: Modifier) {
    SectionCard(title = strings.database, modifier = modifier.height(196.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoRow(strings.size, formatBytes(state?.debugDatabaseSizeBytes ?: 0L), modifier = Modifier.weight(0.84f), divider = true)
            ReadOnlyPathField(state?.debugDatabasePath ?: "-", modifier = Modifier.weight(1.06f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoRow(strings.success, state?.debugLastReadingAt ?: "-", modifier = Modifier.weight(1f), divider = false)
            InfoRow(strings.error, state?.debugLastErrorAt ?: state?.debugLastError ?: "-", modifier = Modifier.weight(1f), divider = false)
        }
    }
}

@Composable
private fun HaTab(
    state: DashboardState?,
    strings: UiStrings,
    mqttDraft: MqttDraft,
    influxDraft: InfluxDraft,
    actions: BydCollectorActions
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle(strings.haTab, strings.haSubtitle, modifier = Modifier.weight(1f))
            Text(strings.sharedCategories, color = LocalBydPalette.current.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            BydSwitch(state?.haSharedCategoriesEnabled == true, actions::onToggleSharedCategories, enabled = state?.influxEnabled != true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MqttCard(state, strings, mqttDraft, actions, Modifier.weight(1f))
            InfluxCard(state, strings, influxDraft, actions, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MqttCard(state: DashboardState?, strings: UiStrings, draft: MqttDraft, actions: BydCollectorActions, modifier: Modifier) {
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
        CredentialGridMqtt(strings, draft, actions)
    }
}

@Composable
private fun InfluxCard(state: DashboardState?, strings: UiStrings, draft: InfluxDraft, actions: BydCollectorActions, modifier: Modifier) {
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
    val categories = listOf(
        "battery" to strings.battery,
        "motion" to strings.motion,
        "body" to strings.body,
        "climate" to strings.climate,
        "safety" to strings.safety
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.chunked(3).forEach { row ->
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
            TextInput(strings.password, draft.password, { actions.onMqttDraftChanged(draft.copy(password = it)) }, Modifier.weight(1f), password = true)
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
            TextInput(strings.password, draft.password, { actions.onInfluxDraftChanged(draft.copy(password = it)) }, Modifier.weight(1f), password = true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(strings.databaseField, draft.database, { actions.onInfluxDraftChanged(draft.copy(database = it)) }, Modifier.weight(1f))
            TextInput(strings.measurement, draft.measurement, { actions.onInfluxDraftChanged(draft.copy(measurement = it)) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ExtraTab(
    state: DashboardState?,
    strings: UiStrings,
    updateAutoCheckEnabled: Boolean,
    actions: BydCollectorActions
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ScreenTitle(strings.extraTab, strings.extraSubtitle)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(strings.network, Modifier.weight(1f)) {
                SwitchRow(strings.keepWifi, state?.keepWifiEnabled == true, actions::onToggleKeepWifi)
                SwitchRow(strings.keepMobile, state?.keepMobileDataEnabled == true, actions::onToggleKeepMobile)
            }
            SectionCard(strings.bluetooth, Modifier.weight(1f)) {
                SwitchRow(strings.keepBluetooth, state?.keepBluetoothEnabled == true, actions::onToggleKeepBluetooth)
            }
            SectionCard(strings.collector, Modifier.weight(1f)) {
                SwitchRow(strings.restoreCollector, state?.recoverCollectorServiceEnabled == true, actions::onToggleKeepCollector)
            }
        }
        SectionCard(strings.updates, Modifier.fillMaxWidth()) {
            UpdateSettingsRow(
                strings = strings,
                updateAutoCheckEnabled = updateAutoCheckEnabled,
                actions = actions
            )
        }
    }
}

@Composable
private fun UpdateSettingsRow(
    strings: UiStrings,
    updateAutoCheckEnabled: Boolean,
    actions: BydCollectorActions
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = strings.checkUpdates,
                color = LocalBydPalette.current.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
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
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = LocalBydPalette.current.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        BydSwitch(checked, onChange)
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
                        is UpdateUiState.Available -> AvailableUpdateNotes(strings, state.info)
                        is UpdateUiState.Downloading -> {
                            DownloadingUpdateHeader(strings)
                            Spacer(Modifier.height(10.dp))
                            AvailableUpdateNotes(strings, state.info)
                        }
                        is UpdateUiState.Error -> Text("${strings.updateError}: ${state.message}", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
    mqttPending: Long,
    influxPending: Long,
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
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    state.running -> DatabaseMaintenanceRunningBody(strings, state)
                    state.error != null -> Text("${strings.dbMaintenanceFailed}: ${state.error}", color = p.red, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    state.completed -> {
                        Text(strings.dbMaintenanceComplete, color = p.green, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        state.archivePath?.let {
                            Text("${strings.dbMaintenanceArchivePath} $it", color = p.muted, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                    else -> DatabaseMaintenanceConfirmBody(strings, state, mqttPending, influxPending)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(
                    strings.yes,
                    onConfirm,
                    primary = true,
                    enabled = !state.running && !state.completed && state.error == null,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(strings.no, onDismiss, enabled = !state.running, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DatabaseMaintenanceConfirmBody(
    strings: UiStrings,
    state: DbMaintenanceUiState,
    mqttPending: Long,
    influxPending: Long
) {
    val p = LocalBydPalette.current
    val pending = mqttPending + influxPending
    Text(strings.dbMaintenanceStopWarning, color = p.text, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
    if (pending > 0) {
        Text(String.format(strings.dbMaintenancePendingTemplate, mqttPending, influxPending), color = p.text, fontSize = 14.sp, lineHeight = 19.sp)
    }
    if (state.operation == DbMaintenanceOperation.ARCHIVE && pending > 0) {
        Text(strings.dbMaintenanceArchivePendingWarning, color = p.text, fontSize = 14.sp, lineHeight = 19.sp)
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

private fun operationTitle(strings: UiStrings, operation: DbMaintenanceOperation): String {
    return when (operation) {
        DbMaintenanceOperation.COMPACT -> strings.compactDatabase
        DbMaintenanceOperation.ARCHIVE -> strings.archiveDatabase
    }
}

@Composable
private fun AvailableUpdateNotes(strings: UiStrings, info: UpdateInfo) {
    Text(
        text = "${strings.availableVersion} ${info.version}",
        color = LocalBydPalette.current.text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(12.dp))
    MarkdownPatchNotesText(info.releaseNotes)
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.lines().map { it.trimEnd() }.dropWhile { it.isBlank() }.forEach { line ->
            when {
                line.isBlank() -> Spacer(Modifier.height(6.dp))
                line.startsWith("## ") -> Text(
                    text = line.removePrefix("## ").trim(),
                    color = LocalBydPalette.current.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                else -> Text(
                    text = line,
                    color = LocalBydPalette.current.text,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun LogsTab(state: DashboardState?, strings: UiStrings, actions: BydCollectorActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
                    strings.size to formatBytes(state?.databaseSizeBytes ?: 0L),
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
                    strings.size to formatBytes(state?.debugDatabaseSizeBytes ?: 0L),
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
        AppTab.ALL_PARAMETERS to (strings.allTab to BottomTabIcon.DATABASE),
        AppTab.HA to (strings.haTab to BottomTabIcon.HA),
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
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pressScaleModifier(interactionSource)
                    .background(if (selected) p.active else p.surface, Rounded8)
                    .clickableNoRipple(interactionSource) { actions.onTabSelected(tab) }
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

private fun Modifier.clickableNoRipple(interactionSource: MutableInteractionSource, onClick: () -> Unit): Modifier =
    this.then(
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    )

@Composable
private fun Modifier.borderSafe(color: androidx.compose.ui.graphics.Color): Modifier =
    this.then(Modifier.border(1.dp, color, Rounded8))

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb < 10.0) String.format(Locale.US, "%.1f MB", mb) else "${mb.roundToLong()} MB"
}

private fun formatCount(value: Long): String = "%,d".format(Locale.US, value).replace(",", " ")

private fun errorCount(state: DashboardState?): String {
    return if (state?.lastErrorAt != null || state?.lastError != null) "1" else "0"
}

private fun hasAccess(state: DashboardState?, key: String): Boolean {
    return state?.requiredAccessRows?.firstOrNull { it.key.equals(key, ignoreCase = true) }?.enabled == true
}

private fun hasAllAccess(state: DashboardState?): Boolean {
    val rows = state?.requiredAccessRows ?: return false
    return rows.isNotEmpty() && rows.all { it.enabled }
}

private fun compactChannelStatusText(status: String?, strings: UiStrings): String {
    return ChannelStatusFormatter.compactText(status, strings)
}

private fun channelStatusKind(status: String?, enabled: Boolean): StatusKind {
    return ChannelStatusFormatter.kind(status, enabled)
}
