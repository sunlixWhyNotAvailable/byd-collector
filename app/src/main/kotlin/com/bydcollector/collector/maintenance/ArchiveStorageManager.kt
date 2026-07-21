package com.bydcollector.collector.maintenance

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ArchiveStorageManager(
    private val archiveRoot: File,
    private val mainDatabaseFile: File,
    private val debugDatabaseFile: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val isRetentionProtected: (String) -> Boolean = { false }
) {
    fun snapshot(limitBytes: Long): ArchiveStorageSnapshot {
        archiveRoot.mkdirs()
        val entries = archiveRoot.listFiles()
            .orEmpty()
            .mapNotNull(::entryFor)
            .sortedWith(compareByDescending<ArchiveStorageEntry> { it.createdAtMs }.thenBy { it.id })
        return ArchiveStorageSnapshot(
            archiveRootPath = archiveRoot.absolutePath,
            mainDatabaseSizeBytes = mainDatabaseFile.takeIf { it.exists() }?.length() ?: 0L,
            debugDatabaseSizeBytes = debugDatabaseFile.takeIf { it.exists() }?.length() ?: 0L,
            archiveBytes = entries.sumOf { it.sizeBytes },
            archiveLimitBytes = limitBytes,
            entries = entries
        )
    }

    fun compressRawArchiveDirectory(
        directory: File,
        onStatus: (ArchiveStorageJobStatus) -> Unit = {}
    ): Boolean {
        archiveRoot.mkdirs()
        if (!isDirectArchiveChild(directory) || !directory.isDirectory || !isArchiveName(directory.name)) return false
        val target = File(archiveRoot, "${directory.name}.zip")
        val tmp = File(archiveRoot, "${directory.name}.zip.tmp")
        if (target.exists()) {
            return target.length() > 0L && directory.deleteRecursively()
        }
        tmp.delete()

        return runCatching {
            onStatus(status(ArchiveStorageJobMode.COMPRESS, 1, 3, "Готуємо архів", "Preparing archive", directory.name))
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
                zipDirectory(directory, zip)
            }
            onStatus(status(ArchiveStorageJobMode.COMPRESS, 2, 3, "Завершуємо ZIP", "Finalizing ZIP", directory.name))
            check(tmp.length() > 0L) { "Archive ZIP is empty" }
            check(tmp.renameTo(target)) { "Cannot finalize archive ZIP" }
            check(target.exists() && target.length() > 0L) { "Archive ZIP was not finalized" }
            onStatus(status(ArchiveStorageJobMode.COMPRESS, 3, 3, "Видаляємо raw архів", "Deleting raw archive", directory.name))
            check(directory.deleteRecursively()) { "Cannot delete raw archive directory" }
            true
        }.getOrElse {
            tmp.delete()
            onStatus(
                status(ArchiveStorageJobMode.COMPRESS, 0, 3, "Помилка ZIP", "ZIP failed", directory.name, it.message)
            )
            false
        }
    }

    fun compressPendingRawArchives(onStatus: (ArchiveStorageJobStatus) -> Unit = {}): Int {
        cleanupTmpFiles()
        return archiveRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && isArchiveName(it.name) }
            .sortedBy { it.lastModified() }
            .count { compressRawArchiveDirectory(it, onStatus) }
    }

    fun enforceRetention(
        limitBytes: Long,
        onStatus: (ArchiveStorageJobStatus) -> Unit = {}
    ): Int {
        val entries = snapshot(limitBytes).entries.filter { it.deletable }
        val protectedIds = entries
            .groupBy { archiveFamily(it.id) }
            .filterKeys { it != null }
            .values
            .mapNotNull { familyEntries ->
                familyEntries.maxWithOrNull(compareBy<ArchiveStorageEntry> { it.createdAtMs }.thenBy { it.id })?.id
            }
            .toSet()
        var total = entries.sumOf { it.sizeBytes }
        var deleted = 0
        entries
            .filter { it.id !in protectedIds && !isRetentionProtected(it.id) }
            .sortedBy { it.createdAtMs }
            .forEach { entry ->
                if (total <= limitBytes) return@forEach
                onStatus(status(ArchiveStorageJobMode.RETENTION, deleted + 1, entries.size, "Видаляємо старий архів", "Deleting old archive", entry.id))
                if (!isRetentionProtected(entry.id) && deleteArchiveFile(entry.id)) {
                    total -= entry.sizeBytes
                    deleted += 1
                }
            }
        return deleted
    }

    fun resolveShareZipFiles(ids: List<String>): List<File>? {
        if (ids.isEmpty() || ids.size != ids.toSet().size) return null
        return ids.map { id ->
            val target = archiveChild(id) ?: return null
            target.takeIf(::isShareableZip) ?: return null
        }
    }

    fun deleteArchiveIds(
        ids: List<String>,
        onStatus: (ArchiveStorageJobStatus) -> Unit = {}
    ): Int {
        var deleted = 0
        val safeIds = ids.distinct()
        safeIds.forEachIndexed { index, id ->
            onStatus(status(ArchiveStorageJobMode.DELETE, index + 1, safeIds.size, "Видаляємо архів", "Deleting archive", id))
            if (deleteArchiveFile(id)) deleted += 1
        }
        return deleted
    }

    private fun deleteArchiveFile(id: String): Boolean {
        val target = archiveChild(id) ?: return false
        if (!target.exists() || !isDeletableArchive(target)) return false
        return if (target.isDirectory) target.deleteRecursively() else target.delete()
    }

    private fun entryFor(file: File): ArchiveStorageEntry? {
        val status = when {
            file.isDirectory && isArchiveName(file.name) -> ArchiveEntryStatus.RAW_DIRECTORY
            file.isFile && file.name.endsWith(".zip") && isArchiveName(file.name.removeSuffix(".zip")) -> ArchiveEntryStatus.COMPRESSED_ZIP
            file.isFile && file.name.endsWith(".zip.tmp") && isArchiveName(file.name.removeSuffix(".zip.tmp")) -> ArchiveEntryStatus.TMP
            else -> return null
        }
        val id = file.name
        return ArchiveStorageEntry(
            id = id,
            displayName = id.removeSuffix(".zip").removeSuffix(".zip.tmp"),
            path = file.absolutePath,
            createdAtMs = file.lastModified().takeIf { it > 0L } ?: clock(),
            sizeBytes = sizeOf(file),
            status = status,
            deletable = isDeletableArchive(file)
        )
    }

    private fun isDeletableArchive(file: File): Boolean {
        return isDirectArchiveChild(file) &&
            ((file.isDirectory && isArchiveName(file.name)) ||
                (file.isFile && file.name.endsWith(".zip") && isArchiveName(file.name.removeSuffix(".zip"))))
    }

    private fun isShareableZip(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L || !file.name.endsWith(".zip")) return false
        if (!isArchiveName(file.name.removeSuffix(".zip"))) return false
        return runCatching { ZipFile(file).use { } }.isSuccess
    }

    private fun cleanupTmpFiles() {
        archiveRoot.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".zip.tmp") && isDirectArchiveChild(it) }
            .forEach { it.delete() }
    }

    private fun zipDirectory(directory: File, zip: ZipOutputStream) {
        directory.walkTopDown()
            .filter { it != directory }
            .sortedBy { it.absolutePath }
            .forEach { file ->
                val entryName = directory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                if (file.isDirectory) {
                    zip.putNextEntry(ZipEntry("$entryName/"))
                    zip.closeEntry()
                } else {
                    zip.putNextEntry(ZipEntry(entryName))
                    BufferedInputStream(FileInputStream(file)).use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
    }

    private fun archiveChild(id: String): File? {
        if (id.isBlank() || id.contains('/') || id.contains('\\') || id.contains("..")) return null
        val child = File(archiveRoot, id)
        return if (isDirectArchiveChild(child)) child else null
    }

    private fun isDirectArchiveChild(file: File): Boolean {
        return runCatching {
            file.canonicalFile.parentFile == archiveRoot.canonicalFile
        }.getOrDefault(false)
    }

    private fun isArchiveName(name: String): Boolean = archiveFamily(name) != null

    private fun archiveFamily(name: String): String? = when {
        name.startsWith(MAIN_ARCHIVE_PREFIX) -> MAIN_ARCHIVE_PREFIX
        name.startsWith(DEBUG_ARCHIVE_PREFIX) -> DEBUG_ARCHIVE_PREFIX
        else -> null
    }

    private fun sizeOf(file: File): Long {
        return when {
            file.isFile -> file.length()
            file.isDirectory -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            else -> 0L
        }
    }

    private fun status(
        mode: ArchiveStorageJobMode,
        stepIndex: Int,
        stepCount: Int,
        messageUk: String,
        messageEn: String,
        itemId: String?,
        error: String? = null
    ): ArchiveStorageJobStatus {
        return ArchiveStorageJobStatus(
            mode = mode,
            running = error == null,
            stepIndex = stepIndex,
            stepCount = stepCount,
            messageUk = messageUk,
            messageEn = messageEn,
            itemId = itemId,
            error = error,
            updatedAtMs = clock()
        )
    }

    companion object {
        const val MAIN_ARCHIVE_PREFIX = "bydcollector_telemetry_"
        const val DEBUG_ARCHIVE_PREFIX = "bydcollector_debug_round_robin_"
    }
}
