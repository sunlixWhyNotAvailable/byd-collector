package com.bydcollector.collector.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.bydcollector.collector.ha.HaConnectionOwnership
import com.bydcollector.collector.ha.HaRunSession
import com.bydcollector.collector.ha.HaExportChannel
import com.bydcollector.collector.maintenance.DbMaintenanceOperation
import com.bydcollector.collector.runtime.RuntimeEndpoint
import android.os.Bundle

object CollectorServiceController {
    fun start(context: Context, forceKeepAliveStatusCheck: Boolean = false) {
        if (RuntimeEndpoint.forward(context, "start", Bundle().apply { putBoolean("force", forceKeepAliveStatusCheck) })) return
        check(CollectorSettings(context).rememberTaskRemoval()) { "Could not persist runtime intent" }
        val intent = CollectorService.startIntent(context, forceKeepAliveStatusCheck)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        if (RuntimeEndpoint.forward(context, "stop")) return
        context.startService(CollectorService.stopIntent(context))
    }

    fun shutdown(context: Context) {
        if (RuntimeEndpoint.forward(context, "shutdown")) return
        context.startService(CollectorService.shutdownIntent(context))
    }

    fun retryKeepAliveStop(context: Context, retryAttempt: Int) {
        val intent = CollectorService.keepAliveStopRetryIntent(context, retryAttempt)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun startDebug(context: Context) {
        if (RuntimeEndpoint.forward(context, "startDebug")) return
        check(CollectorSettings(context).rememberTaskRemoval()) { "Could not persist runtime intent" }
        val intent = CollectorService.startDebugIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopDebug(context: Context) {
        if (RuntimeEndpoint.forward(context, "stopDebug")) return
        context.startService(CollectorService.stopDebugIntent(context))
    }

    fun reconcileKeepAlive(context: Context, forceKeepAliveStatusCheck: Boolean = false) {
        if (RuntimeEndpoint.forward(context, "reconcileKeepAlive", Bundle().apply { putBoolean("force", forceKeepAliveStatusCheck) })) return
        val intent = CollectorService.keepAliveIntent(context, forceKeepAliveStatusCheck)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun startMqttExport(context: Context) {
        if (RuntimeEndpoint.forward(context, "startMqttExport")) return
        if (CollectorService.mqttConnection.owned) return
        startManualChannel(context, HaExportChannel.MQTT) {
            startOwnedChannel(context, CollectorService.mqttConnection, CollectorService.startMqttExportIntent(context))
        }
    }

    fun reconcileDebug(context: Context) {
        if (RuntimeEndpoint.forward(context, "reconcileDebug")) return
        val intent = CollectorService.reconcileDebugIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun reconcileMqttExport(context: Context) {
        if (RuntimeEndpoint.forward(context, "reconcileMqttExport")) return
        startOwnedChannel(context, CollectorService.mqttConnection, CollectorService.reconcileMqttExportIntent(context))
    }

    fun stopMqttExport(context: Context) {
        if (RuntimeEndpoint.forward(context, "stopMqttExport")) return
        stopOwnedChannel(context, CollectorService.mqttConnection, CollectorService.stopMqttExportIntent(context))
    }

    fun startInfluxExport(context: Context) {
        if (RuntimeEndpoint.forward(context, "startInfluxExport")) return
        if (CollectorService.influxConnection.owned) return
        startManualChannel(context, HaExportChannel.INFLUX) {
            startOwnedChannel(context, CollectorService.influxConnection, CollectorService.startInfluxExportIntent(context))
        }
    }

    private inline fun startManualChannel(context: Context, channel: HaExportChannel, start: () -> Unit) {
        val granted = HaRunSession.process.start(channel)
        try {
            check(CollectorSettings(context).rememberTaskRemoval()) { "Could not persist export intent" }
            start()
        } catch (error: RuntimeException) {
            if (granted) HaRunSession.process.stop(channel)
            CollectorSettings(context).rememberTaskRemoval()
            throw error
        }
    }

    fun reconcileInfluxExport(context: Context) {
        if (RuntimeEndpoint.forward(context, "reconcileInfluxExport")) return
        startOwnedChannel(context, CollectorService.influxConnection, CollectorService.reconcileInfluxExportIntent(context))
    }

    fun stopInfluxExport(context: Context) {
        if (RuntimeEndpoint.forward(context, "stopInfluxExport")) return
        stopOwnedChannel(context, CollectorService.influxConnection, CollectorService.stopInfluxExportIntent(context))
    }

    private fun startOwnedChannel(context: Context, owner: HaConnectionOwnership, intent: Intent) {
        if (owner.stopping) return
        val reserved = owner.reserve()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (error: RuntimeException) {
            if (reserved) owner.release()
            throw error
        }
    }

    private fun stopOwnedChannel(context: Context, owner: HaConnectionOwnership, intent: Intent) {
        owner.beginStop()
        try {
            context.startService(intent)
        } catch (error: RuntimeException) {
            owner.stopSubmissionFailed()
            throw error
        }
    }

    fun reconcileTelegram(context: Context) {
        if (RuntimeEndpoint.forward(context, "reconcileTelegram")) return
        val intent = CollectorService.reconcileTelegramIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun testTelegram(context: Context) {
        if (RuntimeEndpoint.forward(context, "testTelegram")) return
        val intent = CollectorService.testTelegramIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun archiveDatabase(context: Context) {
        if (RuntimeEndpoint.forward(context, "archiveDatabase")) return
        val intent = if (CollectorService.isRunning()) {
            CollectorService.archiveDatabaseIntent(context)
        } else {
            DatabaseMaintenanceService.archiveIntent(context, DbMaintenanceOperation.ARCHIVE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun archiveDebugDatabase(context: Context) {
        if (RuntimeEndpoint.forward(context, "archiveDebugDatabase")) return
        val intent = if (CollectorService.isRunning()) {
            CollectorService.archiveDebugDatabaseIntent(context)
        } else {
            DatabaseMaintenanceService.archiveIntent(context, DbMaintenanceOperation.DEBUG_ARCHIVE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun cancelDatabaseMaintenance(context: Context) {
        if (RuntimeEndpoint.forward(context, "cancelDatabaseMaintenance")) return
        val intent = if (DatabaseMaintenanceService.isRunning()) {
            DatabaseMaintenanceService.cancelIntent(context)
        } else {
            CollectorService.cancelDatabaseMaintenanceIntent(context)
        }
        context.startService(intent)
    }

    fun reconcileArchiveStorage(context: Context) {
        if (RuntimeEndpoint.forward(context, "reconcileArchiveStorage")) return
        val intent = CollectorService.reconcileArchiveStorageIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun deleteArchives(context: Context, ids: List<String>) {
        if (RuntimeEndpoint.forward(context, "deleteArchives", Bundle().apply { putStringArrayList("ids", ArrayList(ids)) })) return
        val intent = CollectorService.deleteArchivesIntent(context, ArrayList(ids))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
