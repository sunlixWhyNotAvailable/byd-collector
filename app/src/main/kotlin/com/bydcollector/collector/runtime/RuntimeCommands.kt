package com.bydcollector.collector.runtime

import android.content.Context
import android.os.Bundle
import com.bydcollector.collector.service.CollectorServiceController as Controller

/** Explicit allowlist: clients cannot name components, shell commands, paths or SQL. */
internal object RuntimeCommands {
    fun dispatch(context: Context, operation: String, args: Bundle) {
        when (operation) {
            "start" -> Controller.start(context, args.getBoolean("force"))
            "stop" -> Controller.stop(context)
            "shutdown" -> Controller.shutdown(context)
            "startDebug" -> Controller.startDebug(context)
            "stopDebug" -> Controller.stopDebug(context)
            "reconcileDebug" -> Controller.reconcileDebug(context)
            "reconcileKeepAlive" -> Controller.reconcileKeepAlive(context, args.getBoolean("force"))
            "startMqttExport" -> Controller.startMqttExport(context)
            "stopMqttExport" -> Controller.stopMqttExport(context)
            "reconcileMqttExport" -> Controller.reconcileMqttExport(context)
            "startInfluxExport" -> Controller.startInfluxExport(context)
            "stopInfluxExport" -> Controller.stopInfluxExport(context)
            "reconcileInfluxExport" -> Controller.reconcileInfluxExport(context)
            "reconcileTelegram" -> Controller.reconcileTelegram(context)
            "testTelegram" -> Controller.testTelegram(context)
            "archiveDatabase" -> Controller.archiveDatabase(context)
            "archiveDebugDatabase" -> Controller.archiveDebugDatabase(context)
            "cancelDatabaseMaintenance" -> Controller.cancelDatabaseMaintenance(context)
            "reconcileArchiveStorage" -> Controller.reconcileArchiveStorage(context)
            "deleteArchives" -> {
                val ids = requireNotNull(args.getStringArrayList("ids"))
                require(ids.size in 1..500 && ids.all { it.length in 1..512 })
                Controller.deleteArchives(context, ids)
            }
            else -> throw IllegalArgumentException("Unknown runtime command")
        }
    }
}
