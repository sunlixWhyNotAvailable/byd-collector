package com.bydcollector.collector.system

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.adb.AdbAuthorizationManager
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.util.dispatchOperationalEvent
import com.bydcollector.collector.util.sharedOperationalEventExecutor

/** Android system anchor for ordinary app-process and post-boot recovery. */
class CollectorNotificationListenerService : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        if (CollectorSettings(applicationContext).isUserShutdownRequested()) return
        (applicationContext as BydCollectorApplication).updateRuntime.start("notification_listener")
        AccessHealthRefreshMonitor.start(applicationContext)
        requestRuntimeRecovery("notification_listener_create")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (CollectorSettings(applicationContext).isUserShutdownRequested()) {
            AccessHealthRefreshMonitor.pause()
            return
        }
        AccessHealthRefreshMonitor.start(applicationContext)
        requestRuntimeRecovery("notification_listener_connected")
    }

    override fun onListenerDisconnected() {
        AccessHealthRefreshMonitor.pause()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        AccessHealthRefreshMonitor.stop()
        super.onDestroy()
    }

    private fun requestRuntimeRecovery(trigger: String) {
        val appContext = applicationContext
        dispatchOperationalEvent(sharedOperationalEventExecutor) {
            if (CollectorSettings(appContext).isUserShutdownRequested()) return@dispatchOperationalEvent
            if (CollectorSettings.isDbMaintenanceRunning(appContext)) return@dispatchOperationalEvent
            val store = BydCollectorApplication.store(appContext)
            store.recordEvent(
                "notification_listener_recovery_requested",
                "Notification listener requested collector recovery",
                "trigger=$trigger"
            )
            CollectorAutoStart.recoverFromForeground(
                context = appContext,
                settings = CollectorSettings(appContext, store),
                store = store
            )
        }
    }
}

internal object AccessHealthRefreshMonitor {
    private const val REFRESH_INTERVAL_MS = 5 * 60_000L
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var preferences: SharedPreferences? = null
    private var shutdownListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var callbackScheduled = false
    private var listenerActive = false

    private val refreshTask = Runnable {
        callbackScheduled = false
        val context = appContext ?: return@Runnable
        if (!listenerActive || userShutdownRequested(context)) return@Runnable
        AdbAuthorizationManager.requestObservation(context)
        scheduleNext(context)
    }

    fun start(context: Context) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        listenerActive = true
        watchShutdown(applicationContext)
        scheduleNow(applicationContext)
    }

    /** Stops work while disconnected; the listener is removed only when the service is destroyed. */
    fun pause() {
        listenerActive = false
        stopScheduledWork()
    }

    private fun pauseForShutdown() {
        stopScheduledWork()
    }

    private fun stopScheduledWork() {
        callbackScheduled = false
        handler.removeCallbacks(refreshTask)
        AdbAuthorizationManager.cancelObservation()
    }

    fun stop() {
        pause()
        preferences?.let { prefs -> shutdownListener?.let(prefs::unregisterOnSharedPreferenceChangeListener) }
        preferences = null
        shutdownListener = null
        appContext = null
        listenerActive = false
    }

    private fun watchShutdown(context: Context) {
        if (preferences != null) return
        val prefs = context.getSharedPreferences(CollectorSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == CollectorSettings.KEY_USER_SHUTDOWN) {
                handler.post {
                    val current = appContext ?: return@post
                    if (changed.getBoolean(CollectorSettings.KEY_USER_SHUTDOWN, false)) {
                        pauseForShutdown()
                    } else if (listenerActive) {
                        scheduleNow(current)
                    }
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        preferences = prefs
        shutdownListener = listener
    }

    private fun scheduleNow(context: Context) {
        if (!listenerActive || userShutdownRequested(context) || callbackScheduled) return
        callbackScheduled = true
        handler.post(refreshTask)
    }

    private fun scheduleNext(context: Context) {
        if (!listenerActive || userShutdownRequested(context) || callbackScheduled) return
        callbackScheduled = true
        handler.postDelayed(refreshTask, REFRESH_INTERVAL_MS)
    }

    private fun userShutdownRequested(context: Context): Boolean = context
        .getSharedPreferences(CollectorSettings.PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(CollectorSettings.KEY_USER_SHUTDOWN, false)
}
