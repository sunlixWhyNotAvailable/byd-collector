package com.bydcollector.collector.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.AtomicFile
import androidx.core.content.FileProvider
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.diagnostics.DiagnosticLogRecorder
import com.bydcollector.collector.ha.HaEndpointProfile
import com.bydcollector.collector.influx.InfluxActions
import com.bydcollector.collector.maintenance.ArchiveStorageManager
import com.bydcollector.collector.data.debug.DirectDebugDatabaseResolver
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.trips.TripDatabaseHelper
import com.bydcollector.collector.mqtt.HaMqttActions
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.update.UpdateApkVerifier
import com.bydcollector.collector.update.UpdateDownloader
import com.bydcollector.collector.update.UpdateInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Activities observe these jobs; their death cannot cancel execution or erase its result. */
class RuntimeJobsService : Service() {
    private val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val lanes = mutableSetOf<String>()
    private var lastStartId = 0
    @Volatile private var destroyed = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        synchronized(LOCK) {
            val jobs = load(this)
            if (jobs.any { it.optString("status") == "running" }) {
                jobs.filter { it.optString("status") == "running" }.forEach {
                    // These bounded native operations are retryable. DownloadManager
                    // keeps its accepted ID, so recovery does not enqueue a second download.
                    it.put("status", "queued").put("recovered", true)
                }
                save(this, jobs)
            }
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "BYD Collector", NotificationManager.IMPORTANCE_LOW))
        startForeground(1004, Notification.Builder(this, CHANNEL).setContentTitle("BYD Collector")
            .setContentText("Background task").setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        drain()
        return START_STICKY
    }

    private fun drain() {
        if (destroyed) return
        val jobs = synchronized(LOCK) {
            val all = load(this)
            val ready = all.filter { it.optString("status") == "queued" && lanes.add(lane(it.getString("kind"))) }
            ready.forEach { it.put("status", "running") }
            if (ready.isNotEmpty()) save(this, all)
            ready.map { JSONObject(it.toString()) }
        }
        jobs.forEach { job ->
            executor.execute {
                try {
                    check(!CollectorSettings(this).isUserShutdownRequested()) { "Shutdown requested" }
                    val result = execute(job)
                    if (destroyed) return@execute
                    change(this, job.getString("id")) {
                        if (it.getString("status") == "running") it.put("status", "complete").put("result", result)
                    }
                } catch (error: Exception) {
                    if (destroyed) return@execute
                    change(this, job.getString("id")) {
                        if (it.getString("status") == "running") it.put("status", "error")
                            .put("error", "${error.javaClass.simpleName}: ${error.message.orEmpty().take(300)}")
                    }
                    if (job.getString("kind") == "updateDownload") main.post {
                        (applicationContext as BydCollectorApplication).updateRuntime.onInstallFinished()
                    }
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                } finally {
                    main.post { lanes.remove(lane(job.getString("kind"))); drain() }
                }
            }
        }
        if (lanes.isEmpty() && !DiagnosticLogRecorder.isRecording()) stopSelf(lastStartId)
    }

    override fun onDestroy() {
        destroyed = true
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun execute(job: JSONObject): JSONObject {
        val app = applicationContext as BydCollectorApplication
        val args = job.getJSONObject("args")
        return when (job.getString("kind")) {
            "access" -> {
                val done = java.util.concurrent.CountDownLatch(1)
                val accepted = com.bydcollector.collector.adb.AdbAuthorizationManager.request(this,
                    BydCollectorApplication.store(this), args.optString("source", "ui_client"),
                    com.bydcollector.collector.adb.AccessCheckMode.valueOf(args.getString("mode")),
                    CollectorSettings(this).mainHelperOwnerMode(), onTerminal = { done.countDown() })
                check(accepted) { "Access check already running" }
                check(done.await(90, java.util.concurrent.TimeUnit.SECONDS)) { "Access check timed out" }
                val state = com.bydcollector.collector.adb.AdbAuthorizationManager.currentSnapshot()
                JSONObject().put("permissions", state.permissionsGranted).put("adb", state.adbAuthorized)
            }
            "mqttTest" -> app.withTelemetryStoreRead { store ->
                HaMqttActions.testConnection(store, CollectorSettings(this, store),
                    profile = HaEndpointProfile.valueOf(args.getString("profile"))).let {
                    JSONObject().put("ok", it.ok).put("message", it.message)
                }
            }
            "influxTest" -> app.withTelemetryStoreRead { store ->
                InfluxActions.testConnection(store, CollectorSettings(this, store),
                    profile = HaEndpointProfile.valueOf(args.getString("profile"))).let {
                    JSONObject().put("ok", it.ok).put("message", it.message)
                }
            }
            "logStart" -> {
                check(recordingPrefs(this).edit().putBoolean("recording", true).commit())
                try { JSONObject().put("path", DiagnosticLogRecorder.start(this).absolutePath) }
                catch (error: Exception) {
                    recordingPrefs(this).edit().putBoolean("recording", false).commit()
                    throw error
                }
            }
            "logStop" -> {
                check(recordingPrefs(this).edit().putBoolean("recording", false).commit())
                JSONObject().put("path", DiagnosticLogRecorder.stop()?.absolutePath)
            }
            "logShare" -> JSONObject().put("uri", grant(DiagnosticLogRecorder.prepareShareBundle(this)))
            "logClear" -> DiagnosticLogRecorder.clearCompleted(this).let {
                JSONObject().put("removed", it.removed).put("warnings", JSONArray(it.warnings))
            }
            "archiveShare" -> {
                val ids = args.getJSONArray("ids").let { list -> (0 until list.length()).map(list::getString) }
                check(!CollectorService.isArchiveStorageActive()) { "Archive maintenance is active" }
                val lease = CollectorService.archiveShareLeaseRegistry.acquire(ids)
                try {
                    val files = ArchiveStorageManager(File(filesDir, "db_archive"),
                        getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME),
                        DirectDebugDatabaseResolver.databaseFile(this),
                        getDatabasePath(TripDatabaseHelper.DATABASE_NAME)).resolveShareZipFiles(ids)
                        ?: error("Invalid or stale archive selection")
                    JSONObject().put("uris", JSONArray(files.map(::grant)))
                } catch (error: Exception) {
                    CollectorService.archiveShareLeaseRegistry.release(lease)
                    throw error
                }
            }
            "updateDownload" -> {
                RuntimeControl.call(this, "installStart", android.os.Bundle())
                val info = UpdateInfo(args.getString("version"), args.getString("url"), "",
                    args.optString("contentType").takeIf { it.isNotBlank() })
                val downloader = UpdateDownloader(this)
                val downloadId = job.optLong("downloadId", -1L).takeIf { it > 0 } ?: downloader.enqueue(info)
                change(this, job.getString("id")) { it.put("downloadId", downloadId) }
                var previous = -1
                while (true) {
                    check(!Thread.currentThread().isInterrupted && !CollectorSettings(this).isUserShutdownRequested()) { "Shutdown requested" }
                    val progress = downloader.progress(downloadId)
                    check(progress >= 0) { "Update download failed" }
                    if (progress != previous) {
                        change(this, job.getString("id")) { it.put("progress", progress) }
                        previous = progress
                    }
                    if (progress >= 100) break
                    Thread.sleep(350)
                }
                val file = File(cacheDir, "updates/runtime-${job.getString("id")}.apk")
                file.parentFile?.mkdirs()
                downloader.downloadedFile(info).copyTo(file, overwrite = true)
                val validation = UpdateApkVerifier(this).validate(file)
                check(validation.ok) { validation.message }
                JSONObject().put("uri", grant(file)).put("sha256", validation.sha256)
            }
            else -> error("Unknown background job")
        }
    }

    private fun grant(file: File): String {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        grantUriPermission(RuntimeEndpoint.UI_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return uri.toString()
    }

    companion object {
        private const val CHANNEL = "runtime_jobs"
        private val LOCK = Any()
        private var cached: MutableList<JSONObject>? = null
        private fun lane(kind: String) = when { kind.startsWith("log") || kind == "archiveShare" -> "diagnostics"; kind.endsWith("Test") -> "tests"; else -> kind }
        private val accepted = setOf("access", "mqttTest", "influxTest", "logStart", "logStop", "logShare", "logClear", "archiveShare", "updateDownload")

        fun recover(context: Context) {
            if (CollectorSettings(context).isUserShutdownRequested()) return
            if (recordingPrefs(context).getBoolean("recording", false) && !DiagnosticLogRecorder.isRecording()) {
                submit(context, "logStart", JSONObject())
            }
            val pending = synchronized(LOCK) { load(context).any { it.optString("status") in setOf("queued", "running") } }
            if (pending) context.startForegroundService(Intent(context, RuntimeJobsService::class.java))
        }

        fun submit(context: Context, kind: String, args: JSONObject): String = synchronized(LOCK) {
            require(kind in accepted && args.toString().length <= 64 * 1024) { "Invalid background job" }
            check(!CollectorSettings(context).isUserShutdownRequested()) { "Shutdown requested" }
            if (kind == "mqttTest" || kind == "influxTest") HaEndpointProfile.valueOf(args.getString("profile"))
            if (kind == "access") {
                com.bydcollector.collector.adb.AccessCheckMode.valueOf(args.getString("mode"))
                require(args.optString("source").length <= 160)
            }
            if (kind == "archiveShare") {
                val ids = args.getJSONArray("ids")
                require(ids.length() in 1..500)
                (0 until ids.length()).forEach { require(ids.getString(it).length in 1..512) }
            }
            val all = load(context)
            all.firstOrNull { it.getString("kind") == kind && it.getString("status") in setOf("queued", "running") }
                ?.let { return it.getString("id") }
            check(all.count { it.getString("status") in setOf("queued", "running") } < 16) { "Background task queue is full" }
            val id = UUID.randomUUID().toString()
            all += JSONObject().put("id", id).put("kind", kind).put("args", args)
                .put("status", "queued").put("created", System.currentTimeMillis())
            save(context, all)
            try { context.startForegroundService(Intent(context, RuntimeJobsService::class.java)) }
            catch (error: RuntimeException) {
                change(context, id) { it.put("status", "error").put("error", "Job dispatch failed: ${error.javaClass.simpleName}") }
                throw error
            }
            id
        }

        fun snapshot(context: Context): String = synchronized(LOCK) {
            JSONArray(load(context).filterNot { it.optBoolean("presented") }.map { item ->
                JSONObject(item.toString()).apply { optJSONObject("args")?.remove("notes") }
            }).toString()
        }

        fun presented(context: Context, id: String) {
            require(id.length <= 64)
            change(context, id) { check(it.getString("status") !in setOf("queued", "running")); it.put("presented", true) }
        }

        fun prepareResult(context: Context, id: String) = synchronized(LOCK) {
            require(id.length <= 64)
            val job = load(context).first { it.getString("id") == id }
            check(job.getString("status") == "complete")
            val result = job.getJSONObject("result")
            val uris = mutableListOf<String>()
            result.optString("uri").takeIf(String::isNotBlank)?.let(uris::add)
            result.optJSONArray("uris")?.let { list -> (0 until list.length()).forEach { uris += list.getString(it) } }
            uris.forEach { value ->
                val uri = android.net.Uri.parse(value)
                check(uri.scheme == "content" && uri.authority == "${context.packageName}.fileprovider")
                // Force-stop revokes temporary grants, not the completed native job.
                context.grantUriPermission(RuntimeEndpoint.UI_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        fun cancelForShutdown(context: Context) = synchronized(LOCK) {
            check(recordingPrefs(context).edit().putBoolean("recording", false).commit())
            val all = load(context)
            all.filter { it.getString("status") in setOf("queued", "running") }.forEach {
                if (it.has("downloadId")) context.getSystemService(android.app.DownloadManager::class.java).remove(it.getLong("downloadId"))
                it.put("status", "error").put("error", "Shutdown requested")
            }
            save(context, all)
            context.stopService(Intent(context, RuntimeJobsService::class.java))
        }

        private fun file(context: Context) = AtomicFile(File(context.filesDir, "runtime_jobs.json"))
        private fun recordingPrefs(context: Context) = context.getSharedPreferences("runtime_recording", Context.MODE_PRIVATE)
        private fun load(context: Context): MutableList<JSONObject> {
            cached?.let { return it.map { entry -> JSONObject(entry.toString()) }.toMutableList() }
            val source = file(context)
            if (!source.baseFile.exists()) return mutableListOf()
            val array = JSONArray(source.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            return (0 until array.length()).map { array.getJSONObject(it) }.toMutableList().also { cached = it }
        }
        private fun save(context: Context, jobs: List<JSONObject>) {
            val pending = jobs.filter { it.getString("status") in setOf("queued", "running") }
            val terminal = jobs.filterNot { it in pending }.takeLast(64)
            val target = file(context)
            val output = target.startWrite()
            try {
                output.write(JSONArray(terminal + pending).toString().toByteArray(Charsets.UTF_8))
                target.finishWrite(output)
                cached = (terminal + pending).map { JSONObject(it.toString()) }.toMutableList()
            } catch (error: Exception) { target.failWrite(output); throw error }
        }
        private fun change(context: Context, id: String, update: (JSONObject) -> Unit) = synchronized(LOCK) {
            val all = load(context)
            update(all.first { it.getString("id") == id })
            save(context, all)
        }
    }
}
