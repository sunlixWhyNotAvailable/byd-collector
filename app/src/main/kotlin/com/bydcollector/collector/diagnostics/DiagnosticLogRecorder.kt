package com.bydcollector.collector.diagnostics

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

//captures a small support bundle without making dashboard refresh perform zip work
object DiagnosticLogRecorder {
    private const val DIAGNOSTICS_DIR = "diagnostics"
    private const val LATEST_ZIP_NAME = "bydcollector_diagnostics_latest.zip"
    private const val EVENTS_SNAPSHOT_NAME = "collector_events_snapshot.txt"
    private const val KEEP_ALIVE_LOG_PATH = "/data/local/tmp/bydcollector_keepalive.log"
    private const val KEEP_ALIVE_LOG_SNAPSHOT_NAME = "bydcollector_keepalive.log"
    private const val LOGCAT_COMMAND = "logcat -b all -v threadtime"

    @Volatile private var adbStream: AdbLocalClient.AdbShellStream? = null
    @Volatile private var activeRunDir: File? = null
    @Volatile private var activeContext: Context? = null

    @Synchronized
    fun isRecording(): Boolean {
        val current = adbStream
        if (current?.isAlive == true) return true
        adbStream = null
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

    private fun logRoot(context: Context): File {
        val root = File(context.filesDir, DIAGNOSTICS_DIR)
        check(root.isDirectory || root.mkdirs()) { "Failed to create diagnostics directory: ${root.absolutePath}" }
        return root
    }

    private fun latestZip(context: Context): File = File(logRoot(context), LATEST_ZIP_NAME)

    private fun writeKeepAliveLogNote(runDir: File) {
        File(runDir, KEEP_ALIVE_LOG_SNAPSHOT_NAME).writeText(
            "source_path=$KEEP_ALIVE_LOG_PATH\n",
            Charsets.UTF_8
        )
    }

    private fun writeCollectorEventsSnapshot(context: Context, runDir: File) {
        val output = File(runDir, EVENTS_SNAPSHOT_NAME)
        val dbFile = context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)
        if (!dbFile.exists()) {
            output.writeText("collector_events_snapshot: database missing at ${dbFile.absolutePath}\n", Charsets.UTF_8)
            return
        }
        runCatching {
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
    check(root.isDirectory || root.mkdirs()) { "Failed to create diagnostics directory: ${root.absolutePath}" }
    var suffix = 1
    while (true) {
        val name = if (suffix == 1) "logcat_$timestamp" else "logcat_${timestamp}_$suffix"
        val candidate = File(root, name)
        if (candidate.mkdir()) return candidate
        check(candidate.exists()) { "Failed to create diagnostic run directory: ${candidate.absolutePath}" }
        suffix += 1
    }
}
