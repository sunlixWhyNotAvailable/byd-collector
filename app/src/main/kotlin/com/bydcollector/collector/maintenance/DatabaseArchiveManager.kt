package com.bydcollector.collector.maintenance

import java.io.File

/**
 * Moves a checkpointed, closed SQLite database file set into an archive directory.
 * [DbMaintenanceCoordinator] owns the lifecycle; callers must checkpoint and close the database first.
 */
internal object DatabaseArchiveManager {
    data class ArchiveResult(
        val ok: Boolean,
        val archiveDirectory: File,
        val movedFiles: List<File>,
        val error: String? = null,
        val rollbackOk: Boolean = true
    )

    fun sidecarFiles(databaseFile: File): List<File> = listOf(
        databaseFile,
        File(databaseFile.path + "-wal"),
        File(databaseFile.path + "-shm"),
        File(databaseFile.path + "-journal")
    )

    fun archive(databaseFile: File, archiveRoot: File, timestamp: String): ArchiveResult {
        val archiveDirectory = File(archiveRoot, "${databaseFile.nameWithoutExtension}_$timestamp")
        val movedFiles = mutableListOf<File>()

        if (!databaseFile.exists()) {
            return ArchiveResult(false, archiveDirectory, emptyList(), "Database file does not exist")
        }

        if (archiveDirectory.exists()) {
            return ArchiveResult(false, archiveDirectory, emptyList(), "Archive directory already exists")
        }

        if (!archiveDirectory.mkdirs() && !archiveDirectory.isDirectory) {
            return ArchiveResult(false, archiveDirectory, emptyList(), "Cannot create archive directory")
        }

        for (source in sidecarFiles(databaseFile).filter { it.exists() }) {
            val target = File(archiveDirectory, source.name)
            if (target.exists()) {
                val rollbackOk = rollback(databaseFile, movedFiles)
                return ArchiveResult(false, archiveDirectory, movedFiles.toList(), "Archive target already exists: ${source.name}", rollbackOk)
            }
            if (!source.renameTo(target)) {
                val rollbackOk = rollback(databaseFile, movedFiles)
                return ArchiveResult(false, archiveDirectory, movedFiles.toList(), "Cannot move ${source.name}", rollbackOk)
            }
            movedFiles += target
        }

        return ArchiveResult(true, archiveDirectory, movedFiles.toList())
    }

    internal fun rollback(
        databaseFile: File,
        movedFiles: List<File>,
        moveFile: (File, File) -> Boolean = { source, target -> source.renameTo(target) }
    ): Boolean {
        var rollbackOk = true
        for (moved in movedFiles.asReversed()) {
            if (!moveFile(moved, File(databaseFile.parentFile, moved.name))) {
                rollbackOk = false
            }
        }
        return rollbackOk
    }
}
