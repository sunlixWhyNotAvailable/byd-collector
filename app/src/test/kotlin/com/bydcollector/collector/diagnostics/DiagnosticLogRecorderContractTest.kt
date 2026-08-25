package com.bydcollector.collector.diagnostics

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

    @Test
    fun sameSecondRunsUseDistinctDirectoriesWithoutTruncatingPriorEvidence() {
        val root = Files.createTempDirectory("bydcollector-diagnostic-runs").toFile()
        try {
            val first = createDiagnosticRunDirectory(root, "20260825_120000")
            File(first, "logcat_threadtime.txt").writeText("first run", Charsets.UTF_8)
            val second = createDiagnosticRunDirectory(root, "20260825_120000")
            File(second, "logcat_threadtime.txt").writeText("second run", Charsets.UTF_8)

            assertNotEquals(first.canonicalPath, second.canonicalPath)
            assertEquals("first run", File(first, "logcat_threadtime.txt").readText(Charsets.UTF_8))
            assertEquals("second run", File(second, "logcat_threadtime.txt").readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun projectFile(vararg paths: String): File =
        paths.map(::File).firstOrNull(File::isFile) ?: error("Missing project file: ${paths.joinToString()}")
}
