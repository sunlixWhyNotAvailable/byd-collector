package com.bydcollector.collector.influx

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class InfluxClientContractTest {
    @Test
    fun httpConnectionDisconnectsInFinally() {
        val source = sourceFile("com/bydcollector/collector/influx/InfluxClient.kt").readText()

        assertTrue(source.contains("var connection: HttpURLConnection? = null"))
        assertTrue(source.contains("finally"))
        assertInOrder(source, "finally", "connection?.disconnect()")
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }

    private fun assertInOrder(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue(firstIndex >= 0, "Missing first token: $first")
        assertTrue(secondIndex >= 0, "Missing second token: $second")
        assertTrue(firstIndex < secondIndex, "Expected `$first` before `$second`")
    }
}
