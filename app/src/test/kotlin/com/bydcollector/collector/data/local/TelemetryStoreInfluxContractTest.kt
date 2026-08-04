package com.bydcollector.collector.data.local

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryStoreInfluxContractTest {
    @Test
    fun influxBatchUsesOneGlobalCursorBoundedHistoryQuery() {
        val source = sourceFile("com/bydcollector/collector/data/local/TelemetryStore.kt").readText()
        val query = source.substringAfter("override fun pendingInfluxRows")
            .substringBefore("override fun updateInfluxCursorSuccess")

        assertTrue(query.contains("INNER JOIN influx_export_cursor AS export_cursor"))
        assertTrue(query.contains("history.id > export_cursor.last_exported_history_id"))
        assertTrue(query.contains("history.field_key IN (\$placeholders)"))
        assertTrue(query.contains("ORDER BY history.id"))
        assertTrue(query.contains("LIMIT ?"))
        assertFalse(query.contains("WHERE field_key = ? AND id > ?"))
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
