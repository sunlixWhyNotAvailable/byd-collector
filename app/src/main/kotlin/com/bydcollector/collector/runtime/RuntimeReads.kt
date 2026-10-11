package com.bydcollector.collector.runtime

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.data.debug.DirectDebugDatabaseResolver
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.trips.TripDatabaseHelper
import com.bydcollector.collector.maintenance.StorageFormatCutoverCoordinator
import com.bydcollector.collector.mqtt.MqttCleanupCore
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.ui.*
import com.bydcollector.collector.util.sqliteFootprintBytes
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Read-only results use pipes, never the Binder transaction buffer for an entire route. */
internal object RuntimeReads {
    private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8)) { task ->
        Thread(task, "collector-ui-query").apply { isDaemon = true }
    }
    private val settingsWorker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8)) { task ->
        Thread(task, "collector-ui-settings").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var dashboard: DashboardStateProvider? = null
    private val snapshots = mutableMapOf<DashboardLoadProfile, DashboardState>()

    fun open(context: Context, operation: String, args: Bundle): ParcelFileDescriptor {
        require(operation in setOf("dashboard", "mainPreflight", "tripHierarchy", "tripSession",
            "openTrip", "tripRoute", "footprints", "updates", "jobs", "mqttCleanupGroups")) { "Unknown runtime query" }
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val timeout = Runnable { runCatching { pipe[1].closeWithError("Runtime query timed out") } }
        try {
            main.postDelayed(timeout, 30_000L)
            val lightweight = operation == "mqttCleanupGroups" ||
                (operation == "dashboard" && args.getString("profile") == DashboardLoadProfile.INITIAL.name)
            (if (lightweight) settingsWorker else workers).execute {
                try {
                    val result = query(context, operation, args)
                    ObjectOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])).use { output ->
                        output.writeObject(result)
                    }
                } catch (error: Exception) {
                    runCatching { pipe[1].closeWithError("${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}") }
                } finally { main.removeCallbacks(timeout) }
            }
        } catch (error: RuntimeException) {
            main.removeCallbacks(timeout)
            pipe.forEach { it.close() }
            throw error
        }
        return pipe[0]
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    fun <T> read(context: Context, operation: String, args: Bundle = Bundle()): T {
        RuntimeEndpoint.verifyOwner(context)
        args.putInt("protocol", RuntimeEndpoint.PROTOCOL)
        args.putString("revision", com.bydcollector.collector.BuildConfig.RUNTIME_REVISION)
        args.putString("operation", operation)
        val descriptor = requireNotNull(context.contentResolver.openTypedAssetFileDescriptor(RuntimeEndpoint.uri,
            "application/vnd.bydcollector.query", args))
        return descriptor.use {
            ObjectInputStream(descriptor.createInputStream()).use { input ->
                val result = input.readObject()
                descriptor.parcelFileDescriptor.checkError()
                result as T
            }
        }
    }

    private fun query(context: Context, operation: String, args: Bundle): Any? {
        val app = context.applicationContext as BydCollectorApplication
        // Initial controls need settings, not the lock held by a telemetry/Storage refresh.
        if (operation == "dashboard" && args.getString("profile") == DashboardLoadProfile.INITIAL.name) {
            val source = DashboardStateProvider(context, { BydCollectorApplication.store(context) },
                CollectorSettings(context)).loadInitial()
            val language = VehicleKpiLanguage.valueOf(requireNotNull(args.getString("language")))
            return app.dashboardUiStateStore.snapshotForClient(source, language)
                .copy(logRecording = com.bydcollector.collector.diagnostics.DiagnosticLogRecorder.isRecording())
        }
        return when (operation) {
            "jobs" -> RuntimeJobsService.snapshot(context)
            "mqttCleanupGroups" -> {
                val settings = CollectorSettings(context)
                MqttCleanupCore(app.mqttPublicationJournal, configProvider = {
                    settings.mqttConfig(includeCredentials = false).copy(username = settings.mqttUsername().takeIf { it.isNotBlank() })
                }).groups()
            }
            "updates" -> {
                val check = app.updateChecks.snapshot()
                val target = RuntimeJobsService.activeUpdateInfo(context)
                    ?: (check.uiState as? com.bydcollector.collector.update.UpdateUiState.Available)?.info
                RuntimeUpdateSnapshot(check, target, target?.let(app.releaseNotesHistory::snapshot))
            }
            "dashboard" -> synchronized(lock) {
                val profile = DashboardLoadProfile.valueOf(requireNotNull(args.getString("profile")))
                val language = VehicleKpiLanguage.valueOf(requireNotNull(args.getString("language")))
                val provider = dashboard ?: DashboardStateProvider(context, { BydCollectorApplication.store(context) },
                    CollectorSettings(context)).also { dashboard = it }
                val source = snapshots[profile]?.takeIf { args.getBoolean("cached") }
                    ?: provider.load(profile, snapshots[profile], language, readStorage = false)
                        .also { snapshots[profile] = it }
                app.dashboardUiStateStore.snapshotForClient(
                    source, language
                ).copy(logRecording = com.bydcollector.collector.diagnostics.DiagnosticLogRecorder.isRecording())
            }
            "mainPreflight" -> app.withTelemetryStoreRead {
                StorageFormatCutoverCoordinator.readMainPreflight(it.databaseFile())
            }
            "tripHierarchy" -> BydCollectorApplication.trips(context).queryHierarchy()
            "openTrip" -> BydCollectorApplication.trips(context).loadOpenSession()
            "tripSession" -> BydCollectorApplication.trips(context).session(tripId(args))
            "tripRoute" -> {
                val first = args.getLong("first", 0L)
                require(first >= 0)
                buildList {
                    BydCollectorApplication.trips(context).forEachRoutePointFrom(tripId(args), first, ::add)
                }
            }
            "footprints" -> longArrayOf(
                sqliteFootprintBytes(context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)),
                sqliteFootprintBytes(DirectDebugDatabaseResolver.databaseFile(context)),
                sqliteFootprintBytes(context.getDatabasePath(TripDatabaseHelper.DATABASE_NAME))
            )
            else -> error("Unknown runtime query")
        }
    }

    private fun tripId(args: Bundle): String = requireNotNull(args.getString("trip")).also {
        require(it.length in 1..256) { "Invalid trip ID" }
    }
}
