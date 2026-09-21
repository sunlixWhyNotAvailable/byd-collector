package com.bydcollector.collector.data.debug

import com.bydcollector.collector.maintenance.StorageCutoverJournal
import com.bydcollector.collector.maintenance.StorageFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DirectDebugDatabaseResolverTest {
    @Test
    fun freshInstallUsesSecondaryButLegacyAndItsSidecarsRemainActive() {
        val root = createTempDirectory().toFile()
        assertEquals(DirectDebugDatabaseHelper.DATABASE_NAME, resolve(root).name)

        File(root, "${DirectDebugDatabaseHelper.LEGACY_DATABASE_NAME}-wal").writeText("legacy")
        assertEquals(DirectDebugDatabaseHelper.LEGACY_DATABASE_NAME, resolve(root).name)
    }

    @Test
    fun archiveJournalSwitchesFromExactLegacySourceToSecondaryTarget() {
        val root = createTempDirectory().toFile()
        File(root, DirectDebugDatabaseHelper.LEGACY_DATABASE_NAME).writeText("legacy")
        File(root, DirectDebugDatabaseHelper.DATABASE_NAME).writeText("secondary")
        val journal = debugJournal("ARCHIVING")

        assertEquals(DirectDebugDatabaseHelper.LEGACY_DATABASE_NAME, resolve(root, journal).name)
        assertEquals(
            DirectDebugDatabaseHelper.DATABASE_NAME,
            resolve(root, journal.copy(phase = "CREATING")).name
        )
        assertEquals(
            DirectDebugDatabaseHelper.LEGACY_DATABASE_NAME,
            resolve(root, journal.copy(phase = "ROLLBACK")).name
        )
    }

    @Test
    fun ambiguousActiveNamesWithoutDefinitiveJournalFailClosed() {
        val root = createTempDirectory().toFile()
        File(root, DirectDebugDatabaseHelper.LEGACY_DATABASE_NAME).writeText("legacy")
        File(root, DirectDebugDatabaseHelper.DATABASE_NAME).writeText("secondary")

        assertFailsWith<IllegalStateException> { resolve(root) }
        assertFailsWith<IllegalStateException> {
            resolve(root, debugJournal("CREATING").copy(targetDatabaseName = ""))
        }
    }

    private fun resolve(root: File, journal: StorageCutoverJournal? = null): File =
        DirectDebugDatabaseResolver.resolveDatabaseFile(root, journal)

    private fun debugJournal(phase: String) = StorageCutoverJournal(
        family = DirectDebugDatabaseHelper.SCHEMA_FAMILY,
        archivePath = File("archive").absolutePath,
        phase = phase,
        sourceFormat = StorageFormat.COMPACT_V2,
        manual = true,
        sourceDatabaseName = DirectDebugDatabaseHelper.LEGACY_DATABASE_NAME,
        targetDatabaseName = DirectDebugDatabaseHelper.DATABASE_NAME
    )
}
