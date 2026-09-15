package com.bydcollector.collector.data.trips

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class TripCompressionPhase {
    SNAPSHOT_CREATED, CANDIDATE_BUILT, CANDIDATE_VERIFIED, SOURCE_MOVED, CANDIDATE_MOVED, COMMITTED
}

internal data class TripCompressionResult(val beforeBytes: Long, val afterBytes: Long) {
    val savedBytes: Long get() = (beforeBytes - afterBytes).coerceAtLeast(0L)
}

/** Rewrites only Trips. GPS callbacks keep their receipt clocks and queue at the store lease. */
internal class TripCompression(
    private val context: Context,
    private val store: TripStore,
    private val fileBarrier: ReentrantLock = ReentrantLock(true),
    private val availableBytes: () -> Long = { store.databaseFile.parentFile!!.usableSpace },
    private val onPhase: (TripCompressionPhase) -> Unit = {}
) {
    fun run(onStep: (Int) -> Unit = {}): TripCompressionResult {
        val files = Files(store.databaseFile)
        var snapshot: TripStore? = null
        var candidate: TripStore? = null
        var tracking = false
        try {
            onStep(0)
            fileBarrier.withLock {
                store.withLease {
                    // The same singleton is closed/reopened; never open a competing live writer.
                    store.closeForReplacement()
                    recoverFiles(files)
                    store.reopenDatabase()
                    check(readPhase(files) == null && !files.backup.exists()) { "Previous Trips cleanup is still pending" }
                    cleanupWorkingFiles(files)
                    deleteIfPresent(files.journalTemp)
                    store.beginChangeTracking()
                    tracking = true
                    store.checkpointTruncate()
                    val sourceBytes = files.active.length()
                    require(sourceBytes > 0L && sourceBytes <= (Long.MAX_VALUE - SPACE_RESERVE) / 2L)
                    check(availableBytes() >= sourceBytes * 2L + SPACE_RESERVE) { "Insufficient free space for Trips compression" }
                    store.closeDatabase()
                    try {
                        prepareClosedDatabaseForMove(files.active)
                        copySynced(files.active, files.snapshot, ::checkReserve)
                    } finally {
                        store.reopenDatabase()
                    }
                }
            }
            onPhase(TripCompressionPhase.SNAPSHOT_CREATED)
            val snapshotStore = TripStore(TripDatabaseHelper(context, files.snapshot.name)).also { snapshot = it }
            check(snapshotStore.verify() && snapshotStore.verifyForeignKeys()) { "Trips snapshot verification failed" }
            val candidateStore = TripStore(TripDatabaseHelper(context, files.candidate.name)).also { candidate = it }
            onStep(1)
            val sessions = snapshotStore.allSessions()
            sessions.forEach { session ->
                checkReserve()
                candidateStore.copySessionFrom(snapshotStore, session.tripId)
                candidateStore.copyRouteFrom(snapshotStore, session.tripId, compress = session.state == TripSession.STATE_CLOSED, beforeWrite = ::checkReserve)
            }
            candidateStore.replaceEnergyRuntimeRow(snapshotStore.readEnergyRuntimeRow())
            candidateStore.replaceHistoricalEnergyBackfillRecords(snapshotStore.historicalEnergyBackfillRecords())
            onPhase(TripCompressionPhase.CANDIDATE_BUILT)
            onStep(2)
            check(sameSessions(snapshotStore, candidateStore)) { "Trip sessions changed during compression" }
            check(sameEnergyRuntime(snapshotStore, candidateStore)) { "Energy runtime state changed during compression" }
            check(sameHistoricalEnergyBackfill(snapshotStore, candidateStore)) { "Historical energy progress changed during compression" }
            sessions.forEach { session ->
                checkInterrupted()
                check(sameRoute(snapshotStore, candidateStore, session.tripId)) { "Route equality verification failed" }
            }
            check(candidateStore.verify() && candidateStore.verifyForeignKeys()) { "Compressed Trips verification failed" }
            onPhase(TripCompressionPhase.CANDIDATE_VERIFIED)
            snapshotStore.close()
            snapshot = null
            onStep(3)
            val result = fileBarrier.withLock {
                store.withLease {
                    checkReserve()
                    // Only the changed tails are replayed; the verified snapshot prefix is immutable.
                    val changed = store.changedTripSequences()
                    changed.forEach { (tripId, firstSequence) ->
                        checkInterrupted()
                        candidateStore.copySessionFrom(store, tripId)
                        if (firstSequence != null) {
                            candidateStore.copyRouteTailFrom(store, tripId, firstSequence, beforeWrite = ::checkReserve)
                            check(sameRoute(store, candidateStore, tripId, firstSequence)) { "Trip tail verification failed" }
                        }
                    }
                    // The singleton can change independently of visible Trips while the
                    // candidate is built, so refresh it under the final source lease.
                    candidateStore.replaceEnergyRuntimeRow(store.readEnergyRuntimeRow())
                    if (store.historicalBackfillChanged()) {
                        candidateStore.replaceHistoricalEnergyBackfillRecords(store.historicalEnergyBackfillRecords())
                    }
                    check(sameSessions(store, candidateStore)) { "Concurrent trip state was not preserved" }
                    check(sameEnergyRuntime(store, candidateStore)) { "Concurrent energy runtime state was not preserved" }
                    check(sameHistoricalEnergyBackfill(store, candidateStore)) { "Concurrent historical energy progress was not preserved" }
                    val expectedCount = candidateStore.sessionCount()
                    val expectedEnergyRuntime = candidateStore.readEnergyRuntimeRow()
                    val expectedHistoricalBackfill = candidateStore.historicalEnergyBackfillRecords()
                    candidateStore.checkpointTruncate()
                    candidateStore.closeDatabase()
                    store.checkpointTruncate()
                    val before = files.active.length()
                    val after = files.candidate.length()
                    check(after > 0L) { "Compressed Trips file is empty" }
                    if (after >= before) {
                        TripCompressionResult(before, before)
                    } else {
                        replaceVerified(files, expectedCount, expectedEnergyRuntime, expectedHistoricalBackfill)
                        TripCompressionResult(before, after)
                    }
                }
            }
            onStep(4)
            candidateStore.close()
            candidate = null
            cleanupWorkingFiles(files)
            onStep(5)
            return result
        } finally {
            runCatching { snapshot?.close() }
            runCatching { candidate?.close() }
            if (tracking) store.endChangeTracking()
            // A remaining journal/backup is recovery evidence, not disposable temporary data.
            if (runCatching { readPhase(files) == null && !files.backup.exists() }.getOrDefault(false)) {
                runCatching { cleanupWorkingFiles(files) }
            }
        }
    }

    private fun replaceVerified(
        files: Files,
        expectedSessions: Long,
        expectedEnergyRuntime: com.bydcollector.collector.data.energy.EnergyRuntimeRow?,
        expectedHistoricalBackfill: List<HistoricalEnergyBackfillRecord>
    ) {
        store.closeForReplacement()
        try {
            prepareClosedDatabaseForMove(files.active)
            prepareClosedDatabaseForMove(files.candidate)
            writePhase(files, PREPARED)
            check(files.active.renameTo(files.backup)) { "Cannot retain original Trips database" }
            syncDirectory(files.active.parentFile!!)
            onPhase(TripCompressionPhase.SOURCE_MOVED)
            check(files.candidate.renameTo(files.active)) { "Cannot install compressed Trips database" }
            syncDirectory(files.active.parentFile!!)
            onPhase(TripCompressionPhase.CANDIDATE_MOVED)
            store.reopenDatabase()
            // Full equality/quick_check already ran off-lease. Keep this barrier small.
            check(
                store.schemaVersion() == TripDatabaseHelper.DATABASE_VERSION &&
                    store.sessionCount() == expectedSessions &&
                    store.readEnergyRuntimeRow() == expectedEnergyRuntime &&
                    store.historicalEnergyBackfillRecords() == expectedHistoricalBackfill
            ) {
                "Compressed Trips reopen verification failed"
            }
            writePhase(files, COMMITTED)
            onPhase(TripCompressionPhase.COMMITTED)
            cleanupCommitted(files)
        } catch (failure: Throwable) {
            try {
                store.closeForReplacement()
                // Reads the durable phase: COMMITTED must NEVER restore an older backup.
                recoverFiles(files)
                store.reopenDatabase()
            } catch (recoveryFailure: Throwable) {
                failure.addSuppressed(recoveryFailure)
                // The suspended store cannot silently create an empty DB on the next callback.
            }
            throw failure
        }
    }

    private fun checkReserve() {
        checkInterrupted()
        check(availableBytes() >= SPACE_RESERVE) { "Free-space reserve reached during Trips compression" }
    }

    companion object {
        private const val PREPARED = "PREPARED"
        private const val COMMITTED = "COMMITTED"
        private const val SPACE_RESERVE = 16L * 1024L * 1024L

        /** Must run before SQLiteOpenHelper is allowed to create/open the canonical Trips file. */
        fun recoverBeforeOpen(context: Context, databaseName: String = TripDatabaseHelper.DATABASE_NAME) {
            require(databaseName == File(databaseName).name) { "Invalid Trips database name" }
            recoverFiles(Files(context.getDatabasePath(databaseName)))
        }

        private class Files(val active: File) {
            val snapshot = File(active.parentFile, active.name + ".compression-snapshot.db")
            val candidate = File(active.parentFile, active.name + ".compression-candidate.db")
            val backup = File(active.parentFile, active.name + ".compression-backup.db")
            val journal = File(active.parentFile, active.name + ".compression-journal")
            val journalTemp = File(journal.path + ".tmp")
        }

        private fun recoverFiles(files: Files) {
            val phase = readPhase(files)
            when (phase) {
                null -> {
                    check(!files.backup.exists()) { "Trips backup has no replacement journal" }
                    check(files.active.isFile || (!files.snapshot.exists() && !files.candidate.exists())) {
                        "Trips source is missing; compression files retained"
                    }
                }
                PREPARED -> {
                    if (files.backup.exists()) {
                        check(files.backup.isFile && files.backup.length() > 0L) { "Original Trips backup is invalid" }
                        deleteDatabaseFiles(files.active)
                        check(files.backup.renameTo(files.active)) { "Cannot restore original Trips database" }
                        syncDirectory(files.active.parentFile!!)
                    } else {
                        check(files.active.isFile && files.active.length() > 0L) { "Original Trips database is missing" }
                    }
                }
                COMMITTED -> {
                    check(files.active.isFile && files.active.length() > 0L) { "Committed Trips database is missing" }
                    // Stabilize the observed rename before admitting any new writes. Cleanup
                    // below must not block a valid committed database on a garbage-file error.
                    syncDirectory(files.active.parentFile!!)
                }
                else -> error("Unknown Trips replacement journal; files retained")
            }
            runCatching {
                if (phase == COMMITTED) cleanupCommitted(files)
                else if (phase == PREPARED) {
                    deleteIfPresent(files.journal)
                    syncDirectory(files.active.parentFile!!)
                }
                cleanupWorkingFiles(files)
                deleteIfPresent(files.journalTemp)
            }
        }

        private fun readPhase(files: Files): String? = try {
            FileInputStream(files.journal).use { input ->
                val bytes = ByteArray(32)
                var count = 0
                while (true) {
                    val value = input.read()
                    if (value < 0) break
                    check(count < bytes.size) { "Invalid Trips replacement journal" }
                    bytes[count++] = value.toByte()
                }
                check(count > 0) { "Invalid Trips replacement journal" }
                String(bytes, 0, count, Charsets.US_ASCII)
            }
        } catch (_: FileNotFoundException) {
            null
        }

        private fun writePhase(files: Files, phase: String) {
            // Framework AtomicFile on older Android writes directly to a missing base.
            // A synced sibling + atomic rename also protects the very first marker.
            FileOutputStream(files.journalTemp).use { output ->
                output.write(phase.toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            Os.rename(files.journalTemp.path, files.journal.path)
            syncDirectory(files.active.parentFile!!)
            check(readPhase(files) == phase) { "Trips replacement journal was not persisted" }
        }

        private fun cleanupCommitted(files: Files) {
            deleteDatabaseFiles(files.backup)
            syncDirectory(files.active.parentFile!!)
            deleteIfPresent(files.journal)
            deleteIfPresent(files.journalTemp)
            syncDirectory(files.active.parentFile!!)
        }

        private fun cleanupWorkingFiles(files: Files) {
            deleteDatabaseFiles(files.snapshot)
            deleteDatabaseFiles(files.candidate)
        }

        private fun deleteDatabaseFiles(database: File) {
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                deleteIfPresent(File(database.path + suffix))
            }
        }

        private fun deleteIfPresent(file: File) {
            check(!file.exists() || (file.isFile && file.delete())) { "Cannot remove Trips temporary file" }
        }

        /** Only after a verified TRUNCATE checkpoint and closing every handle. */
        internal fun prepareClosedDatabaseForMove(database: File) {
            listOf("-wal", "-journal").forEach { suffix ->
                val sidecar = File(database.path + suffix)
                check(!sidecar.exists() || (sidecar.isFile && sidecar.length() == 0L)) {
                    "Trips database has an uncheckpointed sidecar"
                }
            }
            listOf("-wal", "-shm", "-journal").forEach { deleteIfPresent(File(database.path + it)) }
        }

        private fun copySynced(source: File, target: File, beforeWrite: () -> Unit) {
            check(!target.exists()) { "Trips snapshot already exists" }
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        beforeWrite()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(source.length() == target.length()) { "Trips snapshot copy is incomplete" }
            syncDirectory(target.parentFile!!)
        }

        private fun syncDirectory(directory: File) {
            check(directory.isDirectory) { "Trips directory is unavailable" }
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }

        private fun sameSessions(left: TripStore, right: TripStore): Boolean =
            left.allSessions().sortedBy { it.tripId } == right.allSessions().sortedBy { it.tripId }

        private fun sameEnergyRuntime(left: TripStore, right: TripStore): Boolean =
            left.readEnergyRuntimeRow() == right.readEnergyRuntimeRow()

        private fun sameHistoricalEnergyBackfill(left: TripStore, right: TripStore): Boolean =
            left.historicalEnergyBackfillRecords() == right.historicalEnergyBackfillRecords()

        private fun sameRoute(left: TripStore, right: TripStore, tripId: String, firstSequence: Long = 0L): Boolean =
            routeDigest(left, tripId, firstSequence).contentEquals(routeDigest(right, tripId, firstSequence))

        private fun routeDigest(store: TripStore, tripId: String, firstSequence: Long): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            DataOutputStream(DigestOutputStream(object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
            }, digest)).use { output ->
                store.forEachRoutePointFrom(tripId, firstSequence) { point ->
                    checkInterrupted()
                    RouteChunkCodec.writePointForDigest(output, point)
                }
            }
            return digest.digest()
        }

        private fun checkInterrupted() {
            check(!Thread.currentThread().isInterrupted) { "Trips compression interrupted" }
        }
    }
}
