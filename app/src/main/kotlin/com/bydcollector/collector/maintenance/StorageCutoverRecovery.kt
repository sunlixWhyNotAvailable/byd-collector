package com.bydcollector.collector.maintenance

import java.io.File

/**
 * The small, filesystem-independent part of journal recovery.  The caller
 * supplies the observed formats/checks; this code decides whether recovery may
 * clear, finish forward, restore, or must stop with the journal intact.
 */
internal object StorageCutoverRecovery {
    const val PHASE_ARCHIVING = "ARCHIVING"
    const val PHASE_CREATING = "CREATING"
    const val PHASE_VERIFYING = "VERIFYING"
    const val PHASE_ROLLBACK = "ROLLBACK"

    enum class Action {
        CLEAR_INTACT_SOURCE,
        COMPLETE_FORWARD,
        RESTORE_ARCHIVE,
        FAIL_CLOSED
    }

    data class Snapshot(
        val phase: String,
        val sourceFormat: StorageFormat,
        val activeFormat: StorageFormat,
        val activeDatabaseExists: Boolean,
        val activeQuickCheck: Boolean,
        val archivedDatabasePresent: Boolean,
        val archivedFormat: StorageFormat,
        val archivedQuickCheck: Boolean,
        val archivedSidecarNames: Set<String>,
        val expectedSidecarNames: Set<String>,
        val unknownArchiveFiles: Boolean,
        val manual: Boolean = false,
        val activeDatabaseName: String = "",
        val activeSidecarNames: Set<String> = emptySet(),
        val sourceDatabaseName: String = ""
    )

    fun decide(snapshot: Snapshot): Action {
        if (snapshot.phase !in setOf(PHASE_ARCHIVING, PHASE_CREATING, PHASE_VERIFYING, PHASE_ROLLBACK)) {
            return Action.FAIL_CLOSED
        }
        if (snapshot.phase == PHASE_ROLLBACK || snapshot.sourceFormat == StorageFormat.ABSENT) {
            return Action.FAIL_CLOSED
        }
        if (snapshot.manual) return decideManual(snapshot)
        if (snapshot.sourceFormat == StorageFormat.UNKNOWN ||
            snapshot.unknownArchiveFiles ||
            snapshot.archivedSidecarNames.any { it !in snapshot.expectedSidecarNames }
        ) return Action.FAIL_CLOSED

        if (snapshot.activeFormat == snapshot.sourceFormat &&
            snapshot.activeQuickCheck &&
            snapshot.archivedSidecarNames.isEmpty()
        ) {
            return Action.CLEAR_INTACT_SOURCE
        }

        if (snapshot.activeFormat == StorageFormat.COMPACT_V2 &&
            snapshot.activeQuickCheck &&
            snapshot.archivedDatabasePresent &&
            snapshot.archivedFormat == snapshot.sourceFormat &&
            snapshot.archivedQuickCheck
        ) {
            return Action.COMPLETE_FORWARD
        }

        val archiveIsVerified = snapshot.archivedDatabasePresent &&
            snapshot.archivedFormat == snapshot.sourceFormat &&
            snapshot.archivedQuickCheck
        val activeMayBeRestored = snapshot.activeFormat == StorageFormat.ABSENT ||
            (snapshot.activeFormat == StorageFormat.UNKNOWN &&
                (!snapshot.activeDatabaseExists || snapshot.phase != PHASE_ARCHIVING))
        if (archiveIsVerified && activeMayBeRestored && snapshot.archivedSidecarNames.isNotEmpty()) {
            return Action.RESTORE_ARCHIVE
        }

        return Action.FAIL_CLOSED
    }

    private fun decideManual(snapshot: Snapshot): Action {
        val sourceDatabaseName = snapshot.sourceDatabaseName.ifBlank { databaseName(snapshot) }
        val expected = snapshot.expectedSidecarNames
        val active = snapshot.activeSidecarNames
        val archived = snapshot.archivedSidecarNames
        if (sourceDatabaseName.isBlank() || sourceDatabaseName !in expected || expected.isEmpty() ||
            snapshot.unknownArchiveFiles || archived.any { it !in expected }
        ) return Action.FAIL_CLOSED

        if (snapshot.activeDatabaseName == sourceDatabaseName &&
            archived.isEmpty() && active == expected && snapshot.activeDatabaseExists
        ) {
            return Action.CLEAR_INTACT_SOURCE
        }
        if (archived == expected &&
            snapshot.activeFormat == StorageFormat.COMPACT_V2 &&
            snapshot.activeQuickCheck
        ) {
            return Action.COMPLETE_FORWARD
        }
        if (snapshot.phase == PHASE_ARCHIVING &&
            archived.isNotEmpty() &&
            active.intersect(archived).isEmpty() &&
            active + archived == expected
        ) {
            return Action.RESTORE_ARCHIVE
        }
        if (snapshot.phase in setOf(PHASE_CREATING, PHASE_VERIFYING) && archived == expected) {
            return Action.RESTORE_ARCHIVE
        }
        return Action.FAIL_CLOSED
    }

    private fun databaseName(snapshot: Snapshot): String = snapshot.activeDatabaseName.ifBlank {
        snapshot.expectedSidecarNames.firstOrNull { it.endsWith(".db") }.orEmpty()
    }

    /** Executes only the already-approved action; every mutation is injected for tests. */
    fun execute(
        action: Action,
        databaseFile: File,
        movedFiles: List<File>,
        deleteActive: (File) -> Boolean,
        restore: (File, List<File>) -> Boolean,
        verifyActive: () -> Boolean,
        clearJournal: () -> Boolean,
        markArchivePending: () -> Boolean = { true },
        cleanupArchive: () -> Unit = {}
    ): Boolean {
        return runCatching {
            when (action) {
                Action.CLEAR_INTACT_SOURCE -> {
                    verifyActive() && clearJournal().also { if (it) cleanupArchive() }
                }

                Action.COMPLETE_FORWARD -> {
                    if (!verifyActive()) false
                    else if (!markArchivePending()) false
                    else clearJournal()
                }

                Action.RESTORE_ARCHIVE -> {
                    if (!deleteActive(databaseFile)) false
                    else if (!restore(databaseFile, movedFiles)) false
                    else if (!verifyActive()) false
                    else clearJournal().also { if (it) cleanupArchive() }
                }

                Action.FAIL_CLOSED -> false
            }
        }.getOrDefault(false)
    }
}
