package com.bydcollector.collector.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.util.namedSingleThreadExecutor
import java.util.concurrent.Executor

// Keeps broadcast completion independent from the shared operational-event queue for this process.
internal val autoStartRecoveryHandoffExecutor: Executor =
    namedSingleThreadExecutor("byd-auto-start-recovery-handoff")

/** Foregrounds boot recovery before any database readiness work begins. */
class AutoStartRecoveryService : Service() {
    private val executor = namedSingleThreadExecutor("byd-auto-start-recovery")
    private val runner = AutoStartRecoveryRunner(executor, ::runRecovery)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_RECOVERY_ACTION)
            ?: return START_NOT_STICKY.also { stopSelf(startId) }
        val request = AutoStartRecoveryRequest(
            action = action,
            retryAttempt = intent.getIntExtra(CollectorAutoStart.EXTRA_RETRY_ATTEMPT, 0)
        )
        if (!runner.submit(request) { stopSelfResult(startId) }) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runRecovery(request: AutoStartRecoveryRequest) {
        try {
            CollectorAutoStart.handleRecoveryRequest(
                applicationContext,
                request.action,
                request.retryAttempt
            )
        } catch (error: Throwable) {
            Log.e(TAG, "Auto-start recovery failed for ${request.action}", error)
        }
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
            .setContentText("Restoring collector runtime")
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
        private const val TAG = "AutoStartRecovery"
        private const val EXTRA_RECOVERY_ACTION = "recovery_action"
        private const val CHANNEL_ID = "auto_start_recovery"
        private const val NOTIFICATION_ID = 1003

        fun enqueue(context: Context, action: String, retryAttempt: Int) {
            val intent = Intent(context, AutoStartRecoveryService::class.java).apply {
                putExtra(EXTRA_RECOVERY_ACTION, action)
                putExtra(CollectorAutoStart.EXTRA_RETRY_ATTEMPT, retryAttempt)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

internal data class AutoStartRecoveryRequest(
    val action: String,
    val retryAttempt: Int
)

internal class AutoStartRecoveryRunner(
    private val executor: Executor,
    private val recover: (AutoStartRecoveryRequest) -> Unit
) {
    fun submit(request: AutoStartRecoveryRequest, onFinished: () -> Unit): Boolean {
        return try {
            executor.execute {
                try {
                    recover(request)
                } finally {
                    onFinished()
                }
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }
}

internal fun handoffAutoStartRecovery(
    receiver: BroadcastReceiver,
    context: Context,
    action: String,
    retryAttempt: Int = 0
) {
    val pendingResult = receiver.goAsync()
    val appContext = context.applicationContext
    val request = AutoStartRecoveryRequest(action, retryAttempt)
    fun submitToRunningOwner(): Boolean {
        val runner = AutoStartRecoveryRunner(autoStartRecoveryHandoffExecutor) { queued ->
            CollectorAutoStart.handleRecoveryRequest(appContext, queued.action, queued.retryAttempt)
        }
        return runner.submit(request, pendingResult::finish)
    }

    if (CollectorService.isRunning() && submitToRunningOwner()) return
    try {
        AutoStartRecoveryService.enqueue(appContext, action, retryAttempt)
        pendingResult.finish()
    } catch (error: RuntimeException) {
        if (submitToRunningOwner()) {
            Log.w("AutoStartRecovery", "Foreground recovery handoff failed; using receiver worker", error)
            return
        }
        pendingResult.finish()
        throw error
    }
}
