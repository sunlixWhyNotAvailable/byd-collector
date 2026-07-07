package com.bydcollector.collector.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.data.local.TelemetryStore
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorServiceController
import com.bydcollector.collector.service.CollectorSettings

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
        if (!shouldRunService(settings)) {
            store.recordEvent("boot_auto_start_skipped", "Auto-start and keep-alive disabled", action)
            cancelRetry(appContext)
            cancelWatchdog(appContext)
            return
        }

        if (clearsManualStops(action)) {
            settings.clearRuntimeManualStops()
        }
        if (settings.isAutoStartEnabled()) {
            //auto-start means main polling should become enabled again after device/process recovery
            ensurePollingEnabled(settings)
            syncDebugAutoStart(settings)
        }
        if (CollectorService.isRunning()) {
            //reconciles an already-running service instead of assuming its previous flags are still correct
            requestServiceReconcile(appContext, settings, store, action)
            cancelRetry(appContext)
            scheduleWatchdog(appContext, settings, store)
            return
        }

        attemptStart(
            context = appContext,
            store = store,
            keepAliveOnly = !settings.isAutoStartEnabled() && settings.keepAliveConfig().anyEnabled,
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
        if (CollectorSettings.isDbMaintenanceRunning(appContext) || !shouldRunService(settings)) return
        if (settings.isAutoStartEnabled()) ensurePollingEnabled(settings)
        if (CollectorService.isRunning()) {
            requestServiceReconcile(appContext, settings, store, "foreground_auto_start_recovery")
            return
        }

        attemptStart(
            context = appContext,
            store = store,
            keepAliveOnly = !settings.isAutoStartEnabled() && settings.keepAliveConfig().anyEnabled,
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
        if (!shouldRunService(settings)) {
            store.recordEvent(
                "task_removed_no_autostart",
                "Collector restart ignored after task removal because auto-start and keep-alive are disabled"
            )
            return
        }

        if (settings.isAutoStartEnabled()) ensurePollingEnabled(settings)
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
        if (!shouldRunService(settings)) return
        if (settings.isAutoStartEnabled()) ensurePollingEnabled(settings)
        if (CollectorService.isRunning()) {
            requestServiceReconcile(appContext, settings, store, "ui_closed_restart")
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

    private fun syncDebugAutoStart(settings: CollectorSettings) {
        if (settings.isDebugAutoStartEnabled()) {
            settings.setDebugPollingEnabled(true)
        } else {
            settings.setDebugPollingEnabled(false)
        }
    }

    private fun shouldRunService(settings: CollectorSettings): Boolean {
        if (settings.isUserShutdownRequested()) return false
        //keep-alive alone is enough reason to keep a foreground service even when polling is disabled
        return settings.isAutoStartEnabled() || settings.keepAliveConfig().anyEnabled
    }

    private fun attemptStart(
        context: Context,
        store: TelemetryStore,
        keepAliveOnly: Boolean,
        category: String,
        successCategory: String,
        failureCategory: String,
        message: String,
        detail: String?
    ) {
        try {
            store.recordEvent(category, message, detail)
            //chooses keep-alive-only reconcile when the user wants radios alive but not telemetry polling
            if (keepAliveOnly) {
                CollectorServiceController.reconcileKeepAlive(context)
            } else {
                CollectorServiceController.start(context)
            }
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
        settings: CollectorSettings,
        store: TelemetryStore,
        action: String
    ) {
        val appContext = context.applicationContext
        val keepAliveOnly = !settings.isAutoStartEnabled() && settings.keepAliveConfig().anyEnabled
        try {
            store.recordEvent(
                "auto_start_reconcile_requested",
                "Collector already running; requested service reconcile",
                "action=$action keep_alive_only=$keepAliveOnly"
            )
            if (keepAliveOnly) {
                CollectorServiceController.reconcileKeepAlive(appContext)
            } else {
                CollectorServiceController.start(appContext)
            }
        } catch (error: RuntimeException) {
            store.recordEvent(
                "auto_start_reconcile_failure",
                "Foreground service reconcile request failed",
                "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            )
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
