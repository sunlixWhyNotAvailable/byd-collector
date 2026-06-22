package com.bydcollector.collector.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class InternalAutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (
            action != CollectorAutoStart.ACTION_RETRY_AUTO_START &&
            action != CollectorAutoStart.ACTION_WATCHDOG_AUTO_START
        ) {
            return
        }
        CollectorAutoStart.handleBroadcast(
            context = context,
            action = action,
            retryAttempt = intent.getIntExtra(CollectorAutoStart.EXTRA_RETRY_ATTEMPT, 0)
        )
    }
}
