package com.bydcollector.collector.diagnostics

import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Stable redaction used to correlate trip and Telegram records without exposing identifiers. */
internal fun diagnosticSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun diagnosticSafeText(value: String?, maxLength: Int = 256): String = value
    ?.replace('\r', ' ')
    ?.replace('\n', ' ')
    ?.replace('\t', ' ')
    ?.take(maxLength)
    .orEmpty()

/** Only receives the disposable Share directory, never a recording or database directory. */
internal fun sanitizeDiagnosticSnapshot(snapshot: File): String {
    require(snapshot.isDirectory && snapshot.name.startsWith("snapshot_"))
    val sanitizer = DiagnosticShareSanitizer()
    val reports = mutableListOf<String>()
    var partial = false
    val files = snapshot.walkTopDown().filter(File::isFile)
        .sortedBy { it.relativeTo(snapshot).invariantSeparatorsPath }.toList()
    files.forEach { file ->
        val name = file.relativeTo(snapshot).invariantSeparatorsPath
        val unrotatedName = file.name.replace(Regex("\\.[1-3]$"), "")
        val format = unrotatedName.substringAfterLast('.', "")
        if (format !in setOf("txt", "log", "jsonl", "json")) {
            check(file.delete()) { "Cannot omit unsupported diagnostic component" }
            reports += "file=$name status=omitted reason=unsupported_type"
            partial = true
            return@forEach
        }
        val temporary = File.createTempFile(".privacy-", ".tmp", file.parentFile)
        var omitted = 0
        var changed = 0
        var truncated = false
        var capReached = false
        var written = 0
        val limit = when (file.name) {
            DiagnosticLogRecorder.INFLUX_EVIDENCE_NAME -> DiagnosticLogRecorder.INFLUX_EVIDENCE_MAX_BYTES
            "trips_telegram_evidence.txt" -> DiagnosticLogRecorder.TRIPS_TELEGRAM_EVIDENCE_MAX_BYTES
            else -> null
        }
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            temporary.outputStream().buffered().use { output ->
                forEachDiagnosticRecord(file) { raw ->
                    val text = runCatching {
                        requireNotNull(raw) { "Record exceeds the diagnostic line limit" }
                        decoder.reset().decode(ByteBuffer.wrap(raw)).toString().removeSuffix("\r")
                    }.getOrElse {
                        omitted += 1
                        return@forEachDiagnosticRecord
                    }
                    if (limit != null && text in setOf("truncated=0", "truncated=1")) {
                        truncated = truncated || text == "truncated=1"
                        return@forEachDiagnosticRecord
                    }
                    if (capReached) return@forEachDiagnosticRecord
                    val sanitized = runCatching {
                        if (format in setOf("jsonl", "json") && text.isNotBlank()) sanitizer.sanitizeJsonLine(text)
                        else sanitizer.sanitizeText(text)
                    }.getOrElse {
                        omitted += 1
                        return@forEachDiagnosticRecord
                    }
                    val bytes = (sanitized + "\n").toByteArray(Charsets.UTF_8)
                    if (limit != null && written + bytes.size + "truncated=1\n".length > limit) {
                        truncated = true
                        capReached = true
                    } else {
                        output.write(bytes)
                        written += bytes.size
                        if (sanitized != text) changed += 1
                    }
                }
                if (limit != null) output.write("truncated=${if (truncated) 1 else 0}\n".toByteArray(Charsets.UTF_8))
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val status = if (omitted > 0 || truncated) "partial" else "ok"
            partial = partial || status == "partial"
            reports += "file=$name status=$status changed_records=$changed omitted_records=$omitted truncated=${if (truncated) 1 else 0}"
        } catch (error: Exception) {
            // A failed component must not fall back to its raw snapshot copy.
            check(!file.exists() || file.delete()) { "Cannot omit failed diagnostic component" }
            reports += "file=$name status=omitted error=${error::class.java.simpleName}"
            partial = true
        } finally {
            // A leftover temp file must not be included by the ordinary ZIP walker.
            check(!temporary.exists() || temporary.delete()) { "Cannot remove diagnostic privacy temporary file" }
        }
    }
    val status = if (partial) "partial" else "ok"
    File(snapshot, "privacy_status.txt").writeText(buildString {
        appendLine("status=$status")
        appendLine("scope=share_copy_only")
        appendLine("policy=recognized_sensitive_contexts")
        appendLine("masked_contexts=SSID,BSSID,labelled_VIN,VIN_API_results,secrets,coordinates")
        appendLine("record_limit_bytes=$DIAGNOSTIC_RECORD_LIMIT_BYTES")
        appendLine("warning=Review before sharing; arbitrary system logcat is best-effort, not guaranteed anonymous")
        appendLine("retained=versions,vehicle_names,firmware,hosts,IP_addresses,ports,technical_ids,timing,cursors")
        reports.forEach(::appendLine)
    }, Charsets.UTF_8)
    return status
}

private const val DIAGNOSTIC_RECORD_LIMIT_BYTES = 256 * 1024

/** Split bytes before UTF-8 decoding so one torn final record cannot discard its valid predecessors. */
private fun forEachDiagnosticRecord(file: File, consume: (ByteArray?) -> Unit) {
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(8192)
        val line = ByteArrayOutputStream()
        var overlong = false
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            for (index in 0 until count) {
                val byte = buffer[index]
                if (byte == '\n'.code.toByte()) {
                    consume(if (overlong) null else line.toByteArray())
                    line.reset()
                    overlong = false
                } else if (!overlong) {
                    if (line.size() == DIAGNOSTIC_RECORD_LIMIT_BYTES) {
                        overlong = true
                        line.reset()
                    } else line.write(byte.toInt())
                }
            }
        }
        if (overlong || line.size() > 0) consume(if (overlong) null else line.toByteArray())
    }
}
