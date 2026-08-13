package com.bydcollector.collector.util

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteFootprintTest {
    @Test
    fun includesDatabaseWalShmAndRollbackJournal() {
        val directory = createTempDirectory("sqlite-footprint").toFile()
        try {
            val database = directory.resolve("telemetry.db").apply { writeBytes(ByteArray(3)) }
            directory.resolve("telemetry.db-wal").writeBytes(ByteArray(5))
            directory.resolve("telemetry.db-shm").writeBytes(ByteArray(7))
            directory.resolve("telemetry.db-journal").writeBytes(ByteArray(11))
            directory.resolve("unrelated.db").writeBytes(ByteArray(100))

            assertEquals(26L, sqliteFootprintBytes(database))
        } finally {
            directory.deleteRecursively()
        }
    }
}
