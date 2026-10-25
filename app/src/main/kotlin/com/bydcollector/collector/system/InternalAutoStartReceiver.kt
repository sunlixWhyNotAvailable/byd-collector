package com.bydcollector.collector.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydcollector.collector.util.sharedOperationalEventExecutor

class InternalAutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == CollectorAutoStart.ACTION_ARCHIVE_STORAGE_RETRY) {
            val pending = goAsync()
            try {
                sharedOperationalEventExecutor.execute {
                    try {
                        CollectorAutoStart.handleArchiveRetry(context.applicationContext)
                    } finally {
                        pending.finish()
                    }
                }
            } catch (error: RuntimeException) {
                CollectorAutoStart.recordArchiveRetryFailure(context, "archive_retry_receiver_dispatch", error)
                pending.finish()
            }
            return
        }
        if (
            action != CollectorAutoStart.ACTION_RETRY_AUTO_START &&
            action != CollectorAutoStart.ACTION_WATCHDOG_AUTO_START &&
            action != CollectorAutoStart.ACTION_KEEP_ALIVE_STOP_RETRY
        ) {
            return
        }
        handoffAutoStartRecovery(
            receiver = this,
            context = context,
            action = action,
            retryAttempt = if (action == CollectorAutoStart.ACTION_KEEP_ALIVE_STOP_RETRY) {
                intent.getIntExtra(CollectorAutoStart.EXTRA_KEEP_ALIVE_STOP_RETRY_ATTEMPT, 0)
            } else {
                intent.getIntExtra(CollectorAutoStart.EXTRA_RETRY_ATTEMPT, 0)
            }
        )
    }
}
