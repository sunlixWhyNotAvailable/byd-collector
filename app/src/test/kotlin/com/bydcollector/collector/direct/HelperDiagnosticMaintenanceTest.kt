package com.bydcollector.collector.direct

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HelperDiagnosticMaintenanceTest {
    @Test
    fun snapshotStreamsOnlyFixedBoundedEntriesWithManifestAndAlignedJsonl() {
        withRoots { diagnostic, legacy, _ ->
            val store = HelperDiagnosticFileStore(diagnostic, legacy, 96)
            store.persist("{\"event\":\"start\"}", "{\"current\":1}")
            File(diagnostic, HelperDiagnosticFileStore.ACTIVE_NAME).writeText(
                (0..30).joinToString("") { "{\"record\":$it}\n" }
            )
            File(diagnostic, HelperDiagnosticFileStore.ACTIVE_NAME + ".1").writeText("{\"old\":1}\n")
            File(diagnostic, HelperDiagnosticFileStore.BOOTSTRAP_NAME).writeBytes(
                ByteArray(120) { 'b'.code.toByte() }
            )
            File(legacy, HelperDiagnosticFileStore.LEGACY_BOOTSTRAP_NAME).writeBytes(
                ByteArray(140) { 'l'.code.toByte() }
            )
            File(legacy, HelperDiagnosticFileStore.LEGACY_BOOTSTRAP_NAME + ".1").writeText("legacy-old")
            val staleExport = File(
                diagnostic,
                HelperDiagnosticFileStore.ARCHIVE_PREFIX + "abandoned" + HelperDiagnosticFileStore.ARCHIVE_SUFFIX
            ).apply {
                writeText("stale")
                setLastModified(System.currentTimeMillis() - HelperDiagnosticFileStore.STALE_ARCHIVE_AGE_MS - 1_000)
            }
            val recentExport = File(
                diagnostic,
                HelperDiagnosticFileStore.ARCHIVE_PREFIX + "recent" + HelperDiagnosticFileStore.ARCHIVE_SUFFIX
            ).apply { writeText("recent") }

            val output = ByteArrayOutputStream()
            assertEquals(0, HelperDiagnosticsMain.streamSnapshot(store, output))
            val entries = unzip(output.toByteArray())
            val manifest = JSONObject(String(assertNotNull(entries[HelperDiagnosticFileStore.MANIFEST_ENTRY])))

            assertEquals(1, manifest.getInt("schema_version"))
            assertEquals("snapshot", manifest.getString("operation"))
            assertEquals("complete", manifest.getString("overall_status"))
            assertEquals("in_jvm_diagnostics_locked", manifest.getString("coherent_scope"))
            assertEquals("best_effort_unlocked_writer", manifest.getString("legacy_pre_jvm_consistency"))
            assertEquals(13, manifest.getJSONArray("files").length())
            assertTrue(entries.keys.all { it in allowedEntries })
            assertTrue(entries.size <= 14)
            entries.filterKeys {
                it != HelperDiagnosticFileStore.MANIFEST_ENTRY &&
                    it != HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.SNAPSHOT_NAME
            }.forEach { (_, bytes) ->
                assertTrue(bytes.size <= 96)
            }
            val jsonl = String(assertNotNull(entries[HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.ACTIVE_NAME]))
            jsonl.lineSequence().filter(String::isNotBlank).forEach { JSONObject(it) }
            assertEquals(
                "{\"current\":1}\n",
                String(assertNotNull(entries[HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.SNAPSHOT_NAME]))
            )
            assertFalse(staleExport.exists())
            assertTrue(recentExport.isFile)
            assertEquals(1, diagnostic.listFiles().orEmpty().count {
                it.name.startsWith(HelperDiagnosticFileStore.ARCHIVE_PREFIX)
            })
        }
    }

    @Test
    fun clearPreservesSnapshotLockAndRawSpoolAndLoggingContinues() {
        withRoots { diagnostic, legacy, raw ->
            val store = HelperDiagnosticFileStore(diagnostic, legacy, 128)
            store.persist("{\"event\":\"before\"}", "{\"current\":7}")
            store.persistBootstrap("bootstrap-before\n".toByteArray())
            File(diagnostic, HelperDiagnosticFileStore.ACTIVE_NAME + ".1").writeText("old-json\n")
            File(diagnostic, HelperDiagnosticFileStore.BOOTSTRAP_NAME + ".1").writeText("old-bootstrap\n")
            File(legacy, HelperDiagnosticFileStore.LEGACY_BOOTSTRAP_NAME).writeText("legacy-active\n")
            File(legacy, HelperDiagnosticFileStore.LEGACY_BOOTSTRAP_NAME + ".1").writeText("legacy-old\n")
            val snapshot = File(diagnostic, HelperDiagnosticFileStore.SNAPSHOT_NAME)
            val snapshotBefore = snapshot.readBytes()
            val rawRecord = File(raw, "record.ready").apply { writeText("raw telemetry") }

            val output = ByteArrayOutputStream()
            assertEquals(0, HelperDiagnosticsMain.clear(store, output))
            val result = JSONObject(output.toString(Charsets.UTF_8.name()))
            assertEquals("complete", result.getString("overall_status"))
            assertTrue(result.getJSONArray("preserved").toString().contains("raw_telemetry_spool"))
            assertEquals(0L, File(diagnostic, HelperDiagnosticFileStore.ACTIVE_NAME).length())
            assertEquals(0L, File(diagnostic, HelperDiagnosticFileStore.BOOTSTRAP_NAME).length())
            assertEquals(0L, File(legacy, HelperDiagnosticFileStore.LEGACY_BOOTSTRAP_NAME).length())
            assertFalse(File(diagnostic, HelperDiagnosticFileStore.ACTIVE_NAME + ".1").exists())
            assertFalse(File(diagnostic, HelperDiagnosticFileStore.BOOTSTRAP_NAME + ".1").exists())
            assertFalse(File(legacy, HelperDiagnosticFileStore.LEGACY_BOOTSTRAP_NAME + ".1").exists())
            assertContentEquals(snapshotBefore, snapshot.readBytes())
            assertTrue(File(diagnostic, HelperDiagnosticFileStore.LOCK_NAME).isFile)
            assertEquals("raw telemetry", rawRecord.readText())

            store.persist("{\"event\":\"after\"}", "{\"current\":8}")
            assertTrue(File(diagnostic, HelperDiagnosticFileStore.ACTIVE_NAME).readText().contains("after"))
        }
    }

    @Test
    fun clearReportsPartialFailureAndStillClearsIndependentFiles() {
        withRoots { diagnostic, legacy, raw ->
            val store = HelperDiagnosticFileStore(diagnostic, legacy, 128)
            store.persist("{\"event\":\"before\"}", "{\"current\":7}")
            val obstructed = File(diagnostic, HelperDiagnosticFileStore.ACTIVE_NAME + ".2")
            assertTrue(obstructed.mkdir())
            val rawRecord = File(raw, "record.ready").apply { writeText("raw") }

            val output = ByteArrayOutputStream()
            assertEquals(1, HelperDiagnosticsMain.clear(store, output))
            val result = JSONObject(output.toString(Charsets.UTF_8.name()))
            assertEquals("partial", result.getString("overall_status"))
            assertTrue(result.getJSONArray("files").toString().contains("not_regular_file"))
            assertEquals(0L, File(diagnostic, HelperDiagnosticFileStore.ACTIVE_NAME).length())
            assertTrue(File(diagnostic, HelperDiagnosticFileStore.SNAPSHOT_NAME).isFile)
            assertEquals("raw", rawRecord.readText())
        }
    }

    @Test
    fun snapshotAndClearSerializeWithAppendRotationAndAlwaysProduceValidOutput() {
        withRoots { diagnostic, legacy, _ ->
            val store = HelperDiagnosticFileStore(diagnostic, legacy, 96)
            store.persist("{\"event\":\"seed\"}", "{\"current\":0}")
            val start = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val writer = thread(start = true, name = "diagnostic-test-writer") {
                try {
                    start.await()
                    repeat(40) {
                        store.persist("{\"event\":\"append-$it\"}", "{\"current\":$it}")
                        store.persistBootstrap("bootstrap-$it\n".toByteArray())
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }
            val maintainer = thread(start = true, name = "diagnostic-test-maintainer") {
                try {
                    start.await()
                    repeat(12) { index ->
                        if (index % 3 == 0) {
                            val clear = ByteArrayOutputStream()
                            HelperDiagnosticsMain.clear(store, clear)
                            JSONObject(clear.toString(Charsets.UTF_8.name()))
                        } else {
                            val snapshot = ByteArrayOutputStream()
                            HelperDiagnosticsMain.streamSnapshot(store, snapshot)
                            JSONObject(String(assertNotNull(unzip(snapshot.toByteArray())[HelperDiagnosticFileStore.MANIFEST_ENTRY])))
                        }
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }
            start.countDown()
            writer.join(5_000)
            maintainer.join(5_000)
            assertFalse(writer.isAlive)
            assertFalse(maintainer.isAlive)
            failure.get()?.let { throw AssertionError("concurrent diagnostic operation failed", it) }
        }
    }

    @Test
    fun snapshotFailureStillReturnsAParseablePartialArchive() {
        withRoots { diagnostic, legacy, _ ->
            val unusable = File(diagnostic, "not-a-directory").apply { writeText("occupied") }
            val store = HelperDiagnosticFileStore(unusable, legacy, 128)
            val output = ByteArrayOutputStream()

            assertEquals(0, HelperDiagnosticsMain.streamSnapshot(store, output))
            val manifest = JSONObject(String(assertNotNull(unzip(output.toByteArray())[HelperDiagnosticFileStore.MANIFEST_ENTRY])))
            assertEquals("partial", manifest.getString("overall_status"))
            assertEquals("unavailable", manifest.getString("coherent_scope"))
            assertTrue(manifest.has("operation_error"))
        }
    }

    @Test
    fun oversizedAtomicCurrentSnapshotIsReportedInsteadOfTailTruncated() {
        withRoots { diagnostic, legacy, _ ->
            val store = HelperDiagnosticFileStore(diagnostic, legacy, 128)
            store.persist("{\"event\":\"seed\"}", "{\"current\":1}")
            File(diagnostic, HelperDiagnosticFileStore.SNAPSHOT_NAME).writeBytes(
                ByteArray((HelperDiagnosticFileStore.MAX_CURRENT_SNAPSHOT_BYTES + 1).toInt()) { 'x'.code.toByte() }
            )

            val output = ByteArrayOutputStream()
            assertEquals(0, HelperDiagnosticsMain.streamSnapshot(store, output))
            val entries = unzip(output.toByteArray())
            val manifest = JSONObject(String(assertNotNull(entries[HelperDiagnosticFileStore.MANIFEST_ENTRY])))
            val current = (0 until manifest.getJSONArray("files").length())
                .map { manifest.getJSONArray("files").getJSONObject(it) }
                .single { it.getString("entry_name").endsWith(HelperDiagnosticFileStore.SNAPSHOT_NAME) }
            assertEquals("partial", manifest.getString("overall_status"))
            assertEquals("error", current.getString("status"))
            assertEquals("source_exceeds_cap", current.getString("error"))
            assertFalse(entries.containsKey(HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.SNAPSHOT_NAME))
        }
    }

    @Test
    fun entrypointIsDiagnosticOnlyAndStreamsCapturedFileOutsideStoreLock() {
        val source = sourceFile("HelperDiagnosticsMain.java").readText()
        assertFalse(source.contains("CollectorHelperDaemon"))
        assertFalse(source.contains("TelemetryWorkerSpool"))
        assertFalse(source.contains("WorkerPollLoop"))
        assertFalse(source.contains("android.os.Binder"))
        assertTrue(source.contains("store.createSnapshotArchive()"))
        assertTrue(source.indexOf("store.createSnapshotArchive()") < source.indexOf("new FileInputStream(archive.file)"))
        assertTrue(source.contains("\"snapshot\".equals(args[0])"))
        assertTrue(source.contains("\"clear\".equals(args[0])"))
        val storeSource = sourceFile("HelperDiagnosticFileStore.java").readText()
        assertTrue(storeSource.contains("symbolic_link_rejected"))
        assertTrue(storeSource.contains("diagnostic lock is a symbolic link"))
        assertTrue(Regex("Files\\.isSymbolicLink").findAll(storeSource).count() >= 4)
        assertTrue(storeSource.contains("MAX_STALE_ARCHIVE_DELETES = 32"))
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun withRoots(block: (File, File, File) -> Unit) {
        val root = Files.createTempDirectory("helper-diagnostic-maintenance").toFile()
        try {
            val diagnostic = File(root, "diagnostic").apply { mkdirs() }
            val legacy = File(root, "legacy").apply { mkdirs() }
            val raw = File(root, "raw-spool").apply { mkdirs() }
            block(diagnostic, legacy, raw)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sourceFile(name: String): File = listOf(
        File("app/src/main/java/com/bydcollector/collector/direct/$name"),
        File("src/main/java/com/bydcollector/collector/direct/$name")
    ).firstOrNull(File::isFile) ?: error("Missing $name")

    private val allowedEntries = setOf(
        HelperDiagnosticFileStore.MANIFEST_ENTRY,
        HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.ACTIVE_NAME,
        HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.ACTIVE_NAME + ".1",
        HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.ACTIVE_NAME + ".2",
        HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.ACTIVE_NAME + ".3",
        HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.BOOTSTRAP_NAME,
        HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.BOOTSTRAP_NAME + ".1",
        HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.BOOTSTRAP_NAME + ".2",
        HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.BOOTSTRAP_NAME + ".3",
        HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.SNAPSHOT_NAME,
        HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.LEGACY_BOOTSTRAP_NAME,
        HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.LEGACY_BOOTSTRAP_NAME + ".1",
        HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.LEGACY_BOOTSTRAP_NAME + ".2",
        HelperDiagnosticFileStore.ZIP_PREFIX + HelperDiagnosticFileStore.LEGACY_BOOTSTRAP_NAME + ".3"
    )
}
