package com.bydcollector.collector.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.service.CollectorSettings

class KeepAliveRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != CollectorAutoStart.ACTION_KEEP_ALIVE_RECOVERY) return

        val appContext = context.applicationContext
        if (CollectorSettings.isDbMaintenanceRunning(appContext)) return
        val store = BydCollectorApplication.store(appContext)
        val settings = CollectorSettings(appContext, store)
        store.recordEvent(
            "keep_alive_recovery_broadcast",
            "Keep-alive recovery broadcast received",
            "action=$action"
        )
        CollectorAutoStart.recoverFromForeground(appContext, settings, store)
    }
}
