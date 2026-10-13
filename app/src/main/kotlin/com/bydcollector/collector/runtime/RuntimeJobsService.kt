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
import com.bydcollector.collector.mqtt.HaMqttConfig
import com.bydcollector.collector.mqtt.MqttCleanupCore
import com.bydcollector.collector.mqtt.MqttCleanupSelection
import com.bydcollector.collector.service.CollectorService
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.update.UpdateApkVerifier
import com.bydcollector.collector.update.UpdateDownloader
import com.bydcollector.collector.update.UpdateInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private class UpdateCancellationRequested : Exception()

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
            val ready = all.filter {
                it.optString("status") in setOf("queued", "cancelling") && lanes.add(lane(it.getString("kind")))
            }
            ready.filter { it.optString("status") == "queued" }.forEach { it.put("status", "running") }
            if (ready.isNotEmpty()) save(this, all)
            ready.map { JSONObject(it.toString()) }
        }
        jobs.forEach { job ->
            executor.execute {
                try {
                    if (job.getString("kind") == "updateDownload" && cancellationRequested(this, job.getString("id"))) {
                        finishUpdateCancellation(this, job.getString("id"))
                        return@execute
                    }
                    check(!CollectorSettings(this).isUserShutdownRequested()) { "Shutdown requested" }
                    val result = execute(job)
                    if (destroyed) return@execute
                    if (job.getString("kind") == "updateDownload" && !complete(this, job.getString("id"), result)) {
                        if (cancellationRequested(this, job.getString("id"))) finishUpdateCancellation(this, job.getString("id"))
                        return@execute
                    }
                    val warnings = result.optJSONArray("warnings")
                    if (warnings != null && warnings.length() > 0) {
                        recordDiagnostic(job, "partial", warnings.toString())
                    } else if (result.has("ok") && !result.optBoolean("ok")) {
                        recordDiagnostic(job, "failed", result.optString("message"))
                    }
                    if (job.getString("kind") != "updateDownload") complete(this, job.getString("id"), result)
                } catch (error: Exception) {
                    if (destroyed) return@execute
                    if (job.getString("kind") == "updateDownload" && cancellationRequested(this, job.getString("id"))) {
                        runCatching { finishUpdateCancellation(this, job.getString("id")) }
                            .onFailure { cleanupError ->
                                markCancellationCleanupFailure(this, job.getString("id"), cleanupError)
                                recordDiagnostic(job, "cancel_cleanup_failed", cleanupError.message.orEmpty())
                            }
                    } else {
                        recordDiagnostic(job, "failed", "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                        change(this, job.getString("id")) {
                            if (it.getString("status") == "running") it.put("status", "error")
                                .put("error", "${error.javaClass.simpleName}: ${error.message.orEmpty().take(300)}")
                        }
                        if (job.getString("kind") == "updateDownload") runCatching {
                            RuntimeControl.call(this, "installFinish", android.os.Bundle())
                        }.onFailure { controlError ->
                            android.util.Log.w("RuntimeJobs", "Could not finish update session after failure", controlError)
                        }
                    }
                    if (error is InterruptedException) Thread.currentThread().interrupt()
                } finally {
                    main.post { lanes.remove(lane(job.getString("kind"))); drain() }
                }
            }
        }
        if (lanes.isEmpty() && !DiagnosticLogRecorder.isRecording()) stopSelf(lastStartId)
    }

    private fun recordDiagnostic(job: JSONObject, outcome: String, detail: String) {
        // Failure to write diagnostics must not change the operation's result.
        runCatching {
            (applicationContext as BydCollectorApplication).operationalEventJournal.append(
                java.time.Instant.now().toString(), android.os.SystemClock.elapsedRealtime(),
                "runtime_job", "${job.getString("kind")}_$outcome", detail
            )
        }
    }

    private fun throwIfUpdateCancelled(id: String) {
        if (cancellationRequested(this, id)) throw UpdateCancellationRequested()
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
            "mqttCleanup" -> {
                val selection = parseCleanupSelection(args)
                val settings = CollectorSettings(this)
                val execution = MqttCleanupCore(
                    journal = app.mqttPublicationJournal,
                    configProvider = { settings.mqttConfig() }
                ).execute(selection) {
                    Thread.currentThread().isInterrupted || settings.isUserShutdownRequested()
                }
                check(execution.result.ok) { "${execution.result.category}: ${execution.result.message}" }
                JSONObject()
                    .put("jobId", job.getString("id"))
                    .put("deviceId", selection.deviceId)
                    .put("fingerprint", selection.fingerprint)
                    .put("operationId", checkNotNull(execution.operationId))
                    .put("acknowledged", true)
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
                val info = updateInfo(job)
                throwIfUpdateCancelled(job.getString("id"))
                RuntimeControl.call(this, "installStart", android.os.Bundle())
                throwIfUpdateCancelled(job.getString("id"))
                app.releaseNotesHistory.request(info)
                throwIfUpdateCancelled(job.getString("id"))
                val downloader = UpdateDownloader(this)
                val downloadId = synchronized(LOCK) {
                    val all = load(this)
                    val current = all.first { it.getString("id") == job.getString("id") }
                    if (current.optBoolean("cancelRequested") || current.getString("status") == "cancelling") {
                        throw UpdateCancellationRequested()
                    }
                    current.optLong("downloadId", -1L).takeIf { it > 0 } ?: downloader.enqueue(info).also { id ->
                        current.put("downloadId", id)
                        save(this, all)
                    }
                }
                var previous = -1
                while (true) {
                    throwIfUpdateCancelled(job.getString("id"))
                    check(!Thread.currentThread().isInterrupted && !CollectorSettings(this).isUserShutdownRequested()) { "Shutdown requested" }
                    val progress = downloader.progress(downloadId)
                    throwIfUpdateCancelled(job.getString("id"))
                    check(progress >= 0) { "Update download failed" }
                    if (progress != previous) {
                        change(this, job.getString("id")) { it.put("progress", progress) }
                        previous = progress
                    }
                    if (progress >= 100) break
                    Thread.sleep(350)
                }
                throwIfUpdateCancelled(job.getString("id"))
                val file = File(cacheDir, "updates/runtime-${job.getString("id")}.apk")
                file.parentFile?.mkdirs()
                downloader.downloadedFile(info).copyTo(file, overwrite = true)
                throwIfUpdateCancelled(job.getString("id"))
                val validation = UpdateApkVerifier(this).validate(file)
                throwIfUpdateCancelled(job.getString("id"))
                check(validation.ok) { validation.message }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                JSONObject().put("uri", uri.toString()).put("sha256", validation.sha256)
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
        private val mqttConfigurationKeys = setOf(
            CollectorSettings.KEY_MQTT_HOST,
            CollectorSettings.KEY_MQTT_PORT,
            CollectorSettings.KEY_MQTT_ALTERNATIVE_HOST,
            CollectorSettings.KEY_MQTT_ALTERNATIVE_PORT,
            CollectorSettings.KEY_MQTT_USERNAME,
            CollectorSettings.KEY_MQTT_PASSWORD,
            CollectorSettings.KEY_MQTT_CLIENT_ID,
            CollectorSettings.KEY_MQTT_TOPIC_PREFIX,
            CollectorSettings.KEY_MQTT_DISCOVERY_PREFIX,
            CollectorSettings.KEY_MQTT_DEVICE_ID,
            CollectorSettings.KEY_MQTT_DEVICE_IDENTIFIER,
            CollectorSettings.KEY_MQTT_DEVICE_NAME,
            CollectorSettings.KEY_MQTT_DEVICE_MODEL,
            CollectorSettings.SECRET_MQTT_USERNAME,
            CollectorSettings.SECRET_MQTT_PASSWORD
        )
        private fun lane(kind: String) = when { kind.startsWith("log") || kind == "archiveShare" -> "diagnostics"; kind.endsWith("Test") -> "tests"; else -> kind }
        private val accepted = setOf("access", "mqttTest", "mqttCleanup", "influxTest", "logStart", "logStop", "logShare", "logClear", "archiveShare", "updateDownload")

        fun recover(context: Context) {
            if (CollectorSettings(context).isUserShutdownRequested()) return
            if (recordingPrefs(context).getBoolean("recording", false) && !DiagnosticLogRecorder.isRecording()) {
                submit(context, "logStart", JSONObject())
            }
            val pending = synchronized(LOCK) {
                load(context).any { it.optString("status") in setOf("queued", "running", "cancelling") }
            }
            if (pending) context.startForegroundService(Intent(context, RuntimeJobsService::class.java))
        }

        fun submit(context: Context, kind: String, args: JSONObject): String = synchronized(LOCK) {
            require(kind in accepted && args.toString().length <= 64 * 1024) { "Invalid background job" }
            check(!CollectorSettings(context).isUserShutdownRequested()) { "Shutdown requested" }
            val requestedCleanup = if (kind == "mqttCleanup") parseCleanupSelection(args) else null
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
            if (kind == "updateDownload") {
                check(all.none {
                    it.optString("kind") == kind && it.optString("status") in setOf("cancelling", "cancelError")
                }) { "Update cancellation cleanup is still pending" }
            }
            if (requestedCleanup != null) {
                all.firstOrNull { it.getString("kind") == kind && it.getString("status") in setOf("queued", "running", "cancelling") }
                    ?.let { active ->
                        check(parseCleanupSelection(active.getJSONObject("args")) == requestedCleanup) {
                            "A different MQTT cleanup is already queued or running"
                        }
                        return active.getString("id")
                    }
            }
            all.firstOrNull { it.getString("kind") == kind && it.getString("status") in setOf("queued", "running", "cancelling") }
                ?.let { return it.getString("id") }
            check(all.count { it.getString("status") in setOf("queued", "running", "cancelling") } < 16) { "Background task queue is full" }
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

        fun <T> withMqttConfigurationEdit(context: Context, keys: Set<String>, action: () -> T): T {
            if (!isMqttConfigurationEdit(keys)) return action()
            return synchronized(LOCK) {
                val cleanupActive = load(context).any {
                    it.optString("kind") == "mqttCleanup" && it.optString("status") in setOf("queued", "running")
                }
                check(!cleanupActive) { "MQTT destination settings cannot change while cleanup is queued or running" }
                action()
            }
        }

        fun isMqttConfigurationEdit(keys: Set<String>): Boolean = keys.any(mqttConfigurationKeys::contains)

        private fun parseCleanupSelection(args: JSONObject): MqttCleanupSelection {
            require(args.length() == 2 && args.has("deviceId") && args.has("fingerprint")) {
                "MQTT cleanup requires only a Device ID and selection fingerprint"
            }
            require(args.opt("deviceId") is String && args.opt("fingerprint") is String) {
                "Invalid MQTT cleanup selection"
            }
            val deviceId = args.getString("deviceId")
            val fingerprint = args.getString("fingerprint")
            require(deviceId.isNotBlank() && HaMqttConfig.isValidDeviceId(deviceId)) {
                "Invalid MQTT cleanup Device ID"
            }
            require(fingerprint.matches(Regex("[a-f0-9]{64}"))) { "Invalid MQTT cleanup selection fingerprint" }
            return MqttCleanupSelection(deviceId, fingerprint)
        }

        fun snapshot(context: Context): String {
            if (com.bydcollector.collector.BuildConfig.RUNTIME_CLIENT) {
                return RuntimeReads.read(context, "jobs")
            }
            return synchronized(LOCK) {
                JSONArray(load(context).filterNot { it.optBoolean("presented") }.map { item ->
                    JSONObject(item.toString()).apply {
                        if (optString("kind") != "updateDownload") optJSONObject("args")?.remove("notes")
                    }
                }).toString()
            }
        }

        internal fun updateInfo(job: JSONObject): UpdateInfo = job.getJSONObject("args").let { args ->
            UpdateInfo(args.getString("version"), args.getString("url"), args.optString("notes"),
                args.optString("contentType").takeIf { it.isNotBlank() })
        }

        internal fun activeUpdateInfo(context: Context): UpdateInfo? = synchronized(LOCK) {
            val jobs = load(context)
            (jobs.lastOrNull {
                it.optString("kind") == "updateDownload" && it.optString("status") in setOf("queued", "running", "cancelling")
            } ?: jobs.lastOrNull {
                it.optString("kind") == "updateDownload" && !it.optBoolean("presented") &&
                    it.optString("status") in setOf("cancelled", "cancelError")
            })?.let(::updateInfo)
        }

        fun presented(context: Context, id: String) {
            require(id.length <= 64)
            if (com.bydcollector.collector.BuildConfig.RUNTIME_CLIENT) {
                RuntimeEndpoint.call(context, "job.presented", android.os.Bundle().apply { putString("id", id) })
                return
            }
            change(context, id) {
                check(it.getString("status") !in setOf("queued", "running", "cancelling"))
                it.put("presented", true)
                if (it.getString("kind") == "updateDownload" && it.optBoolean("installerClaimed")) {
                    it.put("installerHandoffAcknowledged", true)
                    it.remove("installerReopenPending")
                    it.remove("installerRedeliveryInProgress")
                }
            }
        }

        fun dismissUpdateDialog(context: Context, id: String) {
            require(id.length in 1..64)
            if (com.bydcollector.collector.BuildConfig.RUNTIME_CLIENT) {
                RuntimeEndpoint.call(context, "job.dismissUpdate", android.os.Bundle().apply { putString("id", id) })
                return
            }
            change(context, id) {
                check(it.getString("kind") == "updateDownload")
                it.put("dialogDismissed", true)
            }
        }

        fun reopenUpdateDialog(context: Context, id: String?, callerPid: Int = android.os.Process.myPid()): Pair<String, String>? {
            require(id == null || id.length in 1..64)
            if (com.bydcollector.collector.BuildConfig.RUNTIME_CLIENT) {
                val response = RuntimeEndpoint.call(context, "job.reopenUpdate", android.os.Bundle().apply {
                    putString("id", id)
                    putInt("pid", callerPid)
                })
                val reopenedId = response.getString("id") ?: return null
                return reopenedId to requireNotNull(response.getString("jobs"))
            }
            return synchronized(LOCK) {
                val all = load(context)
                val job = all.lastOrNull {
                    it.optString("kind") == "updateDownload" && it.optString("status") in setOf("queued", "running", "cancelling")
                } ?: id?.let { selectedId -> all.firstOrNull {
                    it.optString("id") == selectedId && it.optString("kind") == "updateDownload" && !it.optBoolean("presented")
                } } ?: all.lastOrNull {
                    it.optString("kind") == "updateDownload" && !it.optBoolean("presented")
                }
                    ?: return@synchronized null
                var changed = false
                if (job.has("dialogDismissed")) {
                    job.remove("dialogDismissed")
                    changed = true
                }
                if (job.optString("status") == "complete" && job.optBoolean("installerClaimed")) {
                    // Keep the cancellation fence; an explicit reopen permits one verified re-delivery.
                    job.put("installerClaimedPid", callerPid).put("installerReopenPending", true)
                    changed = true
                }
                if (changed) save(context, all)
                job.getString("id") to snapshot(context)
            }
        }

        fun prepareResult(context: Context, id: String, callerPid: Int = android.os.Process.myPid()): Boolean = synchronized(LOCK) {
            require(id.length <= 64)
            if (com.bydcollector.collector.BuildConfig.RUNTIME_CLIENT) {
                val response = RuntimeEndpoint.call(context, "job.prepare", android.os.Bundle().apply {
                    putString("id", id)
                    putInt("pid", callerPid)
                })
                return@synchronized response.getBoolean("ready")
            }
            val all = load(context)
            val job = all.firstOrNull { it.getString("id") == id } ?: return@synchronized false
            if (job.getString("status") != "complete" || job.optBoolean("cancelRequested")) return@synchronized false
            val redelivery = job.getString("kind") == "updateDownload" && job.optBoolean("installerClaimed") &&
                job.optBoolean("installerReopenPending") && job.optInt("installerClaimedPid", -1) == callerPid
            if (job.getString("kind") == "updateDownload" && job.optBoolean("installerClaimed") && !redelivery) {
                return@synchronized false
            }
            val result = job.getJSONObject("result")
            if (job.getString("kind") == "updateDownload") {
                val stagedFile = File(context.cacheDir, "updates/runtime-$id.apk")
                check(stagedFile.isFile) { "Verified update staging file is missing" }
                val expectedUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", stagedFile)
                check(result.optString("uri") == expectedUri.toString()) { "Update installer path no longer matches its verified staging file" }
                val expectedSha = result.optString("sha256")
                check(expectedSha.matches(Regex("[a-f0-9]{64}")) && UpdateApkVerifier(context).sha256(stagedFile) == expectedSha) {
                    "Verified update changed before installer handoff"
                }
            }
            val uris = mutableListOf<String>()
            result.optString("uri").takeIf(String::isNotBlank)?.let(uris::add)
            result.optJSONArray("uris")?.let { list -> (0 until list.length()).forEach { uris += list.getString(it) } }
            uris.forEach { value ->
                val uri = android.net.Uri.parse(value)
                check(uri.scheme == "content" && uri.authority == "${context.packageName}.fileprovider")
                // Force-stop revokes temporary grants, not the completed native job.
                context.grantUriPermission(RuntimeEndpoint.UI_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (job.getString("kind") == "updateDownload") {
                job.put("installerClaimed", true).put("installerClaimedPid", callerPid)
                if (redelivery) {
                    job.remove("installerReopenPending")
                    job.put("installerRedeliveryInProgress", true)
                }
                save(context, all)
            }
            true
        }

        fun releaseInstallerClaim(context: Context, id: String, callerPid: Int = android.os.Process.myPid()) {
            require(id.length <= 64)
            if (com.bydcollector.collector.BuildConfig.RUNTIME_CLIENT) {
                RuntimeEndpoint.call(context, "job.releaseInstaller", android.os.Bundle().apply {
                    putString("id", id)
                    putInt("pid", callerPid)
                })
                return
            }
            change(context, id) {
                if (it.getString("kind") == "updateDownload" && it.getString("status") == "complete" &&
                    it.optBoolean("installerClaimed") && it.optInt("installerClaimedPid", -1) == callerPid) {
                    if (it.optBoolean("installerRedeliveryInProgress")) {
                        it.remove("installerRedeliveryInProgress")
                    } else if (!it.optBoolean("installerHandoffAcknowledged")) {
                        it.remove("installerClaimed")
                        it.remove("installerClaimedPid")
                    }
                }
            }
        }

        fun cancelUpdateDownload(context: Context, id: String, callerPid: Int = android.os.Process.myPid()): String {
            require(id.length in 1..64 && callerPid > 0)
            if (com.bydcollector.collector.BuildConfig.RUNTIME_CLIENT) {
                val response = RuntimeEndpoint.call(context, "job.cancelUpdate", android.os.Bundle().apply {
                    putString("id", id)
                    putInt("pid", callerPid)
                })
                RuntimeClient.refresh()
                return requireNotNull(response.getString("result"))
            }
            val result = synchronized(LOCK) {
                val all = load(context)
                val job = all.firstOrNull { it.getString("id") == id && it.getString("kind") == "updateDownload" }
                    ?: return@synchronized "NOT_FOUND"
                val status = job.getString("status")
                if (status == "cancelled") return@synchronized "CANCELLED"
                if (job.optBoolean("installerClaimed")) return@synchronized "INSTALLER_CLAIMED"
                if (status !in setOf("queued", "running", "complete", "cancelling", "cancelError")) {
                    return@synchronized "NOT_CANCELLABLE"
                }
                job.put("cancelRequested", true)
                job.remove("error")
                job.remove("cancelCleanupError")
                if (status == "complete" || status == "cancelError") job.put("status", "cancelling")
                val downloadId = job.optLong("downloadId", -1L)
                save(context, all)
                if (downloadId > 0L) runCatching {
                    context.getSystemService(android.app.DownloadManager::class.java).remove(downloadId)
                }
                "REQUESTED"
            }
            if (result == "REQUESTED") {
                runCatching { RuntimeControl.call(context, "installFinish", android.os.Bundle()) }
                    .onFailure { android.util.Log.w("RuntimeJobs", "Could not finish update session after cancellation", it) }
                runCatching { context.startForegroundService(Intent(context, RuntimeJobsService::class.java)) }
                    .onFailure { android.util.Log.w("RuntimeJobs", "Could not dispatch update cancellation cleanup", it) }
            }
            RuntimeEndpoint.changed(context)
            return result
        }

        fun cancelForShutdown(context: Context) = synchronized(LOCK) {
            check(recordingPrefs(context).edit().putBoolean("recording", false).commit())
            val all = load(context)
            all.filter { it.getString("status") in setOf("queued", "running") }.forEach {
                if (it.getString("kind") == "updateDownload" && it.optBoolean("cancelRequested")) {
                    it.put("status", "cancelling").remove("error")
                } else {
                    if (it.has("downloadId")) context.getSystemService(android.app.DownloadManager::class.java).remove(it.getLong("downloadId"))
                    it.put("status", "error").put("error", "Shutdown requested")
                }
            }
            save(context, all)
            context.stopService(Intent(context, RuntimeJobsService::class.java))
        }

        private fun cancellationRequested(context: Context, id: String): Boolean = synchronized(LOCK) {
            val job = load(context).firstOrNull { it.optString("id") == id } ?: return@synchronized false
            job.optBoolean("cancelRequested") || job.optString("status") in setOf("cancelling", "cancelError", "cancelled")
        }

        private fun complete(context: Context, id: String, result: JSONObject): Boolean = synchronized(LOCK) {
            val all = load(context)
            val job = all.firstOrNull { it.optString("id") == id } ?: return@synchronized false
            if (job.optString("status") != "running" ||
                (job.optString("kind") == "updateDownload" && job.optBoolean("cancelRequested"))) {
                return@synchronized false
            }
            job.put("status", "complete").put("result", result)
            save(context, all)
            true
        }

        private fun finishUpdateCancellation(context: Context, id: String): Boolean {
            val target = synchronized(LOCK) {
                val all = load(context)
                val job = all.firstOrNull { it.optString("id") == id && it.optString("kind") == "updateDownload" }
                    ?: error("Update job disappeared during cancellation")
                if (job.optString("status") == "cancelled") return@synchronized null
                check(job.optBoolean("cancelRequested") || job.optString("status") in setOf("cancelling", "cancelError")) {
                    "Update cancellation was not requested"
                }
                check(!job.optBoolean("installerClaimed")) { "Installer handoff already claimed" }
                job.put("status", "cancelling").put("cancelRequested", true)
                save(context, all)
                JSONObject(job.toString())
            } ?: return true

            target.optLong("downloadId", -1L).takeIf { it > 0L }?.let {
                context.getSystemService(android.app.DownloadManager::class.java).remove(it)
            }
            val info = updateInfo(target)
            deleteExact(UpdateDownloader(context).downloadedFile(info))
            deleteExact(File(context.cacheDir, "updates/runtime-$id.apk"))
            target.optJSONObject("result")?.optString("uri")?.takeIf(String::isNotBlank)?.let { value ->
                runCatching {
                    context.revokeUriPermission(android.net.Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            RuntimeControl.call(context, "installFinish", android.os.Bundle())

            synchronized(LOCK) {
                val all = load(context)
                val job = all.first { it.getString("id") == id }
                check(job.optBoolean("cancelRequested") && !job.optBoolean("installerClaimed")) {
                    "Update cancellation lost its owner fence"
                }
                job.put("status", "cancelled").put("cancelledAt", System.currentTimeMillis())
                job.remove("downloadId")
                job.remove("progress")
                job.remove("result")
                job.remove("error")
                job.remove("cancelCleanupError")
                save(context, all)
            }
            RuntimeEndpoint.changed(context)
            return true
        }

        private fun markCancellationCleanupFailure(context: Context, id: String, error: Throwable) = synchronized(LOCK) {
            val all = load(context)
            val job = all.firstOrNull { it.optString("id") == id } ?: return@synchronized
            job.put("status", "cancelError")
                .put("cancelRequested", true)
                .put("cancelCleanupError", "${error.javaClass.simpleName}: ${error.message.orEmpty().take(300)}")
            save(context, all)
            RuntimeEndpoint.changed(context)
        }

        private fun deleteExact(file: File) {
            check(!file.exists() || file.delete()) { "Could not remove ${file.name}" }
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
            val pending = jobs.filter { it.getString("status") in setOf("queued", "running", "cancelling", "cancelError") }
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
