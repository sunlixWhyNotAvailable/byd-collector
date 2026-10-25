package com.bydcollector.collector.maintenance

import android.app.Application
import com.bydcollector.collector.data.local.TelemetryDatabaseHelper
import com.bydcollector.collector.runtime.RuntimePreferences
import com.bydcollector.collector.service.CollectorSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import android.content.SharedPreferences
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ArchiveRetryRetentionTest {
    @Test fun failedRawDoesNotBlockReadyArchives() {
        val context = RuntimeEnvironment.getApplication()
        val source = context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)
        val root = File(context.filesDir, "db_archive")
        var now = System.currentTimeMillis()
        val manager = ArchiveStorageManager(root, source, File(source.parentFile, "secondary.db"), clock = { now })
        val settings = CollectorSettings(context)
        val operation = "real-archive-run"
        assertTrue(settings.beginArchiveStorageOperation(operation, ArchiveStorageJobStatus(mode = ArchiveStorageJobMode.RETENTION)))
        val observed = mutableListOf<ArchiveStorageJobStatus>()
        val publish: (ArchiveStorageJobStatus) -> Unit = { status ->
            observed += status
            val state = status.itemId?.let { id -> status.phase?.let { phase ->
                ArchiveStorageItemState(id.removeSuffix(".zip"), operation, phase,
                    status.stepIndex, status.stepCount, now, now, error = status.error)
            } }
            assertTrue(settings.publishArchiveStorageProgress(operation, status, state))
        }
        fun archive(name: String): File {
            TelemetryDatabaseHelper(context).use { it.writableDatabase }
            val moved = DatabaseArchiveManager.archive(source, root, name)
            assertTrue(moved.ok, moved.error)
            return moved.archiveDirectory
        }
        val older = archive("older")
        assertTrue(manager.compressRawArchiveDirectory(older, publish))
        val olderZip = File(root, "${older.name}.zip")
        assertTrue(olderZip.setLastModified(now - 60_000))
        val corrupt = File(root, "bydcollector_telemetry_corrupt").apply { mkdirs() }
        val evidence = File(corrupt, source.name).apply { writeText("damaged sqlite input") }
        assertFalse(manager.compressRawArchiveDirectory(corrupt, publish))
        val persistedFailure = CollectorSettings(context).archiveStorageItems().single { it.archiveId == corrupt.name }
        assertEquals(ArchiveStorageItemPhase.FAILED, persistedFailure.phase)
        assertNotNull(persistedFailure.error)

        val newer = archive("newer")
        now = persistedFailure.updatedAtMs + 299_999
        val deferred = manager.deferredRetryArchiveIds(CollectorSettings(context).archiveStorageItems())
        assertEquals(setOf(corrupt.name), deferred)
        observed.clear()
        assertEquals(1, manager.compressPendingRawArchives(publish, deferred))
        assertTrue(observed.none { it.itemId == corrupt.name })
        assertTrue(File(root, "${newer.name}.zip").isFile)
        assertEquals(1, manager.enforceRetention(0, publish))
        assertFalse(olderZip.exists())
        assertTrue(File(root, "${newer.name}.zip").isFile) // newest family archive stays protected
        assertEquals("damaged sqlite input", evidence.readText())
        assertEquals(persistedFailure, CollectorSettings(context).archiveStorageItems().single { it.archiveId == corrupt.name })

        now = persistedFailure.updatedAtMs + 300_000
        assertTrue(manager.deferredRetryArchiveIds(listOf(persistedFailure)).isEmpty())
        // Explicit direct retry is not subject to automatic scan backoff; it still preserves bad input.
        assertFalse(manager.compressRawArchiveDirectory(corrupt, publish))
        assertEquals("damaged sqlite input", evidence.readText())
        TelemetryDatabaseHelper(context).use { it.writableDatabase }
        source.copyTo(evidence, overwrite = true)
        assertTrue(manager.compressRawArchiveDirectory(corrupt, publish))
        assertFalse(corrupt.exists())
        assertTrue(File(root, "${corrupt.name}.zip").isFile)
    }

    @Test fun retryBudget() {
        val context = RuntimeEnvironment.getApplication()
        withArchivePrefs(context) { prefs ->
            TelemetryDatabaseHelper(context).use { it.writableDatabase }
            val source = context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)
            val root = File(context.cacheDir, "retry").apply { deleteRecursively() }
            val moved = DatabaseArchiveManager.archive(source, root, "retry-budget")
            assertTrue(moved.ok, moved.error)
            val raw = moved.archiveDirectory
            val database = File(raw, source.name)
            database.writeText("damaged SQLite archive")
            val evidence = database.readBytes()
            var now = 1_000_000L
            val manager = ArchiveStorageManager(
                root,
                source,
                File(context.filesDir, "secondary.db"),
                clock = { now }
            )
            val settings = CollectorSettings(context)

            fun runFailure(operationId: String, claim: String? = null, manual: Boolean = false): ArchiveStorageItemState {
                assertTrue(settings.beginArchiveStorageOperation(
                    operationId,
                    ArchiveStorageJobStatus(mode = ArchiveStorageJobMode.RETENTION, updatedAtMs = now)
                ))
                if (claim != null) {
                    val rawIds = checkNotNull(manager.pendingRawArchiveIds()).toSet()
                    assertTrue(raw.name in rawIds)
                    val claimed = if (manual) {
                        settings.claimManualArchiveStorageRetries(operationId, listOf(raw.name), now)
                    } else {
                        settings.claimAutomaticArchiveStorageRetries(operationId, listOf(raw.name), now)
                    }
                    assertEquals(setOf(raw.name), claimed)
                }
                var lastStatus: ArchiveStorageJobStatus? = null
                val published: (ArchiveStorageJobStatus) -> Unit = { status ->
                    lastStatus = status
                    val item = status.itemId?.let { id -> status.phase?.let { phase ->
                        ArchiveStorageItemState(
                            id.removeSuffix(".zip"), operationId, phase, status.stepIndex, status.stepCount,
                            now, now, error = status.error, operationMode = status.mode
                        )
                    } }
                    assertTrue(settings.publishArchiveStorageProgress(operationId, status, item))
                }
                assertFalse(manager.compressRawArchiveDirectory(raw, published))
                assertContentEquals(evidence, database.readBytes())
                val state = settings.archiveStorageItems().single { it.archiveId == raw.name }
                assertEquals(ArchiveStorageItemPhase.FAILED, state.phase)
                assertEquals(ArchiveStorageJobMode.COMPRESS, state.operationMode)
                assertNotNull(state.error)
                assertTrue(settings.finishArchiveStorageOperation(
                    operationId,
                    checkNotNull(lastStatus).copy(operationId = operationId, running = false)
                ))
                return state
            }

            val initialFailure = runFailure("retry-initial")
            assertEquals(0, initialFailure.automaticRetryCount)
            assertEquals(now + 5 * 60_000L, initialFailure.automaticRetryAtMs)

            val key = CollectorSettings.KEY_ARCHIVE_STORAGE_ITEM_STATES
            fun removeRetryFieldsFromLegacyRow() {
                val items = JSONArray(prefs.getString(key, "[]"))
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    if (item.getString("archiveId") == raw.name) {
                        item.remove("operationMode")
                        item.remove("automaticRetryCount")
                        item.remove("automaticRetryAtMs")
                    }
                }
                assertTrue(prefs.edit().putString(key, items.toString()).commit())
            }

            // Remove only fields absent in v3.3.8 from a real failed row to exercise legacy migration.
            removeRetryFieldsFromLegacyRow()
            val migrated = settings.prepareArchiveStorageRetryMetadata(now).single()
            assertEquals(ArchiveStorageJobMode.COMPRESS, migrated.operationMode)
            assertEquals(0, migrated.automaticRetryCount)
            assertEquals(initialFailure.completedAtMs!! + 5 * 60_000L, migrated.automaticRetryAtMs)

            // A later real successful archive makes the old failure's global job status non-matching.
            TelemetryDatabaseHelper(context).use { it.writableDatabase }
            val other = DatabaseArchiveManager.archive(source, root, "retry-other")
            assertTrue(other.ok, other.error)
            assertTrue(settings.beginArchiveStorageOperation(
                "retry-unrelated-success",
                ArchiveStorageJobStatus(mode = ArchiveStorageJobMode.RETENTION, updatedAtMs = now)
            ))
            var otherStatus: ArchiveStorageJobStatus? = null
            assertTrue(manager.compressRawArchiveDirectory(other.archiveDirectory) { status ->
                otherStatus = status
                val item = status.itemId?.let { id -> status.phase?.let { phase ->
                    ArchiveStorageItemState(
                        id.removeSuffix(".zip"), "retry-unrelated-success", phase,
                        status.stepIndex, status.stepCount, now, now, error = status.error,
                        operationMode = status.mode
                    )
                } }
                assertTrue(settings.publishArchiveStorageProgress("retry-unrelated-success", status, item))
            })
            assertTrue(settings.finishArchiveStorageOperation(
                "retry-unrelated-success",
                checkNotNull(otherStatus).copy(operationId = "retry-unrelated-success", running = false)
            ))
            removeRetryFieldsFromLegacyRow()
            val unknownLegacy = settings.prepareArchiveStorageRetryMetadata(now).single { it.archiveId == raw.name }
            assertNull(unknownLegacy.operationMode)
            assertEquals(0, unknownLegacy.automaticRetryCount)
            assertEquals(initialFailure.completedAtMs!! + 5 * 60_000L, unknownLegacy.automaticRetryAtMs)
            assertNull(settings.nextAutomaticArchiveStorageRetryAtMs(
                checkNotNull(unknownLegacy.automaticRetryAtMs), setOf(raw.name)
            ))

            now = checkNotNull(unknownLegacy.automaticRetryAtMs) - 100_000L
            var failure = runFailure("retry-manual-before-deadline", raw.name, manual = true)
            assertEquals(0, failure.automaticRetryCount)
            assertEquals(now + 5 * 60_000L, failure.automaticRetryAtMs)

            now = checkNotNull(failure.automaticRetryAtMs)
            assertTrue(settings.beginArchiveStorageOperation(
                "retry-auto-1",
                ArchiveStorageJobStatus(mode = ArchiveStorageJobMode.RETENTION, updatedAtMs = now)
            ))
            assertEquals(
                setOf(raw.name),
                settings.claimAutomaticArchiveStorageRetries("retry-auto-1", listOf(raw.name), now)
            )
            val claimed = settings.archiveStorageItems().single { it.archiveId == raw.name }
            assertEquals(ArchiveStorageItemPhase.QUEUED, claimed.phase)
            assertEquals(1, claimed.automaticRetryCount)
            assertNull(claimed.automaticRetryAtMs)
            assertEquals("retry-auto-1", settings.recoverArchiveStorageAfterProcessRestart("retry-process-recovery"))

            now += 10_000L
            val recovery = manager.reconcilePersistedItems(settings.archiveStorageItems())
            assertTrue(recovery.scanSucceeded)
            assertTrue(settings.beginArchiveStorageOperation(
                "retry-interrupted-recovery",
                ArchiveStorageJobStatus(mode = ArchiveStorageJobMode.RETENTION, updatedAtMs = now)
            ))
            assertTrue(settings.reconcileArchiveStorageItems("retry-interrupted-recovery", recovery))
            failure = CollectorSettings(context).archiveStorageItems().single { it.archiveId == raw.name }
            assertEquals(ArchiveStorageItemPhase.FAILED, failure.phase)
            assertEquals(1, failure.automaticRetryCount)
            assertEquals(now + 15 * 60_000L, failure.automaticRetryAtMs)
            assertTrue(settings.finishArchiveStorageOperation(
                "retry-interrupted-recovery",
                ArchiveStorageJobStatus(mode = ArchiveStorageJobMode.RETENTION, running = false, updatedAtMs = now)
            ))

            now = checkNotNull(failure.automaticRetryAtMs)
            failure = runFailure("retry-auto-2", raw.name)
            assertEquals(2, failure.automaticRetryCount)
            assertEquals(now + 60 * 60_000L, failure.automaticRetryAtMs)

            now = checkNotNull(failure.automaticRetryAtMs)
            failure = runFailure("retry-auto-3", raw.name)
            assertEquals(3, failure.automaticRetryCount)
            assertNull(failure.automaticRetryAtMs)

            now += 60 * 60_000L
            assertTrue(settings.beginArchiveStorageOperation(
                "retry-exhausted-check",
                ArchiveStorageJobStatus(mode = ArchiveStorageJobMode.RETENTION, updatedAtMs = now)
            ))
            assertTrue(settings.claimAutomaticArchiveStorageRetries(
                "retry-exhausted-check", listOf(raw.name), now
            ).isEmpty())
            assertTrue(settings.finishArchiveStorageOperation(
                "retry-exhausted-check",
                ArchiveStorageJobStatus(mode = ArchiveStorageJobMode.RETENTION, running = false)
            ))

            failure = runFailure("retry-manual-after-exhaustion", raw.name, manual = true)
            assertEquals(3, failure.automaticRetryCount)
            assertNull(failure.automaticRetryAtMs)
            root.deleteRecursively()
        }
    }

    @Test fun stateReadCases() {
        val context = RuntimeEnvironment.getApplication()
        withArchivePrefs(context) { prefs ->
            val settings = CollectorSettings(context)
            val key = CollectorSettings.KEY_ARCHIVE_STORAGE_ITEM_STATES
            assertTrue(prefs.edit().remove(key).commit())
            assertIs<ArchiveStorageItemsRead.Missing>(settings.archiveStorageItemsRead())

            assertTrue(prefs.edit().putString(key, "[]").commit())
            assertEquals(emptyList(), assertIs<ArchiveStorageItemsRead.Valid>(settings.archiveStorageItemsRead()).items)

            val row = itemJson("bydcollector_telemetry_a")
            assertTrue(prefs.edit().putString(key, JSONArray().put(row).toString()).commit())
            assertEquals("bydcollector_telemetry_a", assertIs<ArchiveStorageItemsRead.Valid>(settings.archiveStorageItemsRead()).items.single().archiveId)
            val invalid = listOf(
                "not-json",
                "[",
                "[] suffix",
                JSONArray().put(row).put(JSONObject.NULL).toString(),
                JSONArray().put(row).put(
                    JSONObject(row.toString())
                        .put("archiveId", "bydcollector_telemetry_b")
                        .put("stepIndex", "1")
                ).toString(),
                JSONArray().put(row).put(row).toString()
            )
            invalid.forEach { raw ->
                assertTrue(prefs.edit().putString(key, raw).commit())
                val result = assertIs<ArchiveStorageItemsRead.Corrupt>(settings.archiveStorageItemsRead())
                assertEquals(raw, result.raw)
                assertNotNull(result.error)
                assertEquals(raw, prefs.all[key])
                assertFailsWith<ArchiveStorageItemsException> { settings.archiveStorageItems() }
                if (raw == "[") assertEquals("JSONException", result.error.javaClass.simpleName)
            }
        }
    }

    @Test fun stateWriterGuards() {
        val context = RuntimeEnvironment.getApplication()
        withArchivePrefs(context) { prefs ->
            val settings = CollectorSettings(context)
            val operation = "state-guard"
            val status = ArchiveStorageJobStatus(
                mode = ArchiveStorageJobMode.RETENTION,
                messageEn = "existing owner"
            )
            assertTrue(settings.beginArchiveStorageOperation(operation, status))
            val key = CollectorSettings.KEY_ARCHIVE_STORAGE_ITEM_STATES
            val raw = "[{bad archive json"
            assertTrue(prefs.edit().putString(key, raw).commit())
            val state = ArchiveStorageItemState(
                "bydcollector_telemetry_a", operation, ArchiveStorageItemPhase.QUEUED,
                startedAtMs = 1L, updatedAtMs = 1L
            )
            assertFailsWith<ArchiveStorageItemsException> {
                settings.publishArchiveStorageProgress(operation, status.copy(running = true), state)
            }
            assertEquals("existing owner", settings.archiveStorageJobStatus().messageEn)
            assertEquals(raw, prefs.all[key])

            assertFailsWith<ArchiveStorageItemsException> {
                settings.queueArchiveStorageItems(operation, listOf(state.archiveId))
            }
            assertEquals(raw, prefs.all[key])

            val root = File(context.cacheDir, "s").apply { deleteRecursively() }
            val manager = ArchiveStorageManager(
                root,
                context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME),
                File(context.filesDir, "secondary.db")
            )
            val scan = manager.reconcilePersistedItems(emptyList())
            assertTrue(scan.scanSucceeded)
            assertFailsWith<ArchiveStorageItemsException> {
                settings.reconcileArchiveStorageItems(operation, scan)
            }
            assertEquals(raw, prefs.all[key])

            assertFailsWith<ArchiveStorageItemsException> {
                settings.deferArchiveStorageAudit("deferred", listOf(state.archiveId))
            }
            assertNull(settings.archiveStorageAuditOperationId())
            assertEquals(raw, prefs.all[key])

            assertFailsWith<ArchiveStorageItemsException> {
                settings.recoverArchiveStorageAfterProcessRestart("recovery")
            }
            assertFalse(settings.archiveStorageJobStatus().running)
            assertEquals("archive_item_state_unavailable", settings.archiveStorageJobStatus().error)
            assertEquals(raw, prefs.all[key])
            root.deleteRecursively()
        }
    }

    @Test fun rawKeptOnBadState() {
        val context = RuntimeEnvironment.getApplication()
        withArchivePrefs(context) { prefs ->
            TelemetryDatabaseHelper(context).use { it.writableDatabase }
            val source = context.getDatabasePath(TelemetryDatabaseHelper.DATABASE_NAME)
            val root = File(context.cacheDir, "a").apply { deleteRecursively() }
            val moved = DatabaseArchiveManager.archive(source, root, "bad-state")
            assertTrue(moved.ok, moved.error)
            val rawDir = moved.archiveDirectory
            val evidence = File(rawDir, source.name)
            val bytes = evidence.readBytes()
            val settings = CollectorSettings(context)
            val operation = "raw-state"
            assertTrue(settings.beginArchiveStorageOperation(
                operation, ArchiveStorageJobStatus(mode = ArchiveStorageJobMode.RETENTION)
            ))
            val key = CollectorSettings.KEY_ARCHIVE_STORAGE_ITEM_STATES
            val raw = "{broken"
            assertTrue(prefs.edit().putString(key, raw).commit())
            val manager = ArchiveStorageManager(root, source, File(context.filesDir, "secondary.db"))

            assertFailsWith<ArchiveStorageItemsException> {
                manager.compressRawArchiveDirectory(rawDir) { progress ->
                    val itemState = progress.itemId?.let { id ->
                        progress.phase?.let { phase -> ArchiveStorageItemState(
                            id.removeSuffix(".zip"), operation, phase, progress.stepIndex, progress.stepCount,
                            1L, 1L, error = progress.error
                        ) }
                    }
                    settings.publishArchiveStorageProgress(operation, progress, itemState)
                }
            }
            assertTrue(rawDir.isDirectory)
            assertContentEquals(bytes, evidence.readBytes())
            assertFalse(File(root, "${rawDir.name}.zip").exists())
            assertEquals(raw, prefs.all[key])
            root.deleteRecursively()
        }
    }

    private fun itemJson(id: String): JSONObject = JSONObject()
        .put("archiveId", id)
        .put("operationId", "op")
        .put("phase", ArchiveStorageItemPhase.READY.name)
        .put("stepIndex", 1)
        .put("stepCount", 1)
        .put("startedAtMs", 1L)
        .put("updatedAtMs", 2L)
        .put("completedAtMs", 2L)
        .put("error", JSONObject.NULL)

    private fun withArchivePrefs(context: Application, block: (SharedPreferences) -> Unit) {
        val prefs = RuntimePreferences.get(context)
        val keys = listOf(
            CollectorSettings.KEY_USER_SHUTDOWN,
            CollectorSettings.KEY_DB_MAINTENANCE_OPERATION,
            CollectorSettings.KEY_DB_MAINTENANCE_RUNNING,
            CollectorSettings.KEY_STORAGE_CUTOVER_JOURNAL_FAMILY,
            CollectorSettings.KEY_STORAGE_CUTOVER_JOURNAL_ARCHIVE_PATH,
            CollectorSettings.KEY_STORAGE_CUTOVER_JOURNAL_PHASE,
            CollectorSettings.KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_FORMAT,
            CollectorSettings.KEY_STORAGE_CUTOVER_JOURNAL_MANUAL,
            CollectorSettings.KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_NAMES,
            CollectorSettings.KEY_STORAGE_CUTOVER_JOURNAL_SOURCE_DATABASE_NAME,
            CollectorSettings.KEY_STORAGE_CUTOVER_JOURNAL_TARGET_DATABASE_NAME,
            CollectorSettings.KEY_STORAGE_CUTOVER_JOURNAL_PENDING_ENERGY_COUNT,
            CollectorSettings.KEY_ARCHIVE_STORAGE_ITEM_STATES,
            CollectorSettings.KEY_ARCHIVE_STORAGE_AUDIT_PENDING_OPERATION_ID,
            CollectorSettings.KEY_ARCHIVE_STORAGE_JOB_MODE,
            CollectorSettings.KEY_ARCHIVE_STORAGE_JOB_RUNNING,
            CollectorSettings.KEY_ARCHIVE_STORAGE_JOB_STEP_INDEX,
            CollectorSettings.KEY_ARCHIVE_STORAGE_JOB_STEP_COUNT,
            CollectorSettings.KEY_ARCHIVE_STORAGE_JOB_MESSAGE_UK,
            CollectorSettings.KEY_ARCHIVE_STORAGE_JOB_MESSAGE_EN,
            CollectorSettings.KEY_ARCHIVE_STORAGE_JOB_ITEM_ID,
            CollectorSettings.KEY_ARCHIVE_STORAGE_JOB_OPERATION_ID,
            CollectorSettings.KEY_ARCHIVE_STORAGE_JOB_PHASE,
            CollectorSettings.KEY_ARCHIVE_STORAGE_JOB_ERROR,
            CollectorSettings.KEY_ARCHIVE_STORAGE_JOB_UPDATED_AT_MS
        )
        val original = keys.associateWith { prefs.all[it] }
        assertTrue(prefs.edit().apply { keys.forEach(::remove) }.commit())
        try {
            block(prefs)
        } finally {
            val editor = prefs.edit().apply { keys.forEach(::remove) }
            original.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
                }
            }
            assertTrue(editor.commit())
        }
    }
}
