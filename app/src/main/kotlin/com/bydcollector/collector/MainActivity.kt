package com.bydcollector.collector

import com.bydcollector.collector.runtime.*
import org.json.JSONObject
import org.json.JSONArray

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.adb.AccessCheckMode
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.data.trips.RoutePoint
import com.bydcollector.collector.data.trips.TripSession
import com.bydcollector.collector.data.debug.DirectDebugDatabaseHelper
import com.bydcollector.collector.data.debug.DirectDebugDatabaseResolver
import com.bydcollector.collector.diagnostics.DiagnosticLogRecorder
import com.bydcollector.collector.influx.InfluxConfig
import com.bydcollector.collector.maintenance.DbMaintenanceOperation
import com.bydcollector.collector.maintenance.DbMaintenanceRuntimeStatus
import com.bydcollector.collector.maintenance.DbMaintenanceUiState
import com.bydcollector.collector.maintenance.MainArchivePreflight
import com.bydcollector.collector.maintenance.StorageFormatCutoverCoordinator
import com.bydcollector.collector.maintenance.ArchiveStorageManager
import com.bydcollector.collector.maintenance.ArchiveShareLeaseRegistry
import com.bydcollector.collector.maintenance.ArchiveStorageJobMode
import com.bydcollector.collector.ha.HaEndpointProfile
import com.bydcollector.collector.ha.HaExportChannel
import com.bydcollector.collector.ha.HaRunSession
import com.bydcollector.collector.ui.compose.validEndpointDraft
import com.bydcollector.collector.mqtt.HaMqttConfig
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorServiceController
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.service.DatabaseMaintenanceService
import com.bydcollector.collector.service.TripCompressionService
import com.bydcollector.collector.system.CollectorAutoStart
import com.bydcollector.collector.telegram.TelegramPayloadLimitState
import com.bydcollector.collector.ui.DashboardState
import com.bydcollector.collector.ui.DashboardLoadProfile
import com.bydcollector.collector.ui.DashboardStateProvider
import com.bydcollector.collector.ui.DashboardUiStateStore
import com.bydcollector.collector.ui.UiSessionState
import com.bydcollector.collector.ui.VehicleKpiLanguage
import com.bydcollector.collector.ui.compose.AppTab
import com.bydcollector.collector.ui.compose.BydCollectorActions
import com.bydcollector.collector.ui.compose.BydCollectorApp
import com.bydcollector.collector.ui.compose.BydCollectorActionUiState
import com.bydcollector.collector.ui.compose.isPending
import com.bydcollector.collector.ui.compose.InfluxDraft
import com.bydcollector.collector.ui.compose.MqttDraft
import com.bydcollector.collector.ui.compose.TelegramConfig
import com.bydcollector.collector.ui.compose.TelegramMessageConfig
import com.bydcollector.collector.ui.compose.TelegramMessageType
import com.bydcollector.collector.ui.compose.TelegramTestStatus
import com.bydcollector.collector.ui.compose.MqttCleanupUiState
import com.bydcollector.collector.mqtt.MqttCleanupGroup
import com.bydcollector.collector.mqtt.MqttCleanupSelection
import com.bydcollector.collector.ui.compose.TelegramUiActions
import com.bydcollector.collector.ui.compose.TelegramUiState
import com.bydcollector.collector.ui.compose.CurrentTripUi
import com.bydcollector.collector.ui.compose.TripsUiActions
import com.bydcollector.collector.ui.compose.TripsUiState
import com.bydcollector.collector.ui.compose.TripsUiMapper
import com.bydcollector.collector.ui.compose.TripYearUi
import com.bydcollector.collector.ui.compose.UiLanguage
import com.bydcollector.collector.ui.compose.strings
import com.bydcollector.collector.ui.compose.runtimeJobMessage
import com.bydcollector.collector.ui.compose.operationFailureMessage
import com.bydcollector.collector.ui.compose.operationFailureDetail
import com.bydcollector.collector.update.UpdateRuntime
import com.bydcollector.collector.update.UpdateHintAppearance
import com.bydcollector.collector.update.UpdateCheckSession
import com.bydcollector.collector.update.UpdateInfo
import com.bydcollector.collector.update.UpdateUiState
import com.bydcollector.collector.runtime.RuntimeJobsService
import com.bydcollector.collector.util.dispatchOperationalEvent
import com.bydcollector.collector.util.diagnosticDetail
import com.bydcollector.collector.util.namedSingleThreadExecutor
import com.bydcollector.collector.util.sqliteFootprintBytes
import java.io.File
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean

//coordinates the user-facing compose shell while CollectorService owns long-running vehicle work
class MainActivity : ComponentActivity() {
    private val runtimeJobCallbacks = mutableMapOf<String, (Boolean) -> Unit>()
    private val runtimeJobSubmissionKinds = mutableMapOf<String, String>()
    private val presentedRuntimeJobs = processPresentedRuntimeJobs
    private val preparingRuntimeJobs = processPreparingRuntimeJobs
    private val dismissedUpdateJobs = processDismissedUpdateJobs
    private var updateDownloadJobId: String? = null
    private var lastRuntimeMetadataAt = 0L
    private val runtimeObserver = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (foreground && !destroyed && (!BuildConfig.RUNTIME_CLIENT ||
                    runtimeCompatibleForUi)) refresh(force = false)
        }
    }

    private var accessJobSubmissionsPending = 0
    private var pendingUpdatePresentationId: String? = null

    private fun submitRuntimeJob(
        kind: String,
        args: JSONObject = JSONObject(),
        beforeSubmit: () -> Unit = {},
        onSubmitted: (String) -> Unit = {},
        onFailure: (Throwable) -> Unit = { error -> showOperationFailure(kind, error) }
    ): Boolean {
        if (kind == "access") accessJobSubmissionsPending += 1
        val immutableArgs = JSONObject(args.toString())
        val accepted = submitUiOwnerAction(
            operation = "jobSubmit:$kind",
            failureKind = kind,
            allowDuplicate = kind.startsWith("log"),
            work = {
                beforeSubmit()
                if (BuildConfig.RUNTIME_CLIENT) {
                    RuntimeEndpoint.call(applicationContext, "job.submit", Bundle().apply {
                        putString("kind", kind); putString("args", immutableArgs.toString())
                    }).getString("id") ?: error("Runtime did not accept the task")
                } else RuntimeJobsService.submit(applicationContext, kind, immutableArgs)
            },
            onSuccess = { id ->
                runtimeJobSubmissionKinds[id] = kind
                onSubmitted(id)
                refresh()
            },
            onFailure = { error ->
                if (kind == "access") accessJobSubmissionsPending = (accessJobSubmissionsPending - 1).coerceAtLeast(0)
                actionUiState = actionUiState.copy(
                    mqttTest = actionUiState.mqttTest && kind != "mqttTest",
                    mqttCleanup = actionUiState.mqttCleanup && kind != "mqttCleanup",
                    influxTest = actionUiState.influxTest && kind != "influxTest",
                    archiveShare = actionUiState.archiveShare && kind != "archiveShare",
                    adbGrant = actionUiState.adbGrant && kind != "access"
                )
                if (kind.startsWith("log")) diagnosticsBusy = false
                recordOperationalEvent("runtime_job_submit_failed", "Runtime task submission failed", "$kind: ${error.diagnosticDetail()}")
                onFailure(error)
                refresh()
            }
        )
        if (!accepted && kind == "access") {
            accessJobSubmissionsPending = (accessJobSubmissionsPending - 1).coerceAtLeast(0)
        }
        if (!accepted) {
            actionUiState = actionUiState.copy(
                mqttTest = actionUiState.mqttTest && kind != "mqttTest",
                mqttCleanup = actionUiState.mqttCleanup && kind != "mqttCleanup",
                influxTest = actionUiState.influxTest && kind != "influxTest",
                archiveShare = actionUiState.archiveShare && kind != "archiveShare",
                adbGrant = actionUiState.adbGrant && kind != "access"
            )
            if (kind.startsWith("log")) diagnosticsBusy = false
        }
        return accepted
    }

    private fun <T> submitUiOwnerAction(
        operation: String,
        failureKind: String = operation,
        allowDuplicate: Boolean = false,
        work: () -> T,
        onSuccess: (T) -> Unit = {},
        onFailure: (Throwable) -> Unit = { error -> showOperationFailure(failureKind, error); refresh() },
        onSettled: (Result<T>) -> Unit = {}
    ): Boolean {
        val count = pendingOperationCounts[operation] ?: 0
        if (!allowDuplicate && count > 0) return false
        pendingOperationCounts[operation] = count + 1
        if (count == 0) actionUiState = actionUiState.copy(pendingOperations = actionUiState.pendingOperations + operation)

        val complete: (Result<T>) -> Unit = { result ->
            val remaining = ((pendingOperationCounts[operation] ?: 1) - 1).coerceAtLeast(0)
            if (remaining == 0) {
                pendingOperationCounts.remove(operation)
                actionUiState = actionUiState.copy(pendingOperations = actionUiState.pendingOperations - operation)
            } else pendingOperationCounts[operation] = remaining
            runCatching { onSettled(result) }
                .onFailure { Log.w(TAG, "Owner action completion cleanup failed: $operation", it) }
            if (!destroyed) {
                runCatching { result.fold(onSuccess, onFailure) }
                    .onFailure { error ->
                        Log.e(TAG, "Owner action UI completion failed: $operation", error)
                        runCatching { onFailure(error) }
                            .onFailure { Log.e(TAG, "Owner action UI failure handler failed: $operation", it) }
                    }
            }
        }
        try {
            RuntimeClient.executeOwnerAction(work, complete)
        } catch (error: RuntimeException) {
            complete(Result.failure(error))
        }
        return true
    }

    private data class RuntimeJobPreparation(val resultPrepared: Boolean, val jobJson: String)

    private fun consumeRuntimeJobs(json: String) {
        val array = JSONArray(json)
        val jobs = (0 until array.length()).map(array::getJSONObject)
        jobs.forEach { runtimeJobSubmissionKinds.remove(it.optString("id")) }
        val pendingKinds = runtimeJobSubmissionKinds.values.toSet()
        val active = jobs.filter { it.optString("status") in setOf("queued", "running", "cancelling") }
        val activeUpdateId = active.firstOrNull { it.optString("kind") == "updateDownload" }?.optString("id")
        val pendingLogSubmit = listOf("logStart", "logStop", "logShare", "logClear")
            .any { actionUiState.isPending("jobSubmit:$it") }
        diagnosticsBusy = active.any { it.getString("kind").startsWith("log") } ||
            pendingKinds.any { it.startsWith("log") } || pendingLogSubmit
        actionUiState = actionUiState.copy(
            mqttTest = actionUiState.isPending("jobSubmit:mqttTest") ||
                active.any { it.getString("kind") == "mqttTest" } || "mqttTest" in pendingKinds,
            mqttCleanup = actionUiState.isPending("jobSubmit:mqttCleanup") ||
                active.any { it.getString("kind") == "mqttCleanup" } || "mqttCleanup" in pendingKinds,
            influxTest = actionUiState.isPending("jobSubmit:influxTest") ||
                active.any { it.getString("kind") == "influxTest" } || "influxTest" in pendingKinds,
            archiveShare = actionUiState.isPending("jobSubmit:archiveShare") ||
                active.any { it.getString("kind") == "archiveShare" } || "archiveShare" in pendingKinds
        )
        active.firstOrNull { it.getString("kind") == "updateDownload" }?.let { job ->
            val id = job.getString("id")
            updateDownloadJobId = id
            if (job.optBoolean("dialogDismissed") || id in dismissedUpdateJobs) return@let
            updateUiState = if (job.optBoolean("cancelRequested") || job.optString("status") == "cancelling") {
                UpdateUiState.Cancelling(RuntimeJobsService.updateInfo(job))
            } else UpdateUiState.Downloading(RuntimeJobsService.updateInfo(job), job.optInt("progress"))
            syncReleaseNotesUi()
        }
        jobs.filter { it !in active && !it.optBoolean("presented") }.forEach { job ->
            val id = job.getString("id")
            val kind = job.getString("kind")
            val status = job.optString("status")
            if (kind == "access" && accessJobSubmissionsPending > 0) return@forEach
            if (!canPresentRuntimeResults()) return@forEach
            if (id == pendingUpdatePresentationId) return@forEach
            if (id in presentedRuntimeJobs || id in preparingRuntimeJobs) return@forEach
            if (kind == "updateDownload" && status in setOf("cancelled", "cancelError")) {
                if (activeUpdateId != null && activeUpdateId != id) return@forEach
                if (!job.optBoolean("dialogDismissed") && id !in dismissedUpdateJobs) {
                    updateDownloadJobId = id
                    val info = RuntimeJobsService.updateInfo(job)
                    updateUiState = if (status == "cancelled") UpdateUiState.Cancelled(info) else UpdateUiState.CancelError(info)
                    pendingUpdatePresentationId = id
                    syncReleaseNotesUi()
                }
                return@forEach
            }
            if (kind == "updateDownload" && status == "complete" &&
                (job.optBoolean("dialogDismissed") || id in dismissedUpdateJobs)) return@forEach

            val immutableJobJson = job.toString()
            val success = status == "complete"
            val result = job.optJSONObject("result") ?: JSONObject()
            val needsResultPreparation = success && (result.has("uri") || result.has("uris"))
            val capturedUpdateGeneration = updateUiGeneration
            val installerClaimAcquired = AtomicBoolean(false)
            preparingRuntimeJobs += id
            val accepted = submitUiOwnerAction(
                operation = "jobPrepare:$id",
                failureKind = kind,
                work = {
                    val prepared = !needsResultPreparation || RuntimeJobsService.prepareResult(applicationContext, id)
                    if (success && kind == "updateDownload" && prepared) installerClaimAcquired.set(true)
                    if (success && kind == "updateDownload") {
                        val currentJobs = JSONArray(RuntimeJobsService.snapshot(applicationContext))
                        val current = (0 until currentJobs.length()).map(currentJobs::getJSONObject)
                            .firstOrNull { it.optString("id") == id }
                        RuntimeJobPreparation(prepared, current?.toString() ?: immutableJobJson)
                    } else RuntimeJobPreparation(prepared, immutableJobJson)
                },
                onSuccess = { preparation ->
                    val current = JSONObject(preparation.jobJson)
                    val stillPresentable = canPresentRuntimeResults()
                    if (!stillPresentable) {
                        if (kind == "updateDownload" && installerClaimAcquired.get()) releaseInstallerClaim(id)
                        return@submitUiOwnerAction
                    }
                    if (kind == "updateDownload" &&
                        (current.optBoolean("dialogDismissed") || id in dismissedUpdateJobs ||
                            updateUiGeneration != capturedUpdateGeneration)) {
                        if (installerClaimAcquired.get()) releaseInstallerClaim(id)
                        if (updateDownloadJobId == id) updateUiState = UpdateUiState.Hidden
                        if (pendingUpdatePresentationId == id) pendingUpdatePresentationId = null
                        return@submitUiOwnerAction
                    }
                    if (success && kind == "updateDownload" && !preparation.resultPrepared) {
                        val currentStatus = current.optString("status")
                        updateDownloadJobId = id
                        if (current.optBoolean("cancelRequested") || currentStatus in setOf("cancelling", "cancelError", "cancelled")) {
                            val info = RuntimeJobsService.updateInfo(current)
                            updateUiState = when (currentStatus) {
                                "cancelled" -> UpdateUiState.Cancelled(info)
                                "cancelError" -> UpdateUiState.CancelError(info)
                                else -> UpdateUiState.Cancelling(info)
                            }
                            pendingUpdatePresentationId = id
                            syncReleaseNotesUi()
                        } else if (current.optBoolean("installerClaimed")) {
                            // A prior process may already have crossed the installer boundary; wait for explicit reopen.
                            presentedRuntimeJobs += id
                            updateUiState = UpdateUiState.Hidden
                        }
                        return@submitUiOwnerAction
                    }
                    if (!preparation.resultPrepared && needsResultPreparation) return@submitUiOwnerAction
                    val deliveredSuccess = current.optString("status") == "complete"
                    val deliveredResult = current.optJSONObject("result") ?: JSONObject()
                    if (deliveredSuccess && (kind == "logShare" || kind == "archiveShare")) {
                        val uris = if (kind == "logShare") arrayListOf(Uri.parse(deliveredResult.getString("uri")))
                        else deliveredResult.getJSONArray("uris").let { list -> ArrayList((0 until list.length()).map { Uri.parse(list.getString(it)) }) }
                        val share = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "application/zip"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
                            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            clipData = android.content.ClipData.newRawUri("BYD Collector", uris.first()).also { clip ->
                                uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
                            }
                        }
                        startActivity(Intent.createChooser(share, "BYD Collector"))
                    } else if (deliveredSuccess && kind == "updateDownload") {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(deliveredResult.getString("uri")),
                                "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (error: Exception) {
                            releaseInstallerClaim(id)
                            recordOperationalEvent("runtime_update_installer_open_failed", "Could not open verified update", error.diagnosticDetail())
                            updateUiState = UpdateUiState.Error("Could not open the verified update installer")
                            pendingUpdatePresentationId = id
                            syncReleaseNotesUi()
                            return@submitUiOwnerAction
                        }
                        submitUiOwnerAction("updateInstallerLaunched", work = { updateRuntime.onInstallerLaunched() })
                        updateUiState = UpdateUiState.Hidden
                        acknowledgeRuntimeJob(id)
                    } else if (kind == "mqttCleanup") {
                        refreshMqttCleanupGroups()
                        Toast.makeText(this, runtimeJobMessage(strings(uiLanguage), kind, deliveredSuccess,
                            deliveredResult, current.optString("error")), Toast.LENGTH_LONG).show()
                    } else if (kind != "access") {
                        val message = runtimeJobMessage(strings(uiLanguage), kind, deliveredSuccess,
                            deliveredResult, current.optString("error"))
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        if (kind == "updateDownload" && !current.optBoolean("dialogDismissed") && id !in dismissedUpdateJobs) {
                            updateDownloadJobId = id
                            updateUiState = UpdateUiState.Error(current.optString("error"))
                            pendingUpdatePresentationId = id
                            syncReleaseNotesUi()
                        }
                    }
                    runtimeJobCallbacks.remove(id)?.invoke(deliveredSuccess)
                    if (kind != "updateDownload") acknowledgeRuntimeJob(id)
                },
                onFailure = { error ->
                    Log.w(TAG, "Runtime result preparation failed", error)
                    if (kind == "updateDownload" && success) {
                        recordOperationalEvent("runtime_update_installer_prepare_failed", "Could not prepare verified update", error.diagnosticDetail())
                        if (installerClaimAcquired.get()) releaseInstallerClaim(id)
                        submitUiOwnerAction("updateInstallFinished", work = { updateRuntime.onInstallFinished() })
                        if (canPresentRuntimeResults() && id !in dismissedUpdateJobs &&
                            updateUiGeneration == capturedUpdateGeneration) {
                            updateDownloadJobId = id
                            updateUiState = UpdateUiState.Error(error.message?.take(300) ?: "Could not prepare the verified update installer")
                            pendingUpdatePresentationId = id
                            syncReleaseNotesUi()
                        }
                    } else {
                        Toast.makeText(this, runtimeJobMessage(strings(uiLanguage), kind, false,
                            JSONObject(), error.diagnosticDetail()), Toast.LENGTH_LONG).show()
                        runtimeJobCallbacks.remove(id)?.invoke(false)
                        acknowledgeRuntimeJob(id)
                    }
                },
                onSettled = { outcome ->
                    preparingRuntimeJobs.remove(id)
                    if (destroyed && kind == "updateDownload" && success &&
                        (outcome.getOrNull()?.resultPrepared == true || installerClaimAcquired.get())) {
                        releaseInstallerClaim(id)
                    }
                }
            )
            if (!accepted) preparingRuntimeJobs.remove(id)
        }
    }

    private fun canPresentRuntimeResults(): Boolean = !destroyed && foreground &&
        (!BuildConfig.RUNTIME_CLIENT || (runtimeCompatibleForUi && runtimeRecoveryForegroundEligible()))

    private fun releaseInstallerClaim(id: String) {
        submitUiOwnerAction("jobReleaseInstaller:$id", failureKind = "updateDownload",
            work = { RuntimeJobsService.releaseInstallerClaim(applicationContext, id) },
            onFailure = { Log.w(TAG, "Could not release stale update installer claim", it) })
    }

    private fun acknowledgeRuntimeJob(id: String) {
        if (!presentedRuntimeJobs.add(id)) return
        submitUiOwnerAction("jobPresented:$id", failureKind = "runtimeJob",
            work = { RuntimeJobsService.presented(applicationContext, id) },
            onFailure = { Log.w(TAG, "Could not persist runtime task presentation", it) },
            onSettled = { result -> if (result.isFailure) presentedRuntimeJobs.remove(id) })
    }

    private fun persistDismissedUpdateJob(id: String) {
        if (!dismissedUpdateJobs.add(id)) return
        val context = applicationContext
        submitUiOwnerAction("updateDismissJob:$id", failureKind = "updateDismiss",
            work = { RuntimeJobsService.dismissUpdateDialog(context, id) },
            onFailure = { Log.w(TAG, "Could not persist stale update dismissal", it) })
    }

    private fun onUpdateDialogPresented() {
        val id = pendingUpdatePresentationId ?: return
        pendingUpdatePresentationId = null
        acknowledgeRuntimeJob(id)
    }
    private lateinit var store: TelemetryStore
    private lateinit var settings: CollectorSettings
    private lateinit var settingsPreferences: SharedPreferences
    private lateinit var stateProvider: DashboardStateProvider
    private lateinit var dashboardUiStateStore: DashboardUiStateStore
    private val handler = Handler(Looper.getMainLooper())
    private val dashboardExecutor = namedSingleThreadExecutor("byd-ui-dash")
    private val diagnosticsExecutor = namedSingleThreadExecutor("byd-diagnostics")
    private val mqttCleanupExecutor = namedSingleThreadExecutor("byd-mqtt-identities")
    private val updateExecutor = namedSingleThreadExecutor("byd-update")
    private val updateChecks: UpdateCheckSession
        get() = (applicationContext as BydCollectorApplication).updateChecks
    private val releaseNotesHistory
        get() = (applicationContext as BydCollectorApplication).releaseNotesHistory
    private var updateReleaseNotes by mutableStateOf<com.bydcollector.collector.update.ReleaseNotesHistory?>(null)
    private val updateRuntime: UpdateRuntime
        get() = (applicationContext as BydCollectorApplication).updateRuntime
    private var runtimeRecoveryUiPhase by mutableStateOf<RuntimeRecoveryUiPhase?>(
        if (BuildConfig.RUNTIME_CLIENT) RuntimeRecoveryUiPhase.WAITING else null
    )
    private var runtimeCompatibleForUi = !BuildConfig.RUNTIME_CLIENT
    private var runtimeInitializationInFlight = false
    private var runtimeSavedInstanceState: Bundle? = null
    private var uiVisibleDelivered = false
    private var uiResumedDelivered = false
    private val runtimeForegroundRecovery = RuntimeForegroundRecovery(
        schedule = { task, delayMs -> handler.postDelayed(task, delayMs) },
        cancel = handler::removeCallbacks,
        probe = ::probeRuntimeCompatibility,
        onStatusChanged = ::onRuntimeRecoveryStatusChanged,
        onClientMismatch = ::launchOwnerRecoveryLauncher
    )
    private var startupBackgroundLaunchPosted = false
    private var startupAdbSelfCheckPosted = false
    private var startupAdbSelfCheckSource = "startup"
    private var startupAccessFlowCompleted = false
    private var mainWindowHasFocus by mutableStateOf(false)
    private val runtimeDialogWindowViews = mutableMapOf<String, View>()
    private val runtimeDialogViewReporter: (String, View?) -> Unit = ::onRuntimeDialogViewChanged
    private var runtimePermissionRequestInFlight = false
    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        runtimePermissionRequestInFlight = false
        if (!destroyed && ::settings.isInitialized) {
            recordUpdateEvent("overlay_permission_returned", "allowed=${(if (BuildConfig.RUNTIME_CLIENT) RuntimeClient.state.getBoolean("overlay") else Settings.canDrawOverlays(this))}")
            updateRuntime.onPresentationAccessChanged()
            maybeContinueStartupAccessFlow()
        }
    }
    @Volatile private var refreshInFlight = false
    @Volatile private var foreground = false
    private val kpiVisibilityOwner = Any()
    private var kpiViewportVisible = false
    @Volatile private var destroyed = false
    private var shutdownUiRequested = false
    private var shutdownReopenInFlight = false
    private val archiveShareInFlight = AtomicBoolean(false)

    private val navigationSession: UiSessionState
        get() = BydCollectorApplication.navigationSession(applicationContext)
    private var navigationSessionGeneration = 0L
    private val activeTab: AppTab
        get() = navigationSession.selectedTab
    private var uiLanguage by mutableStateOf(UiLanguage.UK)
    private var darkTheme by mutableStateOf(true)
    private var updateHintEnabled by mutableStateOf(true)
    private var updateHintAppearance by mutableStateOf(UpdateHintAppearance())
    private var mqttDraft by mutableStateOf(MqttDraft())
    private var influxDraft by mutableStateOf(InfluxDraft())
    private var telegramUiState by mutableStateOf(TelegramUiState())
    private var tripsUiState by mutableStateOf(TripsUiState())
    private var updateUiState by mutableStateOf<UpdateUiState>(UpdateUiState.Hidden)
    private var updateOfferResultId by mutableStateOf<Long?>(null)
    private var updateUiGeneration = 0L
    private var updatePresentationRevision = -1L
    private var pendingMaintenanceOperation by mutableStateOf<DbMaintenanceOperation?>(null)
    private var pendingMainArchivePreflight by mutableStateOf<MainArchivePreflight?>(null)
    private var maintenanceLaunchOperation by mutableStateOf<DbMaintenanceOperation?>(null)
    private var maintenancePreflightInFlight = false
    private var backgroundSetupPromptVisible by mutableStateOf(false)
    private var backgroundSetupPromptAutoLaunch = false
    private var diagnosticsBusy by mutableStateOf(false)
    private var actionUiState by mutableStateOf(BydCollectorActionUiState())
    private val pendingOperationCounts = mutableMapOf<String, Int>()
    private var mqttCleanupUiState by mutableStateOf(MqttCleanupUiState())
    private var mqttCleanupRequestGeneration = 0L
    private var archiveDeleteDispatchStartedAtMs: Long? = null
    private var tripsRequestGeneration = 0L
    private var currentTripRefreshGeneration = 0L
    private var currentTripRefreshInFlight = false
    private var selectedCurrentTripId: String? = null
    @Volatile private var currentBootId: String? = null
    @Volatile private var forcedRefreshPending = false
    private var credentialsLoadStarted = false
    private var uiInitialized = false
    private var runtimeObserverRegistered = false
    private var credentialsLoaded = false
    private var mqttCredentialRevision = 0L
    private var influxCredentialRevision = 0L
    private var telegramCredentialRevision = 0L
    private var telegramConfigRevision = 0L
    private val tripsUiActions = TripsUiActions(
        onColorMetricChanged = { tripsUiState = tripsUiState.copy(colorMetric = it) },
        onSpeedThresholdsChanged = { green, yellow ->
            tripsUiState = tripsUiState.copy(speedGreenThreshold = green.coerceIn(0, 300), speedYellowThreshold = yellow.coerceIn(0, 300))
            val source = settings
            submitUiOwnerAction("tripSpeedThresholds", allowDuplicate = true,
                work = { source.setTripSpeedThresholds(green, yellow) }, onFailure = { showOperationFailure("settings", it) })
        },
        onConsumptionThresholdsChanged = { green, yellow ->
            tripsUiState = tripsUiState.copy(consumptionGreenThreshold = green.coerceIn(0, 300), consumptionYellowThreshold = yellow.coerceIn(0, 300))
            val source = settings
            submitUiOwnerAction("tripConsumptionThresholds", allowDuplicate = true,
                work = { source.setTripConsumptionThresholds(green, yellow) }, onFailure = { showOperationFailure("settings", it) })
        },
        onRouteRequested = { tripId -> loadTripsUi(tripId) },
        onCurrentTripRequested = ::openCurrentTrip,
        onCurrentTripDismissed = ::dismissCurrentTrip,
        onCompressDatabase = {
            val appContext = applicationContext
            submitUiOwnerAction("tripCompression", work = { TripCompressionService.start(appContext) })
        },
        onDismissCompressionResult = {
            submitUiOwnerAction("tripCompressionDismiss", work = { TripCompressionService.dismissResult() },
                onSuccess = { dismissed ->
                    if (dismissed && !destroyed) {
                        loadTripsUi()
                        stateProvider.invalidateArchiveStorageSnapshot()
                        refresh()
                    }
                })
        },
        onRefreshRequested = {
            loadTripsUi()
            stateProvider.invalidateArchiveStorageSnapshot()
            refresh()
        }
    )

    private val refreshTask = object : Runnable {
        override fun run() {
            refresh(force = false)
            handler.postDelayed(this, DASHBOARD_REFRESH_HEARTBEAT_MS)
        }
    }
    private val currentTripRefreshTask = Runnable { refreshCurrentTrip() }
    private val settingsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == CollectorSettings.KEY_USER_SHUTDOWN_PHASE) {
            handler.post { if (!destroyed && shutdownUiRequested) observeShutdownPhase() }
        }
        if (
            key == CollectorSettings.KEY_TELEGRAM_CONNECTION_STATUS ||
            key == CollectorSettings.KEY_TELEGRAM_CONNECTION_MESSAGE ||
            key == CollectorSettings.KEY_TELEGRAM_TRIP_TEMPLATE_LIMIT_STATE
        ) {
            handler.post {
                if (!destroyed && ::settings.isInitialized) syncTelegramUiRuntimeState()
            }
        }
    }
    private val startupAdbSelfCheckTask = Runnable { runStartupAdbSelfCheckIfReady() }
    private val updateCheckUiTask = Runnable { syncUpdateCheckUi() }
    private val updateCheckListener: () -> Unit = { handler.post(updateCheckUiTask); Unit }
    private val releaseNotesListener: () -> Unit = { handler.post { syncReleaseNotesUi() }; Unit }

    private val telegramActions = TelegramUiActions(
        onConfigChanged = ::onTelegramConfigChanged,
        onClearBotToken = ::onClearTelegramBotToken,
        onTestConnection = ::onTestTelegramConnection
    )

    //maps every ui command to persisted settings plus service intents so process restarts keep the same intent
    private val uiActions = object : BydCollectorActions {
        override fun onTabSelected(tab: AppTab) {
            if (!navigationSession.isGenerationCurrent(navigationSessionGeneration)) return
            navigationSession.selectTab(tab)
            updateKpiVisibility()
            if (tab == AppTab.TELEGRAM) syncTelegramUiRuntimeState()
            if (tab == AppTab.TRIPS) loadTripsUi()
            refresh()
        }

        override fun onKpiViewportVisibilityChanged(visible: Boolean) {
            if (destroyed || !navigationSession.isGenerationCurrent(navigationSessionGeneration)) return
            kpiViewportVisible = visible
            updateKpiVisibility()
        }

        override fun onLanguageSelected(language: UiLanguage) {
            uiLanguage = language
            RuntimeUiAppearance.save(applicationContext, language = language)
            dashboardUiStateStore.selectVehicleKpiLanguage(language.vehicleKpiLanguage())
            val previousTelegram = telegramUiState
            telegramUiState = previousTelegram.copy(config = previousTelegram.config.copy(
                messages = previousTelegram.config.messages.mapValues { (type, message) ->
                    if (message.usesDefaultTemplate) message.copy(template = strings(language).telegram.messages.getValue(type).defaultTemplate)
                    else message
                }))
            val source = settings
            val dark = darkTheme
            submitUiOwnerAction("language", allowDuplicate = true, work = {
                source.setUiLanguageCode(language.code)
                if (previousTelegram.config.messages[TelegramMessageType.TRIP_SUMMARY]?.usesDefaultTemplate == true) {
                    source.resetTelegramTripTemplateLimitState()
                }
                if (BuildConfig.RUNTIME_CLIENT) RuntimeClient.control("hintStyle", Bundle().apply { putBoolean("dark", dark) })
            }, onSuccess = {
                if (!BuildConfig.RUNTIME_CLIENT) (applicationContext as BydCollectorApplication).updateHints.refresh(darkTheme)
                refresh()
            })
            if (activeTab == AppTab.TRIPS) loadTripsUi()
        }

        override fun onDarkThemeSelected(dark: Boolean) {
            darkTheme = dark
            RuntimeUiAppearance.save(applicationContext, dark = dark)
            submitUiOwnerAction("theme", allowDuplicate = true, work = {
                if (BuildConfig.RUNTIME_CLIENT) RuntimeClient.control("hintStyle", Bundle().apply { putBoolean("dark", dark) })
            }, onSuccess = {
                if (!BuildConfig.RUNTIME_CLIENT) (applicationContext as BydCollectorApplication).updateHints.refresh(darkTheme)
            })
        }

        override fun onStartMain() {
            refreshStoreBackedState()
            requestAccessCheck("start_main", AccessCheckMode.NORMAL)
            val source = settings
            submitUiOwnerAction("mainStart", work = {
                source.setMainManuallyStopped(false)
                source.setPollingEnabled(true)
                CollectorServiceController.start(applicationContext)
            }, onSuccess = { refresh() })
        }

        override fun onStopMain() {
            refreshStoreBackedState()
            val source = settings
            submitUiOwnerAction("mainStop", work = {
                source.setMainManuallyStopped(true)
                source.setPollingEnabled(false)
                CollectorServiceController.stop(applicationContext)
            }, onSuccess = { refresh() })
        }

        override fun onToggleMainAutoStart(enabled: Boolean) {
            if (
                CollectorSettings.isDbMaintenanceRunning(applicationContext) ||
                CollectorService.isMaintenanceRunningInProcess()
            ) {
                Toast.makeText(
                    this@MainActivity,
                    strings(uiLanguage).dbMaintenanceAlreadyRunning,
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            refreshStoreBackedState()
            val source = settings
            val tab = activeTab
            submitUiOwnerAction("mainAuto", allowDuplicate = true, work = {
                if (enabled) source.setMainManuallyStopped(false)
                source.setAutoStartEnabled(
                    enabled,
                    detail = "source=ui control=main_auto_start tab=$tab"
                )
                if (enabled) {
                    if (BuildConfig.RUNTIME_CLIENT) RuntimeClient.control("watchdog")
                    else CollectorAutoStart.scheduleWatchdog(applicationContext, source, currentStore())
                }
            }, onSuccess = { refresh() })
        }

        override fun onGrantAdb() {
            actionUiState = actionUiState.copy(adbGrant = true)
            requestAdbAuthorizationFlow("grant_button")
            refresh()
        }

        override fun onOpenBackgroundApps() {
            showBackgroundSetupPrompt(autoLaunch = false)
        }

        override fun onOpenArchiveDatabase() {
            actionUiState = actionUiState.copy(mainArchivePreflight = true)
            openMainArchiveDialog()
        }

        override fun onOpenArchiveDebugDatabase() {
            pendingMainArchivePreflight = null
            pendingMaintenanceOperation = DbMaintenanceOperation.DEBUG_ARCHIVE
            refresh()
        }

        override fun onConfirmDatabaseMaintenance() {
            val operation = pendingMaintenanceOperation ?: return
            maintenanceLaunchOperation = operation
            pendingMaintenanceOperation = null
            pendingMainArchivePreflight = null
            stateProvider.invalidateArchiveStorageSnapshot()
            if (BuildConfig.RUNTIME_CLIENT) {
                submitUiOwnerAction("archiveStart", work = {
                    if (operation == DbMaintenanceOperation.ARCHIVE) CollectorServiceController.archiveDatabase(applicationContext)
                    else CollectorServiceController.archiveDebugDatabase(applicationContext)
                }, onSuccess = { refresh() }, onFailure = ::postMaintenanceLaunchFailure)
                return
            }
            val runningStatus = DbMaintenanceRuntimeStatus(
                operation = operation,
                running = true,
                completed = false,
                stepIndex = 1,
                stepCount = operation.stepsUk.size,
                messageUk = operation.stepsUk.first(),
                messageEn = operation.stepsEn.first()
            )
            try {
                dashboardExecutor.execute {
                    val committed = runCatching {
                        settings.setDbMaintenanceStatus(runningStatus, synchronous = true)
                    }.getOrElse { error ->
                        postMaintenanceLaunchFailure(error)
                        return@execute
                    }
                    if (!committed) {
                        postMaintenanceLaunchFailure(
                            IllegalStateException("Could not persist database maintenance status")
                        )
                        return@execute
                    }
                    runCatching {
                        when (operation) {
                            DbMaintenanceOperation.ARCHIVE -> CollectorServiceController.archiveDatabase(applicationContext)
                            DbMaintenanceOperation.DEBUG_ARCHIVE -> CollectorServiceController.archiveDebugDatabase(applicationContext)
                        }
                    }.onSuccess {
                        runOnUiThread {
                            if (!destroyed) refresh()
                        }
                    }.onFailure { error ->
                        val failure = settings.dbMaintenanceStatus().copy(
                            running = false,
                            completed = false,
                            error = dashboardErrorDetail(error)
                        )
                        val persisted = runCatching {
                            settings.setDbMaintenanceStatus(failure, synchronous = true)
                        }.getOrElse { persistError ->
                            Log.e(TAG, "Database maintenance failure status could not be persisted", persistError)
                            false
                        }
                        if (!persisted) {
                            Log.e(TAG, "Database maintenance failure status commit returned false")
                        }
                        postMaintenanceLaunchFailure(error)
                    }
                }
            } catch (error: RuntimeException) {
                postMaintenanceLaunchFailure(error)
            }
        }

        override fun onCancelDatabaseMaintenance() {
            submitUiOwnerAction("archiveCancel", work = {
                CollectorServiceController.cancelDatabaseMaintenance(applicationContext)
            }, onSuccess = { refresh() })
        }

        override fun onDismissDatabaseMaintenance() {
            if (dashboardUiStateStore.currentChrome()?.dbMaintenanceStatus?.running == true || maintenanceLaunchOperation != null) return
            pendingMaintenanceOperation = null
            pendingMainArchivePreflight = null
            val source = settings
            submitUiOwnerAction("archiveDismiss", work = { source.clearDbMaintenanceStatus() }, onSuccess = { refresh() })
        }

        override fun onSetArchiveStorageLimitGb(value: Int) {
            refreshStoreBackedState()
            val source = settings
            stateProvider.invalidateArchiveStorageSnapshot()
            submitUiOwnerAction("archiveLimit", allowDuplicate = true, work = {
                source.setArchiveStorageLimitGb(value)
                CollectorServiceController.reconcileArchiveStorage(applicationContext, manualRetry = true)
            }, onSuccess = { refresh() })
        }

        override fun onDeleteArchives(ids: List<String>) {
            if (ids.isEmpty()) return
            archiveDeleteDispatchStartedAtMs = System.currentTimeMillis()
            actionUiState = actionUiState.copy(archiveDeleteDispatch = true)
            stateProvider.retireArchiveStorageEntries(ids)
            stateProvider.invalidateArchiveStorageSnapshot()
            val requestedIds = ids.toList()
            submitUiOwnerAction("archiveDelete", work = {
                CollectorServiceController.deleteArchives(applicationContext, requestedIds)
            }, onSuccess = { refresh() }, onFailure = { error ->
                archiveDeleteDispatchStartedAtMs = null
                actionUiState = actionUiState.copy(archiveDeleteDispatch = false)
                stateProvider.restoreRetiredArchiveStorageEntries(ids)
                stateProvider.invalidateArchiveStorageSnapshot()
                recordOperationalEvent(
                    "archive_delete_dispatch_error",
                    "Archive delete command could not be dispatched",
                    error.diagnosticDetail("archive_ids=${ids.joinToString(",")}")
                )
                showOperationFailure("archiveDelete", error)
                refresh()
            })
            refresh()
        }

        override fun onShareArchives(ids: List<String>) {
            actionUiState = actionUiState.copy(archiveShare = true)
            shareArchives(ids)
        }

        override fun onStartDebug() {
            refreshStoreBackedState()
            requestAccessCheck("start_debug", AccessCheckMode.NORMAL)
            val source = settings
            submitUiOwnerAction("debugStart", work = {
                source.setDebugManuallyStopped(false)
                source.setDebugPollingEnabled(true)
                CollectorServiceController.startDebug(applicationContext)
            }, onSuccess = { refresh() })
        }

        override fun onStopDebug() {
            refreshStoreBackedState()
            val source = settings
            submitUiOwnerAction("debugStop", work = {
                source.setDebugManuallyStopped(true)
                source.setDebugPollingEnabled(false)
                CollectorServiceController.stopDebug(applicationContext)
            }, onSuccess = { refresh() })
        }

        override fun onToggleDebugAutoStart(enabled: Boolean) {
            val source = settings
            val tab = activeTab
            submitUiOwnerAction("debugAuto", allowDuplicate = true, work = {
                if (enabled) source.setDebugManuallyStopped(false)
                source.setDebugAutoStartEnabled(
                    enabled,
                    detail = "source=ui control=debug_auto_start tab=$tab"
                )
                if (enabled) {
                    if (BuildConfig.RUNTIME_CLIENT) RuntimeClient.control("watchdog")
                    else CollectorAutoStart.scheduleWatchdog(applicationContext, source, currentStore())
                }
            }, onSuccess = { refresh() })
        }

        override fun onToggleSharedCategories(enabled: Boolean) {
            val source = settings
            submitUiOwnerAction("sharedCategories", allowDuplicate = true,
                work = { source.setHaSharedCategoriesEnabled(enabled) }, onSuccess = { refresh() })
        }

        override fun onStartMqtt() {
            refreshStoreBackedState()
            if (actionUiState.mqttTest || actionUiState.mqttCleanup || CollectorService.mqttConnection.owned) return
            if (!CollectorService.mqttConnection.owned && !validateMqttDraft()) return
            requestAccessCheck("start_mqtt", AccessCheckMode.NORMAL)
            val draft = mqttDraft
            val loaded = credentialsLoaded
            val source = settings
            submitUiOwnerAction("mqttStart", work = {
                saveMqttDraft(source, draft, loaded)
                source.setMqttManuallyStopped(false)
                source.setMqttEnabled(true)
                try { CollectorServiceController.startMqttExport(applicationContext) }
                catch (error: Exception) {
                    if (!CollectorService.mqttConnection.owned) source.setMqttEnabled(false)
                    throw error
                }
            }, onSuccess = { refresh() })
        }

        override fun onStopMqtt() {
            refreshStoreBackedState()
            val source = settings
            submitUiOwnerAction("mqttStop", work = {
                source.setMqttManuallyStopped(true)
                source.setMqttEnabled(false)
                CollectorServiceController.stopMqttExport(applicationContext)
            }, onSuccess = { refresh() })
        }

        override fun onTestMqtt() {
            if (actionUiState.mqttTest || actionUiState.mqttCleanup || !validateMqttDraft(mqttDraft.editingProfile)) return
            val selected = mqttDraft.editingProfile
            actionUiState = actionUiState.copy(mqttTest = true)
            val draft = mqttDraft
            val loaded = credentialsLoaded
            val source = settings
            submitRuntimeJob("mqttTest", JSONObject().put("profile", selected.name),
                beforeSubmit = { saveMqttDraft(source, draft, loaded) })
        }

        override fun onToggleMqttAutoStart(enabled: Boolean) {
            val source = settings
            submitUiOwnerAction("mqttAuto", allowDuplicate = true, work = {
                if (!enabled && CollectorService.mqttConnection.owned && !CollectorService.mqttConnection.stopping &&
                    !source.isMqttManuallyStopped()) if (BuildConfig.RUNTIME_CLIENT) RuntimeClient.control("grantMqtt") else HaRunSession.process.start(HaExportChannel.MQTT)
                if (enabled) source.setMqttManuallyStopped(false)
                source.setMqttAutoStartEnabled(enabled)
            }, onSuccess = { refresh() })
        }

        override fun onToggleMqttCategory(category: String, enabled: Boolean) {
            val source = settings
            submitUiOwnerAction("mqttCategory", allowDuplicate = true,
                work = { source.setMqttCategoryEnabled(category, enabled) }, onSuccess = { refresh() })
        }

        override fun onMqttDraftChanged(draft: MqttDraft) {
            if (CollectorService.mqttConnection.owned || actionUiState.mqttTest || actionUiState.mqttCleanup) return
            if (draft.username != mqttDraft.username || draft.password != mqttDraft.password) {
                mqttCredentialRevision += 1L
            }
            mqttDraft = draft
            val source = settings
            val loaded = credentialsLoaded
            submitUiOwnerAction("mqttSave", allowDuplicate = true,
                work = { saveMqttDraft(source, draft, loaded) }, onSuccess = { refresh() })
        }

        override fun onRefreshMqttCleanup() = refreshMqttCleanupGroups()

        override fun onCleanupMqttIdentity(selection: MqttCleanupSelection) {
            if (actionUiState.mqttCleanup) return
            actionUiState = actionUiState.copy(mqttCleanup = true)
            submitRuntimeJob("mqttCleanup", JSONObject()
                .put("deviceId", selection.deviceId).put("fingerprint", selection.fingerprint))
            refresh()
        }

        override fun onStartInflux() {
            refreshStoreBackedState()
            if (actionUiState.influxTest || CollectorService.influxConnection.owned) return
            if (!CollectorService.influxConnection.owned && !validateInfluxDraft()) return
            requestAccessCheck("start_influx", AccessCheckMode.NORMAL)
            val draft = influxDraft
            val loaded = credentialsLoaded
            val source = settings
            submitUiOwnerAction("influxStart", work = {
                saveInfluxDraft(source, draft, loaded)
                source.setInfluxManuallyStopped(false)
                source.setInfluxEnabled(true)
                try { CollectorServiceController.startInfluxExport(applicationContext) }
                catch (error: Exception) {
                    if (!CollectorService.influxConnection.owned) source.setInfluxEnabled(false)
                    throw error
                }
            }, onSuccess = { refresh() })
        }

        override fun onStopInflux() {
            refreshStoreBackedState()
            val source = settings
            submitUiOwnerAction("influxStop", work = {
                source.setInfluxManuallyStopped(true)
                source.setInfluxEnabled(false)
                CollectorServiceController.stopInfluxExport(applicationContext)
            }, onSuccess = { refresh() })
        }

        override fun onTestInflux() {
            if (actionUiState.influxTest || !validateInfluxDraft(influxDraft.editingProfile)) return
            val selected = influxDraft.editingProfile
            actionUiState = actionUiState.copy(influxTest = true)
            val draft = influxDraft
            val loaded = credentialsLoaded
            val source = settings
            submitRuntimeJob("influxTest", JSONObject().put("profile", selected.name),
                beforeSubmit = { saveInfluxDraft(source, draft, loaded) })
        }

        override fun onToggleInfluxAutoStart(enabled: Boolean) {
            val source = settings
            submitUiOwnerAction("influxAuto", allowDuplicate = true, work = {
                if (!enabled && CollectorService.influxConnection.owned && !CollectorService.influxConnection.stopping &&
                    !source.isInfluxManuallyStopped()) if (BuildConfig.RUNTIME_CLIENT) RuntimeClient.control("grantInflux") else HaRunSession.process.start(HaExportChannel.INFLUX)
                if (enabled) source.setInfluxManuallyStopped(false)
                source.setInfluxAutoStartEnabled(enabled)
            }, onSuccess = { refresh() })
        }

        override fun onToggleInfluxCategory(category: String, enabled: Boolean) {
            val source = settings
            submitUiOwnerAction("influxCategory", allowDuplicate = true,
                work = { source.setInfluxCategoryEnabled(category, enabled) }, onSuccess = { refresh() })
        }

        override fun onInfluxDraftChanged(draft: InfluxDraft) {
            if (CollectorService.influxConnection.owned || actionUiState.influxTest) return
            if (draft.username != influxDraft.username || draft.password != influxDraft.password) {
                influxCredentialRevision += 1L
            }
            influxDraft = draft
            val source = settings
            val loaded = credentialsLoaded
            submitUiOwnerAction("influxSave", allowDuplicate = true,
                work = { saveInfluxDraft(source, draft, loaded) }, onSuccess = { refresh() })
        }

        override fun onToggleConnectivityRecovery(enabled: Boolean) {
            val source = settings
            updateKeepAliveSetting("connectivityRecovery", enabled) { source.setConnectivityRecoveryEnabled(it) }
        }

        override fun onToggleKeepBluetooth(enabled: Boolean) {
            val source = settings
            updateKeepAliveSetting("bluetoothRecovery", enabled) { source.setKeepBluetoothEnabled(it) }
        }

        override fun onToggleKeepCollector(enabled: Boolean) {
            val source = settings
            updateKeepAliveSetting("collectorRecovery", enabled) { source.setRecoverCollectorServiceEnabled(it) }
        }

        override fun onToggleTailscaleActivation(enabled: Boolean) {
            refreshStoreBackedState()
            val source = settings
            submitUiOwnerAction("tailscale", allowDuplicate = true,
                work = { source.setTailscaleActivationEnabled(enabled) }, onSuccess = { refresh() })
        }

        override fun onToggleUpdateAutoCheck(enabled: Boolean) {
            val source = settings
            if (!BuildConfig.RUNTIME_CLIENT) {
                source.setUpdateAutoCheckEnabled(enabled)
                updateRuntime.onAutoCheckEnabledChanged()
                refresh()
                return
            }
            submitUiOwnerAction("updateAutoCheck", failureKind = "settings", work = {
                source.setUpdateAutoCheckEnabled(enabled)
                updateRuntime.onAutoCheckEnabledChanged()
            }, onSuccess = { refresh() })
        }

        override fun onCheckForUpdates() {
            val context = applicationContext
            val selectedId = updateDownloadJobId
            val requestGeneration = updateUiGeneration
            submitUiOwnerAction("updateReopen", failureKind = "updateReopen",
                work = { RuntimeJobsService.reopenUpdateDialog(context, selectedId) },
                onSuccess = { reopened ->
                    if (updateUiGeneration != requestGeneration) {
                        reopened?.first?.let(::persistDismissedUpdateJob)
                        return@submitUiOwnerAction
                    }
                    if (reopened == null) {
                        runUpdateCheck(force = true)
                        return@submitUiOwnerAction
                    }
                    val id = reopened.first
                    updateDownloadJobId = id
                    dismissedUpdateJobs.remove(id)
                    presentedRuntimeJobs.remove(id)
                    consumeRuntimeJobs(reopened.second)
                },
                onFailure = { error ->
                    Log.w(TAG, "Could not reopen update task", error)
                    recordOperationalEvent("runtime_update_reopen_failed", "Could not reopen update progress", error.diagnosticDetail())
                    if (updateUiGeneration == requestGeneration) {
                        updateUiState = UpdateUiState.Error("Runtime update status unavailable")
                        syncReleaseNotesUi()
                    }
                })
        }

        override fun onToggleUpdateHint(enabled: Boolean) {
            val source = settings
            val dark = darkTheme
            val app = applicationContext as BydCollectorApplication
            if (!BuildConfig.RUNTIME_CLIENT) {
                source.setUpdateHintEnabled(enabled)
                updateHintEnabled = source.isUpdateHintEnabled()
                app.updateHints.refresh(dark)
                if (enabled) maybeRequestOverlayAccess(userInitiated = true)
                updateRuntime.onPresentationAccessChanged()
                return
            }
            updateHintEnabled = enabled
            submitUiOwnerAction("updateHint", failureKind = "settings", work = {
                source.setUpdateHintEnabled(enabled)
                RuntimeClient.control("hintStyle", Bundle().apply { putBoolean("dark", dark) })
                updateRuntime.onPresentationAccessChanged()
                source.isUpdateHintEnabled()
            }, onSuccess = { saved ->
                updateHintEnabled = saved
                if (saved) maybeRequestOverlayAccess(userInitiated = true)
            }, onFailure = { error ->
                updateHintEnabled = source.isUpdateHintEnabled()
                showOperationFailure("settings", error)
            })
        }

        override fun onUpdateHintAppearanceChanged(appearance: UpdateHintAppearance) {
            val source = settings
            val dark = darkTheme
            val app = applicationContext as BydCollectorApplication
            if (!BuildConfig.RUNTIME_CLIENT) {
                source.setUpdateHintAppearance(appearance)
                updateHintAppearance = source.updateHintAppearance()
                app.updateHints.refresh(dark)
                return
            }
            val previous = updateHintAppearance
            updateHintAppearance = appearance
            submitUiOwnerAction("updateHintAppearance", failureKind = "settings", work = {
                source.setUpdateHintAppearance(appearance)
                RuntimeClient.control("hintStyle", Bundle().apply { putBoolean("dark", dark) })
                source.updateHintAppearance()
            }, onSuccess = { updateHintAppearance = it }, onFailure = { error ->
                updateHintAppearance = previous
                showOperationFailure("settings", error)
            })
        }

        override fun onDismissUpdateDialog() {
            val id = updateDownloadJobId
            id?.let {
                // Fence an already-read progress snapshot before persisting the dismissal.
                dismissedUpdateJobs += it
            }
            if (BuildConfig.RUNTIME_CLIENT) {
                val context = applicationContext
                submitUiOwnerAction("updateDismiss", failureKind = "updateDismiss", work = {
                    id?.let { RuntimeJobsService.dismissUpdateDialog(context, it) }
                    updateRuntime.dismissOffer()
                }, onSettled = { result ->
                    if (destroyed) result.exceptionOrNull()?.let { processUpdateDismissFailure = it }
                })
            } else {
                id?.let { selected ->
                    submitUiOwnerAction("updateDismissJob", failureKind = "updateDismiss",
                        work = { RuntimeJobsService.dismissUpdateDialog(applicationContext, selected) },
                        onSettled = { result ->
                            if (destroyed) result.exceptionOrNull()?.let { processUpdateDismissFailure = it }
                        })
                }
                updateRuntime.dismissOffer()
            }
            updatePresentationRevision = updateChecks.snapshot().revision
            updateUiGeneration += 1L
            updateUiState = UpdateUiState.Hidden
        }

        override fun onInstallUpdate() {
            when (val state = updateUiState) {
                is UpdateUiState.Available -> startUpdateDownload(state.info)
                is UpdateUiState.Cancelled -> startUpdateDownload(state.info)
                else -> Unit
            }
        }

        override fun onCancelUpdateDownload() {
            val info = when (val state = updateUiState) {
                is UpdateUiState.Downloading -> state.info
                is UpdateUiState.CancelError -> state.info
                else -> return
            }
            val id = updateDownloadJobId ?: return
            val requestGeneration = updateUiGeneration
            updateUiState = UpdateUiState.Cancelling(info)
            syncReleaseNotesUi()
            submitUiOwnerAction("updateCancel", failureKind = "updateCancel",
                work = { RuntimeJobsService.cancelUpdateDownload(applicationContext, id) },
                onSuccess = { result ->
                    if (updateUiGeneration != requestGeneration) return@submitUiOwnerAction
                    updateUiState = when (result) {
                        "REQUESTED" -> UpdateUiState.Cancelling(info)
                        "CANCELLED" -> UpdateUiState.Cancelled(info)
                        "INSTALLER_CLAIMED" -> UpdateUiState.Hidden
                        else -> UpdateUiState.CancelError(info)
                    }
                    syncReleaseNotesUi()
                }, onFailure = { error ->
                    Log.w(TAG, "Could not cancel update download", error)
                    recordOperationalEvent("runtime_update_cancel_failed", "Could not cancel update download", error.diagnosticDetail())
                    if (updateUiGeneration != requestGeneration) return@submitUiOwnerAction
                    updateUiState = UpdateUiState.CancelError(info)
                    syncReleaseNotesUi()
                    Toast.makeText(this@MainActivity, strings(uiLanguage).updateCancelFailed, Toast.LENGTH_LONG).show()
                })
        }

        override fun onUpdateDialogPresented() = this@MainActivity.onUpdateDialogPresented()

        override fun onShutdownApp() {
            if (shutdownReopenInFlight || CollectorService.isUserShutdownInProgress()) return
            shutdownUiRequested = true
            handler.removeCallbacks(startupAdbSelfCheckTask)
            startupAdbSelfCheckPosted = false
            if (!BuildConfig.RUNTIME_CLIENT) updateRuntime.shutdown()
            updateUiGeneration += 1L
            updateUiState = UpdateUiState.Hidden
            val source = settings
            val context = applicationContext
            submitUiOwnerAction("shutdown", failureKind = "shutdown", work = {
                if (!source.setUserShutdownRequested(true)) error("Could not save Shutdown request")
                try {
                    if (BuildConfig.RUNTIME_CLIENT) {
                        RuntimeClient.control("cancelWatchdog")
                        updateRuntime.shutdown()
                    } else CollectorAutoStart.cancelScheduled(context)
                    CollectorServiceController.shutdown(context)
                } catch (error: RuntimeException) {
                    source.setUserShutdownPhase(CollectorSettings.SHUTDOWN_PHASE_ERROR,
                        detail = "${error::class.java.simpleName}: ${error.message}")
                    throw error
                }
            }, onFailure = { error ->
                shutdownUiRequested = false
                val detail = if (error.message == "Could not save Shutdown request") error.message.orEmpty()
                else source.userShutdownDetail().orEmpty().ifBlank { error.diagnosticDetail() }
                showShutdownFailure(detail)
            })
        }

        override fun onStartLogcat() {
            startLogcatRecording()
        }

        override fun onStopLogcat() {
            stopLogcatRecording()
        }

        override fun onShareLogs() {
            shareDiagnosticLogs()
        }

        override fun onClearLogs() {
            clearDiagnosticLogs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.RUNTIME_CLIENT && savedInstanceState == null) RuntimeUiAppearance.acceptLaunch(this, intent)
        darkTheme = RuntimeUiAppearance.dark(this)
        uiLanguage = RuntimeUiAppearance.language(this)
        setTheme(if (darkTheme) R.style.AppTheme else R.style.AppTheme_Light)
        super.onCreate(savedInstanceState)
        if (BuildConfig.RUNTIME_CLIENT) {
            initializeRuntimeUi(savedInstanceState)
        } else initializeUi(savedInstanceState)
    }

    private fun initializeRuntimeUi(savedInstanceState: Bundle?) {
        runtimeSavedInstanceState = savedInstanceState
        setContent {
            RuntimeRecoveryPanel(
                language = uiLanguage,
                phase = runtimeRecoveryUiPhase ?: RuntimeRecoveryUiPhase.WAITING,
                darkTheme = darkTheme,
                onRetry = ::retryRuntimeRecovery,
                onClose = ::closeRuntimeRecovery
            )
        }
        updateRuntimeRecoveryEligibility()
    }

    private fun runtimeRecoveryForegroundEligible(): Boolean =
        BuildConfig.RUNTIME_CLIENT && !destroyed && !isFinishing &&
            runtimeRecoveryEligible(
                activityResumed = lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED,
                activityWindowFocused = mainWindowHasFocus && window.decorView.hasWindowFocus(),
                ownedDialogWindowFocused = runtimeDialogWindowViews.values.any {
                    it.isAttachedToWindow && it.hasWindowFocus()
                }
            )

    private fun onRuntimeDialogViewChanged(dialogId: String, view: View?) {
        if (!BuildConfig.RUNTIME_CLIENT) return
        if (view == null) runtimeDialogWindowViews.remove(dialogId)
        else runtimeDialogWindowViews[dialogId] = view
        updateRuntimeRecoveryEligibility()
    }

    private fun updateRuntimeRecoveryEligibility() {
        if (!BuildConfig.RUNTIME_CLIENT) return
        val eligible = runtimeRecoveryForegroundEligible()
        runtimeForegroundRecovery.setEligible(eligible)
        // A cold owner load can finish while focus belongs to a permission dialog or another
        // app. Its callback defers UI creation; retry the local continuation when focus returns.
        if (eligible && runtimeCompatibleForUi && !uiInitialized) initializeRuntimeUiAfterPreflight()
    }

    private fun probeRuntimeCompatibility(complete: (RuntimePreflightResult) -> Unit) {
        dashboardExecutor.execute {
            val result = runCatching {
                val identity = RuntimeEndpoint.call(applicationContext, "identity")
                check(identity.getString("package") == RuntimeEndpoint.OWNER_PACKAGE &&
                    identity.getInt("protocol") == RuntimeEndpoint.PROTOCOL) { "Runtime identity mismatch" }
                RuntimePreflightResult.COMPATIBLE
            }.getOrElse { error ->
                Log.w(TAG, "Runtime compatibility preflight failed", error)
                classifyRuntimePreflightFailure(error).also { result ->
                    recordRuntimeRecoveryDiagnostic("preflight_${result.name.lowercase()}", error)
                }
            }
            runOnUiThread { if (!destroyed && !isFinishing) complete(result) }
        }
    }

    private fun onRuntimeRecoveryStatusChanged(status: RuntimeRecoveryStatus) {
        if (!BuildConfig.RUNTIME_CLIENT) return
        runtimeCompatibleForUi = status == RuntimeRecoveryStatus.READY
        runtimeRecoveryUiPhase = runtimeRecoveryPhase(status, runtimeRecoveryUiPhase)
        when (status) {
            RuntimeRecoveryStatus.READY -> {
                if (uiInitialized) dispatchRuntimeUiLifecycle()
                else initializeRuntimeUiAfterPreflight()
            }
            else -> Unit
        }
    }

    private fun initializeRuntimeUiAfterPreflight() {
        if (!runtimeCompatibleForUi || !runtimeRecoveryForegroundEligible() || uiInitialized ||
            runtimeInitializationInFlight || destroyed || isFinishing) return
        val cache = BydCollectorApplication.dashboardUiStateStore(applicationContext)
        if (RuntimePreferences.isInitialized() && cache.currentChrome() != null) {
            runCatching { initializeUi(runtimeSavedInstanceState) }
                .onSuccess {
                    runtimeSavedInstanceState = null
                    runtimeRecoveryUiPhase = null
                    dispatchRuntimeUiLifecycle()
                }
                .onFailure(::failRuntimeUiInitialization)
            return
        }

        runtimeInitializationInFlight = true
        dashboardExecutor.execute {
            val result = runCatching {
                RuntimeClient.refresh()
                RuntimePreferences.get(applicationContext)
                if (cache.currentChrome() == null) {
                    val initialSettings = CollectorSettings(applicationContext)
                    cache.seed(DashboardStateProvider(applicationContext,
                        { BydCollectorApplication.store(applicationContext) }, initialSettings).loadInitial())
                }
            }
            runOnUiThread {
                runtimeInitializationInFlight = false
                if (destroyed || isFinishing) return@runOnUiThread
                uiLanguage = RuntimeUiAppearance.language(applicationContext)
                result.onFailure(::failRuntimeUiInitialization)
                if (result.isFailure || !runtimeCompatibleForUi || !runtimeRecoveryForegroundEligible()) return@runOnUiThread
                runCatching { initializeUi(runtimeSavedInstanceState) }
                    .onSuccess {
                        runtimeSavedInstanceState = null
                        runtimeRecoveryUiPhase = null
                        dispatchRuntimeUiLifecycle()
                    }
                    .onFailure(::failRuntimeUiInitialization)
            }
        }
    }

    private fun failRuntimeUiInitialization(error: Throwable) {
        Log.e(TAG, "UI initialization failed after runtime compatibility check", error)
        recordRuntimeRecoveryDiagnostic("ui_initialization_failed", error)
        runtimeCompatibleForUi = false
        runtimeForegroundRecovery.failCurrentRecovery()
    }

    private fun dispatchRuntimeUiLifecycle() {
        if (!runtimeCompatibleForUi || !runtimeRecoveryForegroundEligible() || !uiInitialized) return
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) startUi()
        if (lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED) resumeUi()
    }

    private fun retryRuntimeRecovery() {
        if (!BuildConfig.RUNTIME_CLIENT || destroyed || isFinishing) return
        updateRuntimeRecoveryEligibility()
        if (runtimeRecoveryForegroundEligible()) runtimeForegroundRecovery.retry()
    }

    private fun closeRuntimeRecovery() {
        runtimeForegroundRecovery.setEligible(false)
        finishAndRemoveTask()
    }

    private fun launchOwnerRecoveryLauncher() {
        if (!runtimeRecoveryForegroundEligible()) return
        try {
            startActivity(Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(RuntimeEndpoint.OWNER_PACKAGE, "com.bydcollector.collector.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finishAndRemoveTask()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not open the owner recovery launcher", error)
            recordRuntimeRecoveryDiagnostic("owner_launcher_failed", error)
            runtimeForegroundRecovery.failCurrentRecovery()
        }
    }

    private fun recordRuntimeRecoveryDiagnostic(event: String, error: Throwable) {
        val app = applicationContext as BydCollectorApplication
        val detail = error.diagnosticDetail("phase=$event").take(8_192)
        dispatchOperationalEvent(diagnosticsExecutor) {
            app.operationalEventJournal.append(
                timestamp = java.time.Instant.now().toString(),
                elapsedMs = SystemClock.elapsedRealtime(),
                category = "runtime_recovery",
                message = event,
                detail = detail
            )
        }
    }

    private fun initializeUi(savedInstanceState: Bundle?) {
        if (!BuildConfig.RUNTIME_CLIENT) CollectorService.influxRuntimeDiagnostics.attachJournal(applicationContext)
        navigationSessionGeneration = navigationSession.captureGeneration()
        settings = CollectorSettings(applicationContext)
        val restoredShutdownInThisProcess = savedInstanceState?.getInt("shutdownOwnerPid") == android.os.Process.myPid()
        shutdownUiRequested = restoredShutdownInThisProcess &&
            savedInstanceState?.getBoolean("shutdownUiRequested", false) == true && settings.isUserShutdownRequested()
        uiLanguage = UiLanguage.fromCode(settings.uiLanguageCode())
        RuntimeUiAppearance.save(applicationContext, language = uiLanguage)
        updateHintEnabled = settings.isUpdateHintEnabled()
        updateHintAppearance = settings.updateHintAppearance()
        settingsPreferences = RuntimePreferences.get(applicationContext)
        settingsPreferences.registerOnSharedPreferenceChangeListener(settingsChangeListener)
        if (!BuildConfig.RUNTIME_CLIENT && !CollectorService.isMaintenanceRunningInProcess() && !DatabaseMaintenanceService.isRunning()) {
            settings.recoverInterruptedDbMaintenanceIfNeeded("activity_start")
        }
        updateChecks.addListener(updateCheckListener)
        releaseNotesHistory.addListener(releaseNotesListener)
        stateProvider = DashboardStateProvider(applicationContext, { BydCollectorApplication.store(applicationContext) }, settings)
        dashboardUiStateStore = BydCollectorApplication.dashboardUiStateStore(applicationContext)
        dashboardUiStateStore.selectVehicleKpiLanguage(uiLanguage.vehicleKpiLanguage())
        mqttDraft = MqttDraft(
            host = settings.mqttHost(),
            port = settings.mqttPort().toString(),
            clientId = settings.mqttClientId(),
            topicPrefix = settings.mqttTopicPrefix(),
            discoveryPrefix = settings.mqttDiscoveryPrefix(),
            deviceId = settings.mqttDeviceId(),
            deviceIdentifier = settings.mqttDeviceIdentifier(),
            deviceName = settings.mqttDeviceName(),
            deviceModel = settings.mqttDeviceModel(),
            alternativeHost = settings.mqttAlternativeHost().orEmpty(),
            alternativePort = settings.mqttAlternativePort()?.toString().orEmpty()
        )
        influxDraft = InfluxDraft(
            host = settings.influxHost(),
            port = settings.influxPort().toString(),
            database = settings.influxDatabase(),
            measurement = settings.influxMeasurement(),
            vehicleId = settings.influxVehicleId(),
            alternativeHost = settings.influxAlternativeHost().orEmpty(),
            alternativePort = settings.influxAlternativePort()?.toString().orEmpty()
        )
        telegramUiState = loadTelegramUiState()
        tripsUiState = TripsUiState(
            speedGreenThreshold = settings.tripSpeedGreenThreshold(),
            speedYellowThreshold = settings.tripSpeedYellowThreshold(),
            consumptionGreenThreshold = settings.tripConsumptionGreenThreshold(),
            consumptionYellowThreshold = settings.tripConsumptionYellowThreshold()
        )
        //An Activity recreation must render the process cache immediately; only a cold process
        //needs the lightweight initial snapshot before Compose starts collecting the flows.
        if (dashboardUiStateStore.currentChrome() == null) {
            dashboardUiStateStore.seed(stateProvider.loadInitial())
        }
        setContent {
            Box(Modifier.fillMaxSize()) {
                val renderedOfferResultId = updateOfferResultId
                val chromeSnapshot by dashboardUiStateStore.chromeState.collectAsStateWithLifecycle()
                // A different flow must start with its own cached value, not the previous tab's frame.
                val tabSnapshot = key(activeTab) {
                    val snapshot by dashboardUiStateStore.tabState(activeTab).collectAsStateWithLifecycle()
                    snapshot
                }
                val renderedChrome = chromeSnapshot?.state
                val renderedTab = (tabSnapshot?.state ?: renderedChrome)?.let { state ->
                    if (activeTab == AppTab.STORAGE && tabSnapshot?.lastError != null) state.copy(
                        archiveStorageScanPending = false,
                        archiveStorageScanError = tabSnapshot.lastError
                    ) else state
                }
                // Cached content stays real during refresh; only an empty pending archive scan is a placeholder.
                val tabContentReady = renderedTab != null &&
                    (activeTab != AppTab.STORAGE || !renderedTab.archiveStorageScanPending ||
                        renderedTab.archiveStorageSnapshot.entries.isNotEmpty())
                CompositionLocalProvider(
                    LocalRuntimeDialogRecovery provides RuntimeDialogRecoveryHost(
                        phase = runtimeRecoveryUiPhase.takeIf { BuildConfig.RUNTIME_CLIENT },
                        language = uiLanguage,
                        darkTheme = darkTheme,
                        onRetry = ::retryRuntimeRecovery,
                        onClose = ::closeRuntimeRecovery,
                        onDialogViewChanged = runtimeDialogViewReporter
                    )
                ) {
                    BydCollectorApp(
                    state = renderedTab,
                    chromeState = renderedChrome,
                    activeTab = activeTab,
                    navigationSession = navigationSession,
                    tabContentReady = tabContentReady,
                    language = uiLanguage,
                    darkTheme = darkTheme,
                    mqttDraft = mqttDraft,
                    influxDraft = influxDraft,
                    tripsUiState = tripsUiState,
                    tripsUiActions = tripsUiActions,
                    telegramUiState = telegramUiState,
                    telegramActions = telegramActions,
                    appVersionName = BuildConfig.VERSION_NAME,
                    updateAutoCheckEnabled = settings.isUpdateAutoCheckEnabled(),
                    updateHintEnabled = updateHintEnabled,
                    updateHintAppearance = updateHintAppearance,
                    updateUiState = updateUiState,
                    releaseNotesHistory = updateReleaseNotes,
                    onUpdateOfferPresented = {
                        // A frame drawn behind native permission UI is not an offer
                        // shown to the user. Focus is observed in the draw callback.
                        if (mainWindowHasFocus && foreground) {
                            renderedOfferResultId?.let(updateRuntime::onOfferPresented)
                        }
                    },
                    databaseMaintenanceUiState = currentMaintenanceUiState(renderedChrome),
                    diagnosticsBusy = diagnosticsBusy,
                    actionUiState = actionUiState,
                    mqttCleanupUiState = mqttCleanupUiState,
                    actions = uiActions,
                    backgroundSetupPromptVisible = backgroundSetupPromptVisible,
                    onOpenBackgroundSettingsFromPrompt = ::onOpenBackgroundSettingsFromPrompt,
                    onDismissBackgroundSetupPrompt = ::onDismissBackgroundSetupPrompt
                    )
                }
                runtimeRecoveryUiPhase?.takeIf { BuildConfig.RUNTIME_CLIENT }?.let { phase ->
                    RuntimeRecoveryPanel(
                        language = uiLanguage,
                        phase = phase,
                        darkTheme = darkTheme,
                        onRetry = ::retryRuntimeRecovery,
                        onClose = ::closeRuntimeRecovery,
                        preserveContent = true,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
        uiInitialized = true
        loadCredentialsAfterFirstFrame()
        updateRuntime.start("activity")
        dashboardExecutor.execute {
            val runtimeStore = currentStore()
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                store = runtimeStore
                settings = CollectorSettings(
                    applicationContext,
                    runtimeStore,
                    eventExecutor = dashboardExecutor
                )
                reconcileCutoverArchiveStorageIfNeeded()
                hydrateDashboardTabsOnce()
            }
        }
        // Recreation or a service-driven entry is not consent to undo explicit Shutdown.
        if (shutdownUiRequested) observeShutdownPhase()
        else if (savedInstanceState == null || !restoredShutdownInThisProcess) reopenAfterExplicitLauncherEntry(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("shutdownUiRequested", shutdownUiRequested)
        outState.putInt("shutdownOwnerPid", android.os.Process.myPid())
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (BuildConfig.RUNTIME_CLIENT) updateRuntimeRecoveryEligibility()
        if (uiInitialized) startUi()
    }

    private fun startUi() {
        if (!uiInitialized || (BuildConfig.RUNTIME_CLIENT &&
                (!runtimeCompatibleForUi || !runtimeRecoveryForegroundEligible()))) return
        if (uiVisibleDelivered) return
        uiVisibleDelivered = true
        if (BuildConfig.RUNTIME_CLIENT) {
            contentResolver.registerContentObserver(RuntimeEndpoint.uri, false, runtimeObserver)
            runtimeObserverRegistered = true
        }
        updateRuntime.onUiVisible()
        if (BuildConfig.RUNTIME_CLIENT) RuntimeClient.controlAsync("attach")
        else if (!settings.isUserShutdownRequested()) CollectorServiceController.start(this)
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.RUNTIME_CLIENT) updateRuntimeRecoveryEligibility()
        else if (uiInitialized) resumeUi()
    }

    override fun onPostResume() {
        super.onPostResume()
        if (BuildConfig.RUNTIME_CLIENT) updateRuntimeRecoveryEligibility()
    }

    private fun resumeUi() {
        if (!uiInitialized || (BuildConfig.RUNTIME_CLIENT &&
                (!runtimeCompatibleForUi || !runtimeRecoveryForegroundEligible()))) return
        if (uiResumedDelivered) return
        uiResumedDelivered = true
        foreground = true
        updateRuntime.onUiResumed()
        consumeUpdateHintOpen()
        updateKpiVisibility()
        reconcileCutoverArchiveStorageIfNeeded()
        syncTelegramUiRuntimeState()
        if (activeTab == AppTab.TRIPS) loadTripsUi()
        if (tripsUiState.currentTripModal?.trip?.open == true) scheduleCurrentTripRefresh(0L)
        refresh()
        maybeContinueStartupAccessFlow()
        syncUpdateCheckUi()
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, DASHBOARD_REFRESH_HEARTBEAT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        mainWindowHasFocus = hasFocus
        if (BuildConfig.RUNTIME_CLIENT) updateRuntimeRecoveryEligibility()
        if (hasFocus && uiInitialized && (!BuildConfig.RUNTIME_CLIENT || runtimeCompatibleForUi)) {
            maybeContinueStartupAccessFlow()
            syncUpdateCheckUi()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!uiInitialized) return
        reopenAfterExplicitLauncherEntry(intent)
        consumeUpdateHintOpen()
        syncUpdateCheckUi()
    }

    private fun observeShutdownPhase() {
        if (!settings.isUserShutdownRequested()) return
        when (settings.userShutdownPhase()) {
            CollectorSettings.SHUTDOWN_PHASE_HANDOFF -> {
                shutdownUiRequested = false
                navigationSession.clear()
                finishAndRemoveTask()
            }
            CollectorSettings.SHUTDOWN_PHASE_ERROR -> {
                shutdownUiRequested = false
                showShutdownFailure(settings.userShutdownDetail().orEmpty())
            }
        }
    }

    private fun showShutdownFailure(detail: String) {
        val label = if (uiLanguage == UiLanguage.UK) "Завершення роботи не виконане" else "Shutdown did not complete"
        Toast.makeText(this, "$label\n${operationFailureDetail(strings(uiLanguage), detail)}", Toast.LENGTH_LONG).show()
    }

    private fun showOperationFailure(kind: String, error: Throwable) {
        recordOperationalEvent("ui_action_failed", "UI action failed", error.diagnosticDetail("action=$kind"))
        Toast.makeText(this, operationFailureMessage(strings(uiLanguage), kind, error.message.orEmpty()), Toast.LENGTH_LONG).show()
    }

    private fun reopenAfterExplicitLauncherEntry(entry: Intent?) {
        if (entry?.action != Intent.ACTION_MAIN || !entry.hasCategory(Intent.CATEGORY_LAUNCHER)) return
        if (!settings.isUserShutdownRequested() && settings.shutdownListenerPreviousState() == null &&
            settings.userShutdownPhase() == CollectorSettings.SHUTDOWN_PHASE_IDLE) return
        if (shutdownReopenInFlight) return
        if (CollectorService.isUserShutdownInProgress()) {
            shutdownUiRequested = true
            observeShutdownPhase()
            return
        }
        if (BuildConfig.RUNTIME_CLIENT) {
            shutdownReopenInFlight = true
            diagnosticsExecutor.execute {
                val result = runCatching { RuntimeEndpoint.call(this, "reopen").getBoolean("reopened") }
                runOnUiThread {
                    shutdownReopenInFlight = false
                    if (!destroyed) {
                        if (result.getOrDefault(false)) {
                            shutdownUiRequested = false
                            startupAccessFlowCompleted = false
                            updateRuntime.start("explicit_reopen")
                            maybeContinueStartupAccessFlow()
                            refresh()
                        } else {
                            val detail = result.exceptionOrNull()?.message ?: "Runtime recovery failed"
                            recordOperationalEvent("runtime_reopen_failed", "Runtime recovery failed", detail)
                            showShutdownFailure(detail)
                        }
                    }
                }
            }
            return
        }
        shutdownReopenInFlight = true
        // Cancelling the detached finalizer needs local ADB; never block Activity creation on it.
        diagnosticsExecutor.execute {
            var previousShutdownFailure: String? = null
            val result = runCatching {
                CollectorService.clearShutdownForExplicitReopen(applicationContext) { previousShutdownFailure = it }
            }
            runOnUiThread {
                shutdownReopenInFlight = false
                if (destroyed) return@runOnUiThread
                if (result.getOrDefault(false)) {
                    shutdownUiRequested = false
                    previousShutdownFailure?.let { showShutdownFailure(it) }
                    updateRuntime.shutdown()
                    updateRuntime.start("explicit_reopen")
                    if (foreground) updateRuntime.onUiVisible()
                    dashboardExecutor.execute {
                        if (settings.isUserShutdownRequested()) return@execute
                        val pendingCutover = settings.storageCutoverJournal()
                        runCatching {
                            if (pendingCutover?.family == DirectDebugDatabaseHelper.SCHEMA_FAMILY) {
                                check(BydCollectorApplication.ensureDebugStorageReady(applicationContext)) {
                                    "Secondary storage recovery did not complete"
                                }
                            }
                            val runtimeStore = currentStore() // existing Main recovery runs before open
                            check(settings.storageCutoverJournal() == null) {
                                "Storage cutover remains pending after explicit reopening"
                            }
                            if (pendingCutover != null) runtimeStore.recordEvent(
                                "shutdown_cutover_recovery_verified", "Interrupted storage cutover recovered",
                                "family=${pendingCutover.family} previous_phase=${pendingCutover.phase}"
                            )
                            if (!settings.isUserShutdownRequested()) {
                                CollectorAutoStart.recoverFromForeground(applicationContext, settings, runtimeStore)
                            }
                        }.onFailure { error ->
                            Log.e("BYDCollectorShutdown", "Explicit reopen storage/runtime recovery failed", error)
                            runOnUiThread {
                                if (!destroyed && !settings.isUserShutdownRequested()) {
                                    settings.setUserShutdownPhase(CollectorSettings.SHUTDOWN_PHASE_ERROR,
                                        detail = "Reopen storage/runtime recovery failed: ${error::class.java.simpleName}: ${error.message}")
                                    val label = if (uiLanguage == UiLanguage.UK) "Відновлення після Shutdown не завершене"
                                        else "Recovery after Shutdown did not complete"
                                    Toast.makeText(this, "$label\n${operationFailureDetail(strings(uiLanguage), error.message.orEmpty())}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    startupAccessFlowCompleted = false
                    maybeContinueStartupAccessFlow()
                    refresh()
                } else {
                    showShutdownFailure(result.exceptionOrNull()?.message
                        ?: settings.userShutdownDetail() ?: "Could not restore runtime after explicit opening")
                }
            }
        }
    }

    private fun consumeUpdateHintOpen() {
        if (BuildConfig.RUNTIME_CLIENT) {
            RuntimeClient.controlAsync("hintOpen") { response ->
                runOnUiThread { if (!destroyed && response.getBoolean("opened")) openUpdateHintTab() }
            }
        } else if ((applicationContext as BydCollectorApplication).updateHints.consumeOpenRequest()) openUpdateHintTab()
    }

    private fun openUpdateHintTab() {
        navigationSession.selectTab(AppTab.EXTRA)
        updateKpiVisibility()
        updatePresentationRevision = -1L
    }

    override fun onPause() {
        foreground = false
        uiResumedDelivered = false
        if (BuildConfig.RUNTIME_CLIENT) runtimeForegroundRecovery.endForeground()
        updateKpiVisibility()
        mainWindowHasFocus = false
        handler.removeCallbacks(refreshTask)
        handler.removeCallbacks(currentTripRefreshTask)
        super.onPause()
    }

    override fun onStop() {
        if (BuildConfig.RUNTIME_CLIENT && runtimeObserverRegistered) {
            contentResolver.unregisterContentObserver(runtimeObserver)
            runtimeObserverRegistered = false
        }
        if (uiInitialized && !isChangingConfigurations && !settings.isUserShutdownRequested()) {
            updateRuntime.onUiHidden()
        }
        uiVisibleDelivered = false
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        if (BuildConfig.RUNTIME_CLIENT) runtimeForegroundRecovery.setEligible(false)
        if (BuildConfig.RUNTIME_CLIENT && runtimeObserverRegistered) {
            contentResolver.unregisterContentObserver(runtimeObserver)
            runtimeObserverRegistered = false
        }
        if (BuildConfig.RUNTIME_CLIENT) runCatching { RuntimeClient.visibility(ui = false, kpi = false) }
        else com.bydcollector.collector.service.KpiUiVisibility.update(kpiVisibilityOwner, false)
        if (uiInitialized) {
            updateChecks.removeListener(updateCheckListener)
            releaseNotesHistory.removeListener(releaseNotesListener)
        }
        archiveDeleteDispatchStartedAtMs = null
        actionUiState = BydCollectorActionUiState()
        diagnosticsBusy = false
        //asks the watchdog path to recover service work if the user closes only the activity
        if (
            !BuildConfig.RUNTIME_CLIENT && ::settings.isInitialized &&
            !settings.isUserShutdownRequested() &&
            !CollectorSettings.isDbMaintenanceRunning(applicationContext)
        ) {
            refreshStoreBackedState()
            CollectorAutoStart.scheduleRestartAfterUiClosed(applicationContext, settings, currentStore())
        }
        dashboardExecutor.shutdownNow()
        diagnosticsExecutor.shutdownNow()
        mqttCleanupExecutor.shutdownNow()
        updateExecutor.shutdownNow()
        if (::settingsPreferences.isInitialized) {
            settingsPreferences.unregisterOnSharedPreferenceChangeListener(settingsChangeListener)
        }
        handler.removeCallbacks(updateCheckUiTask)
        handler.removeCallbacks(startupAdbSelfCheckTask)
        handler.removeCallbacks(currentTripRefreshTask)
        super.onDestroy()
    }

    private fun updateKpiVisibility() {
        if (!uiInitialized) return
        if (BuildConfig.RUNTIME_CLIENT) {
            RuntimeClient.visibility(kpi = !destroyed && foreground && activeTab == AppTab.ALL_PARAMETERS && kpiViewportVisible)
            return
        }
        com.bydcollector.collector.service.KpiUiVisibility.update(kpiVisibilityOwner,
            !destroyed && foreground && activeTab == AppTab.ALL_PARAMETERS && kpiViewportVisible)
    }

    @SuppressLint("MissingSuperCall")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun currentStore(): TelemetryStore = BydCollectorApplication.store(applicationContext)

    private fun <T> withTelemetryStoreRead(action: (TelemetryStore) -> T): T =
        (applicationContext as BydCollectorApplication).withTelemetryStoreRead(action)

    private fun recordOperationalEvent(category: String, message: String, detail: String? = null) {
        dispatchOperationalEvent(dashboardExecutor) {
            withTelemetryStoreRead { eventStore ->
                eventStore.recordEvent(category, message, detail)
            }
        }
    }

    private fun postMaintenanceLaunchFailure(error: Throwable) {
        Log.e(TAG, "Database maintenance could not start", error)
        runOnUiThread {
            if (destroyed) return@runOnUiThread
            maintenanceLaunchOperation = null
            pendingMaintenanceOperation = null
            pendingMainArchivePreflight = null
            refresh()
        }
    }

    private fun reconcileCutoverArchiveStorageIfNeeded() {
        if (settings.isCutoverArchiveStoragePending() && !CollectorService.isArchiveStorageActive()) {
            submitUiOwnerAction("archiveReconcile", work = {
                CollectorServiceController.reconcileArchiveStorage(applicationContext)
            })
        }
    }

    private fun shareArchives(ids: List<String>) {
        if (BuildConfig.RUNTIME_CLIENT) { submitRuntimeJob("archiveShare", JSONObject().put("ids", JSONArray(ids))); return }
        if (!archiveShareInFlight.compareAndSet(false, true)) return
        val requestedIds = ids.toList()
        if (CollectorService.isArchiveStorageActive()) {
            failArchiveShare(requestedIds, null, "archive_job_active")
            return
        }
        val lease = CollectorService.archiveShareLeaseRegistry.acquire(requestedIds)
        try {
            dashboardExecutor.execute {
                if (CollectorService.isArchiveStorageActive()) {
                    failArchiveShare(requestedIds, lease, "archive_job_started")
                    return@execute
                }
                val files = runCatching {
                    ArchiveStorageManager(
                        archiveRoot = File(filesDir, "db_archive"),
                        mainDatabaseFile = currentStore().databaseFile(),
                        // Sharing archive metadata does not read the active secondary database.
                        debugDatabaseFile = getDatabasePath(DirectDebugDatabaseHelper.DATABASE_NAME),
                        tripsDatabaseFile = getDatabasePath(com.bydcollector.collector.data.trips.TripDatabaseHelper.DATABASE_NAME)
                    ).resolveShareZipFiles(requestedIds)
                }.getOrNull()
                if (files == null) {
                    failArchiveShare(requestedIds, lease, "invalid_or_stale_selection")
                    return@execute
                }
                val uris = runCatching {
                    files.map { file ->
                        FileProvider.getUriForFile(
                            this@MainActivity,
                            "${BuildConfig.APPLICATION_ID}.fileprovider",
                            file
                        )
                    }
                }.getOrElse {
                    failArchiveShare(requestedIds, lease, "uri_resolution_failed:${it::class.java.simpleName}")
                    return@execute
                }
                handler.post {
                    if (destroyed) {
                        CollectorService.archiveShareLeaseRegistry.release(lease)
                        archiveShareInFlight.set(false)
                        actionUiState = actionUiState.copy(archiveShare = false)
                    } else {
                        openArchiveShareChooser(requestedIds, uris, lease)
                    }
                }
            }
        } catch (error: RuntimeException) {
            failArchiveShare(requestedIds, lease, "executor_rejected:${error::class.java.simpleName}")
        }
    }

    private fun openArchiveShareChooser(
        ids: List<String>,
        uris: List<Uri>,
        lease: ArchiveShareLeaseRegistry.Lease
    ) {
        try {
            check(uris.isNotEmpty())
            val sendIntent = Intent(
                if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            ).apply {
                type = "application/zip"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (uris.size == 1) {
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                } else {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
                clipData = ClipData.newUri(
                    contentResolver,
                    strings(uiLanguage).shareSelectedArchives,
                    uris.first()
                ).also { clip ->
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
            }
            startActivity(Intent.createChooser(sendIntent, strings(uiLanguage).shareSelectedArchives))
            actionUiState = actionUiState.copy(
                archiveShareHandoffGeneration = actionUiState.archiveShareHandoffGeneration + 1L
            )
            recordOperationalEvent(
                "archive_share_chooser_opened",
                "Archive share chooser opened",
                "count=${ids.size}"
            )
        } catch (error: ActivityNotFoundException) {
            failArchiveShare(ids, lease, "no_share_target")
            return
        } catch (error: RuntimeException) {
            failArchiveShare(ids, lease, "chooser_failed:${error::class.java.simpleName}")
            return
        } finally {
            archiveShareInFlight.set(false)
            actionUiState = actionUiState.copy(archiveShare = false)
        }
    }

    private fun failArchiveShare(
        ids: List<String>,
        lease: ArchiveShareLeaseRegistry.Lease?,
        reason: String
    ) {
        lease?.let(CollectorService.archiveShareLeaseRegistry::release)
        archiveShareInFlight.set(false)
        recordOperationalEvent(
            "archive_share_failed",
            "Archive share rejected",
            "reason=$reason count=${ids.size}"
        )
        if (!destroyed) {
            handler.post {
                if (!destroyed) {
                    actionUiState = actionUiState.copy(archiveShare = false)
                    Toast.makeText(
                        this@MainActivity,
                        strings(uiLanguage).archiveShareFailed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun refreshStoreBackedState() {
        store = currentStore()
        settings = CollectorSettings(
            applicationContext,
            store,
            eventExecutor = dashboardExecutor
        )
    }

    private fun hydrateDashboardTabsOnce() {
        if (!dashboardUiStateStore.beginInitialHydration()) return
        val kpiLanguage = uiLanguage.vehicleKpiLanguage()
        val targets = listOf(
            AppTab.MAIN to DashboardLoadProfile.MAIN,
            AppTab.ALL_PARAMETERS to DashboardLoadProfile.ALL_PARAMETERS,
            AppTab.HA to DashboardLoadProfile.HA,
            AppTab.EXTRA to DashboardLoadProfile.EXTRA
        )
        runCatching {
            dashboardExecutor.execute {
                targets.forEach { (tab, profile) ->
                    if (Thread.currentThread().isInterrupted) return@execute
                    val generation = dashboardUiStateStore.beginTabRefresh(tab)
                    runCatching {
                        stateProvider.load(
                            profile = profile,
                            previous = dashboardUiStateStore.currentTab(tab),
                            vehicleKpiLanguage = kpiLanguage
                        )
                    }.onSuccess { state ->
                        dashboardUiStateStore.publishTab(tab, generation, state)
                    }.onFailure { error ->
                        dashboardUiStateStore.failTab(tab, generation, dashboardErrorDetail(error))
                        Log.w(TAG, "Initial dashboard hydration failed for $tab", error)
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Initial dashboard hydration rejected", error)
        }
    }

    private fun openMainArchiveDialog() {
        if (maintenancePreflightInFlight || destroyed) {
            actionUiState = actionUiState.copy(mainArchivePreflight = false)
            return
        }
        maintenancePreflightInFlight = true
        val task = Runnable {
            val result = runCatching {
                if (BuildConfig.RUNTIME_CLIENT) RuntimeReads.read<MainArchivePreflight>(this, "mainPreflight")
                else withTelemetryStoreRead { preflightStore ->
                    StorageFormatCutoverCoordinator.readMainPreflight(preflightStore.databaseFile())
                }
            }
            runOnUiThread {
                maintenancePreflightInFlight = false
                actionUiState = actionUiState.copy(mainArchivePreflight = false)
                if (destroyed) return@runOnUiThread
                result
                    .onSuccess { preflight ->
                        pendingMainArchivePreflight = withTelegramStorageWarning(preflight)
                        pendingMaintenanceOperation = DbMaintenanceOperation.ARCHIVE
                    }
                    .onFailure { error ->
                        recordDashboardRefreshFailure("maintenance_preflight", error)
                        pendingMainArchivePreflight = withTelegramStorageWarning(MainArchivePreflight(
                            warning = "${strings(uiLanguage).archivePreflightFailed}: ${dashboardErrorDetail(error)}"
                        ))
                        pendingMaintenanceOperation = DbMaintenanceOperation.ARCHIVE
                        refresh()
                    }
            }
        }
        runCatching { dashboardExecutor.execute(task) }.onFailure { error ->
            maintenancePreflightInFlight = false
            actionUiState = actionUiState.copy(mainArchivePreflight = false)
            recordDashboardRefreshFailure("maintenance_preflight", error)
            pendingMainArchivePreflight = withTelegramStorageWarning(MainArchivePreflight(
                warning = "${strings(uiLanguage).archivePreflightFailed}: ${dashboardErrorDetail(error)}"
            ))
            pendingMaintenanceOperation = DbMaintenanceOperation.ARCHIVE
            refresh()
        }
    }

    private fun withTelegramStorageWarning(preflight: MainArchivePreflight): MainArchivePreflight {
        val error = (applicationContext as BydCollectorApplication).telegramStorageError() ?: return preflight
        return preflight.copy(
            telegramStorageWarning = String.format(
                strings(uiLanguage).dbMaintenanceTelegramStorageWarningTemplate,
                error.take(300)
            )
        )
    }

    private fun loadTripsUi(routeTripId: String? = null) {
        if (destroyed || !navigationSession.isGenerationCurrent(navigationSessionGeneration)) return
        val requestedLanguage = uiLanguage
        val requestGeneration = ++tripsRequestGeneration
        val sessionGeneration = navigationSession.captureGeneration()
        // A refresh superseding an in-flight route request must finish that
        // request, not replace it with a route-less hierarchy.
        val requestedRouteId = routeTripId ?: tripsUiState.routeLoadingId
        tripsUiState = tripsUiState.copy(routeLoadingId = requestedRouteId)
        runCatching { dashboardExecutor.execute {
            val result = runCatching {
                val trips = BydCollectorApplication.trips(applicationContext)
                val groups = trips.queryHierarchy()
                val routes = requestedRouteId?.let { id -> mapOf(id to trips.queryRoutePoints(id)) }.orEmpty()
                val availableCurrentTrip = trips.loadOpenSession()?.let { TripsUiMapper.current(it, requestedLanguage) }
                val remoteBytes = if (BuildConfig.RUNTIME_CLIENT) RuntimeReads.read<LongArray>(this@MainActivity, "footprints") else null
                val tripsBytes = remoteBytes?.get(2) ?: sqliteFootprintBytes(trips.databaseFile)
                runCatching { remoteBytes?.get(1) ?: sqliteFootprintBytes(DirectDebugDatabaseResolver.databaseFile(this@MainActivity)) }
                    .getOrNull()
                    ?.let { debugBytes ->
                        dashboardUiStateStore.publishDatabaseFootprints(
                            remoteBytes?.get(0) ?: sqliteFootprintBytes(getDatabasePath(com.bydcollector.collector.data.local.TelemetryDatabaseHelper.DATABASE_NAME)),
                            debugBytes,
                            tripsBytes
                        )
                    }
                TripsLoadResult(
                    years = TripsUiMapper.years(groups, requestedLanguage, routes),
                    refreshedRouteId = requestedRouteId,
                    databasePath = if (BuildConfig.RUNTIME_CLIENT) RuntimeClient.state.getString("tripPath").orEmpty() else trips.databaseFile.absolutePath,
                    databaseSizeBytes = tripsBytes,
                    availableCurrentTrip = availableCurrentTrip
                )
            }
            runOnUiThread {
                if (destroyed ||
                    uiLanguage != requestedLanguage ||
                    requestGeneration != tripsRequestGeneration ||
                    !navigationSession.isGenerationCurrent(sessionGeneration)
                ) return@runOnUiThread
                result.onSuccess { loaded ->
                    tripsUiState = tripsUiState.copy(
                        years = TripsUiMapper.retainRoutes(loaded.years, tripsUiState.years, loaded.refreshedRouteId),
                        routeLoadingId = null,
                        databasePath = loaded.databasePath,
                        databaseSizeBytes = loaded.databaseSizeBytes,
                        currentTripAvailabilityKnown = true,
                        availableCurrentTrip = loaded.availableCurrentTrip
                    )
                }.onFailure { error ->
                    tripsUiState = tripsUiState.copy(routeLoadingId = null)
                    recordDashboardRefreshFailure("trips", error)
                }
            }
        } }.onFailure { error ->
            if (!destroyed &&
                requestGeneration == tripsRequestGeneration &&
                navigationSession.isGenerationCurrent(sessionGeneration)
            ) {
                tripsUiState = tripsUiState.copy(routeLoadingId = null)
                recordDashboardRefreshFailure("trips", error)
            }
        }
    }

    private fun openCurrentTrip() {
        val available = tripsUiState.availableCurrentTrip ?: return
        selectedCurrentTripId = available.trip.id
        currentTripRefreshGeneration++
        currentTripRefreshInFlight = false
        tripsUiState = tripsUiState.copy(
            currentTripModal = available.copy(
                trip = available.trip.copy(route = emptyList()),
                nextRouteSequence = 0L,
                position = null
            )
        )
        scheduleCurrentTripRefresh(0L)
    }

    private fun dismissCurrentTrip() {
        selectedCurrentTripId = null
        currentTripRefreshGeneration++
        currentTripRefreshInFlight = false
        handler.removeCallbacks(currentTripRefreshTask)
        tripsUiState = tripsUiState.copy(currentTripModal = null)
    }

    private fun scheduleCurrentTripRefresh(delayMs: Long = CURRENT_TRIP_REFRESH_MS) {
        handler.removeCallbacks(currentTripRefreshTask)
        if (
            destroyed || !foreground || activeTab != AppTab.TRIPS ||
            selectedCurrentTripId == null || tripsUiState.currentTripModal?.trip?.open != true
        ) return
        handler.postDelayed(currentTripRefreshTask, delayMs.coerceAtLeast(0L))
    }

    private fun refreshCurrentTrip() {
        val tripId = selectedCurrentTripId ?: return
        val modal = tripsUiState.currentTripModal ?: return
        if (destroyed || !foreground || activeTab != AppTab.TRIPS || currentTripRefreshInFlight) return
        if (modal.trip.open) {
            val agedPosition = TripsUiMapper.currentPosition(
                route = modal.trip.route,
                currentBootId = currentBootId,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                nowWallTimeMs = System.currentTimeMillis()
            )
            if (agedPosition != modal.position) {
                tripsUiState = tripsUiState.copy(currentTripModal = modal.copy(position = agedPosition))
            }
        }
        val generation = currentTripRefreshGeneration
        val requestedLanguage = uiLanguage
        //The final marker rewrites the last persisted point without allocating a new sequence.
        val firstSequence = (modal.nextRouteSequence - 1L).coerceAtLeast(0L)
        currentTripRefreshInFlight = true
        runCatching {
            dashboardExecutor.execute {
                val result = runCatching {
                    val trips = BydCollectorApplication.trips(applicationContext)
                    val session = trips.session(tripId)
                    session?.let {
                        val routeFirstSequence = if (it.state == TripSession.STATE_OPEN) {
                            firstSequence
                        } else {
                            //Power-off may mark the last valid point final behind one or more GPS-gap rows.
                            modal.trip.route.lastOrNull { point -> !point.gap }?.sequence ?: 0L
                        }
                        val tail = buildList { trips.forEachRoutePointFrom(tripId, routeFirstSequence, ::add) }
                        CurrentTripRead(
                            session = it,
                            firstSequence = routeFirstSequence,
                            routeTail = tail,
                            currentBootId = resolveCurrentBootId(),
                            readElapsedMs = SystemClock.elapsedRealtime(),
                            readWallTimeMs = System.currentTimeMillis()
                        )
                    }
                }
                runOnUiThread {
                    if (
                        destroyed || generation != currentTripRefreshGeneration ||
                        selectedCurrentTripId != tripId
                    ) return@runOnUiThread
                    currentTripRefreshInFlight = false
                    result.onSuccess { read ->
                        if (read == null) {
                            scheduleCurrentTripRefresh()
                            return@onSuccess
                        }
                        val previous = tripsUiState.currentTripModal ?: return@onSuccess
                        if (previous.trip.id != tripId) return@onSuccess
                        val mappedTail = TripsUiMapper.routePoints(read.routeTail)
                        val routeChanged = mappedTail.any { candidate ->
                            previous.trip.route.lastOrNull { it.sequence == candidate.sequence } != candidate
                        }
                        val mergedRoute = if (!routeChanged) {
                            previous.trip.route
                        } else {
                            previous.trip.route.filter { it.sequence < read.firstSequence } + mappedTail
                        }
                        val nextSequence = read.routeTail.maxOfOrNull { it.sequence }
                            ?.takeIf { it != Long.MAX_VALUE }
                            ?.plus(1L)
                            ?: modal.nextRouteSequence
                        val refreshed = TripsUiMapper.current(read.session, requestedLanguage)
                        val updated = refreshed.copy(
                            trip = refreshed.trip.copy(route = mergedRoute),
                            nextRouteSequence = nextSequence,
                            position = if (refreshed.trip.open) {
                                TripsUiMapper.currentPosition(
                                    route = mergedRoute,
                                    currentBootId = read.currentBootId,
                                    nowElapsedMs = read.readElapsedMs,
                                    nowWallTimeMs = read.readWallTimeMs
                                )
                            } else {
                                null
                            }
                        )
                        tripsUiState = tripsUiState.copy(
                            currentTripModal = updated,
                            availableCurrentTrip = if (updated.trip.open) {
                                updated.copy(
                                    trip = updated.trip.copy(route = emptyList()),
                                    nextRouteSequence = 0L,
                                    position = null
                                )
                            } else {
                                null
                            }
                        )
                        if (updated.trip.open) {
                            scheduleCurrentTripRefresh()
                        } else {
                            loadTripsUi()
                        }
                    }.onFailure { error ->
                        recordDashboardRefreshFailure("current_trip", error)
                        scheduleCurrentTripRefresh()
                    }
                }
            }
        }.onFailure { error ->
            currentTripRefreshInFlight = false
            recordDashboardRefreshFailure("current_trip_dispatch", error)
            scheduleCurrentTripRefresh()
        }
    }

    private fun resolveCurrentBootId(): String? {
        currentBootId?.let { return it }
        return runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()?.also { currentBootId = it }
    }

    private fun refresh(force: Boolean = true) {
        if (destroyed || !foreground) return
        if (BuildConfig.RUNTIME_CLIENT && !runtimeCompatibleForUi) return
        processUpdateDismissFailure?.let { error ->
            processUpdateDismissFailure = null
            showOperationFailure("updateDismiss", error)
        }
        val nowMs = SystemClock.elapsedRealtime()
        val tab = activeTab
        val profile = dashboardProfile(tab)
        val tabSnapshot = dashboardUiStateStore.tabState(tab).value
        val tabIntervalMs = dashboardTabRefreshIntervalMs(
            tab = tab,
            storageRefreshPending = tabSnapshot?.state?.let { state ->
                state.archiveStorageJobStatus.running || state.archiveStorageScanPending
            } == true || tabSnapshot?.lastError != null || tabSnapshot?.loadedAtElapsedMs == 0L || actionUiState.archiveDeleteDispatch
        )
        val tabDue = profile != null && (
            force || dashboardSnapshotDue(tabSnapshot?.loadedAtElapsedMs, tabIntervalMs, nowMs)
        )
        val chromeSnapshot = dashboardUiStateStore.chromeState.value
        val chromeDue = force || dashboardSnapshotDue(
            loadedAtElapsedMs = chromeSnapshot?.loadedAtElapsedMs,
            intervalMs = DASHBOARD_CHROME_REFRESH_INTERVAL_MS,
            nowMs = nowMs
        )
        if (!tabDue && !chromeDue && !BuildConfig.RUNTIME_CLIENT) return
        if (refreshInFlight) {
            if (force) forcedRefreshPending = true
            return
        }

        refreshInFlight = true
        val tabGeneration = if (tabDue || (BuildConfig.RUNTIME_CLIENT && profile != null)) dashboardUiStateStore.beginTabRefresh(tab) else null
        val chromeGeneration = if (chromeDue || BuildConfig.RUNTIME_CLIENT) dashboardUiStateStore.beginChromeRefresh() else null
        val kpiLanguage = uiLanguage.vehicleKpiLanguage()
        dashboardExecutor.execute {
            val runtimeJobs = if ((BuildConfig.RUNTIME_CLIENT || updateDownloadJobId != null) &&
                nowMs - lastRuntimeMetadataAt >= 1_000L) runCatching {
                if (BuildConfig.RUNTIME_CLIENT) {
                    RuntimeClient.refresh()
                    RuntimePreferences.refresh(this@MainActivity)
                    RuntimeClient.refreshUpdates()
                }
                lastRuntimeMetadataAt = nowMs
                RuntimeJobsService.snapshot(this@MainActivity)
            } else null
            val tabResult = if (tabGeneration != null && profile != null) {
                runCatching {
                    if (BuildConfig.RUNTIME_CLIENT && !tabDue) RuntimeReads.read<DashboardState>(this@MainActivity, "dashboard", Bundle().apply {
                        putString("profile", profile.name); putString("language", kpiLanguage.name); putBoolean("cached", true)
                    }) else stateProvider.load(
                        profile = profile,
                        previous = dashboardUiStateStore.currentTab(tab),
                        vehicleKpiLanguage = kpiLanguage
                    )
                }
            } else {
                null
            }
            val chromeResult = if (chromeGeneration != null) {
                val reusableTabState = tabResult?.getOrNull()
                    ?.takeIf { profile?.healthDetail != null && (tabDue || !chromeDue) }
                reusableTabState?.let { Result.success(it) } ?: runCatching {
                    if (BuildConfig.RUNTIME_CLIENT && !chromeDue) RuntimeReads.read<DashboardState>(this@MainActivity, "dashboard", Bundle().apply {
                        putString("profile", DashboardLoadProfile.CHROME.name); putString("language", kpiLanguage.name); putBoolean("cached", true)
                    }) else stateProvider.load(
                        profile = DashboardLoadProfile.CHROME,
                        previous = dashboardUiStateStore.currentChrome(),
                        vehicleKpiLanguage = kpiLanguage
                    )
                }
            } else {
                null
            }
            runOnUiThread {
                refreshInFlight = false
                if (destroyed) return@runOnUiThread
                runtimeJobs?.onSuccess { syncUpdateCheckUi(); syncReleaseNotesUi(); consumeRuntimeJobs(it) }
                if (tabGeneration != null && tabResult != null) {
                    tabResult
                        .onSuccess { state ->
                            dashboardUiStateStore.publishTab(tab, tabGeneration, state, dataRefreshed = tabDue)
                            reconcileActionUiState(state)
                        }
                        .onFailure { error ->
                            dashboardUiStateStore.failTab(tab, tabGeneration, dashboardErrorDetail(error))
                            recordDashboardRefreshFailure("tab=$tab", error)
                        }
                }
                if (chromeGeneration != null && chromeResult != null) {
                    chromeResult
                        .onSuccess { state ->
                            dashboardUiStateStore.publishChrome(
                                chromeGeneration, state,
                                dataRefreshed = chromeDue || (tabDue && profile?.healthDetail != null)
                            )
                            reconcileActionUiState(state)
                        }
                        .onFailure { error ->
                            dashboardUiStateStore.failChrome(chromeGeneration, dashboardErrorDetail(error))
                            recordDashboardRefreshFailure("chrome", error)
                        }
                }
                reconcileCutoverArchiveStorageIfNeeded()
                if (forcedRefreshPending && !destroyed) {
                    forcedRefreshPending = false
                    refresh(force = true)
                }
            }
        }
    }

    private fun recordDashboardRefreshFailure(scope: String, error: Throwable) {
        val detail = dashboardErrorDetail(error)
        Log.e(TAG, "Dashboard refresh failed: $scope", error)
        runCatching {
            recordOperationalEvent(
                "dashboard_refresh_failed",
                "Dashboard refresh failed",
                "$scope $detail"
            )
        }.onFailure { eventError ->
            Log.e(TAG, "Dashboard refresh failure could not be recorded", eventError)
        }
    }

    private fun reconcileActionUiState(state: DashboardState) {
        val archiveJob = state.archiveStorageJobStatus
        val dispatchedAtMs = archiveDeleteDispatchStartedAtMs
        if (
            actionUiState.archiveDeleteDispatch &&
            dispatchedAtMs != null &&
            archiveJob.mode == ArchiveStorageJobMode.DELETE &&
            !archiveJob.running &&
            archiveJob.updatedAtMs >= dispatchedAtMs
        ) {
            archiveDeleteDispatchStartedAtMs = null
            actionUiState = actionUiState.copy(archiveDeleteDispatch = false)
            refresh()
        }
    }

    private fun updateKeepAliveSetting(
        operation: String,
        enabled: Boolean,
        applySetting: (Boolean) -> Unit
    ) {
        submitUiOwnerAction(operation, allowDuplicate = true, work = {
            applySetting(enabled)
            CollectorServiceController.reconcileKeepAlive(applicationContext)
        }, onSuccess = { refresh() })
    }

    private fun maybeRunStartupSetup(): Boolean {
        val prefs = getSharedPreferences(STARTUP_SETUP_PREFS, MODE_PRIVATE)
        //runs the byd background-app prompt once per app version because dilink may reset this after updates
        if (prefs.getBoolean(KEY_BACKGROUND_SETTINGS_PENDING_RETURN, false)) {
            prefs.edit()
                .putBoolean(KEY_BACKGROUND_SETTINGS_PENDING_RETURN, false)
                .putInt(KEY_BACKGROUND_SETTINGS_VERSION, BuildConfig.VERSION_CODE)
                .apply()
            recordOperationalEvent(
                "startup_background_settings_returned",
                "Returned from background settings",
                "version=${BuildConfig.VERSION_CODE}"
            )
            recordOperationalEvent(
                "adb_authorization_ready_after_background",
                "Background setup completed; ADB self-check may continue"
            )
            startupAdbSelfCheckSource = "after_background"
            return false
        }

        val checkedVersion = prefs.getInt(KEY_BACKGROUND_SETTINGS_VERSION, -1)
        if (checkedVersion == BuildConfig.VERSION_CODE) return false
        if (startupBackgroundLaunchPosted) return true

        startupBackgroundLaunchPosted = true
        recordOperationalEvent(
            "startup_background_check_required",
            "Showing BYD background settings prompt for this app version",
            "checked_version=$checkedVersion current_version=${BuildConfig.VERSION_CODE}"
        )
        showBackgroundSetupPrompt(autoLaunch = true)
        return true
    }

    private fun showBackgroundSetupPrompt(autoLaunch: Boolean) {
        if (BuildConfig.RUNTIME_CLIENT) {
            RuntimeClient.controlAsync("accessFlow", Bundle().apply { putString("request", "background") })
            return
        }
        backgroundSetupPromptAutoLaunch = autoLaunch
        backgroundSetupPromptVisible = true
    }

    private fun onOpenBackgroundSettingsFromPrompt() {
        val autoLaunch = backgroundSetupPromptAutoLaunch
        backgroundSetupPromptVisible = false
        backgroundSetupPromptAutoLaunch = false
        val opened = openBackgroundSettings(autoLaunch = autoLaunch)
        if (!opened && autoLaunch) {
            markStartupBackgroundSetupHandled(
                eventKey = "startup_background_settings_unavailable",
                message = "Background settings could not be opened from setup prompt"
            )
            maybeContinueStartupAccessFlow()
        }
    }

    private fun onDismissBackgroundSetupPrompt() {
        val autoLaunch = backgroundSetupPromptAutoLaunch
        backgroundSetupPromptVisible = false
        backgroundSetupPromptAutoLaunch = false
        if (autoLaunch) {
            markStartupBackgroundSetupHandled(
                eventKey = "startup_background_settings_prompt_dismissed",
                message = "Background settings setup prompt dismissed"
            )
        }
        maybeContinueStartupAccessFlow()
    }

    private fun markStartupBackgroundSetupHandled(eventKey: String, message: String) {
        getSharedPreferences(STARTUP_SETUP_PREFS, MODE_PRIVATE)
            .edit()
            .putInt(KEY_BACKGROUND_SETTINGS_VERSION, BuildConfig.VERSION_CODE)
            .remove(KEY_BACKGROUND_SETTINGS_PENDING_RETURN)
            .apply()
        recordOperationalEvent(
            eventKey,
            message,
            "version=${BuildConfig.VERSION_CODE}"
        )
    }

    private fun openBackgroundSettings(autoLaunch: Boolean): Boolean {
        //tries the byd-specific settings screen first, then falls back to generic android settings
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).apply {
                setClassName(
                    "com.byd.appstartmanagement",
                    "com.byd.appstartmanagement.frame.AppStartManagement"
                )
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${if (BuildConfig.RUNTIME_CLIENT) RuntimeEndpoint.OWNER_PACKAGE else packageName}")
            },
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in intents) {
            val opened = runCatching {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) {
                if (autoLaunch) {
                    getSharedPreferences(STARTUP_SETUP_PREFS, MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_BACKGROUND_SETTINGS_PENDING_RETURN, true)
                        .apply()
                    recordOperationalEvent(
                        "startup_background_settings_opened",
                        "Background settings opened automatically",
                        "version=${BuildConfig.VERSION_CODE}"
                    )
                }
                return true
            }
        }

        Toast.makeText(
            this,
            strings(uiLanguage).backgroundSetupBody,
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    private fun requestAdbAuthorizationFlow(source: String): Boolean {
        if (BuildConfig.RUNTIME_CLIENT) {
            actionUiState = actionUiState.copy(adbGrant = false)
            RuntimeClient.controlAsync("accessFlow", Bundle().apply { putString("request", "adb") })
            return true
        }
        recordOperationalEvent(
            "adb_authorization_flow_started",
            "Starting local ADB RSA authorization request",
            "source=$source"
        )
        val submitted = requestAccessCheck(
            source,
            AccessCheckMode.FORCE,
            afterComplete = {
                actionUiState = actionUiState.copy(adbGrant = false)
                completeStartupAccessFlow()
            },
            onTerminal = { actionUiState = actionUiState.copy(adbGrant = false) }
        )
        if (!submitted) actionUiState = actionUiState.copy(adbGrant = false)
        return submitted
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_PERMISSION_REQUEST_CODE) return
        runtimePermissionRequestInFlight = false
        val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        recordOperationalEvent(
            "startup_location_permission_result",
            if (granted) "Startup location permission granted" else "Startup location permission denied"
        )
        refresh()
        maybeContinueStartupAccessFlow()
    }

    private fun maybeContinueStartupAccessFlow() {
        if (BuildConfig.RUNTIME_CLIENT) return // The owner serializes all access before opening this UI.
        if (destroyed || startupAccessFlowCompleted) return
        if (startupAdbSelfCheckPosted) return
        if (startupHardFlowBlocked()) return
        if (maybeRunStartupSetup()) return
        if (maybeRunStartupLocationPermission()) return
        if (maybeRequestOverlayAccess(userInitiated = false)) return
        if (startupHardFlowBlocked()) return
        maybeRunStartupAdbSelfCheck(startupAdbSelfCheckSource)
    }

    private fun maybeRequestOverlayAccess(userInitiated: Boolean): Boolean {
        if (destroyed || !foreground || !mainWindowHasFocus || !settings.isUpdateHintEnabled()) return false
        if (BuildConfig.RUNTIME_CLIENT) {
            if (!userInitiated || RuntimeClient.state.getBoolean("overlay")) return false
            RuntimeClient.controlAsync("accessFlow", Bundle().apply { putString("request", "overlay") })
            return true
        }
        if (runtimePermissionRequestInFlight) return true
        if ((if (BuildConfig.RUNTIME_CLIENT) RuntimeClient.state.getBoolean("overlay") else Settings.canDrawOverlays(this))) return false
        val prefs = getSharedPreferences(STARTUP_SETUP_PREFS, MODE_PRIVATE)
        if (!userInitiated && prefs.getBoolean(KEY_OVERLAY_PERMISSION_SETUP_CONSUMED, false)) return false
        // Persist before opening Settings so denial/recreation is not another prompt.
        prefs.edit().putBoolean(KEY_OVERLAY_PERMISSION_SETUP_CONSUMED, true).apply()
        runtimePermissionRequestInFlight = true
        return try {
            overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${if (BuildConfig.RUNTIME_CLIENT) RuntimeEndpoint.OWNER_PACKAGE else packageName}")))
            recordUpdateEvent("overlay_permission_requested", "source=${if (userInitiated) "toggle" else "visible_startup"}")
            true
        } catch (error: RuntimeException) {
            runtimePermissionRequestInFlight = false
            recordUpdateEvent("overlay_permission_unavailable", error::class.java.simpleName)
            false // Optional access cannot block the required access/collection flow.
        }
    }

    private fun maybeRunStartupLocationPermission(): Boolean {
        if (BuildConfig.RUNTIME_CLIENT) return false // The owner launcher requests its own location permission.
        val prefs = getSharedPreferences(STARTUP_SETUP_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LOCATION_PERMISSION_SETUP_CONSUMED, false)) return false
        if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean(KEY_LOCATION_PERMISSION_SETUP_CONSUMED, true).commit()
            recordOperationalEvent(
                "startup_location_permission_already_granted",
                "Startup location permission already granted"
            )
            return false
        }
        if (runtimePermissionRequestInFlight) return true
        prefs.edit().putBoolean(KEY_LOCATION_PERMISSION_SETUP_CONSUMED, true).commit()
        runtimePermissionRequestInFlight = true
        recordOperationalEvent(
            "startup_location_permission_requested",
            "Requesting startup fine and coarse location permissions"
        )
        runCatching {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }.onFailure { error ->
            runtimePermissionRequestInFlight = false
            recordOperationalEvent(
                "startup_location_permission_request_failed",
                "Startup location permission request failed",
                error::class.java.simpleName
            )
            refresh()
            maybeContinueStartupAccessFlow()
        }
        return true
    }

    private fun startupHardFlowBlocked(): Boolean {
        if (settings.isUserShutdownRequested() || shutdownReopenInFlight) return true
        return startupHardFlowBlocked(
            foreground = foreground,
            windowFocused = mainWindowHasFocus,
            backgroundPromptVisible = backgroundSetupPromptVisible,
            runtimePermissionRequestInFlight = runtimePermissionRequestInFlight
        )
    }

    private fun maybeRunStartupAdbSelfCheck(source: String) {
        if (startupAdbSelfCheckPosted) return
        startupAdbSelfCheckPosted = true
        startupAdbSelfCheckSource = source
        handler.removeCallbacks(startupAdbSelfCheckTask)
        handler.postDelayed(startupAdbSelfCheckTask, STARTUP_ADB_SELF_CHECK_DELAY_MS)
    }

    private fun runStartupAdbSelfCheckIfReady() {
        if (destroyed || startupAccessFlowCompleted) return
        if (startupHardFlowBlocked()) {
            startupAdbSelfCheckPosted = false
            return
        }
        recordOperationalEvent(
            "startup_adb_self_check_started",
            "Starting startup ADB authorization self-check",
            "source=$startupAdbSelfCheckSource mode=${AccessCheckMode.COLD_START.name.lowercase()}"
        )
        val submitted = requestAccessCheck(
            source = startupAdbSelfCheckSource,
            mode = AccessCheckMode.COLD_START,
            afterComplete = ::completeStartupAccessFlow
        )
        if (!submitted) startupAdbSelfCheckPosted = false
    }

    private fun requestAccessCheck(
        source: String,
        mode: AccessCheckMode,
        afterComplete: (() -> Unit)? = null,
        onTerminal: (() -> Unit)? = null
    ): Boolean {
        if (BuildConfig.RUNTIME_CLIENT) {
            return submitRuntimeJob(
                "access",
                JSONObject().put("source", source).put("mode", mode.name),
                onSubmitted = { id ->
                    runtimeJobCallbacks[id] = { success ->
                        if (success) afterComplete?.invoke()
                        onTerminal?.invoke()
                    }
                    accessJobSubmissionsPending = (accessJobSubmissionsPending - 1).coerceAtLeast(0)
                    refresh()
                },
                onFailure = { error ->
                    showOperationFailure("access", error)
                    onTerminal?.invoke()
                }
            )
        }
        return AdbAuthorizationManager.request(
            context = applicationContext,
            store = currentStore(),
            source = source,
            mode = mode,
            helperOwnerMode = settings.mainHelperOwnerMode(),
            onComplete = {
                handler.post {
                    if (!destroyed) {
                        refresh()
                        afterComplete?.invoke()
                    }
                }
            },
            onTerminal = {
                handler.post {
                    if (!destroyed) onTerminal?.invoke()
                }
            }
        )
    }

    private fun completeStartupAccessFlow() {
        if (startupAccessFlowCompleted) return
        startupAccessFlowCompleted = true
    }

    private fun runUpdateCheck(force: Boolean) {
        if (destroyed || !foreground || updateUiState is UpdateUiState.Downloading ||
            updateUiState is UpdateUiState.Cancelling || updateUiState is UpdateUiState.Cancelled ||
            updateUiState is UpdateUiState.CancelError) return
        if (BuildConfig.RUNTIME_CLIENT) {
            submitUiOwnerAction("updateCheck", failureKind = "updateCheck",
                work = { updateRuntime.request(manual = force) },
                onSuccess = { accepted ->
                    if (accepted) updateUiGeneration += 1L
                    syncUpdateCheckUi()
                })
            return
        }
        val accepted = updateRuntime.request(manual = force)
        if (accepted) {
            updateUiGeneration += 1L
        }
        syncUpdateCheckUi()
    }

    private fun syncUpdateCheckUi() {
        if (destroyed || !updateRuntime.ownUiVisible) return
        val snapshot = updateChecks.snapshot()
        if (snapshot.revision == updatePresentationRevision) return
        updatePresentationRevision = snapshot.revision
        updateOfferResultId = snapshot.availableResultId
        // The native job owns its modal after an offer has become a download.
        if (actionUiState.isPending("updateInstall") || (updateDownloadJobId != null && (updateUiState is UpdateUiState.Downloading ||
                updateUiState is UpdateUiState.Cancelling || updateUiState is UpdateUiState.Cancelled ||
                updateUiState is UpdateUiState.CancelError))) return
        updateUiState = snapshot.uiState
        (updateUiState as? UpdateUiState.Available)?.let { releaseNotesHistory.request(it.info) }
        syncReleaseNotesUi()
    }

    private fun syncReleaseNotesUi() {
        if (destroyed) return
        val info = when (val state = updateUiState) {
            is UpdateUiState.Available -> state.info
            is UpdateUiState.Downloading -> state.info
            is UpdateUiState.Cancelling -> state.info
            is UpdateUiState.Cancelled -> state.info
            is UpdateUiState.CancelError -> state.info
            else -> null
        }
        updateReleaseNotes = info?.let(releaseNotesHistory::snapshot)
    }

    private fun recordUpdateEvent(message: String, detail: String? = null) {
        (applicationContext as BydCollectorApplication).recordUpdateEvent(message, detail)
    }

    private fun startUpdateDownload(info: UpdateInfo) {
        if (!BuildConfig.RUNTIME_CLIENT) {
            updateRuntime.onInstallStarted()
            updateChecks.clearPresentation()
            updatePresentationRevision = updateChecks.snapshot().revision
        }
        updateUiState = UpdateUiState.Downloading(info, 0)
        syncReleaseNotesUi()
        val requestGeneration = updateUiGeneration
        val context = applicationContext
        val args = JSONObject().put("version", info.version).put("url", info.downloadUrl)
            .put("notes", info.releaseNotes).put("contentType", info.downloadContentType)
        submitUiOwnerAction("updateInstall", failureKind = "updateDownload", work = {
            if (BuildConfig.RUNTIME_CLIENT) {
                updateRuntime.onInstallStarted()
                RuntimeClient.control("updateClear")
            }
            try {
                if (BuildConfig.RUNTIME_CLIENT) {
                    RuntimeEndpoint.call(context, "job.submit", Bundle().apply {
                        putString("kind", "updateDownload")
                        putString("args", args.toString())
                    }).getString("id") ?: error("Runtime did not accept the update task")
                } else RuntimeJobsService.submit(context, "updateDownload", args)
            } catch (error: Throwable) {
                if (BuildConfig.RUNTIME_CLIENT) updateRuntime.onInstallFinished()
                throw error
            }
        }, onSuccess = { id ->
            updateDownloadJobId = id
            if (updateUiGeneration != requestGeneration) {
                persistDismissedUpdateJob(id)
                return@submitUiOwnerAction
            }
            dismissedUpdateJobs.remove(id)
            updateUiState = UpdateUiState.Downloading(info, 0)
            syncReleaseNotesUi()
            refresh()
        }, onFailure = { error ->
            if (!BuildConfig.RUNTIME_CLIENT) updateRuntime.onInstallFinished()
            if (updateUiGeneration == requestGeneration) {
                updateUiState = UpdateUiState.Error(error.message ?: "Could not start the update download")
                syncReleaseNotesUi()
                showOperationFailure("updateDownload", error)
            }
        }, onSettled = { result ->
            result.getOrNull()?.let { id ->
                if (updateUiGeneration != requestGeneration) persistDismissedUpdateJob(id)
            }
        })
    }

    private fun loadCredentialsAfterFirstFrame() {
        if (credentialsLoadStarted) return
        credentialsLoadStarted = true
        val mqttRevision = mqttCredentialRevision
        val influxRevision = influxCredentialRevision
        val telegramRevision = telegramCredentialRevision
        val source = settings
        updateExecutor.execute {
            val result = runCatching {
                LoadedCredentials(
                    mqttUsername = source.mqttUsername(),
                    mqttPassword = source.mqttPassword(),
                    influxUsername = source.influxUsername(),
                    influxPassword = source.influxPassword(),
                    telegramBotToken = source.telegramBotToken()
                )
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                result
                    .onSuccess { loaded ->
                        credentialsLoaded = true
                        if (mqttRevision == mqttCredentialRevision) {
                            mqttDraft = mqttDraft.copy(
                                username = loaded.mqttUsername,
                                password = loaded.mqttPassword
                            )
                        }
                        if (influxRevision == influxCredentialRevision) {
                            influxDraft = influxDraft.copy(
                                username = loaded.influxUsername,
                                password = loaded.influxPassword
                            )
                        }
                        if (telegramRevision == telegramCredentialRevision) {
                            telegramUiState = telegramUiState.copy(
                                config = telegramUiState.config.copy(
                                    botToken = loaded.telegramBotToken,
                                    botTokenSet = loaded.telegramBotToken.isNotEmpty()
                                )
                            )
                        }
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Credential UI hydration failed", error)
                    }
            }
        }
    }

    private fun refreshMqttCleanupGroups() {
        val generation = ++mqttCleanupRequestGeneration
        // Saved configuration is already local; historical topics are a separate background read.
        mqttCleanupUiState = MqttCleanupUiState(loading = true,
            groups = listOf(MqttCleanupGroup.current(settings.mqttConfig(includeCredentials = false))))
        mqttCleanupExecutor.execute {
            val result = runCatching { RuntimeReads.read<List<MqttCleanupGroup>>(this, "mqttCleanupGroups") }
            runOnUiThread {
                if (destroyed || generation != mqttCleanupRequestGeneration) return@runOnUiThread
                mqttCleanupUiState = result.fold(
                    onSuccess = { MqttCleanupUiState(groups = it) },
                    onFailure = {
                        recordOperationalEvent("mqtt_cleanup_list_failed", "Could not load MQTT cleanup groups", it.diagnosticDetail())
                        mqttCleanupUiState.copy(loading = false, error = it.message.orEmpty())
                    }
                )
            }
        }
    }

    private fun saveMqttDraft(source: CollectorSettings, draft: MqttDraft, credentialsReady: Boolean) {
        check(!CollectorService.mqttConnection.owned) { "MQTT is already active" }
        // Empty password means retain the existing secret, not clear it.
        val config = source.mqttConfig(includeCredentials = false).copy(
            host = draft.host,
            port = draft.port.toIntOrNull() ?: 0,
            clientId = draft.clientId,
            topicPrefix = draft.topicPrefix,
            discoveryPrefix = draft.discoveryPrefix,
            deviceId = draft.deviceId,
            deviceIdentifier = draft.deviceIdentifier,
            deviceName = draft.deviceName,
            deviceModel = draft.deviceModel,
            alternativeHost = draft.alternativeHost,
            alternativePort = if (draft.alternativePort.isBlank()) null else draft.alternativePort.toIntOrNull() ?: 0
        )
        val usernameUpdate = if (credentialsReady || draft.username.isNotBlank()) draft.username else null
        check(source.saveMqttConfiguration(config, usernameUpdate, draft.password.takeIf(String::isNotBlank))) {
            "MQTT settings or credentials were not persisted"
        }
    }

    private fun validateMqttDraft(selected: HaEndpointProfile? = null): Boolean {
        if (selected == null && !HaMqttConfig.isValidDeviceId(mqttDraft.deviceId)) {
            Toast.makeText(this, strings(uiLanguage).haIdentity.invalidMqttDeviceId, Toast.LENGTH_LONG).show()
            return false
        }
        val valid = when (selected) {
            HaEndpointProfile.PRIMARY -> validEndpointDraft(mqttDraft.host, mqttDraft.port)
            HaEndpointProfile.ALTERNATIVE -> validEndpointDraft(mqttDraft.alternativeHost, mqttDraft.alternativePort)
            null -> validEndpointDraft(mqttDraft.host, mqttDraft.port) &&
                validEndpointDraft(mqttDraft.alternativeHost, mqttDraft.alternativePort, optional = true)
        }
        if (!valid) Toast.makeText(this, strings(uiLanguage).invalidEndpoint, Toast.LENGTH_LONG).show()
        return valid
    }

    private fun saveInfluxDraft(source: CollectorSettings, draft: InfluxDraft, credentialsReady: Boolean) {
        check(!CollectorService.influxConnection.owned) { "InfluxDB is already active" }
        //mirrors mqtt draft semantics so editing non-secret influx fields never clears the stored password
        source.setInfluxHost(draft.host)
        source.setInfluxPort(draft.port.toIntOrNull() ?: 0)
        source.setInfluxAlternativeHost(draft.alternativeHost)
        source.setInfluxAlternativePort(if (draft.alternativePort.isBlank()) null else draft.alternativePort.toIntOrNull() ?: 0)
        val usernameStored = if (credentialsReady || draft.username.isNotBlank()) {
            source.setInfluxUsername(draft.username)
        } else {
            true
        }
        val passwordStored = draft.password.isBlank() || source.setInfluxPassword(draft.password)
        source.setInfluxDatabase(draft.database)
        source.setInfluxMeasurement(draft.measurement)
        source.setInfluxVehicleId(draft.vehicleId)
        check(usernameStored && passwordStored) { "InfluxDB credentials were not persisted" }
    }

    private fun validateInfluxDraft(selected: HaEndpointProfile? = null): Boolean {
        if (selected == null && !InfluxConfig.isValidVehicleId(influxDraft.vehicleId)) {
            Toast.makeText(this, strings(uiLanguage).haIdentity.invalidInfluxVehicleId, Toast.LENGTH_LONG).show()
            return false
        }
        val valid = when (selected) {
            HaEndpointProfile.PRIMARY -> validEndpointDraft(influxDraft.host, influxDraft.port)
            HaEndpointProfile.ALTERNATIVE -> validEndpointDraft(influxDraft.alternativeHost, influxDraft.alternativePort)
            null -> validEndpointDraft(influxDraft.host, influxDraft.port) &&
                validEndpointDraft(influxDraft.alternativeHost, influxDraft.alternativePort, optional = true)
        }
        if (!valid) Toast.makeText(this, strings(uiLanguage).invalidEndpoint, Toast.LENGTH_LONG).show()
        return valid
    }

    private fun loadTelegramUiState(
        settings: CollectorSettings = this.settings,
        language: UiLanguage = uiLanguage,
        includeTokenState: Boolean = false
    ): TelegramUiState {
        val localizedMessages = strings(language).telegram.messages
        val botToken = ""
        val messages = TelegramMessageType.entries.associateWith { type ->
            val eventKey = type.eventKey()
            val savedTemplate = settings.telegramTemplate(eventKey)
            TelegramMessageConfig(
                enabled = settings.isTelegramEventEnabled(eventKey),
                template = savedTemplate ?: localizedMessages.getValue(type).defaultTemplate,
                usesDefaultTemplate = savedTemplate == null
            )
        }
        return TelegramUiState(
            config = TelegramConfig(
                enabled = settings.isTelegramEnabled(),
                botToken = botToken,
                botTokenSet = includeTokenState && settings.isTelegramBotTokenSet(),
                chatId = settings.telegramChatId(),
                chargeStepPercent = settings.telegramChargeStepPercent(),
                low12vThresholdVolts = settings.telegramLowVoltageThreshold(),
                telemetryUnavailableMinutes = settings.telegramUnavailableDelayMinutes(),
                tripSummaryDelaySeconds = settings.telegramTripEndDelaySeconds(),
                sendLocation = settings.isTelegramSendLocationEnabled(),
                navigatorMask = settings.telegramNavigatorMask(),
                messages = messages
            ),
            testStatus = telegramTestStatus(settings.telegramConnectionStatus()),
            tripTemplateLimitState = settings.telegramTripTemplateLimitState()
        )
    }

    private fun onTelegramConfigChanged(config: TelegramConfig) {
        refreshStoreBackedState()
        if (config.botToken != telegramUiState.config.botToken) telegramCredentialRevision += 1L
        val revision = ++telegramConfigRevision
        val source = settings
        val language = uiLanguage
        val captured = config.copy(messages = config.messages.toMap())
        telegramUiState = telegramUiState.copy(config = captured)
        submitUiOwnerAction("telegramSave", allowDuplicate = true, work = {
            persistTelegramConfig(source, captured, language)
        }, onSuccess = { saved ->
            if (revision == telegramConfigRevision) telegramUiState = saved
            refresh()
        })
    }

    private fun persistTelegramConfig(settings: CollectorSettings, config: TelegramConfig, language: UiLanguage): TelegramUiState {
        val previousState = loadTelegramUiState(settings, language, includeTokenState = true)
        val previous = previousState.config.copy(botToken = settings.telegramBotToken())
        val previousTrip = previous.messages[TelegramMessageType.TRIP_SUMMARY]
        val nextTrip = config.messages[TelegramMessageType.TRIP_SUMMARY]
        val tripLimitInputsChanged = previous.sendLocation != config.sendLocation ||
            previous.navigatorMask != config.navigatorMask ||
            previousTrip?.template != nextTrip?.template ||
            previousTrip?.usesDefaultTemplate != nextTrip?.usesDefaultTemplate
        var tokenSet = previous.botTokenSet
        var secretWriteFailed = false

        if (previous.enabled != config.enabled) settings.setTelegramEnabled(config.enabled)
        if (previous.chatId != config.chatId) settings.setTelegramChatId(config.chatId)
        if (config.botToken.isNotBlank() && config.botToken != previous.botToken) {
            tokenSet = settings.setTelegramBotToken(config.botToken)
            secretWriteFailed = !tokenSet
        }
        if (previous.chargeStepPercent != config.chargeStepPercent) {
            settings.setTelegramChargeStepPercent(config.chargeStepPercent)
        }
        if (previous.low12vThresholdVolts != config.low12vThresholdVolts) {
            settings.setTelegramLowVoltageThreshold(config.low12vThresholdVolts)
        }
        if (previous.telemetryUnavailableMinutes != config.telemetryUnavailableMinutes) {
            settings.setTelegramUnavailableDelayMinutes(config.telemetryUnavailableMinutes)
        }
        if (previous.tripSummaryDelaySeconds != config.tripSummaryDelaySeconds) {
            settings.setTelegramTripEndDelaySeconds(config.tripSummaryDelaySeconds)
        }
        if (previous.sendLocation != config.sendLocation) {
            settings.setTelegramSendLocationEnabled(config.sendLocation)
        }
        if (previous.navigatorMask != config.navigatorMask) {
            settings.setTelegramNavigatorMask(config.navigatorMask)
        }
        TelegramMessageType.entries.forEach { type ->
            val oldMessage = previous.messages[type]
            val newMessage = config.messages[type] ?: return@forEach
            val eventKey = type.eventKey()
            if (oldMessage?.enabled != newMessage.enabled) {
                settings.setTelegramEventEnabled(eventKey, newMessage.enabled)
            }
            if (newMessage.usesDefaultTemplate) {
                if (settings.telegramTemplate(eventKey) != null) settings.clearTelegramTemplate(eventKey)
            } else if (oldMessage?.template != newMessage.template || settings.telegramTemplate(eventKey) == null) {
                settings.setTelegramTemplate(eventKey, newMessage.template)
            }
        }
        if (tripLimitInputsChanged) {
            settings.resetTelegramTripTemplateLimitState()
        }

        val credentialsChanged = previous.chatId != config.chatId ||
            (config.botToken.isNotBlank() && config.botToken != previous.botToken)
        val nextTestStatus = when {
            secretWriteFailed -> TelegramTestStatus.STORAGE_ERROR
            credentialsChanged -> TelegramTestStatus.NOT_TESTED
            else -> previousState.testStatus
        }
        if (secretWriteFailed) {
            settings.setTelegramConnectionStatus("storage_error", "keystore")
            settings.setTelegramEnabled(false)
        } else if (credentialsChanged) {
            settings.setTelegramConnectionStatus("not_tested", null)
        }
        val effectiveConfig = config.copy(
            enabled = if (secretWriteFailed) false else config.enabled,
            botToken = if (secretWriteFailed) settings.telegramBotToken() else config.botToken,
            botTokenSet = if (secretWriteFailed) settings.isTelegramBotTokenSet() else tokenSet
        )
        val saved = previousState.copy(
            config = effectiveConfig,
            testStatus = nextTestStatus,
            tripTemplateLimitState = if (tripLimitInputsChanged) {
                TelegramPayloadLimitState.NONE
            } else {
                previousState.tripTemplateLimitState
            }
        )
        if (secretWriteFailed) {
            recordOperationalEvent(
                "telegram_secret_write_failed",
                "Telegram bot token could not be stored in Android Keystore"
            )
        }

        val enabledChanged = previous.enabled != effectiveConfig.enabled
        if (enabledChanged || secretWriteFailed || (credentialsChanged && effectiveConfig.enabled)) {
            CollectorServiceController.reconcileTelegram(applicationContext)
        }
        return saved
    }

    private fun onClearTelegramBotToken() {
        refreshStoreBackedState()
        telegramCredentialRevision += 1L
        val revision = ++telegramConfigRevision
        val source = settings
        submitUiOwnerAction("telegramClear", work = {
            val cleared = source.clearTelegramBotToken()
            if (!cleared) source.setTelegramEnabled(false)
            val persistedToken = source.telegramBotToken()
            source.setTelegramConnectionStatus(if (cleared) "not_tested" else "storage_error", if (cleared) null else "clear_failed")
            if (!cleared) recordOperationalEvent("telegram_secret_clear_failed", "Telegram bot token could not be cleared from Android Keystore")
            CollectorServiceController.reconcileTelegram(applicationContext)
            Triple(persistedToken, source.isTelegramEnabled(), cleared)
        }, onSuccess = { (token, enabled, cleared) ->
            if (revision == telegramConfigRevision) telegramUiState = telegramUiState.copy(
                config = telegramUiState.config.copy(enabled = enabled, botToken = token, botTokenSet = token.isNotEmpty()),
                testStatus = if (cleared) TelegramTestStatus.NOT_TESTED else TelegramTestStatus.STORAGE_ERROR
            )
        })
    }

    private fun onTestTelegramConnection() {
        refreshStoreBackedState()
        telegramUiState = telegramUiState.copy(testStatus = TelegramTestStatus.TESTING)
        val source = settings
        submitUiOwnerAction("telegramTest", work = {
            if (!source.isTelegramBotTokenSet()) {
                source.setTelegramConnectionStatus("storage_error", "missing_persisted_token")
            } else {
                source.setTelegramConnectionStatus("testing", null)
                try { CollectorServiceController.testTelegram(applicationContext) }
                catch (error: Exception) {
                    source.setTelegramConnectionStatus("failed", error.message ?: error::class.java.simpleName)
                    throw error
                }
            }
        }, onSuccess = { syncTelegramUiRuntimeState() }, onFailure = { error ->
            telegramUiState = telegramUiState.copy(testStatus = TelegramTestStatus.FAILED)
            showOperationFailure("telegramTest", error)
        })
    }

    private fun syncTelegramUiRuntimeState() {
        if (telegramUiState.config.messages.isEmpty()) return
        if (actionUiState.pendingOperations.any { it in setOf("telegramSave", "telegramClear", "telegramTest") }) return
        telegramUiState = telegramUiState.copy(
            config = telegramUiState.config.copy(
                enabled = settings.isTelegramEnabled()
            ),
            testStatus = telegramTestStatus(settings.telegramConnectionStatus()),
            tripTemplateLimitState = settings.telegramTripTemplateLimitState()
        )
    }

    private fun currentMaintenanceUiState(state: DashboardState? = dashboardUiStateStore.currentChrome()): DbMaintenanceUiState? {
        val runtime = state?.dbMaintenanceStatus
        val runtimeOperation = runtime?.operation
        if (runtimeOperation != null && (runtime.running || runtime.completed || runtime.error != null)) {
            if (runtime.completed || runtime.error != null) {
                maintenanceLaunchOperation = null
            }
            return DbMaintenanceUiState(
                operation = runtimeOperation,
                running = runtime.running,
                completed = runtime.completed,
                stepIndex = runtime.stepIndex,
                stepCount = runtime.stepCount.takeIf { it > 0 } ?: runtimeOperation.stepsUk.size,
                messageUk = runtime.messageUk,
                messageEn = runtime.messageEn,
                error = runtime.error,
                warning = runtime.warning,
                archivePath = runtime.archivePath,
                cancelAvailable = runtime.cancelAvailable
            )
        }
        maintenanceLaunchOperation?.let { operation ->
            return DbMaintenanceUiState(
                operation = operation,
                running = true,
                completed = false,
                stepIndex = 1,
                stepCount = operation.stepsUk.size,
                messageUk = operation.stepsUk.first(),
                messageEn = operation.stepsEn.first()
            )
        }
        return pendingMaintenanceOperation?.let { operation ->
            DbMaintenanceUiState(
                operation = operation,
                mainArchivePreflight = pendingMainArchivePreflight.takeIf {
                    operation == DbMaintenanceOperation.ARCHIVE
                }
            )
        }
    }

    private fun startLogcatRecording() {
        if (BuildConfig.RUNTIME_CLIENT) { submitRuntimeJob("logStart"); return }
        if (diagnosticsBusy || DiagnosticLogRecorder.shareStage.value != null) return
        refreshStoreBackedState()
        diagnosticsBusy = true
        val submitted = requestAccessCheck(
            source = "start_logcat",
            mode = AccessCheckMode.NORMAL,
            afterComplete = {
                if (!AdbAuthorizationManager.currentSnapshot().adbAuthorized) {
                    recordOperationalEvent("log_recording_error", "Full system logcat authorization failed", "adb_not_authorized")
                    diagnosticsBusy = false
                    Toast.makeText(this, operationFailureMessage(strings(uiLanguage), "logStart", "adb_not_authorized"), Toast.LENGTH_LONG).show()
                    refresh()
                } else {
                    val task = Runnable {
                        val result = runCatching { DiagnosticLogRecorder.start(applicationContext) }
                        handler.post {
                            if (destroyed) return@post
                            result.onSuccess { dir ->
                                recordOperationalEvent("log_recording_started", "Full system logcat recording started", "source=logcat path=${dir.absolutePath}")
                                Toast.makeText(this, runtimeJobMessage(strings(uiLanguage), "logStart", true), Toast.LENGTH_SHORT).show()
                            }.onFailure { error ->
                                recordOperationalEvent("log_recording_error", "Full system logcat recording failed", error.message)
                                Toast.makeText(this, operationFailureMessage(strings(uiLanguage), "logStart", error.message.orEmpty()), Toast.LENGTH_LONG).show()
                            }
                            diagnosticsBusy = false
                            refresh()
                        }
                    }
                    runCatching { diagnosticsExecutor.execute(task) }.onFailure { error ->
                        diagnosticsBusy = false
                        recordOperationalEvent("log_recording_error", "Full system logcat start was rejected", error.message)
                        Toast.makeText(this, operationFailureMessage(strings(uiLanguage), "logStart", error.message.orEmpty()), Toast.LENGTH_LONG).show()
                        refresh()
                    }
                }
            },
            onTerminal = {
                diagnosticsBusy = false
                refresh()
            }
        )
        if (!submitted) {
            recordOperationalEvent("log_recording_error", "Full system logcat access check was not started", "adb_self_check_in_progress")
            diagnosticsBusy = false
            Toast.makeText(this, operationFailureMessage(strings(uiLanguage), "logStart", "Access check already running"), Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private fun stopLogcatRecording() {
        if (BuildConfig.RUNTIME_CLIENT) { submitRuntimeJob("logStop"); return }
        if (diagnosticsBusy || DiagnosticLogRecorder.shareStage.value != null) return
        refreshStoreBackedState()
        diagnosticsBusy = true
        val task = Runnable {
            val result = runCatching { DiagnosticLogRecorder.stop() }
            handler.post {
                if (destroyed) return@post
                result.onSuccess { dir ->
                    recordOperationalEvent("log_recording_stopped", "Full system logcat recording stopped", "source=logcat path=${dir?.absolutePath}")
                    Toast.makeText(this, runtimeJobMessage(strings(uiLanguage), "logStop", true), Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    recordOperationalEvent("log_recording_error", "Full system logcat stop failed", error.message)
                    Toast.makeText(this, operationFailureMessage(strings(uiLanguage), "logStop", error.message.orEmpty()), Toast.LENGTH_LONG).show()
                }
                diagnosticsBusy = false
                refresh()
            }
        }
        runCatching { diagnosticsExecutor.execute(task) }.onFailure { error ->
            diagnosticsBusy = false
            recordOperationalEvent("log_recording_error", "Full system logcat stop was rejected", error.message)
            Toast.makeText(this, operationFailureMessage(strings(uiLanguage), "logStop", error.message.orEmpty()), Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private fun shareDiagnosticLogs() {
        if (BuildConfig.RUNTIME_CLIENT) { submitRuntimeJob("logShare"); return }
        if (diagnosticsBusy || DiagnosticLogRecorder.shareStage.value != null) return
        diagnosticsBusy = true
        val title = strings(uiLanguage).shareLogs
        val task = Runnable {
            val result = runCatching {
                val bundle = DiagnosticLogRecorder.prepareShareBundle(applicationContext)
                val appContext = applicationContext
                val uri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    bundle
                )
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(appContext.contentResolver, title, uri)
                }
                Triple(bundle, sendIntent, bundle.length())
            }
            handler.post {
                if (destroyed) {
                    diagnosticsBusy = false
                    return@post
                }
                result.onSuccess { (bundle, sendIntent, bundleSize) ->
                    runCatching { startActivity(Intent.createChooser(sendIntent, title)) }
                        .onSuccess {
                            recordOperationalEvent(
                                "diagnostic_share_chooser_opened",
                                "Diagnostic share chooser opened",
                                "file=${bundle.name} size=$bundleSize"
                            )
                        }
                        .onFailure { error ->
                            recordOperationalEvent("diagnostic_share_failed", "Diagnostic share failed", error.message)
                            Toast.makeText(this, strings(uiLanguage).logsShareFailed, Toast.LENGTH_LONG).show()
                        }
                }.onFailure { error ->
                    recordOperationalEvent("diagnostic_share_failed", "Diagnostic share failed", error.message)
                    Toast.makeText(this, strings(uiLanguage).logsShareFailed, Toast.LENGTH_LONG).show()
                }
                diagnosticsBusy = false
                refresh()
            }
        }
        runCatching { diagnosticsExecutor.execute(task) }.onFailure { error ->
            diagnosticsBusy = false
            recordOperationalEvent("diagnostic_share_failed", "Diagnostic share was rejected", error.message)
            Toast.makeText(this, strings(uiLanguage).logsShareFailed, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private fun clearDiagnosticLogs() {
        if (BuildConfig.RUNTIME_CLIENT) { submitRuntimeJob("logClear"); return }
        if (diagnosticsBusy || DiagnosticLogRecorder.shareStage.value != null) return
        diagnosticsBusy = true
        val task = Runnable {
            val result = runCatching { DiagnosticLogRecorder.clearCompleted(applicationContext) }
            handler.post {
                if (destroyed) {
                    diagnosticsBusy = false
                    return@post
                }
                result.onSuccess { clearResult ->
                    recordOperationalEvent(
                        "diagnostic_logs_cleared",
                        "Completed diagnostic logs cleared",
                        "removed=${clearResult.removed} active_recording=${DiagnosticLogRecorder.isRecording()} " +
                            "warnings=${clearResult.warnings.joinToString(" | ").ifBlank { "none" }}"
                    )
                    val message = if (clearResult.warnings.isEmpty()) {
                        strings(uiLanguage).logsCleared
                    } else {
                        strings(uiLanguage).logsPartiallyCleared
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    recordOperationalEvent("diagnostic_clear_failed", "Diagnostic log cleanup failed", error.message)
                    Toast.makeText(this, strings(uiLanguage).logsClearFailed, Toast.LENGTH_LONG).show()
                }
                diagnosticsBusy = false
                refresh()
            }
        }
        runCatching { diagnosticsExecutor.execute(task) }.onFailure { error ->
            diagnosticsBusy = false
            recordOperationalEvent("diagnostic_clear_failed", "Diagnostic cleanup was rejected", error.message)
            Toast.makeText(this, strings(uiLanguage).logsClearFailed, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    companion object {
        private const val TAG = "BYDCollectorUI"
        private const val STARTUP_SETUP_PREFS = "startup_setup"
        private const val KEY_OVERLAY_PERMISSION_SETUP_CONSUMED = "overlay_permission_setup_consumed"
        private const val KEY_BACKGROUND_SETTINGS_VERSION = "background_settings_version"
        private const val KEY_BACKGROUND_SETTINGS_PENDING_RETURN = "background_settings_pending_return"
        private const val KEY_LOCATION_PERMISSION_SETUP_CONSUMED = "location_permission_setup_consumed"
        private const val STARTUP_ADB_SELF_CHECK_DELAY_MS = 600L
        private const val LOCATION_PERMISSION_REQUEST_CODE = 4101
        private val processPresentedRuntimeJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val processPreparingRuntimeJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val processDismissedUpdateJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private var processUpdateDismissFailure: Throwable? = null
    }
}

internal const val DASHBOARD_REFRESH_HEARTBEAT_MS = 1_000L
internal const val CURRENT_TRIP_REFRESH_MS = 1_000L
//Chrome/status fields are producer-fed while the service runs. Activity entry, resume, tab changes,
//and explicit actions still force a reconciliation; the foreground heartbeat does not reread SQLite.
internal val DASHBOARD_CHROME_REFRESH_INTERVAL_MS: Long? = null

internal fun dashboardProfile(tab: AppTab): DashboardLoadProfile? = when (tab) {
    AppTab.MAIN -> DashboardLoadProfile.MAIN
    AppTab.ALL_PARAMETERS -> DashboardLoadProfile.ALL_PARAMETERS
    AppTab.TRIPS -> null
    AppTab.HA -> DashboardLoadProfile.HA
    AppTab.TELEGRAM -> null
    AppTab.STORAGE -> DashboardLoadProfile.STORAGE
    AppTab.EXTRA -> DashboardLoadProfile.EXTRA
}

internal fun dashboardTabRefreshIntervalMs(tab: AppTab, storageRefreshPending: Boolean): Long? = when (tab) {
    //Main/All/HA dynamic values are producer-fed into the process cache; entry/resume still force
    //one async reconciliation without polling SQLite on every foreground heartbeat.
    AppTab.MAIN -> null
    AppTab.ALL_PARAMETERS -> null
    AppTab.TRIPS -> null
    AppTab.HA -> null
    AppTab.TELEGRAM -> null
    AppTab.STORAGE -> if (storageRefreshPending) 1_000L else null
    AppTab.EXTRA -> null
}

internal fun dashboardSnapshotDue(
    loadedAtElapsedMs: Long?,
    intervalMs: Long?,
    nowMs: Long
): Boolean {
    if (intervalMs == null) return false
    if (loadedAtElapsedMs == null || loadedAtElapsedMs <= 0L || nowMs < loadedAtElapsedMs) return true
    return nowMs - loadedAtElapsedMs >= intervalMs
}

private fun dashboardErrorDetail(error: Throwable): String =
    "${error::class.java.simpleName}: ${error.message ?: "no message"}"

private fun UiLanguage.vehicleKpiLanguage(): VehicleKpiLanguage = when (this) {
    UiLanguage.UK -> VehicleKpiLanguage.UK
    UiLanguage.EN -> VehicleKpiLanguage.EN
}

private fun TelegramMessageType.eventKey(): String = when (this) {
    TelegramMessageType.CHARGING_STARTED -> "charging-started"
    TelegramMessageType.CHARGING_PROGRESS -> "charging-progress"
    TelegramMessageType.CHARGED_TO_100 -> "charged-full"
    TelegramMessageType.CHARGING_STOPPED -> "charging-stopped"
    TelegramMessageType.CHARGE_GUN_CONNECTED -> "charge-gun-connected"
    TelegramMessageType.CHARGE_GUN_DISCONNECTED -> "charge-gun-disconnected"
    TelegramMessageType.LOW_12V_VOLTAGE -> "low-12v"
    TelegramMessageType.TELEMETRY_UNAVAILABLE -> "telemetry-unavailable"
    TelegramMessageType.TRIP_SUMMARY -> "trip-summary"
}

private fun telegramTestStatus(value: String): TelegramTestStatus = when (value) {
    "testing" -> TelegramTestStatus.TESTING
    "success" -> TelegramTestStatus.SUCCESS
    "storage_error" -> TelegramTestStatus.STORAGE_ERROR
    "failed" -> TelegramTestStatus.FAILED
    else -> TelegramTestStatus.NOT_TESTED
}

private data class LoadedCredentials(
    val mqttUsername: String,
    val mqttPassword: String,
    val influxUsername: String,
    val influxPassword: String,
    val telegramBotToken: String
)

private data class TripsLoadResult(
    val years: List<TripYearUi>,
    val refreshedRouteId: String?,
    val databasePath: String,
    val databaseSizeBytes: Long,
    val availableCurrentTrip: CurrentTripUi?
)

private data class CurrentTripRead(
    val session: TripSession,
    val firstSequence: Long,
    val routeTail: List<RoutePoint>,
    val currentBootId: String?,
    val readElapsedMs: Long,
    val readWallTimeMs: Long
)

internal fun startupHardFlowBlocked(
    foreground: Boolean,
    windowFocused: Boolean,
    backgroundPromptVisible: Boolean,
    runtimePermissionRequestInFlight: Boolean
): Boolean = !foreground ||
    !windowFocused ||
    backgroundPromptVisible ||
    runtimePermissionRequestInFlight
