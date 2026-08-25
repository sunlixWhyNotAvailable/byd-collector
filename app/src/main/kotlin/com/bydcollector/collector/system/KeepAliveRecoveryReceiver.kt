package com.bydcollector.collector.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class KeepAliveRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != CollectorAutoStart.ACTION_KEEP_ALIVE_RECOVERY) return

        handoffAutoStartRecovery(this, context, action)
    }
}
