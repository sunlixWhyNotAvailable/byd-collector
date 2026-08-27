package com.bydcollector.collector.data.trips

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class TripStoreStep8ContractTest {
    @Test
    fun exposesOperationDirtyTrackingAndBoundedTailCopy() {
        val source = sourceFile("com/bydcollector/collector/data/trips/TripStore.kt").readText()

        assertTrue(source.contains("internal fun beginChangeTracking()"))
        assertTrue(source.contains("internal fun changedTripSequences(): Map<String, Long?>"))
        assertTrue(source.contains("internal fun endChangeTracking()"))
        assertTrue(source.contains("internal fun forEachRoutePointFrom(tripId: String, firstSequence: Long"))
        assertTrue(source.contains("internal fun copyRouteTailFrom(source: TripStore, tripId: String, firstSequence: Long, beforeWrite:"))
        assertTrue(source.contains("internal fun checkpointTruncate()"))
        assertTrue(source.contains("internal fun verifyForeignKeys(): Boolean"))
        assertTrue(source.contains("internal fun sessionCount(): Long"))
        assertTrue(source.contains("internal fun schemaVersion(): Int"))
    }

    @Test
    fun replacementCloseFailClosedAndOpenRoutesRejectChunks() {
        val source = sourceFile("com/bydcollector/collector/data/trips/TripStore.kt").readText()

        assertTrue(source.contains("internal fun closeForReplacement()"))
        assertTrue(source.contains("fileAccessSuspended = true"))
        assertTrue(source.contains("check(databaseFile.isFile && databaseFile.length() > 0L)"))
        assertTrue(source.contains("Open trip cannot have route chunks"))
    }

    private fun sourceFile(path: String): File = listOf(
        File("src/main/kotlin/$path"),
        File("app/src/main/kotlin/$path")
    ).firstOrNull(File::isFile) ?: error("Missing source file: $path")
}
