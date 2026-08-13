package com.bydcollector.collector.util

import java.io.File

// Reports the active SQLite footprint without opening the database or forcing a checkpoint.
fun sqliteFootprintBytes(databaseFile: File): Long {
    return listOf(
        databaseFile,
        File(databaseFile.path + "-wal"),
        File(databaseFile.path + "-shm"),
        File(databaseFile.path + "-journal")
    ).sumOf { file -> file.takeIf(File::isFile)?.length() ?: 0L }
}
