package com.bydcollector.collector.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.adb.AccessCheckMode
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorServiceController
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.service.RuntimeDemand
import com.bydcollector.collector.service.RuntimeRecoveryAction

//restores collector service intent after boot, task removal, ui close, or keep-alive recovery broadcasts
object CollectorAutoStart {
    val ACTION_RETRY_AUTO_START: String = "${BuildConfig.ACTION_PREFIX}.action.RETRY_AUTO_START"
    val ACTION_WATCHDOG_AUTO_START: String = "${BuildConfig.ACTION_PREFIX}.action.WATCHDOG_AUTO_START"
    val ACTION_KEEP_ALIVE_RECOVERY: String = "${BuildConfig.ACTION_PREFIX}.action.KEEP_ALIVE_RECOVERY"
    const val EXTRA_RETRY_ATTEMPT = "retry_attempt"
    const val RETRY_DELAY_MS = 30_000L
    const val WATCHDOG_DELAY_MS = 60_000L
    const val TASK_REMOVED_RETRY_DELAY_MS = 5_000L
    const val MAX_RETRY_ATTEMPTS = 20

    fun handleBroadcast(context: Context, action: String, retryAttempt: Int = 0) {
        val appContext = context.applicationContext
        if (CollectorSettings.isDbMaintenanceRunning(appContext)) return
        val store = BydCollectorApplication.store(appContext)
        val settings = CollectorSettings(appContext, store)
        if (settings.isUserShutdownRequested()) {
            store.recordEvent("boot_auto_start_skipped", "User shutdown blocks runtime recovery", action)
            cancelRetry(appContext)
            cancelWatchdog(appContext)
            return
        }
        if (clearsManualStops(action)) {
            settings.clearRuntimeManualStops()
        }
        val demand = prepareRuntimeDemand(settings)
        if (!demand.any) {
            store.recordEvent("boot_auto_start_skipped", "No runtime channel is eligible for recovery", action)
            cancelRetry(appContext)
            cancelWatchdog(appContext)
            return
        }
        if (clearsManualStops(action) && demand.main && settings.hasActiveAccessWork()) {
            AdbAuthorizationManager.request(
                context = appContext,
                store = store,
                source = "broadcast_${action.substringAfterLast('.')}",
                mode = AccessCheckMode.NORMAL,
                helperOwnerMode = settings.mainHelperOwnerMode()
            )
        }
        if (CollectorService.isRunning()) {
            //reconciles an already-running service instead of assuming its previous flags are still correct
            requestServiceReconcile(appContext, demand, store, action)
            cancelRetry(appContext)
            scheduleWatchdog(appContext, settings, store)
            return
        }

        attemptStart(
            context = appContext,
            store = store,
            demand = demand,
            category = if (action == ACTION_RETRY_AUTO_START) "boot_auto_start_retry" else "boot_auto_start_attempt",
            successCategory = "boot_auto_start_requested",
            failureCategory = "boot_auto_start_failure",
            message = "Attempting collector auto-start",
            detail = "action=$action attempt=$retryAttempt"
        )
        scheduleRetryIfNeeded(appContext, store, retryAttempt)
        scheduleWatchdog(appContext, settings, store)
    }

    fun recoverFromForeground(context: Context, settings: CollectorSettings, store: TelemetryStore) {
        val appContext = context.applicationContext
        if (CollectorSettings.isDbMaintenanceRunning(appContext)) return
        val demand = prepareRuntimeDemand(settings)
        if (!demand.any) return
        if (CollectorService.isRunning()) {
            requestServiceReconcile(appContext, demand, store, "foreground_auto_start_recovery")
            return
        }

        attemptStart(
            context = appContext,
            store = store,
            demand = demand,
            category = "foreground_auto_start_recovery",
            successCategory = "foreground_auto_start_requested",
            failureCategory = "foreground_auto_start_failure",
            message = "Attempting collector auto-start from foreground",
            detail = null
        )
    }

    fun scheduleRestartAfterTaskRemoved(
        context: Context,
        settings: CollectorSettings,
        store: TelemetryStore
    ) {
        val appContext = context.applicationContext
        if (CollectorSettings.isDbMaintenanceRunning(appContext)) return
        val demand = prepareRuntimeDemand(settings)
        if (!demand.any) {
            store.recordEvent(
                "task_removed_no_autostart",
                "Collector restart ignored after task removal because no runtime channel is eligible"
            )
            return
        }
        scheduleRetry(
            context = appContext,
            store = store,
            retryAttempt = 0,
            delayMs = TASK_REMOVED_RETRY_DELAY_MS,
            category = "task_removed_restart_scheduled"
        )
    }

    fun scheduleRestartAfterUiClosed(
        context: Context,
        settings: CollectorSettings,
        store: TelemetryStore
    ) {
        val appContext = context.applicationContext
        if (CollectorSettings.isDbMaintenanceRunning(appContext)) return
        val demand = prepareRuntimeDemand(settings)
        if (!demand.any) return
        if (CollectorService.isRunning()) {
            requestServiceReconcile(appContext, demand, store, "ui_closed_restart")
            scheduleWatchdog(appContext, settings, store)
            return
        }

        scheduleRetry(
            context = appContext,
            store = store,
            retryAttempt = 0,
            delayMs = TASK_REMOVED_RETRY_DELAY_MS,
            category = "ui_closed_restart_scheduled"
        )
    }

    fun scheduleWatchdog(context: Context, settings: CollectorSettings, store: TelemetryStore) {
        val appContext = context.applicationContext
        if (CollectorSettings.isDbMaintenanceRunning(appContext)) return
        if (!shouldRunService(settings)) {
            cancelWatchdog(appContext)
            return
        }

        //periodically re-enters the same decision path because dilink can kill background work silently
        appContext.getSystemService(AlarmManager::class.java).set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + WATCHDOG_DELAY_MS,
            watchdogIntent(appContext)
        )
        store.recordEvent(
            "watchdog_restart_scheduled",
            "Scheduled collector watchdog restart check",
            "delay_ms=$WATCHDOG_DELAY_MS"
        )
    }

    fun cancelScheduled(context: Context) {
        val appContext = context.applicationContext
        cancelRetry(appContext)
        cancelWatchdog(appContext)
    }

    private fun ensurePollingEnabled(settings: CollectorSettings) {
        if (!settings.isPollingEnabled()) {
            settings.setPollingEnabled(true)
        }
    }

    private fun clearsManualStops(action: String): Boolean {
        return action != ACTION_RETRY_AUTO_START &&
            action != ACTION_WATCHDOG_AUTO_START &&
            action != ACTION_KEEP_ALIVE_RECOVERY
    }

    private fun prepareRuntimeDemand(settings: CollectorSettings): RuntimeDemand {
        val demand = settings.runtimeDemand()
        if (demand.main) {
            ensurePollingEnabled(settings)
            settings.setDebugPollingEnabled(demand.debug)
        } else if (demand.debug) {
            settings.setDebugPollingEnabled(true)
        }
        return demand
    }

    private fun shouldRunService(settings: CollectorSettings): Boolean {
        return settings.runtimeDemand().any
    }

    private fun attemptStart(
        context: Context,
        store: TelemetryStore,
        demand: RuntimeDemand,
        category: String,
        successCategory: String,
        failureCategory: String,
        message: String,
        detail: String?
    ) {
        try {
            store.recordEvent(category, message, detail)
            dispatchRuntimeRecovery(context, demand)
            store.recordEvent(successCategory, "Collector service start requested", detail)
        } catch (error: RuntimeException) {
            store.recordEvent(
                failureCategory,
                "Foreground service start failed",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }
    }

    private fun requestServiceReconcile(
        context: Context,
        demand: RuntimeDemand,
        store: TelemetryStore,
        action: String
    ) {
        val appContext = context.applicationContext
        try {
            store.recordEvent(
                "auto_start_reconcile_requested",
                "Collector already running; requested service reconcile",
                "action=$action channels=${demand.recoveryActions().joinToString(",") { it.name }}"
            )
            dispatchRuntimeRecovery(appContext, demand)
        } catch (error: RuntimeException) {
            store.recordEvent(
                "auto_start_reconcile_failure",
                "Foreground service reconcile request failed",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
        }
    }

    private fun dispatchRuntimeRecovery(context: Context, demand: RuntimeDemand) {
        demand.recoveryActions().forEach { action ->
            when (action) {
                RuntimeRecoveryAction.MAIN -> CollectorServiceController.start(context)
                RuntimeRecoveryAction.DEBUG -> CollectorServiceController.reconcileDebug(context)
                RuntimeRecoveryAction.MQTT -> CollectorServiceController.reconcileMqttExport(context)
                RuntimeRecoveryAction.INFLUX -> CollectorServiceController.reconcileInfluxExport(context)
                RuntimeRecoveryAction.TELEGRAM -> CollectorServiceController.reconcileTelegram(context)
                RuntimeRecoveryAction.KEEP_ALIVE -> CollectorServiceController.reconcileKeepAlive(context)
            }
        }
    }

    private fun scheduleRetryIfNeeded(context: Context, store: TelemetryStore, retryAttempt: Int) {
        if (retryAttempt >= MAX_RETRY_ATTEMPTS) {
            store.recordEvent(
                "auto_start_retry_exhausted",
                "Collector auto-start retry limit reached",
                "attempt=$retryAttempt"
            )
            return
        }

        scheduleRetry(
            context = context,
            store = store,
            retryAttempt = retryAttempt,
            delayMs = RETRY_DELAY_MS,
            category = "auto_start_retry_scheduled"
        )
    }

    private fun scheduleRetry(
        context: Context,
        store: TelemetryStore,
        retryAttempt: Int,
        delayMs: Long,
        category: String
    ) {
        val nextAttempt = retryAttempt + 1
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            retryIntent(context, nextAttempt)
        )
        store.recordEvent(
            category,
            "Scheduled collector auto-start retry",
            "attempt=$nextAttempt delay_ms=$delayMs"
        )
    }

    private fun cancelRetry(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(retryIntent(context, 0))
    }

    private fun cancelWatchdog(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(watchdogIntent(context))
    }

    private fun retryIntent(context: Context, retryAttempt: Int): PendingIntent {
        val intent = Intent(context, InternalAutoStartReceiver::class.java).apply {
            action = ACTION_RETRY_AUTO_START
            putExtra(EXTRA_RETRY_ATTEMPT, retryAttempt)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun watchdogIntent(context: Context): PendingIntent {
        val intent = Intent(context, InternalAutoStartReceiver::class.java).apply {
            action = ACTION_WATCHDOG_AUTO_START
        }
        return PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
