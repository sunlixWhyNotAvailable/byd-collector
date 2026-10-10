package com.bydcollector.collector.diagnostics

import android.content.Context
import android.os.Process
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Keeps operational evidence available even when SQLite cannot be opened.
class OperationalEventJournal internal constructor(
    private val root: File,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val retainedRotations: Int = RETAINED_ROTATIONS,
    private val bootId: String = readBootId(),
    private val pid: Int = Process.myPid()
) {
    constructor(context: Context) : this(File(context.filesDir, JOURNAL_DIR))
    private val lock = ReentrantLock()

    fun append(
        timestamp: String,
        elapsedMs: Long,
        category: String,
        message: String,
        detail: String?
    ) = lock.withLock { appendLocked(timestamp, elapsedMs, category, message, detail) }

    // A fatal handler must not wait behind a diagnostic Share snapshot indefinitely.
    // This bounds lock acquisition, not filesystem latency; persistence is best effort.
    internal fun tryAppend(
        timestamp: String,
        elapsedMs: Long,
        category: String,
        message: String,
        detail: String?,
        lockWaitMs: Long = 100L
    ): Boolean {
        if (!lock.tryLock(lockWaitMs, TimeUnit.MILLISECONDS)) return false
        return try {
            appendLocked(timestamp, elapsedMs, category, message, detail)
            true
        } finally {
            lock.unlock()
        }
    }

    private fun appendLocked(timestamp: String, elapsedMs: Long, category: String, message: String, detail: String?) {
        val line = journalLine(timestamp, elapsedMs, bootId, pid, category, message, detail)
        check(root.isDirectory || root.mkdirs()) {
            "Failed to create operational journal directory: ${root.absolutePath}"
        }
        val active = segment(0)
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (active.isFile && active.length() > 0L && active.length() + bytes.size > maxFileBytes) {
            rotate()
        }
        FileOutputStream(active, true).use { output -> output.write(bytes) }
    }

    fun snapshotTo(target: File): Int {
        check(target.isDirectory || target.mkdirs()) {
            "Failed to create operational journal snapshot: ${target.absolutePath}"
        }
        check(target.canonicalFile != root.canonicalFile) {
            "Operational journal snapshot target must not be the journal directory"
        }
        val sources = lock.withLock {
            val opened = mutableListOf<SnapshotSegment>()
            try {
                segmentFiles().forEach { source ->
                    if (source.isFile) {
                        val input = FileInputStream(source)
                        try {
                            opened += SnapshotSegment(source.name, input.channel.size(), input)
                        } catch (error: Throwable) {
                            try { input.close() }
                            catch (closeError: Throwable) { if (error !== closeError) error.addSuppressed(closeError) }
                            throw error
                        }
                    }
                }
                opened.toList()
            } catch (error: Throwable) {
                closeSnapshotSegments(opened, error)
                throw error
            }
        }
        var copied = 0
        var failure: Throwable? = null
        try {
            sources.forEach { source ->
                FileOutputStream(File(target, source.name), false).use { output ->
                    val buffer = ByteArray(SNAPSHOT_BUFFER_BYTES)
                    var remaining = source.byteCount
                    while (remaining > 0L) {
                        val read = source.input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) throw EOFException("Operational journal segment was truncated: ${source.name}")
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
                copied += 1
            }
        } catch (error: Throwable) {
            failure = error
        }
        failure = closeSnapshotSegments(sources, failure)
        failure?.let { throw it }
        return copied
    }

    private fun closeSnapshotSegments(
        sources: List<SnapshotSegment>,
        failure: Throwable?
    ): Throwable? {
        var result = failure
        sources.forEach { source ->
            try {
                source.input.close()
            } catch (error: Throwable) {
                if (result == null) result = error
                else if (result !== error) result.addSuppressed(error)
            }
        }
        return result
    }

    private data class SnapshotSegment(val name: String, val byteCount: Long, val input: FileInputStream)

    fun clear(): Int = lock.withLock {
        var removed = 0
        segmentFiles().forEach { file ->
            if (!file.exists()) return@forEach
            check(file.delete()) { "Failed to delete operational journal segment: ${file.absolutePath}" }
            removed += 1
        }
        removed
    }

    internal fun retainedBytes(): Long = lock.withLock {
        segmentFiles().filter { it.isFile }.sumOf { it.length() }
    }

    private fun rotate() {
        for (index in retainedRotations downTo 1) {
            val source = segment(index - 1)
            if (!source.exists()) continue
            Files.move(
                source.toPath(),
                segment(index).toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun segmentFiles(): List<File> =
        (0..retainedRotations).map(::segment)

    private fun segment(index: Int): File =
        if (index == 0) {
            File(root, ACTIVE_FILE_NAME)
        } else {
            File(root, "operational_events.$index.jsonl")
        }

    companion object {
        internal const val JOURNAL_DIR = "diagnostic_journal"
        internal const val ACTIVE_FILE_NAME = "operational_events.jsonl"
        internal const val MAX_FILE_BYTES = 16L * 1024L * 1024L
        internal const val MAINTENANCE_DIR = "maintenance_journal"
        internal const val MAINTENANCE_MAX_FILE_BYTES = 2L * 1024L * 1024L
        internal const val RETAINED_ROTATIONS = 3
        private const val SNAPSHOT_BUFFER_BYTES = 16 * 1024
        private const val MAX_CATEGORY_CHARS = 512
        private const val MAX_MESSAGE_CHARS = 16_384
        private const val MAX_DETAIL_CHARS = 32_768

        internal fun isMaintenanceEvent(category: String, message: String): Boolean =
            category.startsWith("archive_") ||
                ((category == "database_maintenance" || category.startsWith("database_maintenance_")) &&
                    category != "database_maintenance_heartbeat" && message != "database_maintenance_heartbeat")

        private fun readBootId(): String = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText(Charsets.UTF_8).trim()
        }.getOrDefault("unavailable")

        internal fun journalLine(
            timestamp: String,
            elapsedMs: Long,
            bootId: String,
            pid: Int,
            category: String,
            message: String,
            detail: String?
        ): String {
            var truncated = false
            fun bounded(value: String, limit: Int): String {
                if (value.length <= limit) return value
                truncated = true
                return value.take(limit)
            }

            return JSONObject()
                .put("schema_version", 1)
                .put("timestamp", timestamp)
                .put("elapsed_ms", elapsedMs)
                .put("boot_id", bootId)
                .put("pid", pid)
                .put("category", bounded(category, MAX_CATEGORY_CHARS))
                .put("message", bounded(message, MAX_MESSAGE_CHARS))
                .put("detail", detail?.let { bounded(it, MAX_DETAIL_CHARS) } ?: JSONObject.NULL)
                .put("truncated", truncated)
                .toString() + "\n"
        }
    }
}
