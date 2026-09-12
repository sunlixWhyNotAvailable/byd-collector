package com.bydcollector.collector.system

import android.service.notification.NotificationListenerService
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.util.dispatchOperationalEvent
import com.bydcollector.collector.util.sharedOperationalEventExecutor

/** Android system anchor for ordinary app-process and post-boot recovery. */
class CollectorNotificationListenerService : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        (applicationContext as BydCollectorApplication).updateRuntime.start("notification_listener")
        requestRuntimeRecovery("notification_listener_create")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        requestRuntimeRecovery("notification_listener_connected")
    }

    private fun requestRuntimeRecovery(trigger: String) {
        val appContext = applicationContext
        dispatchOperationalEvent(sharedOperationalEventExecutor) {
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
