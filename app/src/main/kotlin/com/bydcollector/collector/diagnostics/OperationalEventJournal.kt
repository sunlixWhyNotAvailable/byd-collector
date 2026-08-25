package com.bydcollector.collector.diagnostics

import android.content.Context
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// Keeps operational evidence available even when SQLite cannot be opened.
class OperationalEventJournal internal constructor(
    private val root: File,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val retainedRotations: Int = RETAINED_ROTATIONS,
    private val bootId: String = readBootId(),
    private val pid: Int = Process.myPid()
) {
    constructor(context: Context) : this(File(context.filesDir, JOURNAL_DIR))

    @Synchronized
    fun append(
        timestamp: String,
        elapsedMs: Long,
        category: String,
        message: String,
        detail: String?
    ) {
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

    @Synchronized
    fun snapshotTo(target: File): Int {
        check(target.isDirectory || target.mkdirs()) {
            "Failed to create operational journal snapshot: ${target.absolutePath}"
        }
        var copied = 0
        segmentFiles().forEach { source ->
            if (!source.isFile) return@forEach
            source.copyTo(File(target, source.name), overwrite = true)
            copied += 1
        }
        return copied
    }

    @Synchronized
    fun clear(): Int {
        var removed = 0
        segmentFiles().forEach { file ->
            if (!file.exists()) return@forEach
            check(file.delete()) { "Failed to delete operational journal segment: ${file.absolutePath}" }
            removed += 1
        }
        return removed
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
        internal const val MAX_FILE_BYTES = 2L * 1024L * 1024L
        internal const val RETAINED_ROTATIONS = 3
        private const val MAX_CATEGORY_CHARS = 512
        private const val MAX_MESSAGE_CHARS = 16_384
        private const val MAX_DETAIL_CHARS = 32_768

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
