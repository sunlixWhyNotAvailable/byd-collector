package com.bydcollector.collector.diagnostics

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.adb.AdbLocalClient
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.local.TelegramDiagnosticRow
import com.bydcollector.collector.data.local.TelegramDiagnosticSnapshot
import com.bydcollector.collector.data.trips.TripRouteDiagnosticEvidence
import com.bydcollector.collector.data.trips.TripSession
import com.bydcollector.collector.maintenance.ArchiveShareLeaseRegistry
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.withLock

internal data class DiagnosticTripSelection(
    val selection: String,
    val session: TripSession?
)

internal fun selectDiagnosticTrip(
    pendingTripId: String?,
    pendingPowerSessionId: String?,
    findByPowerSessionId: (String) -> TripSession?,
    findNewestClosed: () -> TripSession?
): DiagnosticTripSelection {
    val pending = pendingTripId?.takeIf(String::isNotBlank)
    if (pending != null) {
        val parent = pendingPowerSessionId?.takeIf(String::isNotBlank)
            ?: return DiagnosticTripSelection("pending_unlinked", null)
        return findByPowerSessionId(parent)?.let { DiagnosticTripSelection("pending", it) }
            ?: DiagnosticTripSelection("pending_missing", null)
    }
    return findNewestClosed()?.let { DiagnosticTripSelection("newest_closed", it) }
        ?: DiagnosticTripSelection("none", null)
}

//captures a small support bundle without making dashboard refresh perform zip work
object DiagnosticLogRecorder {
    private const val DIAGNOSTICS_DIR = "diagnostics"
    private const val DIAGNOSTIC_SHARES_DIR = "diagnostic_shares"
    private const val LATEST_ZIP_NAME = "bydcollector_diagnostics_latest.zip"
    private const val EVENTS_SNAPSHOT_NAME = "collector_events_snapshot.txt"
    private const val TRIPS_TELEGRAM_EVIDENCE_NAME = "trips_telegram_evidence.txt"
    internal const val TRIPS_TELEGRAM_EVIDENCE_MAX_BYTES = 64 * 1024
    private const val TELEGRAM_EVIDENCE_ROW_LIMIT = 64
    private const val KEEP_ALIVE_LOG_PATH = "/data/local/tmp/bydcollector_keepalive.log"
    private const val KEEP_ALIVE_LOG_SNAPSHOT_NAME = "bydcollector_keepalive.log"
    private const val KEEP_ALIVE_LOG_STATUS_NAME = "bydcollector_keepalive_status.txt"
    private const val KEEP_ALIVE_LOG_TAIL_BYTES = 512 * 1024
    private const val SHARE_PREPARE_HEADROOM_BYTES = 16L * 1024L * 1024L
    private const val LOGCAT_COMMAND = "logcat -b all -v threadtime"
    internal const val LOGCAT_SEGMENT_BYTES = 16L * 1024L * 1024L
    internal const val LOGCAT_SEGMENT_COUNT = 8
    internal const val SHARE_HANDOFF_RETENTION_MS = ArchiveShareLeaseRegistry.LEASE_TTL_MS

    private val workLock = Any()
    private val stateLock = Any()
    @Volatile private var adbStream: AdbLocalClient.AdbShellStream? = null
    @Volatile private var activeRunDir: File? = null
    @Volatile private var activeContext: Context? = null

    fun isRecording(): Boolean {
        synchronized(stateLock) {
            val current = adbStream
            if (current?.isAlive == true) return true
            if (current != null || activeRunDir != null || activeContext != null) {
                adbStream = null
                activeRunDir = null
                activeContext = null
            }
            return false
        }
    }

    fun start(context: Context): File {
        synchronized(workLock) {
            val current = captureState()
            if (current.stream?.isAlive == true) return current.runDir ?: logRoot(context)

            val appContext = context.applicationContext
            //creates one run directory per recording so logs, events, and notes describe the same incident window
            val runDir = createDiagnosticRunDirectory(logRoot(appContext), timestamp())
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

                val output = DiagnosticLogcatOutputStream(runDir)
                val stream = try {
                    AdbLocalClient(File(appContext.filesDir, "adb_keys")).openShellStream(
                        command = LOGCAT_COMMAND,
                        output = output
                    )
                } catch (error: Throwable) {
                    runCatching { output.close() }
                    throw error
                }
                synchronized(stateLock) {
                    adbStream = stream
                    activeRunDir = runDir
                    activeContext = appContext
                }
            } catch (error: Throwable) {
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
    }

    fun stop(): File? {
        synchronized(workLock) {
            val state = captureState()
            try {
                state.stream?.close()
                state.runDir?.let {
                    File(it, "stopped.txt").writeText("stopped_at=${timestamp()}\n", Charsets.UTF_8)
                    state.context?.let { appContext -> writeCollectorEventsSnapshot(appContext, it) }
                    writeKeepAliveLogNote(it)
                    writeLatestZipFromRunDir(it)
                }
                return state.runDir
            } finally {
                synchronized(stateLock) {
                    if (adbStream === state.stream) {
                        adbStream = null
                        activeRunDir = null
                        activeContext = null
                    }
                }
            }
        }
    }

    fun prepareShareBundle(context: Context): File {
        synchronized(workLock) {
            val appContext = context.applicationContext
            val root = logRoot(appContext)
            val captureStamp = timestamp()
            val sourceSize = diagnosticLogcatSourceBytes(root, captureState().runDir)
            val shareRoot = shareRoot(appContext)
            check(
                hasDiagnosticShareSpace(
                    usableBytes = shareRoot.usableSpace,
                    sourceBytes = sourceSize,
                    headroomBytes = SHARE_PREPARE_HEADROOM_BYTES
                )
            ) { "Insufficient storage for diagnostics share" }
            val snapshotDir = createDiagnosticSnapshotDirectory(root, captureStamp)
            return try {
                val logcatStatus = writeLogcatSnapshot(root, snapshotDir)
                val journalStatus = writeOperationalJournalSnapshot(appContext, snapshotDir)
                val databaseStatus = writeCollectorEventsSnapshot(appContext, snapshotDir)
                val helperStatus = writeKeepAliveLogSnapshot(appContext, snapshotDir)
                val tripsTelegramStatus = writeTripsTelegramEvidence(appContext, snapshotDir)
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
                        appendLine("trips_telegram_evidence=$tripsTelegramStatus")
                    },
                    Charsets.UTF_8
                )
                val latestZip = latestZip(appContext).also { writeLatestZip(it, snapshotDir) }
                val handedOff = createDiagnosticShareCopy(latestZip, shareRoot, captureStamp)
                pruneExpiredDiagnosticShareFiles(
                    shareRoot,
                    System.currentTimeMillis(),
                    SHARE_HANDOFF_RETENTION_MS,
                    protectedFile = handedOff
                )
                handedOff
            } finally {
                snapshotDir.deleteRecursively()
            }
        }
    }

    fun clearCompleted(context: Context): DiagnosticClearResult {
        synchronized(workLock) {
            val appContext = context.applicationContext
            val active = captureState().runDir?.takeIf { isRecording() }
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
    }

    private data class CaptureState(
        val stream: AdbLocalClient.AdbShellStream?,
        val runDir: File?,
        val context: Context?
    )

    private fun captureState(): CaptureState = synchronized(stateLock) {
        CaptureState(adbStream, activeRunDir, activeContext)
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
        val stateSnapshot = captureState()
        val active = stateSnapshot.runDir?.takeIf { stateSnapshot.stream?.isAlive == true && it.isDirectory }
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
                                file.name == "logcat_error.txt" ||
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

    private fun writeTripsTelegramEvidence(context: Context, runDir: File): String {
        val output = File(runDir, TRIPS_TELEGRAM_EVIDENCE_NAME)
        val lines = mutableListOf<String>(
            "schema_version=1",
            "captured_at=${timestampIso()}",
            "redaction=trip_telegram_metadata_only"
        )
        val app = context.applicationContext as BydCollectorApplication
        var telegram: TelegramDiagnosticSnapshot? = null
        var telegramStatus = "not_initialized"
        var telegramError: Throwable? = null
        runCatching {
            val store = app.telegramStoreOrNull()
            if (store == null) {
                telegramStatus = if (app.telegramStorageError() == null) "not_initialized" else "error"
            } else {
                telegram = store.diagnosticSnapshot(TELEGRAM_EVIDENCE_ROW_LIMIT)
                telegramStatus = telegram?.status ?: "error"
            }
        }.onFailure {
            telegramStatus = "error"
            telegramError = it
        }

        var selectedSession: TripSession? = null
        var route: TripRouteDiagnosticEvidence? = null
        var tripSelection = "none"
        var tripCorrelation = "not_requested"
        var tripError: Throwable? = null
        val pendingTripId = telegram?.pendingPowerOffLocationTripId
            ?.takeIf(String::isNotBlank)
        val pendingPowerSessionId = telegram?.pendingPowerOffLocationPowerSessionId
            ?.takeIf(String::isNotBlank)
        if (pendingTripId != null && pendingPowerSessionId == null) {
            tripSelection = "pending_unlinked"
            tripCorrelation = "unavailable"
        } else {
            val trips = app.tripsStoreOrNull()
            if (trips == null) {
                tripSelection = "not_initialized"
                if (pendingTripId != null) tripCorrelation = "unavailable"
            } else if (!trips.databaseFile.isFile || trips.databaseFile.length() == 0L) {
                tripSelection = "missing"
                if (pendingTripId != null) tripCorrelation = "unavailable"
            } else runCatching {
                app.tripsFileOperationLock.withLock {
                    trips.withLease {
                        val selection = selectDiagnosticTrip(
                            pendingTripId = pendingTripId,
                            pendingPowerSessionId = pendingPowerSessionId,
                            findByPowerSessionId = trips::session,
                            findNewestClosed = trips::diagnosticLatestClosedSession
                        )
                        tripSelection = selection.selection
                        selectedSession = selection.session
                        tripCorrelation = when (tripSelection) {
                            "pending" -> "linked"
                            "pending_missing" -> "missing"
                            else -> "not_requested"
                        }
                        selectedSession?.let { session ->
                            route = trips.diagnosticRouteEvidence(session.tripId)
                        }
                    }
                }
            }.onFailure {
                tripSelection = "error"
                if (pendingTripId != null) tripCorrelation = "error"
                tripError = it
            }
        }

        lines += "trips_status=${when (tripSelection) {
            "not_initialized" -> "not_initialized"
            "missing" -> "missing"
            "pending_unlinked" -> "not_queried"
            "error" -> "error"
            else -> "ok"
        }}"
        lines += "trip_selection=$tripSelection"
        lines += "trip_correlation=$tripCorrelation"
        tripError?.let { lines += "trips_error=${it::class.java.simpleName}" }
        selectedSession?.let { session ->
            lines += "trip_ref=${diagnosticSha256(session.tripId)}"
            lines += "trip_state=${diagnosticSafeText(session.state, 32)}"
            lines += "trip_started_at=${diagnosticSafeText(session.startedAt, 64)}"
            lines += "trip_ended_at=${diagnosticSafeText(session.endedAt, 64).ifBlank { "none" }}"
            lines += "trip_termination=${diagnosticSafeText(session.termination, 64).ifBlank { "none" }}"
            lines += "trip_quality=${diagnosticSafeText(session.quality, 128)}"
            lines += "trip_telegram_eligible=${session.telegramEligible}"
            lines += "trip_telegram_enqueued=${session.telegramEnqueued}"
        }
        route?.let { evidence ->
            lines += "route_storage=${evidence.storage}"
            lines += "route_point_count=${evidence.pointCount}"
            lines += "route_valid_count=${evidence.validCount}"
            lines += "route_gap_count=${evidence.gapCount}"
            lines += "route_untrusted_count=${evidence.untrustedCount}"
            lines += "route_final_marker_count=${evidence.finalMarkerCount}"
            lines += "route_sequence_contiguous=${evidence.sequenceContiguous}"
            lines += "route_final_is_latest_valid=${evidence.finalIsLatestValid}"
            evidence.finalPoint?.let { point ->
                lines += "route_final_sequence=${point.sequence}"
                lines += "route_final_observed_at=${diagnosticSafeText(point.observedAt, 64)}"
                lines += "route_final_quality=${diagnosticSafeText(point.quality, 128)}"
                lines += "route_final_has_coordinate=${point.hasCoordinate}"
            } ?: lines.add("route_final=none")
            evidence.latestValidPoint?.let { point ->
                lines += "route_latest_valid_sequence=${point.sequence}"
                lines += "route_latest_valid_observed_at=${diagnosticSafeText(point.observedAt, 64)}"
                lines += "route_latest_valid_quality=${diagnosticSafeText(point.quality, 128)}"
                lines += "route_latest_valid_has_coordinate=${point.hasCoordinate}"
            } ?: lines.add("route_latest_valid=none")
        }

        lines += "telegram_status=$telegramStatus"
        telegramError?.let { lines += "telegram_error=${it::class.java.simpleName}" }
        telegram?.let { snapshot ->
            lines += "telegram_runtime_state=${when {
                !snapshot.runtimeStatePresent -> "missing"
                snapshot.runtimeStateValid -> "valid"
                else -> "invalid"
            }}"
            lines += "telegram_runtime_updated_at_ms=${snapshot.runtimeStateUpdatedAtMs ?: "none"}"
            lines += "telegram_pending_trip_ref=${snapshot.pendingPowerOffLocationTripId
                ?.takeIf(String::isNotBlank)?.let(::diagnosticSha256) ?: "none"}"
            lines += "telegram_pending_power_session_ref=${snapshot.pendingPowerOffLocationPowerSessionId
                ?.takeIf { snapshot.pendingPowerOffLocationTripId?.isNotBlank() == true }
                ?.takeIf(String::isNotBlank)?.let(::diagnosticSha256) ?: "none"}"
            lines += "telegram_pending_summary_delivered=${snapshot.pendingPowerOffLocationSummaryDelivered}"
            lines += "telegram_outbox_total=${snapshot.outboxTotal}"
            lines += "telegram_relevant_rows_total=${snapshot.relevantRowsTotal}"
            lines += "telegram_rows_returned=${snapshot.rows.size}"
            lines += "telegram_rows_truncated=${snapshot.rowsTruncated}"
            lines += "telegram_location_obligation=${telegramLocationObligation(snapshot)}"
            snapshot.rows.forEach { row -> appendTelegramDiagnosticRow(lines, row) }
        }

        val encoded = boundedDiagnosticUtf8(lines, TRIPS_TELEGRAM_EVIDENCE_MAX_BYTES)
        return runCatching {
            output.writeBytes(encoded)
            "ok bytes=${output.length()}"
        }.getOrElse { error ->
            runCatching {
                output.writeText(
                    "schema_version=1\nstatus=error\nerror=${error::class.java.simpleName}\n",
                    Charsets.UTF_8
                )
            }
            "error"
        }
    }

    private fun appendTelegramDiagnosticRow(lines: MutableList<String>, row: TelegramDiagnosticRow) {
        val tripId = telegramTripIdForDiagnostic(row.dedupeKey)
        lines += "telegram_event=${diagnosticSafeText(row.eventType, 64)}"
        lines += "telegram_delivery_kind=${when {
            row.dedupeKey.endsWith(":location") -> "location_follow_up"
            row.dedupeKey.endsWith(":summary") -> "summary"
            else -> "other"
        }}"
        if (row.dedupeKey.endsWith(":location")) lines += "telegram_location=only"
        lines += "telegram_trip_ref=${tripId?.let(::diagnosticSha256) ?: "none"}"
        lines += "telegram_dedupe_ref=${diagnosticSha256(row.dedupeKey)}"
        lines += "telegram_dependency_ref=${row.waitsForSummaryKey?.let(::diagnosticSha256) ?: "none"}"
        lines += "telegram_created_at_ms=${row.createdAtMs}"
        lines += "telegram_next_attempt_at_ms=${row.nextAttemptAtMs}"
        lines += "telegram_attempt_count=${row.attemptCount}"
        lines += "telegram_blocked=${row.blocked}"
    }

    private fun telegramLocationObligation(snapshot: TelegramDiagnosticSnapshot): String {
        val pending = snapshot.pendingPowerOffLocationTripId
            ?.takeIf(String::isNotBlank)
        val locationRows = snapshot.rows.filter { it.dedupeKey.endsWith(":location") }
        val pendingLocation = pending?.let { id -> locationRows.firstOrNull { it.dedupeKey == "$id:location" } }
        return when {
            pendingLocation?.blocked == true -> "blocked"
            pendingLocation != null -> "queued"
            pending != null && !snapshot.pendingPowerOffLocationSummaryDelivered -> "waiting_summary"
            pending != null -> "pending_follow_up"
            locationRows.isNotEmpty() -> "queued"
            else -> "none_or_delivered"
        }
    }

    private fun telegramTripIdForDiagnostic(dedupeKey: String): String? = when {
        dedupeKey.endsWith(":summary") -> dedupeKey.removeSuffix(":summary")
        dedupeKey.endsWith(":location") -> dedupeKey.removeSuffix(":location")
        else -> null
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

internal fun boundedDiagnosticUtf8(lines: List<String>, maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "Diagnostic evidence limit must be positive" }
    val notTruncated = "truncated=0\n".toByteArray(StandardCharsets.UTF_8)
    val truncated = "truncated=1\n".toByteArray(StandardCharsets.UTF_8)
    require(truncated.size < maxBytes) { "Diagnostic evidence limit is too small" }
    val output = StringBuilder()
    var bytes = 0
    var wasTruncated = false
    lines.forEach { line ->
        if (wasTruncated) return@forEach
        val encoded = (line.replace('\r', ' ').replace('\n', ' ') + "\n")
            .toByteArray(StandardCharsets.UTF_8)
        val markerBytes = if (bytes + encoded.size + notTruncated.size <= maxBytes) notTruncated else truncated
        if (bytes + encoded.size + markerBytes.size > maxBytes) {
            wasTruncated = true
        } else {
            output.append(String(encoded, StandardCharsets.UTF_8))
            bytes += encoded.size
        }
    }
    val marker = if (wasTruncated) truncated else notTruncated
    if (bytes + marker.size > maxBytes) {
        return output.toString().toByteArray(StandardCharsets.UTF_8)
            .copyOf(maxBytes - marker.size) + marker
    }
    return output.toString().toByteArray(StandardCharsets.UTF_8) + marker
}

internal fun createDiagnosticRunDirectory(root: File, timestamp: String): File {
    return createUniqueDiagnosticDirectory(root, "logcat", timestamp)
}

internal fun diagnosticLogcatSourceBytes(root: File, activeRunDir: File? = null): Long {
    val source = activeRunDir?.takeIf { it.isDirectory } ?: latestCompletedDiagnosticRun(root)
    return source?.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("logcat_") }
        .sumOf(File::length)
}

internal fun hasDiagnosticShareSpace(usableBytes: Long, sourceBytes: Long, headroomBytes: Long): Boolean {
    if (usableBytes < 0L || sourceBytes < 0L || headroomBytes < 0L) return false
    if (sourceBytes > (Long.MAX_VALUE - headroomBytes) / 4L) return false
    val required = sourceBytes * 4L + headroomBytes
    return usableBytes >= required
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

internal fun pruneExpiredDiagnosticShareFiles(
    root: File,
    nowMs: Long,
    retentionMs: Long,
    protectedFile: File? = null
): Int {
    if (!root.isDirectory) return 0
    val cutoff = nowMs - retentionMs
    val protectedCanonical = protectedFile?.canonicalFile
    var removed = 0
    root.listFiles().orEmpty()
        .filter { it.isFile && it.canonicalFile != protectedCanonical && it.lastModified() <= cutoff }
        .forEach { file ->
            check(file.delete() || !file.exists()) {
                "Failed to delete diagnostic share file: ${file.absolutePath}"
            }
            removed += 1
        }
    return removed
}
