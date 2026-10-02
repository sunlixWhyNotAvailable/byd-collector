package com.bydcollector.collector

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.bydcollector.collector.runtime.RuntimeEndpoint
import com.bydcollector.collector.ha.HaExportChannel
import com.bydcollector.collector.ha.HaRunSession
import com.bydcollector.collector.service.CollectorSettings
import java.io.ObjectInputStream

/** One emulator gate: real IPC/serialization, distinct UIDs, and manual grants across UI force-stop. */
internal object RuntimeBoundaryGate {
    fun run(test: Instrumentation): String {
        val ctx = test.targetContext
        val app = ctx.applicationContext as BydCollectorApplication
        val pm = ctx.packageManager
        val owner = pm.getApplicationInfo(RuntimeEndpoint.OWNER_PACKAGE, 0)
        val ui = pm.getApplicationInfo(RuntimeEndpoint.UI_PACKAGE, 0)
        check(owner.uid != ui.uid)
        check(pm.checkSignatures(owner.uid, ui.uid) == android.content.pm.PackageManager.SIGNATURE_MATCH)
        val prefs = ctx.getSharedPreferences(CollectorSettings.PREFS_NAME, 0)
        val saved = prefs.all.toMap()
        val mqttSocket = java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress())
        val influxSocket = java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress())
        fun options(operation: String) = Bundle().apply {
            putInt("protocol", RuntimeEndpoint.PROTOCOL)
            putString("revision", BuildConfig.RUNTIME_REVISION)
            putString("operation", operation)
        }
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            test.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        try {
            val settings = CollectorSettings(ctx)
            settings.setAutoStartEnabled(false)
            settings.setDebugAutoStartEnabled(false)
            settings.setMqttAutoStartEnabled(false)
            settings.setInfluxAutoStartEnabled(false)
            settings.setMqttEnabled(false)
            settings.setInfluxEnabled(false)
            settings.setUpdateAutoCheckEnabled(false)
            settings.setPollingEnabled(false)
            settings.setDebugPollingEnabled(false)
            settings.setTelegramEnabled(false)
            settings.setConnectivityRecoveryEnabled(false)
            settings.setKeepBluetoothEnabled(false)
            settings.setTailscaleActivationEnabled(false)
            settings.setMqttHost("127.0.0.1")
            settings.setMqttPort(mqttSocket.localPort)
            settings.setMqttAlternativeHost(null)
            settings.setInfluxHost("127.0.0.1")
            settings.setInfluxPort(influxSocket.localPort)
            settings.setInfluxDatabase("runtime_boundary_test")
            settings.setInfluxAlternativeHost(null)
            verifyStorageStartupDoesNotBlockMain(test, app, settings)
            for (profile in com.bydcollector.collector.ui.DashboardLoadProfile.entries) {
                val args = options("dashboard").apply { putString("profile", profile.name); putString("language", "UK") }
                ctx.contentResolver.openTypedAssetFileDescriptor(RuntimeEndpoint.uri, "application/vnd.bydcollector.query", args)!!.use { fd ->
                    ObjectInputStream(fd.createInputStream()).use { input ->
                        check(input.readObject() is com.bydcollector.collector.ui.DashboardState)
                        fd.parcelFileDescriptor.checkError()
                    }
                }
            }
            listOf("tripHierarchy", "openTrip", "footprints", "updates", "jobs").forEach { query ->
                ctx.contentResolver.openTypedAssetFileDescriptor(RuntimeEndpoint.uri, "application/vnd.bydcollector.query", options(query))!!.use { fd ->
                    ObjectInputStream(fd.createInputStream()).use { it.readObject(); fd.parcelFileDescriptor.checkError() }
                }
            }
            val kpi = com.bydcollector.collector.ui.VehicleKpis(socPercent = "73%")
            app.dashboardUiStateStore.publishVehicleKpis(kpi, kpi)
            val cachedArgs = options("dashboard").apply {
                putString("profile", "ALL_PARAMETERS"); putString("language", "UK"); putBoolean("cached", true)
            }
            ctx.contentResolver.openTypedAssetFileDescriptor(RuntimeEndpoint.uri, "application/vnd.bydcollector.query", cachedArgs)!!.use { fd ->
                ObjectInputStream(fd.createInputStream()).use {
                    check((it.readObject() as com.bydcollector.collector.ui.DashboardState).vehicleKpis == kpi)
                }
            }
            app.dashboardUiStateStore.clearVehicleKpis()
            check(pm.checkPermission(RuntimeEndpoint.PERMISSION, "com.android.shell") == android.content.pm.PackageManager.PERMISSION_DENIED)
            val pid = android.os.Process.myPid()
            ctx.startActivity(Intent().setClassName(RuntimeEndpoint.UI_PACKAGE, "com.bydcollector.collector.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(2_000)
            val clientPid = shell("pidof ${RuntimeEndpoint.UI_PACKAGE}").trim()
            check(clientPid.isNotEmpty()) { "UI failed to start; inspect logcat" }
            settings.setMqttManuallyStopped(false)
            settings.setInfluxManuallyStopped(false)
            settings.setMqttEnabled(true)
            settings.setInfluxEnabled(true)
            com.bydcollector.collector.service.CollectorServiceController.startMqttExport(ctx)
            com.bydcollector.collector.service.CollectorServiceController.startInfluxExport(ctx)
            check(HaRunSession.process.allows(HaExportChannel.MQTT, false))
            check(HaRunSession.process.allows(HaExportChannel.INFLUX, false))
            val job = com.bydcollector.collector.runtime.RuntimeJobsService.submit(ctx, "logStop", org.json.JSONObject())
            shell("am force-stop ${RuntimeEndpoint.UI_PACKAGE}")
            Thread.sleep(500)
            check(shell("pidof ${RuntimeEndpoint.UI_PACKAGE}").isBlank())
            check(shell("pidof ${RuntimeEndpoint.OWNER_PACKAGE}").trim().split(' ').contains(pid.toString()))
            check(HaRunSession.process.allows(HaExportChannel.MQTT, false))
            check(HaRunSession.process.allows(HaExportChannel.INFLUX, false))
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
            while (!com.bydcollector.collector.runtime.RuntimeJobsService.snapshot(ctx).let { json ->
                val jobs = org.json.JSONArray(json)
                (0 until jobs.length()).map(jobs::getJSONObject).any { it.getString("id") == job && it.getString("status") == "complete" }
            }) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Accepted job did not finish after UI force-stop" }
                Thread.sleep(50)
            }
            com.bydcollector.collector.runtime.RuntimeJobsService.presented(ctx, job)
            ctx.startActivity(Intent().setClassName(RuntimeEndpoint.UI_PACKAGE, "com.bydcollector.collector.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(2_000)
            check(shell("pidof ${RuntimeEndpoint.UI_PACKAGE}").trim().let { it.isNotEmpty() && it != clientPid })
            check(HaRunSession.process.allows(HaExportChannel.MQTT, false))
            check(HaRunSession.process.allows(HaExportChannel.INFLUX, false))
            return "PASS: blocked storage leaves main responsive; Stop fences pending Main start; native IPC snapshots; shell caller rejected; UI force-stop/relaunch; owner PID and both manual grants retained"
        } finally {
            mqttSocket.close()
            influxSocket.close()
            shell("am force-stop ${RuntimeEndpoint.UI_PACKAGE}")
            val edit = prefs.edit().clear()
            saved.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            check(edit.commit())
            HaRunSession.process.clear()
        }
    }

    private fun verifyStorageStartupDoesNotBlockMain(
        test: Instrumentation, app: BydCollectorApplication, settings: CollectorSettings
    ) {
        val ctx = test.targetContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun mainBarrier() {
            val barrier = java.util.concurrent.FutureTask { true }
            check(main.post(barrier))
            check(barrier.get(2, java.util.concurrent.TimeUnit.SECONDS)) { "Storage blocked Android main thread" }
        }
        fun awaitState(message: String, condition: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (!condition()) {
                check(android.os.SystemClock.elapsedRealtime() < deadline) { message }
                Thread.sleep(20)
            }
        }
        settings.setUserShutdownRequested(false)
        app.withExclusiveDatabaseMaintenance {
            ctx.startForegroundService(com.bydcollector.collector.service.CollectorService.startIntent(ctx))
            awaitState("Storage initialization did not use a background worker") {
                Thread.getAllStackTraces().any { (thread, stack) ->
                    thread.name == "byd-runtime-start" && stack.any { it.className.endsWith("BydCollectorApplication") && it.methodName == "store" }
                }
            }
            mainBarrier()
        }
        awaitState("Runtime did not initialize after storage became available") {
            com.bydcollector.collector.service.CollectorService.isRunning()
        }
        mainBarrier()
        app.withExclusiveDatabaseMaintenance {
            settings.setMainManuallyStopped(false)
            settings.setPollingEnabled(true)
            ctx.startForegroundService(com.bydcollector.collector.service.CollectorService.startIntent(ctx))
            awaitState("Main start did not enter preparation") {
                com.bydcollector.collector.service.CollectorService.mainRuntimeStatus() ==
                    com.bydcollector.collector.ui.RuntimeActionStatus.STARTING
            }
            settings.setMainManuallyStopped(true)
            settings.setPollingEnabled(false)
            ctx.startService(com.bydcollector.collector.service.CollectorService.stopIntent(ctx))
            awaitState("Stop did not cancel blocked Main start") {
                com.bydcollector.collector.service.CollectorService.mainRuntimeStatus() ==
                    com.bydcollector.collector.ui.RuntimeActionStatus.STOPPED
            }
            mainBarrier()
        }
        Thread.sleep(200)
        mainBarrier()
        check(!com.bydcollector.collector.service.CollectorService.isMainPollingRunning()) {
            "Delayed storage preparation revived stopped collection"
        }
    }
}
