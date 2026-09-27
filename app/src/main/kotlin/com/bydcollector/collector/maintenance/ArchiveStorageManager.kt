package com.bydcollector.collector.maintenance

import com.bydcollector.collector.data.debug.DirectDebugDatabaseHelper
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.data.trips.TripDatabaseHelper
import com.bydcollector.collector.util.sqliteFootprintBytes
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ArchiveStorageManager(
    private val archiveRoot: File,
    private val mainDatabaseFile: File,
    private val debugDatabaseFile: File,
    private val tripsDatabaseFile: File = File(mainDatabaseFile.parentFile, TripDatabaseHelper.DATABASE_NAME),
    private val debugDatabaseFileProvider: () -> File = { debugDatabaseFile },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val isRetentionProtected: (String) -> Boolean = { false },
    private val archivedDatabaseVerifier: (String, File, File) -> String? = { family, databaseFile, archiveDirectory ->
        val archivedDatabase = File(archiveDirectory, databaseFile.name)
        val sourceFormat = when (family) {
            TelemetryDatabaseHelper.SCHEMA_FAMILY -> StorageFormatCutoverCoordinator.detectArchivedMain(archivedDatabase)
            DirectDebugDatabaseHelper.SCHEMA_FAMILY -> StorageFormatCutoverCoordinator.detectArchivedDebug(archivedDatabase)
            else -> StorageFormat.UNKNOWN
        }
        if (sourceFormat != StorageFormat.LEGACY_V1 && sourceFormat != StorageFormat.COMPACT_V2) {
            "archive_verification_unavailable:format"
        } else if (
            StorageFormatCutoverCoordinator.verifyArchivedSource(
                family,
                databaseFile,
                archiveDirectory,
                sourceFormat
            )
        ) {
            null
        } else {
            "archive_verification_failed"
        }
    },
    private val isArchiveInUse: (String) -> Boolean = { false }
) {
    fun snapshot(limitBytes: Long): ArchiveStorageSnapshot {
        archiveRoot.mkdirs()
        val entries = archiveRoot.listFiles()
            .orEmpty()
            .mapNotNull(::entryFor)
            .sortedWith(compareByDescending<ArchiveStorageEntry> { it.createdAtMs }.thenBy { it.id })
        return ArchiveStorageSnapshot(
            archiveRootPath = archiveRoot.absolutePath,
            mainDatabaseSizeBytes = sqliteFootprintBytes(mainDatabaseFile),
            debugDatabaseSizeBytes = sqliteFootprintBytes(debugDatabaseFileProvider()),
            tripsDatabaseSizeBytes = sqliteFootprintBytes(tripsDatabaseFile),
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
        if (!isDirectArchiveChild(directory) || !directory.isDirectory || !isArchiveName(directory.name) ||
            isArchiveInUse(directory.name)
        ) return false
        val target = File(archiveRoot, "${directory.name}.zip")
        val tmp = File(archiveRoot, "${directory.name}.zip.tmp")
        var verifying = true
        return try {
            onStatus(status(
                ArchiveStorageJobMode.COMPRESS,
                1,
                4,
                "Перевіряємо базу даних архіву",
                "Verifying archived database",
                directory.name
            ))
            val verificationError = auditRawArchive(directory)
            if (verificationError != null) {
                onStatus(verificationFailureStatus(directory.name, verificationError))
                return false
            }
            verifying = false
            check(!isArchiveInUse(directory.name)) { "archive_in_use" }
            check(!Thread.currentThread().isInterrupted) { "archive_compression_interrupted" }

            if (target.exists()) {
                onStatus(status(
                    ArchiveStorageJobMode.COMPRESS,
                    3,
                    4,
                    "Перевіряємо готовий ZIP",
                    "Verifying existing ZIP",
                    directory.name
                ))
                check(!isArchiveInUse(directory.name)) { "archive_in_use" }
                check(zipMatchesRawDirectory(target, directory)) { "archive_zip_mismatch" }
            } else {
                tmp.delete()
                onStatus(status(ArchiveStorageJobMode.COMPRESS, 2, 4, "Готуємо архів", "Preparing archive", directory.name))
                check(!isArchiveInUse(directory.name)) { "archive_in_use" }
                ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
                    zipDirectory(directory, zip)
                }
                onStatus(status(ArchiveStorageJobMode.COMPRESS, 3, 4, "Завершуємо ZIP", "Finalizing ZIP", directory.name))
                check(tmp.length() > 0L) { "Archive ZIP is empty" }
                check(!isArchiveInUse(directory.name)) { "archive_in_use" }
                check(tmp.renameTo(target)) { "Cannot finalize archive ZIP" }
                check(zipMatchesRawDirectory(target, directory)) { "archive_zip_verify_failed" }
            }
            check(!isArchiveInUse(directory.name)) { "archive_in_use" }
            check(!Thread.currentThread().isInterrupted) { "archive_compression_interrupted" }
            onStatus(status(ArchiveStorageJobMode.COMPRESS, 4, 4, "Видаляємо raw архів", "Deleting raw archive", directory.name))
            check(!isArchiveInUse(directory.name)) { "archive_in_use" }
            check(!Thread.currentThread().isInterrupted) { "archive_compression_interrupted" }
            check(directory.deleteRecursively() && !directory.exists()) { "Cannot delete raw archive directory" }
            true
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            tmp.delete()
            val errorCode = when {
                error is InterruptedException || error is java.util.concurrent.CancellationException ->
                    if (verifying) "archive_verification_interrupted" else "archive_compression_interrupted"
                Thread.currentThread().isInterrupted -> "archive_compression_interrupted"
                verifying -> "archive_verification_unavailable:${error::class.java.simpleName}"
                else -> error.message?.takeIf { it.startsWith("archive_") }
                    ?: "archive_compression_failed:${error::class.java.simpleName}"
            }
            onStatus(verificationFailureStatus(directory.name, errorCode))
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
        val candidates = entries.filter {
            it.status == ArchiveEntryStatus.COMPRESSED_ZIP && !isArchiveInUse(archiveBaseName(it.id))
        }
        val protectedIds = candidates
            .groupBy { archiveFamily(it.id) }
            .filterKeys { it != null }
            .values
            .mapNotNull { familyEntries ->
                familyEntries.maxWithOrNull(compareBy<ArchiveStorageEntry> { it.createdAtMs }.thenBy { it.id })?.id
            }
            .toSet()
        var total = entries.sumOf { it.sizeBytes }
        var deleted = 0
        candidates
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
        onStatus(
            status(
                mode = ArchiveStorageJobMode.DELETE,
                stepIndex = 0,
                stepCount = safeIds.size,
                messageUk = "Готуємо видалення",
                messageEn = "Preparing archive deletion",
                itemId = null,
                running = true
            )
        )
        safeIds.forEachIndexed { index, id ->
            onStatus(
                status(
                    ArchiveStorageJobMode.DELETE,
                    index,
                    safeIds.size,
                    "Видаляємо архів",
                    "Deleting archive",
                    id,
                    running = true
                )
            )
            val outcome = deleteArchiveFileOutcome(id)
            if (outcome.success) {
                deleted += 1
                onStatus(
                    status(
                        ArchiveStorageJobMode.DELETE,
                        index + 1,
                        safeIds.size,
                        "Архів видалено",
                        "Archive deleted",
                        id,
                        running = true
                    )
                )
            } else {
                onStatus(
                    status(
                        ArchiveStorageJobMode.DELETE,
                        index + 1,
                        safeIds.size,
                        "Не вдалося видалити архів",
                        "Archive deletion failed",
                        id,
                        error = outcome.error,
                        running = true
                    )
                )
            }
        }
        return deleted
    }

    private fun deleteArchiveFile(id: String): Boolean {
        return deleteArchiveFileOutcome(id).success
    }

    private fun deleteArchiveFileOutcome(id: String): DeleteOutcome {
        val target = archiveChild(id) ?: return DeleteOutcome(false, "archive_id_rejected")
        if (!target.exists()) return DeleteOutcome(false, "archive_missing")
        if (isArchiveInUse(archiveBaseName(target.name))) return DeleteOutcome(false, "archive_in_use")
        if (!isDeletableArchive(target)) return DeleteOutcome(false, "archive_not_deletable")
        if (isArchiveInUse(archiveBaseName(target.name))) return DeleteOutcome(false, "archive_in_use")
        val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
        return if (deleted && !target.exists()) {
            DeleteOutcome(true, null)
        } else {
            DeleteOutcome(false, "archive_delete_verify_failed")
        }
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

    private fun auditRawArchive(directory: File): String? {
        if (isArchiveInUse(directory.name)) return "archive_in_use"
        if (!writeVerificationMarker(directory, "IN_PROGRESS")) {
            return "archive_verification_unavailable:status_marker"
        }
        return try {
            check(!isArchiveInUse(directory.name)) { "archive_in_use" }
            check(!Thread.currentThread().isInterrupted) { "archive_verification_interrupted" }
            val archiveDatabase = archiveDatabaseFor(directory)
                ?: error("archive_verification_unavailable:database_name")
            check(hasExactDatabaseFileSet(directory, archiveDatabase.databaseFile)) {
                "archive_verification_failed:file_set"
            }
            check(!Thread.currentThread().isInterrupted) { "archive_verification_interrupted" }
            val errorCode = archivedDatabaseVerifier(
                archiveDatabase.family,
                archiveDatabase.databaseFile,
                directory
            )
            if (errorCode != null) error(errorCode)
            check(!Thread.currentThread().isInterrupted) { "archive_verification_interrupted" }
            if (!writeVerificationMarker(directory, "PASSED")) {
                error("archive_verification_unavailable:status_marker")
            }
            null
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            val errorCode = when {
                error is InterruptedException || error is java.util.concurrent.CancellationException ||
                    Thread.currentThread().isInterrupted -> "archive_verification_interrupted"
                error.message?.startsWith("archive_verification_") == true || error.message == "archive_in_use" -> error.message!!
                else -> "archive_verification_unavailable:${error::class.java.simpleName}"
            }
            writeVerificationMarker(directory, "FAILED", errorCode)
            errorCode
        }
    }

    private fun writeVerificationMarker(directory: File, state: String, error: String? = null): Boolean {
        val marker = runCatching { File(directory.canonicalFile, ARCHIVE_VERIFICATION_MARKER) }.getOrNull()
            ?: return false
        if (marker.canonicalFile != marker.absoluteFile) return false
        val content = buildString {
            append("state=").append(state).append('\n')
            append("updated_at_ms=").append(clock()).append('\n')
            error?.let { append("error=").append(it.replace('\n', ' ').replace('\r', ' ').take(160)).append('\n') }
        }
        return runCatching {
            marker.writeText(content, Charsets.UTF_8)
            marker.isFile && marker.canonicalFile == marker.absoluteFile && marker.readText(Charsets.UTF_8) == content
        }.getOrDefault(false)
    }

    private fun hasExactDatabaseFileSet(directory: File, databaseFile: File): Boolean {
        val allowedNames = DatabaseArchiveManager.sidecarFiles(databaseFile).mapTo(mutableSetOf()) { it.name }
        val files = directory.listFiles() ?: return false
        if (files.none { it.name == databaseFile.name && it.isFile }) return false
        return files.all { file ->
            file.name == ARCHIVE_VERIFICATION_MARKER || file.name in allowedNames
        } && files.all { file ->
            file.isFile && runCatching {
                file.canonicalFile == file.absoluteFile && file.canonicalFile.parentFile == directory.canonicalFile
            }.getOrDefault(false)
        }
    }

    private fun archiveDatabaseFor(directory: File): ArchiveDatabase? = when {
        directory.name.startsWith(MAIN_ARCHIVE_PREFIX) -> ArchiveDatabase(
            TelemetryDatabaseHelper.SCHEMA_FAMILY,
            mainDatabaseFile
        )
        directory.name.startsWith(DEBUG_ARCHIVE_PREFIX) -> ArchiveDatabase(
            DirectDebugDatabaseHelper.SCHEMA_FAMILY,
            File(checkNotNull(debugDatabaseFile.parentFile), DirectDebugDatabaseHelper.DATABASE_NAME)
        )
        directory.name.startsWith(LEGACY_DEBUG_ARCHIVE_PREFIX) -> ArchiveDatabase(
            DirectDebugDatabaseHelper.SCHEMA_FAMILY,
            File(checkNotNull(debugDatabaseFile.parentFile), DirectDebugDatabaseHelper.LEGACY_DATABASE_NAME)
        )
        else -> null
    }

    private fun zipMatchesRawDirectory(zipFile: File, directory: File): Boolean = runCatching {
        if (!isDirectArchiveChild(zipFile) || !zipFile.isFile || zipFile.canonicalFile != zipFile.absoluteFile) {
            return@runCatching false
        }
        val rawFiles = directory.walkTopDown().filter { it != directory }.toList()
        val expectedEntries = rawFiles.associateBy { file ->
            val relative = directory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
            if (file.isDirectory) "$relative/" else relative
        }
        if (expectedEntries.size != rawFiles.size) return@runCatching false
        ZipFile(zipFile).use { archive ->
            val actualEntries = mutableMapOf<String, ZipEntry>()
            val enumeration = archive.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (actualEntries.put(entry.name, entry) != null) return@use false
            }
            if (actualEntries.keys != expectedEntries.keys) return@use false
            expectedEntries.all { (name, rawFile) ->
                val zipEntry = actualEntries[name] ?: return@all false
                when {
                    rawFile.isDirectory -> zipEntry.isDirectory
                    rawFile.name == ARCHIVE_VERIFICATION_MARKER ->
                        !zipEntry.isDirectory && zipMarkerPassed(archive, zipEntry)
                    rawFile.isFile && !zipEntry.isDirectory -> sameContent(rawFile, archive, zipEntry)
                    else -> false
                }
            }
        }
    }.getOrDefault(false)

    private fun zipMarkerPassed(archive: ZipFile, entry: ZipEntry): Boolean =
        runCatching {
            if (entry.size !in 1L..MAX_VERIFICATION_MARKER_BYTES) return@runCatching false
            archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readLine() == "state=PASSED" }
        }.getOrDefault(false)

    private fun sameContent(rawFile: File, archive: ZipFile, entry: ZipEntry): Boolean = runCatching {
        val rawDigest = MessageDigest.getInstance("SHA-256")
        FileInputStream(rawFile).use { digestInto(it, rawDigest) }
        val zipDigest = MessageDigest.getInstance("SHA-256")
        archive.getInputStream(entry).use { digestInto(it, zipDigest) }
        rawDigest.digest().contentEquals(zipDigest.digest())
    }.getOrDefault(false)

    private fun digestInto(input: java.io.InputStream, digest: MessageDigest) {
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            digest.update(buffer, 0, count)
        }
    }

    private fun archiveBaseName(id: String): String = id.removeSuffix(".zip")

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
        isSecondaryArchiveName(name) -> DEBUG_ARCHIVE_FAMILY
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
        error: String? = null,
        running: Boolean = error == null
    ): ArchiveStorageJobStatus {
        return ArchiveStorageJobStatus(
            mode = mode,
            running = running,
            stepIndex = stepIndex,
            stepCount = stepCount,
            messageUk = messageUk,
            messageEn = messageEn,
            itemId = itemId,
            error = error,
            updatedAtMs = clock()
        )
    }

    private data class DeleteOutcome(
        val success: Boolean,
        val error: String?
    )

    private data class ArchiveDatabase(
        val family: String,
        val databaseFile: File
    )

    private fun verificationFailureStatus(itemId: String, error: String): ArchiveStorageJobStatus {
        val verificationFailed = error.startsWith("archive_verification_")
        return status(
            mode = ArchiveStorageJobMode.COMPRESS,
            stepIndex = 0,
            stepCount = 4,
            messageUk = if (verificationFailed) "Перевірку не завершено; raw архів збережено" else "ZIP не створено; raw архів збережено",
            messageEn = if (verificationFailed) "Archive verification failed; raw retained" else "ZIP failed; raw archive retained",
            itemId = itemId,
            error = error,
            running = false
        )
    }

    companion object {
        const val MAIN_ARCHIVE_PREFIX = "bydcollector_telemetry_"
        const val DEBUG_ARCHIVE_PREFIX = "bydcollector_secondary_"
        const val LEGACY_DEBUG_ARCHIVE_PREFIX = "bydcollector_debug_round_robin_"
        private const val DEBUG_ARCHIVE_FAMILY = "bydcollector_secondary"
        private const val MAX_VERIFICATION_MARKER_BYTES = 256L
        const val ARCHIVE_VERIFICATION_MARKER = ".archive-verification"

        fun isSecondaryArchiveName(name: String): Boolean =
            name.startsWith(DEBUG_ARCHIVE_PREFIX) || name.startsWith(LEGACY_DEBUG_ARCHIVE_PREFIX)
    }
}
