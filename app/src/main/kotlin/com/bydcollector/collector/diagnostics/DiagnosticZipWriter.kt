package com.bydcollector.collector.diagnostics

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

//publishes the latest diagnostics zip atomically while avoiding self-inclusion of zip outputs
internal object DiagnosticZipWriter {
    fun writeLatestZip(
        zipFile: File,
        runDir: File,
        publish: (File, File) -> Unit = ::publishLatestZip
    ) {
        if (!runDir.isDirectory) return
        val parent = zipFile.parentFile ?: return
        check(parent.isDirectory || parent.mkdirs()) { "Failed to create diagnostics directory: ${parent.absolutePath}" }
        val tempZip = File.createTempFile("${zipFile.name}.", ".tmp", parent)
        val zipCanonical = zipFile.canonicalFile
        val tempZipCanonical = tempZip.canonicalFile
        try {
            //writes to a temp file first so readers never pull a partially-written zip
            ZipOutputStream(FileOutputStream(tempZip)).use { zip ->
                runDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val fileCanonical = file.canonicalFile
                        //prevents the zip writer from recursively adding its own output or temp file
                        if (
                            fileCanonical == zipCanonical ||
                            fileCanonical == tempZipCanonical ||
                            fileCanonical.parentFile == zipCanonical.parentFile &&
                            fileCanonical.name.startsWith("${zipCanonical.name}.")
                        ) return@forEach
                        val entryName = runDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                        zip.putNextEntry(ZipEntry(entryName))
                        FileInputStream(file).use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            publish(tempZip, zipFile)
        } catch (error: Exception) {
            tempZip.delete()
            throw error
        }
    }

    private fun publishLatestZip(tempZip: File, zipFile: File) {
        try {
            Files.move(
                tempZip.toPath(),
                zipFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            publishWithBackup(tempZip, zipFile)
        } catch (_: UnsupportedOperationException) {
            publishWithBackup(tempZip, zipFile)
        }
    }

    private fun publishWithBackup(tempZip: File, zipFile: File) {
        if (!zipFile.exists()) {
            Files.move(tempZip.toPath(), zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        }
        val backup = File.createTempFile("${zipFile.name}.", ".bak", zipFile.parentFile)
        Files.copy(zipFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        var removeBackup = false
        try {
            Files.move(tempZip.toPath(), zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            removeBackup = true
        } catch (error: Exception) {
            try {
                Files.move(backup.toPath(), zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                removeBackup = true
            } catch (restoreError: Exception) {
                error.addSuppressed(restoreError)
            }
            throw error
        } finally {
            if (removeBackup) backup.delete()
        }
    }
}
