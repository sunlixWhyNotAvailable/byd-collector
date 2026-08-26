package com.bydcollector.collector.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// Keeps the stdout stream bounded while adb continues writing to one OutputStream.
internal class DiagnosticLogcatOutputStream(
    private val runDir: File,
    private val segmentBytes: Long = DiagnosticLogRecorder.LOGCAT_SEGMENT_BYTES,
    private val segmentCount: Int = DiagnosticLogRecorder.LOGCAT_SEGMENT_COUNT
) : OutputStream() {
    private var currentFile: File
    private var output: FileOutputStream
    private var closed = false

    init {
        require(segmentBytes > 0L) { "segmentBytes must be positive" }
        require(segmentCount > 0) { "segmentCount must be positive" }
        check(runDir.isDirectory || runDir.mkdirs()) {
            "Failed to create logcat directory: ${runDir.absolutePath}"
        }
        currentFile = segment(0)
        output = FileOutputStream(currentFile, true)
    }

    @Synchronized
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!closed) { "stream is closed" }
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "invalid write range" }
        var position = offset
        var remaining = length
        while (remaining > 0) {
            if (currentFile.length() >= segmentBytes) rotate()
            val room = (segmentBytes - currentFile.length()).coerceAtLeast(1L).coerceAtMost(remaining.toLong()).toInt()
            output.write(bytes, position, room)
            position += room
            remaining -= room
        }
    }

    override fun write(value: Int) {
        write(byteArrayOf(value.toByte()))
    }

    @Synchronized
    override fun flush() {
        if (!closed) output.flush()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        output.close()
    }

    private fun rotate() {
        output.flush()
        output.close()
        Files.deleteIfExists(segment(segmentCount - 1).toPath())
        for (index in segmentCount - 1 downTo 1) {
            val source = segment(index - 1)
            if (source.exists()) {
                Files.move(
                    source.toPath(),
                    segment(index).toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        }
        currentFile = segment(0)
        output = FileOutputStream(currentFile, false)
    }

    private fun segment(index: Int): File = File(runDir, "logcat_$index.txt")
}
