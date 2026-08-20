package com.bydcollector.collector.diagnostics

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticLogRecorderContractTest {
    @Test
    fun recordsUnfilteredSystemLogcatThroughAuthenticatedAdbStream() {
        val source = projectFile(
            "app/src/main/kotlin/com/bydcollector/collector/diagnostics/DiagnosticLogRecorder.kt",
            "src/main/kotlin/com/bydcollector/collector/diagnostics/DiagnosticLogRecorder.kt"
        ).readText()

        assertTrue(source.contains("logcat -b all -v threadtime"))
        assertTrue(source.contains("openShellStream("))
        assertTrue(source.contains("logcat_error.txt"))
        assertFalse(source.contains("ProcessBuilder(\"logcat\""))
        assertFalse(source.contains("--pid"))
    }

    private fun projectFile(vararg paths: String): File =
        paths.map(::File).firstOrNull(File::isFile) ?: error("Missing project file: ${paths.joinToString()}")
}
