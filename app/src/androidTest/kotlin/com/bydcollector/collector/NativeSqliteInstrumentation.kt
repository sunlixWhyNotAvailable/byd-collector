package com.bydcollector.collector

import android.app.Activity
import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import com.bydcollector.collector.data.debug.DirectDebugDatabaseHelper
import com.bydcollector.collector.data.debug.DirectDebugParameterAsset
import com.bydcollector.collector.data.debug.DirectDebugStore
import com.bydcollector.collector.data.debug.SecondaryImportResult
import com.bydcollector.collector.direct.SecondaryTelemetrySpool
import com.bydcollector.collector.direct.TelemetryCallbackBatch
import com.bydcollector.collector.direct.CallbackParcelGate
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Destructive persistence/process scenarios are restricted to an emulator. */
class NativeSqliteInstrumentation : Instrumentation() {
    private val prefix = "callback_gate_${UUID.randomUUID()}"
    private val completed = mutableListOf<String>()
    private var hintFixture = false
    private var shutdownShellFixture = false
    private var runtimeBoundaryFixture = false
    private var listenerFixture: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        hintFixture = arguments?.getString("hintFixture") == "true"
        shutdownShellFixture = arguments?.getString("shutdownShellFixture") == "true"
        runtimeBoundaryFixture = arguments?.getString("runtimeBoundary") == "true"
        listenerFixture = arguments?.getString("listenerFixture")
        start()
    }

    override fun onStart() {
        if (!isEmulator()) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "Tests require an emulator") })
            return
        }
        if (runtimeBoundaryFixture || hintFixture || shutdownShellFixture) {
            val result = runCatching {
                when {
                    runtimeBoundaryFixture -> RuntimeBoundaryGate.run(this)
                    shutdownShellFixture -> ShutdownShellBehaviorGate.run(this)
                    else -> HintWindowBehaviorGate.run(this)
                }
            }
            finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", result.getOrElse { it.stackTraceToString() }) })
            return
        }
        listenerFixture?.let { action ->
            try {
                val component = android.content.ComponentName(targetContext,
                    com.bydcollector.collector.system.CollectorNotificationListenerService::class.java)
                val manager = targetContext.packageManager
                val prefs = targetContext.getSharedPreferences("shutdown_listener_fixture", 0)
                val desired = when (action) {
                    "disable" -> {
                        if (!prefs.contains("previous")) check(prefs.edit().putInt("previous",
                            manager.getComponentEnabledSetting(component)).commit())
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    }
                    "restore" -> {
                        check(prefs.contains("previous")) { "No saved listener state" }
                        prefs.getInt("previous", android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                    }
                    else -> error("Unknown listener fixture action")
                }
                manager.setComponentEnabledSetting(component, desired, android.content.pm.PackageManager.DONT_KILL_APP)
                check(manager.getComponentEnabledSetting(component) == desired)
                if (action == "restore") check(prefs.edit().remove("previous").commit())
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "LISTENER_FIXTURE $action state=$desired") })
            } catch (failure: Throwable) {
                finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", failure.stackTraceToString()) })
            }
            return
        }
        val tests = SettingsBehaviorGate.cases(targetContext, prefix, this) +
            StorageBehaviorGate.cases(targetContext, prefix) + listOf(
                "secondary_old_new_full_delta_replay" to ::secondaryCatalogReplay,
                "callback_android_parcel_paging_ack" to {
                    CallbackParcelGate.run(File(targetContext.cacheDir, "${prefix}_parcel"))
                }
            )
        var failure: Throwable? = null
        try {
            tests.forEachIndexed { index, (name, body) ->
                val status = Bundle().apply {
                    putString("id", "InstrumentationTestRunner")
                    putString("class", this@NativeSqliteInstrumentation.javaClass.name)
                    putString("test", name)
                    putInt("numtests", tests.size)
                    putInt("current", index + 1)
                }
                sendStatus(1, status)
                body()
                completed += name
                sendStatus(0, status)
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { cleanupTestFiles() }.onFailure { cleanupError ->
                if (failure == null) failure = cleanupError else failure!!.addSuppressed(cleanupError)
            }
        }
        val result = failure
        finish(if (result == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", if (result == null)
                "CALLBACK_ANDROID_GATE_PASS ${completed.size}/${tests.size}\n" + completed.joinToString("\n")
            else "CALLBACK_ANDROID_GATE_FAIL after ${completed.size}: ${result.stackTraceToString()}")
        })
    }

    private fun isEmulator(): Boolean = android.os.Build.MODEL.startsWith("sdk_gphone") ||
        android.os.Build.FINGERPRINT.contains("generic")

    private fun cleanupTestFiles() {
        val drained = java.util.concurrent.CountDownLatch(1)
        com.bydcollector.collector.util.sharedOperationalEventExecutor.execute { drained.countDown() }
        check(drained.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Event writer did not drain before cleanup" }
        targetContext.databaseList().filter { it.startsWith("${prefix}_") && it.endsWith(".db") }
            .forEach { check(targetContext.deleteDatabase(it)) { "Could not remove test database: $it" } }
        val root = targetContext.cacheDir.canonicalFile
        root.listFiles().orEmpty().filter { it.name.startsWith("${prefix}_") }.forEach { file ->
            check(file.canonicalFile.parentFile == root)
            check(file.deleteRecursively()) { "Could not remove test cache: $file" }
        }
    }

    private fun secondaryCatalogReplay() {
        val active = DirectDebugParameterAsset.load(targetContext)
        val definitions = DirectDebugParameterAsset.loadDefinitions(targetContext)
        val excludedOrdinal = definitions.indexOfFirst { !DirectDebugParameterAsset.isRuntimeSelected(it) }
        check(excludedOrdinal >= 0)
        val name = "${prefix}_secondary_catalogs.db"
        val old = records(DirectDebugParameterAsset.LEGACY_SOURCE_VERSION, definitions.size, excludedOrdinal)
            .let { legacyRecord(it.first) to legacyRecord(it.second) }
        val current = records(DirectDebugParameterAsset.SOURCE_VERSION, active.size, 0, cachedOrdinal = 1)
        DirectDebugDatabaseHelper(targetContext, name).let { helper ->
            DirectDebugStore(targetContext, helper).use { store ->
                val session = store.openSession(active, active.size)
                checkImport(store, session, old.first, duplicate = false)
                // Simulate a released pre-callback database with a retained v1 predecessor.
                helper.writableDatabase.execSQL("DROP TABLE debug_secondary_replay_cached_flags")
            }
        }
        // Persisted legacy predecessor must still be usable after reopening with active-v2 catalog.
        DirectDebugDatabaseHelper(targetContext, name).let { helper ->
            DirectDebugStore(targetContext, helper).use { store ->
                val session = store.openSession(active, active.size)
                checkImport(store, session, old.second, duplicate = false)
                checkImport(store, session, old.second, duplicate = true)
                checkImport(store, session, current.first, duplicate = false)
                checkCachedOrdinal(helper.readableDatabase, 1)
                checkImport(store, session, current.second, duplicate = false)
                checkCachedOrdinal(helper.readableDatabase, 1)
                check(count(helper.readableDatabase, "debug_secondary_receipts") == 4L)
                val excluded = definitions[excludedOrdinal]
                helper.readableDatabase.rawQuery("""
                    SELECT r.raw_int FROM debug_direct_readings r
                    JOIN debug_direct_candidates c ON c.id=r.candidate_id
                    WHERE c.dev=? AND c.fid=? AND c.tx=? ORDER BY r.id DESC LIMIT 1
                """.trimIndent(), arrayOf(excluded.dev.toString(), excluded.fid.toString(), excluded.tx.toString())).use { row ->
                    check(row.moveToFirst() && row.getInt(0) == 77)
                }
            }
        }
    }

    private fun checkImport(store: DirectDebugStore, session: Long, bytes: ByteArray, duplicate: Boolean) {
        val record = SecondaryTelemetrySpool.Codec.decode(bytes)
        val result = store.importSecondaryRecord(session, record, TelemetryCallbackBatch.digest(bytes).uppercase())
        check(result is SecondaryImportResult.Committed && result.duplicate == duplicate) { "Secondary import failed: $result" }
    }

    private fun checkCachedOrdinal(db: SQLiteDatabase, ordinal: Int) {
        db.rawQuery("""
            SELECT f.cached_bits FROM debug_secondary_replay_cached_flags f
            JOIN debug_direct_catalog_versions c ON c.id=f.catalog_version_id
            WHERE c.source_version=?
        """.trimIndent(), arrayOf(DirectDebugParameterAsset.SOURCE_VERSION)).use {
            check(it.moveToFirst())
            check(it.getBlob(0)[ordinal / 8].toInt() and (1 shl (ordinal % 8)) != 0)
        }
    }

    private fun legacyRecord(bytes: ByteArray): ByteArray = org.json.JSONObject(String(bytes, Charsets.UTF_8))
        .put("record_version", 1).toString().toByteArray(Charsets.UTF_8)

    private fun records(catalog: String, fields: Int, changedOrdinal: Int, cachedOrdinal: Int = -1): Pair<ByteArray, ByteArray> {
        val root = File(targetContext.cacheDir, "${prefix}_${fields}_spool")
        check(root.mkdirs())
        val factory = SecondaryTelemetrySpool::class.java.getDeclaredMethod("openForTest",
            File::class.java, java.lang.Long.TYPE, Integer.TYPE).apply { isAccessible = true }
        return (factory.invoke(null, root, 32L * 1024 * 1024, fields) as SecondaryTelemetrySpool).use { spool ->
            fun cycle(sequence: Long, changed: Boolean) = SecondaryTelemetrySpool.Cycle(
                SecondaryTelemetrySpool.CycleIdentity("boot", "helper", "gap_$fields", sequence), catalog,
                1_790_000_000_000L + sequence, 1000 + sequence, 10, 0, 1, true, 1, 0, 0, 0, null,
                List(fields) { ordinal -> SecondaryTelemetrySpool.Value(ordinal, 0, true,
                    if (changed && ordinal == changedOrdinal) 77 else 2, null, ordinal == cachedOrdinal) })
            spool.append(cycle(0, false))
            spool.append(cycle(1, true))
            readAndAck(spool) to readAndAck(spool)
        }
    }

    private fun readAndAck(spool: SecondaryTelemetrySpool): ByteArray {
        val descriptor = checkNotNull(spool.oldest())
        val output = ByteArrayOutputStream()
        var offset = 0L
        while (offset < descriptor.length) {
            val bytes = spool.readSlice(descriptor, offset, SecondaryTelemetrySpool.MAX_SLICE_BYTES)
            check(bytes.isNotEmpty())
            output.write(bytes)
            offset += bytes.size
        }
        check(spool.acknowledge(descriptor) == SecondaryTelemetrySpool.AckResult.RELEASED)
        return output.toByteArray()
    }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
}
