package com.bydcollector.collector

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ProductionHostPathContractTest {
    @Test
    fun productionSourcesContainNoDeveloperHostPaths() {
        val root = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("Missing app/src/main")
        val forbidden = listOf("D:\\Work_folder", "C:\\Users", "/Users/", "/home/")
        val matches = root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in TEXT_EXTENSIONS }
            .flatMap { file ->
                val text = file.readText(Charsets.UTF_8)
                forbidden.asSequence()
                    .filter(text::contains)
                    .map { token -> "${file.relativeTo(root).path}: $token" }
            }
            .toList()

        assertEquals(emptyList(), matches)
    }

    companion object {
        private val TEXT_EXTENSIONS = setOf("kt", "java", "xml", "json", "txt", "md", "csv", "properties")
    }
}
