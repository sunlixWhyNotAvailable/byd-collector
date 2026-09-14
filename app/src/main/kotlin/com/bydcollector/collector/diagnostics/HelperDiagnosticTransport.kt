package com.bydcollector.collector.diagnostics

import android.content.Context
import com.bydcollector.collector.adb.AdbCancellation
import com.bydcollector.collector.adb.AdbLocalClient
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Explicit support operations only; never bootstraps the telemetry service or helper. */
internal object HelperDiagnosticTransport {
    const val MAX_ARCHIVE_BYTES = 32L * 1024L * 1024L
    private const val TIMEOUT_MS = 45_000L

    fun command(apk: String, operation: String): String {
        require(operation == "snapshot" || operation == "clear")
        val quotedApk = "'" + apk.replace("'", "'\\''") + "'"
        return "CLASSPATH=$quotedApk app_process / com.bydcollector.collector.direct.HelperDiagnosticsMain $operation"
    }

    fun snapshot(context: Context, destination: File): String {
        // This optional component must not block sharing the other diagnostics on a nearly full device.
        check(context.cacheDir.usableSpace >= 4 * MAX_ARCHIVE_BYTES) { "helper_snapshot_insufficient_space" }
        val archive = File.createTempFile("helper-diagnostics-", ".zip", context.cacheDir)
        val cancellation = AdbCancellation()
        val timer = Timer("collector-diagnostic-deadline", true)
        timer.schedule(object : TimerTask() {
            override fun run() = cancellation.cancel()
        }, TIMEOUT_MS)
        return try {
            val client = AdbLocalClient(File(context.filesDir, "adb_keys"), cancellation = cancellation)
            BoundedDiagnosticOutput(archive.outputStream(), MAX_ARCHIVE_BYTES).use { output ->
                client.openShellStream(command(context.applicationInfo.sourceDir, "snapshot"), output,
                    allowAuthorizationPrompt = false).use {
                    check(output.finished.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "helper_snapshot_timeout" }
                    check(!cancellation.isCancelled) { "helper_snapshot_timeout" }
                    output.failure?.let { throw it }
                }
            }
            extractHelperDiagnosticArchive(archive, destination)
        } finally {
            cancellation.cancel()
            timer.cancel()
            archive.delete()
        }
    }

    fun clear(context: Context): String? {
        val result = AdbLocalClient(File(context.filesDir, "adb_keys")).execShell(
            command(context.applicationInfo.sourceDir, "clear"), timeoutMs = 15_000,
            allowAuthorizationPrompt = false
        )
        val report = runCatching { JSONObject(result.output) }.getOrNull()
            ?: return "helper_diagnostics=${diagnosticSafeText(result.error ?: result.output)}"
        return if (result.ok && report.optInt("schema_version") == 1 && report.optString("operation") == "clear" && report.optString("overall_status") == "complete") null
        else "helper_diagnostics=partial ${diagnosticSafeText(report.toString(), 1024)}"
    }
}

internal class BoundedDiagnosticOutput(private val target: OutputStream, private val limit: Long) : OutputStream() {
    val finished = CountDownLatch(1)
    @Volatile var failure: Exception? = null
        private set
    private var written = 0L
    private var closed = false

    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) {
        try {
            check(!closed) { "diagnostic_stream_closed" }
            check(length >= 0 && written <= limit - length) { "helper_snapshot_too_large" }
            target.write(bytes, offset, length)
            written += length
        } catch (error: Exception) {
            failure = error
            throw error
        }
    }

    @Synchronized override fun flush() = target.flush()

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { target.close() } catch (error: Exception) { failure = error; throw error }
        finally { finished.countDown() }
    }
}

internal fun helperDiagnosticEntryLimit(name: String): Long? {
    val leaf = name.removePrefix("helper-diagnostics/")
    if (name == leaf || '/' in leaf || '\\' in leaf) return null
    if (leaf == "manifest.json") return 64L * 1024
    if (leaf == "helper-diagnostics-current.json") return 256L * 1024
    return if (Regex("(?:helper-diagnostics\\.jsonl|helper-bootstrap\\.log|bydcollector_helper\\.log)(?:\\.[1-3])?")
            .matches(leaf)) 2L * 1024 * 1024 else null
}

/** Fixed allowlist, per-entry caps, central directory and CRC checks before the existing privacy pass. */
internal fun extractHelperDiagnosticArchive(archive: File, destination: File): String {
    require(archive.length() <= HelperDiagnosticTransport.MAX_ARCHIVE_BYTES)
    val seen = mutableSetOf<String>()
    ZipFile(archive).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val limit = requireNotNull(helperDiagnosticEntryLimit(entry.name)) { "unexpected_helper_entry" }
            require(seen.add(entry.name) && !entry.isDirectory && entry.size in 0..limit) { "invalid_helper_entry" }
            val output = File(destination, entry.name)
            check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs()) { "helper_snapshot_directory_failed" }
            val crc = CRC32()
            var count = 0L
            zip.getInputStream(entry).use { input ->
                output.outputStream().use { target ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val length = input.read(buffer)
                        if (length < 0) break
                        count += length
                        require(count <= limit) { "helper_entry_too_large" }
                        crc.update(buffer, 0, length)
                        target.write(buffer, 0, length)
                    }
                }
            }
            check(count == entry.size && crc.value == entry.crc) { "helper_entry_incomplete" }
        }
    }
    val manifest = File(destination, "helper-diagnostics/manifest.json")
    require(manifest.isFile) { "helper_manifest_missing" }
    val report = JSONObject(manifest.readText(Charsets.UTF_8))
    require(report.optInt("schema_version") == 1 && report.optString("operation") == "snapshot") { "helper_manifest_invalid" }
    val status = report.optString("overall_status")
    require(status in setOf("complete", "partial")) { "helper_manifest_status_invalid" }
    return "$status files=${seen.size - 1} legacy=best_effort_unlocked_writer"
}
