package com.bydcollector.collector

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductionReadOnlySourceContractTest {
    @Test
    fun productionSourcesContainNoForbiddenVehicleWriteApis() {
        val root = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("Missing app/src/main")
        val matches = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in SOURCE_EXTENSIONS }
            .forEach { file ->
                file.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
                    FORBIDDEN_API_PATTERN.findAll(line).forEach { match ->
                        matches += "${file.relativeTo(root).path}:${index + 1}: ${match.value}"
                    }
                }
            }

        assertEquals(emptyList(), matches)
    }

    @Test
    fun arrayWriteApisRemainCoveredByTheReadOnlyGuard() {
        assertTrue(FORBIDDEN_API_PATTERN.containsMatchIn("setIntArray"))
        assertTrue(FORBIDDEN_API_PATTERN.containsMatchIn("setDoubleArray"))
    }

    companion object {
        private val SOURCE_EXTENSIONS = setOf("kt", "java", "xml")
        private val FORBIDDEN_API_PATTERN = Regex(
            "\\b(?:sendCmd|setXD|setTrigger|wakeUpMcu|setAction|setActionsBatch|setIntArray|setDoubleArray|TX_WRITE)\\b"
        )
    }
}
