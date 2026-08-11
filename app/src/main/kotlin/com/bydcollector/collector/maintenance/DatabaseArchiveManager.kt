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

    fun plannedArchiveDirectory(databaseFile: File, archiveRoot: File, timestamp: String): File =
        File(archiveRoot, "${databaseFile.nameWithoutExtension}_$timestamp")

    fun archive(databaseFile: File, archiveRoot: File, timestamp: String): ArchiveResult {
        val archiveDirectory = plannedArchiveDirectory(databaseFile, archiveRoot, timestamp)
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
                val rollbackOk = restore(databaseFile, movedFiles)
                return ArchiveResult(false, archiveDirectory, movedFiles.toList(), "Archive target already exists: ${source.name}", rollbackOk)
            }
            if (!source.renameTo(target)) {
                val rollbackOk = restore(databaseFile, movedFiles)
                return ArchiveResult(false, archiveDirectory, movedFiles.toList(), "Cannot move ${source.name}", rollbackOk)
            }
            movedFiles += target
        }

        return ArchiveResult(true, archiveDirectory, movedFiles.toList())
    }

    internal fun restore(
        databaseFile: File,
        movedFiles: List<File>,
        moveFile: (File, File) -> Boolean = { source, target -> source.renameTo(target) }
    ): Boolean {
        val expectedNames = sidecarFiles(databaseFile).map { it.name }.toSet()
        val activeParent = runCatching { databaseFile.canonicalFile.parentFile }.getOrNull()
        if (movedFiles.map { it.name }.toSet().size != movedFiles.size ||
            movedFiles.any { it.name !in expectedNames ||
                runCatching { it.canonicalFile.parentFile == activeParent }.getOrDefault(false) }
        ) {
            return false
        }
        var rollbackOk = true
        for (moved in movedFiles.asReversed()) {
            val target = File(databaseFile.parentFile, moved.name)
            if (target.exists() || !moveFile(moved, target)) {
                rollbackOk = false
            }
        }
        return rollbackOk
    }

    internal fun rollback(
        databaseFile: File,
        movedFiles: List<File>,
        moveFile: (File, File) -> Boolean = { source, target -> source.renameTo(target) }
    ): Boolean = restore(databaseFile, movedFiles, moveFile)
}
