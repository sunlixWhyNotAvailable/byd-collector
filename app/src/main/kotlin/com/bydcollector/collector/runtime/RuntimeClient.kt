package com.bydcollector.collector.runtime

import android.content.Context
import android.os.Bundle
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.DatabaseMaintenanceService
import com.bydcollector.collector.ha.HaEndpointProfile
import com.bydcollector.collector.ha.HaConnectionState
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.update.*
import com.bydcollector.collector.service.TripCompressionService
import com.bydcollector.collector.diagnostics.DiagnosticLogRecorder
import com.bydcollector.collector.diagnostics.DiagnosticShareStage

/** Process-local display mirror; it owns no collection/export executor or run intent. */
internal object RuntimeClient {
    lateinit var context: Context
        private set
    @Volatile var state: Bundle = Bundle()
        private set
    @Volatile var update = UpdateCheckSession.Snapshot(UpdateUiState.Hidden, false, -1, null)
        private set
    @Volatile var history: ReleaseNotesHistory? = null
        private set
    @Volatile var historyTarget: UpdateInfo? = null
        private set
    private val token = android.os.Binder()
    private var uiVisible = false
    private var kpiVisible = false
    private val lifecycleExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "collector-ui-lifecycle")
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Acknowledged UI operations share one process-owned lane, including across Activity recreation. */
    fun <T> executeOwnerAction(work: () -> T, complete: (Result<T>) -> Unit) {
        lifecycleExecutor.execute {
            val result = runCatching(work)
            main.post { complete(result) }
        }
    }
    private val visibilityDelivery = RuntimeVisibilityDelivery(
        schedule = { task, delay -> lifecycleExecutor.schedule(task, delay, java.util.concurrent.TimeUnit.MILLISECONDS); Unit },
        deliver = { ui, kpi -> control("visibility", Bundle().apply {
            putBinder("token", token); putBoolean("ui", ui); putBoolean("kpi", kpi)
        }); Unit },
        canRetry = { !com.bydcollector.collector.service.CollectorSettings(context).isUserShutdownRequested() },
        onFailure = { recordControlFailure("visibility", it) }
    )

    fun controlAsync(operation: String, args: Bundle = Bundle(), onSuccess: (Bundle) -> Unit = {}) {
        lifecycleExecutor.execute {
            runCatching { control(operation, args) }
                .onSuccess(onSuccess)
                .onFailure { recordControlFailure(operation, it) }
        }
    }

    private fun recordControlFailure(operation: String, error: Throwable) {
        android.util.Log.e("CollectorRuntime", "UI lifecycle IPC failed: $operation", error)
        (context as BydCollectorApplication).recordUpdateEvent("ui_lifecycle_ipc_failed",
            "operation=$operation error=${error::class.java.simpleName}: ${error.message}")
    }

    fun control(operation: String, args: Bundle = Bundle()): Bundle {
        args.putString("operation", operation)
        return RuntimeEndpoint.call(context, "control", args)
    }

    fun visibility(ui: Boolean = uiVisible, kpi: Boolean = kpiVisible) {
        uiVisible = ui
        kpiVisible = kpi
        visibilityDelivery.update(ui, kpi)
    }

    fun initialize(context: Context) {
        check(BuildConfig.RUNTIME_CLIENT)
        this.context = context.applicationContext
    }

    fun refresh() {
        val next = RuntimeEndpoint.call(context, "state")
        state = next
        CollectorService.mqttConnection.displaySnapshot(connection(next, "mqtt"))
        CollectorService.influxConnection.displaySnapshot(connection(next, "influx"))
        @Suppress("DEPRECATION")
        TripCompressionService.displaySnapshot(next.getSerializable("compression") as com.bydcollector.collector.service.TripCompressionState)
        DiagnosticLogRecorder.displayShareStage(next.getString("shareStage")?.let(DiagnosticShareStage::valueOf))
    }

    fun refreshUpdates() {
        val snapshot = RuntimeReads.read<RuntimeUpdateSnapshot>(context, "updates")
        update = snapshot.check
        historyTarget = snapshot.target
        history = snapshot.history
    }

    private fun connection(snapshot: Bundle, prefix: String) = HaConnectionState(
        owned = snapshot.getBoolean("${prefix}Owned"),
        stopping = snapshot.getBoolean("${prefix}Stopping"),
        activeRoute = snapshot.getString("${prefix}Route")?.let(HaEndpointProfile::valueOf)
    )

    fun snapshotOwner(app: BydCollectorApplication): Bundle = Bundle().apply {
        check(!BuildConfig.RUNTIME_CLIENT)
        putBoolean("running", CollectorService.isRunning())
        putBoolean("shutdown", CollectorService.isUserShutdownInProgress())
        putBoolean("main", CollectorService.isMainPollingRunning())
        putBoolean("debug", CollectorService.isDebugRunning())
        putBoolean("maintenance", CollectorService.isMaintenanceRunningInProcess() || DatabaseMaintenanceService.isRunning())
        putBoolean("archive", CollectorService.isArchiveStorageActive())
        putBoolean("accessPermissions", com.bydcollector.collector.adb.AdbAuthorizationManager.currentSnapshot().permissionsGranted)
        putBoolean("accessAdb", com.bydcollector.collector.adb.AdbAuthorizationManager.currentSnapshot().adbAuthorized)
        putBoolean("overlay", android.provider.Settings.canDrawOverlays(app))
        putBoolean("debugReady", app.isDebugStorageReady())
        putString("telegramStorageError", app.telegramStorageError())
        putString("tripPath", app.getDatabasePath(com.bydcollector.collector.data.trips.TripDatabaseHelper.DATABASE_NAME).absolutePath)
        putSerializable("compression", TripCompressionService.state.value)
        putString("shareStage", DiagnosticLogRecorder.shareStage.value?.name)
        putBoolean("recording", DiagnosticLogRecorder.isRecording())
        putString("mainStatus", CollectorService.mainRuntimeStatus().name)
        putString("debugStatus", CollectorService.debugRuntimeStatus().name)
        putString("mqttStatus", CollectorService.mqttRuntimeStatus().name)
        putString("influxStatus", CollectorService.influxRuntimeStatus().name)
        com.bydcollector.collector.ha.HaExportChannel.entries.forEach {
            putBoolean("manual${it.name}", com.bydcollector.collector.ha.HaRunSession.process.allows(it, false))
        }
        listOf("mqtt" to CollectorService.mqttConnection, "influx" to CollectorService.influxConnection).forEach { (name, owner) ->
            putBoolean("${name}Owned", owner.owned)
            putBoolean("${name}Stopping", owner.stopping)
            putString("${name}Route", owner.state.value.activeRoute?.name)
        }
    }
}

internal data class RuntimeUpdateSnapshot(val check: UpdateCheckSession.Snapshot, val target: UpdateInfo?,
    val history: ReleaseNotesHistory?) : java.io.Serializable
