package com.bydcollector.collector.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_QUICKBOOT_POWERON &&
            action != Intent.ACTION_USER_PRESENT
        ) {
            return
        }
        CollectorAutoStart.handleBroadcast(
            context = context,
            action = action,
            retryAttempt = intent.getIntExtra(CollectorAutoStart.EXTRA_RETRY_ATTEMPT, 0)
        )
    }

    companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
