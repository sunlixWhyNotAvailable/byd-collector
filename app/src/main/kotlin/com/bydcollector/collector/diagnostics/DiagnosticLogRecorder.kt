package com.bydcollector.collector.diagnostics

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.maintenance.ArchiveShareLeaseRegistry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

//captures a small support bundle without making dashboard refresh perform zip work
object DiagnosticLogRecorder {
    private const val DIAGNOSTICS_DIR = "diagnostics"
    private const val DIAGNOSTIC_SHARES_DIR = "diagnostic_shares"
    private const val LATEST_ZIP_NAME = "bydcollector_diagnostics_latest.zip"
    private const val EVENTS_SNAPSHOT_NAME = "collector_events_snapshot.txt"
    private const val KEEP_ALIVE_LOG_PATH = "/data/local/tmp/bydcollector_keepalive.log"
    private const val KEEP_ALIVE_LOG_SNAPSHOT_NAME = "bydcollector_keepalive.log"
    private const val KEEP_ALIVE_LOG_STATUS_NAME = "bydcollector_keepalive_status.txt"
    private const val KEEP_ALIVE_LOG_TAIL_BYTES = 512 * 1024
    private const val LOGCAT_COMMAND = "logcat -b all -v threadtime"
    internal const val SHARE_HANDOFF_RETENTION_MS = ArchiveShareLeaseRegistry.LEASE_TTL_MS

    @Volatile private var adbStream: AdbLocalClient.AdbShellStream? = null
    @Volatile private var activeRunDir: File? = null
    @Volatile private var activeContext: Context? = null

    @Synchronized
    fun isRecording(): Boolean {
        val current = adbStream
        if (current?.isAlive == true) return true
        adbStream = null
        activeRunDir = null
        activeContext = null
        return false
    }

    @Synchronized
    fun start(context: Context): File {
        if (isRecording()) return activeRunDir ?: logRoot(context)

        val appContext = context.applicationContext
        //creates one run directory per recording so logs, events, and notes describe the same incident window
        val runDir = createDiagnosticRunDirectory(logRoot(appContext), timestamp())
        activeContext = appContext
        activeRunDir = runDir
        try {
            File(runDir, "diagnostic_info.txt").writeText(
                buildString {
                    appendLine("started_at=${timestamp()}")
                    appendLine("package=${BuildConfig.APPLICATION_ID}")
                    appendLine("log_dir=${runDir.absolutePath}")
                },
                Charsets.UTF_8
            )
            writeCollectorEventsSnapshot(appContext, runDir)
            writeKeepAliveLogNote(runDir)
            //updates latest zip only at explicit diagnostics lifecycle points, never from passive ui state loading
            writeLatestZip(appContext, runDir)

            val output = File(runDir, "logcat_threadtime.txt").outputStream()
            try {
                adbStream = AdbLocalClient(File(appContext.filesDir, "adb_keys")).openShellStream(
                    command = LOGCAT_COMMAND,
                    output = output
                )
            } catch (error: Throwable) {
                runCatching { output.close() }
                throw error
            }
        } catch (error: Throwable) {
            adbStream = null
            activeRunDir = null
            activeContext = null
            File(runDir, "logcat_error.txt").writeText(
                "full_system_logcat_unavailable=${error::class.java.simpleName}: ${error.message ?: "no message"}\n" +
                    "command=$LOGCAT_COMMAND\n",
                Charsets.UTF_8
            )
            writeLatestZip(context, runDir)
            throw IllegalStateException("Full system logcat unavailable: ${error.message ?: error::class.java.simpleName}", error)
        }
        return runDir
    }

    @Synchronized
    fun stop(): File? {
        val runDir = activeRunDir
        val context = activeContext
        try {
            adbStream?.close()
            runDir?.let {
                File(it, "stopped.txt").writeText("stopped_at=${timestamp()}\n", Charsets.UTF_8)
                context?.let { appContext -> writeCollectorEventsSnapshot(appContext, it) }
                writeKeepAliveLogNote(it)
                writeLatestZipFromRunDir(it)
            }
            return runDir
        } finally {
            adbStream = null
            activeRunDir = null
            activeContext = null
        }
    }

    @Synchronized
    fun prepareShareBundle(context: Context): File {
        val appContext = context.applicationContext
        val root = logRoot(appContext)
        val captureStamp = timestamp()
        val snapshotDir = createDiagnosticSnapshotDirectory(root, captureStamp)
        return try {
            val logcatStatus = writeLogcatSnapshot(root, snapshotDir)
            val journalStatus = writeOperationalJournalSnapshot(appContext, snapshotDir)
            val databaseStatus = writeCollectorEventsSnapshot(appContext, snapshotDir)
            val helperStatus = writeKeepAliveLogSnapshot(appContext, snapshotDir)
            File(snapshotDir, "diagnostic_info.txt").writeText(
                buildString {
                    appendLine("captured_at=${timestampIso()}")
                    appendLine("package=${BuildConfig.APPLICATION_ID}")
                    appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("snapshot=${snapshotDir.name}")
                    appendLine("logcat=$logcatStatus")
                    appendLine("operational_journal=$journalStatus")
                    appendLine("collector_events=$databaseStatus")
                    appendLine("keep_alive_log=$helperStatus")
                },
                Charsets.UTF_8
            )
            val latestZip = latestZip(appContext).also { writeLatestZip(it, snapshotDir) }
            val shareRoot = shareRoot(appContext)
            pruneExpiredDiagnosticShareFiles(shareRoot, System.currentTimeMillis(), SHARE_HANDOFF_RETENTION_MS)
            createDiagnosticShareCopy(latestZip, shareRoot, captureStamp)
        } finally {
            snapshotDir.deleteRecursively()
        }
    }

    @Synchronized
    fun clearCompleted(context: Context): DiagnosticClearResult {
        val appContext = context.applicationContext
        val active = activeRunDir?.takeIf { isRecording() }
        var removed = 0
        val warnings = mutableListOf<String>()
        runCatching { clearCompletedDiagnosticFiles(logRoot(appContext), active) }
            .onSuccess { removed += it }
            .onFailure { warnings += "diagnostic_files=${it::class.java.simpleName}: ${it.message ?: "no message"}" }
        runCatching {
            pruneExpiredDiagnosticShareFiles(
                File(appContext.cacheDir, DIAGNOSTIC_SHARES_DIR),
                System.currentTimeMillis(),
                SHARE_HANDOFF_RETENTION_MS
            )
        }.onSuccess { removed += it }
            .onFailure { warnings += "share_cache=${it::class.java.simpleName}: ${it.message ?: "no message"}" }
        runCatching {
            (appContext as BydCollectorApplication).operationalEventJournal.clear()
        }.onSuccess { removed += it }
            .onFailure { warnings += "operational_journal=${it::class.java.simpleName}: ${it.message ?: "no message"}" }
        val helperResult = runCatching {
            AdbLocalClient(File(appContext.filesDir, "adb_keys")).execShell(
                command = ": > $KEEP_ALIVE_LOG_PATH",
                timeoutMs = 10_000,
                allowAuthorizationPrompt = false
            )
        }.getOrElse { error ->
            warnings += "keep_alive_log=${error::class.java.simpleName}: ${error.message ?: "no message"}"
            null
        }
        if (helperResult != null && !helperResult.ok) {
            warnings += "keep_alive_log=${helperResult.error ?: "truncate failed"}"
        }
        return DiagnosticClearResult(removed = removed, warnings = warnings)
    }

    private fun logRoot(context: Context): File {
        val root = File(context.filesDir, DIAGNOSTICS_DIR)
        check(root.isDirectory || root.mkdirs()) { "Failed to create diagnostics directory: ${root.absolutePath}" }
        return root
    }

    private fun latestZip(context: Context): File = File(logRoot(context), LATEST_ZIP_NAME)

    private fun shareRoot(context: Context): File {
        val root = File(context.cacheDir, DIAGNOSTIC_SHARES_DIR)
        check(root.isDirectory || root.mkdirs()) { "Failed to create diagnostic share directory: ${root.absolutePath}" }
        return root
    }

    private fun writeKeepAliveLogNote(runDir: File) {
        File(runDir, KEEP_ALIVE_LOG_SNAPSHOT_NAME).writeText(
            "source_path=$KEEP_ALIVE_LOG_PATH\n",
            Charsets.UTF_8
        )
    }

    private fun writeOperationalJournalSnapshot(context: Context, runDir: File): String {
        val output = File(runDir, "operational_journal")
        return runCatching {
            val count = (context.applicationContext as BydCollectorApplication)
                .operationalEventJournal
                .snapshotTo(output)
            File(output, "status.txt").writeText(
                "status=ok\nsegments=$count\n",
                Charsets.UTF_8
            )
            "ok segments=$count"
        }.getOrElse { error ->
            output.mkdirs()
            File(output, "status.txt").writeText(
                "status=error\nerror=${error::class.java.simpleName}: ${error.message ?: "no message"}\n",
                Charsets.UTF_8
            )
            "error"
        }
    }

    private fun writeLogcatSnapshot(root: File, runDir: File): String {
        val active = activeRunDir?.takeIf { isRecording() && it.isDirectory }
        val source = active ?: latestCompletedDiagnosticRun(root)
        val state = when {
            source == null -> "none"
            source == active -> "active"
            File(source, "logcat_error.txt").isFile -> "failed"
            File(source, "stopped.txt").isFile -> "stopped"
            else -> "orphaned"
        }
        val destination = File(runDir, "logcat")
        var copiedFiles = 0
        var copiedBytes = 0L
        var errorText: String? = null
        if (source != null) {
            runCatching {
                check(destination.isDirectory || destination.mkdirs()) {
                    "Failed to create logcat snapshot directory: ${destination.absolutePath}"
                }
                source.listFiles().orEmpty()
                    .filter { file ->
                        file.isFile && (
                            file.name.startsWith("logcat_") ||
                                file.name == "diagnostic_info.txt" ||
                                file.name == "stopped.txt"
                            )
                    }
                    .forEach { file ->
                        val copy = File(destination, file.name)
                        file.copyTo(copy, overwrite = true)
                        copiedFiles += 1
                        copiedBytes += copy.length()
                    }
            }.onFailure { error ->
                errorText = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
            }
        }
        File(runDir, "logcat_provenance.txt").writeText(
            buildString {
                appendLine("captured_at=${timestampIso()}")
                appendLine("state=$state")
                appendLine("source_run=${source?.name ?: "none"}")
                appendLine("copied_files=$copiedFiles")
                appendLine("copied_bytes=$copiedBytes")
                errorText?.let { appendLine("copy_error=$it") }
                if (state == "active") appendLine("note=point-in-time best-effort copy of a live stream")
            },
            Charsets.UTF_8
        )
        return if (errorText == null) "$state files=$copiedFiles" else "$state partial"
    }

    private fun writeKeepAliveLogSnapshot(context: Context, runDir: File): String {
        val output = File(runDir, KEEP_ALIVE_LOG_SNAPSHOT_NAME)
        val status = File(runDir, KEEP_ALIVE_LOG_STATUS_NAME)
        return runCatching {
            val result = AdbLocalClient(File(context.filesDir, "adb_keys")).execShell(
                command = "tail -c $KEEP_ALIVE_LOG_TAIL_BYTES $KEEP_ALIVE_LOG_PATH 2>/dev/null",
                timeoutMs = 10_000,
                allowAuthorizationPrompt = false
            )
            if (!result.ok) {
                status.writeText(
                    "status=unavailable\nsource_path=$KEEP_ALIVE_LOG_PATH\nlimit_bytes=$KEEP_ALIVE_LOG_TAIL_BYTES\n" +
                        "error=${result.error ?: "read failed"}\nelapsed_ms=${result.elapsedMs}\n",
                    Charsets.UTF_8
                )
                "unavailable"
            } else {
                output.writeText(result.output, Charsets.UTF_8)
                status.writeText(
                    "status=ok\nsource_path=$KEEP_ALIVE_LOG_PATH\nlimit_bytes=$KEEP_ALIVE_LOG_TAIL_BYTES\n" +
                        "captured_bytes=${output.length()}\nelapsed_ms=${result.elapsedMs}\n",
                    Charsets.UTF_8
                )
                "ok bytes=${output.length()}"
            }
        }.getOrElse { error ->
            runCatching {
                status.writeText(
                    "status=error\nsource_path=$KEEP_ALIVE_LOG_PATH\nlimit_bytes=$KEEP_ALIVE_LOG_TAIL_BYTES\n" +
                        "error=${error::class.java.simpleName}: ${error.message ?: "no message"}\n",
                    Charsets.UTF_8
                )
            }
            "error"
        }
    }

    private fun writeCollectorEventsSnapshot(context: Context, runDir: File): String {
        val output = File(runDir, EVENTS_SNAPSHOT_NAME)
        return runCatching {
            (context.applicationContext as BydCollectorApplication).withDatabaseRead {
                val dbFile = context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)
                if (!dbFile.exists()) {
                    output.writeText("collector_events_snapshot: database missing at ${dbFile.absolutePath}\n", Charsets.UTF_8)
                    return@withDatabaseRead "missing"
                }
                //opens sqlite read-only so diagnostics cannot mutate live telemetry while copying recent events
                SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                ).use { db ->
                    db.rawQuery(
                        """
                        SELECT id, ts, category, message, detail
                        FROM collector_events
                        ORDER BY id DESC
                        LIMIT 200
                        """.trimIndent(),
                        emptyArray()
                    ).use { cursor ->
                        val text = buildString {
                            while (cursor.moveToNext()) {
                                append(cursor.getLong(0))
                                    .append('\t')
                                    .append(cursor.getString(1))
                                    .append('\t')
                                    .append(cursor.getString(2))
                                    .append('\t')
                                    .append(cursor.getString(3))
                                    .append('\t')
                                    .append(cursor.getString(4).orEmpty())
                                    .append('\n')
                            }
                        }
                        output.writeText(text, Charsets.UTF_8)
                        "ok"
                    }
                }
            }
        }.getOrElse { error ->
            output.writeText(
                "collector_events_snapshot_error=${error::class.java.simpleName}: ${error.message ?: "no message"}\n",
                Charsets.UTF_8
            )
            "error"
        }
    }

    private fun writeLatestZip(context: Context, runDir: File) {
        writeLatestZip(latestZip(context), runDir)
    }

    private fun writeLatestZipFromRunDir(runDir: File) {
        val root = runDir.parentFile ?: return
        writeLatestZip(File(root, LATEST_ZIP_NAME), runDir)
    }

    private fun writeLatestZip(zipFile: File, runDir: File) {
        DiagnosticZipWriter.writeLatestZip(zipFile, runDir)
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }

    private fun timestampIso(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
    }
}

data class DiagnosticClearResult(
    val removed: Int,
    val warnings: List<String>
)

internal fun createDiagnosticRunDirectory(root: File, timestamp: String): File {
    return createUniqueDiagnosticDirectory(root, "logcat", timestamp)
}

private fun createDiagnosticSnapshotDirectory(root: File, timestamp: String): File {
    return createUniqueDiagnosticDirectory(root, "snapshot", timestamp)
}

private fun createUniqueDiagnosticDirectory(root: File, prefix: String, timestamp: String): File {
    check(root.isDirectory || root.mkdirs()) { "Failed to create diagnostics directory: ${root.absolutePath}" }
    var suffix = 1
    while (true) {
        val name = if (suffix == 1) "${prefix}_$timestamp" else "${prefix}_${timestamp}_$suffix"
        val candidate = File(root, name)
        if (candidate.mkdir()) return candidate
        check(candidate.exists()) { "Failed to create diagnostic run directory: ${candidate.absolutePath}" }
        suffix += 1
    }
}

internal fun latestCompletedDiagnosticRun(root: File): File? {
    return root.listFiles().orEmpty()
        .asSequence()
        .filter { it.isDirectory && it.name.startsWith("logcat_") }
        .maxByOrNull(File::lastModified)
}

internal fun clearCompletedDiagnosticFiles(root: File, activeRunDir: File?): Int {
    val active = activeRunDir?.canonicalFile
    var removed = 0
    root.listFiles().orEmpty().forEach { entry ->
        if (entry.canonicalFile == active) return@forEach
        check(entry.deleteRecursively() || !entry.exists()) {
            "Failed to delete diagnostic file: ${entry.absolutePath}"
        }
        removed += 1
    }
    return removed
}

internal fun createDiagnosticShareCopy(sourceZip: File, shareRoot: File, timestamp: String): File {
    check(shareRoot.isDirectory || shareRoot.mkdirs()) {
        "Failed to create diagnostic share directory: ${shareRoot.absolutePath}"
    }
    val shareFile = File.createTempFile("bydcollector_diagnostics_${timestamp}_", ".zip", shareRoot)
    return try {
        sourceZip.copyTo(shareFile, overwrite = true)
    } catch (error: Throwable) {
        shareFile.delete()
        throw error
    }
}

internal fun pruneExpiredDiagnosticShareFiles(root: File, nowMs: Long, retentionMs: Long): Int {
    if (!root.isDirectory) return 0
    val cutoff = nowMs - retentionMs
    var removed = 0
    root.listFiles().orEmpty()
        .filter { it.isFile && it.lastModified() <= cutoff }
        .forEach { file ->
            check(file.delete() || !file.exists()) {
                "Failed to delete diagnostic share file: ${file.absolutePath}"
            }
            removed += 1
        }
    return removed
}
