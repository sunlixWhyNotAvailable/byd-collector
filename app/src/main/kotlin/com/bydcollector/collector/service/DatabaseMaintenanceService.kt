package com.bydcollector.collector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.data.debug.DirectDebugStore
import com.bydcollector.collector.data.direct.DirectHelperOwnerMode
import com.bydcollector.collector.data.direct.DirectVehicleHelperClient
import com.bydcollector.collector.maintenance.DbMaintenanceCoordinator
import com.bydcollector.collector.maintenance.DbMaintenanceOperation
import com.bydcollector.collector.util.namedSingleThreadExecutor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Recovery entry point that deliberately opens no collector database during service startup. */
class DatabaseMaintenanceService : Service() {
    private lateinit var settings: CollectorSettings
    private lateinit var coordinator: DbMaintenanceCoordinator
    private val executor = namedSingleThreadExecutor("byd-db-recovery")
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        settings = CollectorSettings(applicationContext)
        val application = applicationContext as BydCollectorApplication
        coordinator = DbMaintenanceCoordinator(
            context = applicationContext,
            settings = settings,
            application = application,
            stopRuntime = ::stopCollectorRuntime,
            onStoreReopened = {},
            closeDebugStore = {},
            onDebugStoreReopened = { store -> closeReopenedDebugStore(application, store) }
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            coordinator.requestCancel()
            if (!running.get()) stopSelf(startId)
            return START_NOT_STICKY
        }
        val operation = DbMaintenanceOperation.fromKey(intent?.getStringExtra(EXTRA_OPERATION))
            ?: return START_NOT_STICKY.also { stopSelf(startId) }
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY

        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        try {
            executor.execute { runMaintenance(operation) }
        } catch (error: RejectedExecutionException) {
            settings.setDbMaintenanceStatus(
                settings.dbMaintenanceStatus().copy(
                    running = false,
                    completed = false,
                    error = "${error::class.java.simpleName}: ${error.message ?: "maintenance rejected"}",
                    cancelAvailable = false
                ),
                synchronous = true
            )
            finishService(restartCollector = false)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        releaseWakeLock()
        running.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runMaintenance(operation: DbMaintenanceOperation) {
        var restartCollector = false
        var archiveCreated = false
        try {
            val result = coordinator.run(operation) { restartCollector = true }
            archiveCreated = result.ok && result.archivePath != null
            if (archiveCreated) {
                runCatching { settings.setCutoverArchiveStoragePending(true) }
                    .onFailure { error ->
                        settings.setDbMaintenanceStatus(
                            settings.dbMaintenanceStatus().copy(
                                warning = listOfNotNull(
                                    settings.dbMaintenanceStatus().warning,
                                    "Archive compression was not scheduled: ${error::class.java.simpleName}"
                                ).joinToString("; ")
                            ),
                            synchronous = true
                        )
                    }
            }
        } finally {
            finishService(restartCollector, archiveCreated)
        }
    }

    private fun stopCollectorRuntime(operation: DbMaintenanceOperation) {
        stopService(Intent(applicationContext, CollectorService::class.java))
        val deadline = SystemClock.elapsedRealtime() + COLLECTOR_STOP_TIMEOUT_MS
        while (CollectorService.isRunning() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(COLLECTOR_STOP_POLL_MS)
        }
        check(!CollectorService.isRunning()) { "Collector service did not stop for database recovery" }
        if (operation == DbMaintenanceOperation.ARCHIVE) {
            val helper = DirectVehicleHelperClient()
            if (helper.ownerMode() == DirectHelperOwnerMode.APP_GAP_SPOOL) {
                helper.requestStop(DirectHelperOwnerMode.APP_GAP_SPOOL)
            }
        }
    }

    private fun closeReopenedDebugStore(application: BydCollectorApplication, store: DirectDebugStore) {
        val ready = runCatching { store.isCompactV2() }.getOrDefault(false)
        application.setDebugStorageReadyAfterMaintenance(ready)
        store.close()
    }

    private fun finishService(restartCollector: Boolean, archiveCreated: Boolean = false) {
        if (restartCollector && !settings.isUserShutdownRequested()) {
            CollectorServiceController.start(applicationContext)
            if (settings.isMqttEnabled() && !settings.isMqttManuallyStopped()) {
                CollectorServiceController.startMqttExport(applicationContext)
            }
            if (settings.isInfluxEnabled() && !settings.isInfluxManuallyStopped()) {
                CollectorServiceController.startInfluxExport(applicationContext)
            }
            if (archiveCreated || settings.isCutoverArchiveStoragePending()) {
                CollectorServiceController.reconcileArchiveStorage(applicationContext)
            }
        }
        releaseWakeLock()
        running.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:db-recovery")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(BuildConfig.COLLECTOR_DISPLAY_NAME)
            .setContentText("Database maintenance")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                BuildConfig.COLLECTOR_DISPLAY_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private val ACTION_RUN = "${BuildConfig.ACTION_PREFIX}.action.RUN_DATABASE_MAINTENANCE"
        private val ACTION_CANCEL = "${BuildConfig.ACTION_PREFIX}.action.CANCEL_DATABASE_RECOVERY"
        private const val EXTRA_OPERATION = "operation"
        private const val CHANNEL_ID = "database_maintenance"
        private const val NOTIFICATION_ID = 1002
        private const val COLLECTOR_STOP_TIMEOUT_MS = 20_000L
        private const val COLLECTOR_STOP_POLL_MS = 25L
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60_000L
        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()

        fun archiveIntent(context: Context, operation: DbMaintenanceOperation): Intent =
            Intent(context, DatabaseMaintenanceService::class.java).apply {
                action = ACTION_RUN
                putExtra(EXTRA_OPERATION, operation.key)
            }

        fun cancelIntent(context: Context): Intent = Intent(context, DatabaseMaintenanceService::class.java).apply {
            action = ACTION_CANCEL
        }
    }
}
