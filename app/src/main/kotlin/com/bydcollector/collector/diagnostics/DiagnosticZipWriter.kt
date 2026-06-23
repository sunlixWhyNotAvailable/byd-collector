package com.bydcollector.collector.diagnostics

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

//publishes the latest diagnostics zip atomically while avoiding self-inclusion of zip outputs
internal object DiagnosticZipWriter {
    fun writeLatestZip(zipFile: File, runDir: File) {
        if (!runDir.isDirectory) return
        zipFile.parentFile?.mkdirs()
        val tempZip = File(zipFile.parentFile, "${zipFile.name}.tmp")
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
                        if (fileCanonical == zipCanonical || fileCanonical == tempZipCanonical) return@forEach
                        val entryName = runDir.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                        zip.putNextEntry(ZipEntry(entryName))
                        FileInputStream(file).use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            if (zipFile.exists()) zipFile.delete()
            if (!tempZip.renameTo(zipFile)) {
                tempZip.delete()
                throw IllegalStateException("Failed to publish latest diagnostics zip")
            }
        } catch (error: Exception) {
            tempZip.delete()
            throw error
        }
    }
}
