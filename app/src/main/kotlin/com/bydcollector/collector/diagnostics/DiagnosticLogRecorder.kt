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
        val runDir = activeRunDir?.takeIf(File::isDirectory)
            ?: latestCompletedDiagnosticRun(root)
            ?: createDiagnosticSnapshotDirectory(root, timestamp()).also { snapshotDir ->
                File(snapshotDir, "diagnostic_info.txt").writeText(
                    "captured_at=${timestamp()}\npackage=${BuildConfig.APPLICATION_ID}\nlog_dir=${snapshotDir.absolutePath}\n",
                    Charsets.UTF_8
                )
        }
        writeCollectorEventsSnapshot(appContext, runDir)
        writeKeepAliveLogNote(runDir)
        val latestZip = latestZip(appContext).also { writeLatestZip(it, runDir) }
        val shareRoot = shareRoot(appContext)
        pruneExpiredDiagnosticShareFiles(shareRoot, System.currentTimeMillis(), SHARE_HANDOFF_RETENTION_MS)
        return createDiagnosticShareCopy(latestZip, shareRoot, timestamp())
    }

    @Synchronized
    fun clearCompleted(context: Context): Int {
        val appContext = context.applicationContext
        val active = activeRunDir?.takeIf { isRecording() }
        return clearCompletedDiagnosticFiles(logRoot(appContext), active) +
            pruneExpiredDiagnosticShareFiles(
                File(appContext.cacheDir, DIAGNOSTIC_SHARES_DIR),
                System.currentTimeMillis(),
                SHARE_HANDOFF_RETENTION_MS
            )
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

    private fun writeCollectorEventsSnapshot(context: Context, runDir: File) {
        val output = File(runDir, EVENTS_SNAPSHOT_NAME)
        runCatching {
            (context.applicationContext as BydCollectorApplication).withDatabaseRead {
                val dbFile = context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)
                if (!dbFile.exists()) {
                    output.writeText("collector_events_snapshot: database missing at ${dbFile.absolutePath}\n", Charsets.UTF_8)
                    return@withDatabaseRead
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
                    }
                }
            }
        }.onFailure { error ->
            output.writeText(
                "collector_events_snapshot_error=${error::class.java.simpleName}: ${error.message ?: "no message"}\n",
                Charsets.UTF_8
            )
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
}

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
        .filter(File::isDirectory)
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
