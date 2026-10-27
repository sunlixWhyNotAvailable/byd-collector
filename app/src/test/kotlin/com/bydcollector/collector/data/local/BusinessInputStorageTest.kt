package com.bydcollector.collector.data.local

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import com.bydcollector.collector.data.callback.CallbackDelivery
import com.bydcollector.collector.data.callback.CallbackImportResult
import com.bydcollector.collector.data.direct.DirectFidRegistry
import com.bydcollector.collector.data.input.BusinessInputCheckpointKeys
import com.bydcollector.collector.data.input.BusinessInputKind
import com.bydcollector.collector.data.input.BusinessInputOrigin
import com.bydcollector.collector.data.input.BusinessInputProjection
import com.bydcollector.collector.data.input.BusinessInputReducer
import com.bydcollector.collector.data.input.BusinessInputRef
import com.bydcollector.collector.data.input.BusinessInputTimerSource
import com.bydcollector.collector.data.normalized.VehicleStateNormalizer
import com.bydcollector.collector.data.polling.PollOrigin
import com.bydcollector.collector.data.polling.PollSampleSource
import com.bydcollector.collector.diagnostics.OperationalEventJournal
import com.bydcollector.collector.direct.TelemetryCallbackBatch
import com.bydcollector.collector.service.TelegramOrderedInput
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class BusinessInputStorageTest {
    @Test
    fun capturedAuxVoltagePassesSharedJournalWithoutEnteringEnergyCheckpoint() {
        // Real 27Oct vehicle GET payload, encoded as both GET and callback inputs; no Binder outcome is faked.
        val sample = JSONObject(checkNotNull(javaClass.classLoader?.getResourceAsStream(
            "com/bydcollector/collector/direct/worker-v3-20261027-24.json"
        )).bufferedReader(Charsets.UTF_8).use { it.readText() })
        val values = sample.getJSONArray("values")
        val aux = (0 until values.length()).map(values::getJSONObject).single {
            it.getInt("dev") == 1032 && it.getInt("fid") == 1150287896
        }
        val identity = sample.getJSONObject("identity")
        val boot = identity.getString("boot_id")
        val generation = identity.getString("helper_generation")
        val sequence = identity.getLong("poll_sequence")
        val wall = sample.getLong("captured_wall_ms")
        val elapsed = sample.getLong("captured_elapsed_ms")
        val raw = aux.getInt("raw")
        val key = "ota_battery_voltage"
        val context = RuntimeEnvironment.getApplication()
        val name = "shared_aux_${System.nanoTime()}.db"
        var store = newStore(context, name)
        try {
            val session = store.openSession()
            val batch = TelemetryCallbackBatch(boot, generation, 1, 0L, sequence, listOf(
                TelemetryCallbackBatch.Event(sequence, aux.getInt("dev"), aux.getInt("fid"),
                    TelemetryCallbackBatch.TYPE_FLOAT, raw, null, wall, elapsed, null, "callback")
            ))
            assertIs<CallbackImportResult.Committed>(store.importCallbackBatch(
                batch, TelemetryCallbackBatch.digest(batch.encode()), CallbackDelivery.REPLAY))
            val entry = DirectFidRegistry.entries.single { it.key == key }
            store.insertPoll(session, PersistedPollInput(
                timestamp = java.time.Instant.ofEpochMilli(wall).toString(), ok = true,
                elapsedMs = sample.getLong("poll_elapsed_ms"), requestCount = 1, errors = null,
                rawResponseBody = null,
                readings = listOf(PollReading(key, raw.toString(),
                    com.bydcollector.collector.data.direct.DirectValueDecoders.decode(entry, raw))),
                source = PollSampleSource("get:$boot:$generation:$sequence", boot, elapsed, generation, sequence),
                origin = PollOrigin.REPLAY
            ), store.getActiveCatalogParameters())

            val kinds = mutableSetOf<BusinessInputKind>()
            while (true) {
                val ref = store.nextPendingBusinessInput() ?: break
                kinds += ref.kind
                val delta = store.loadBusinessInputDelta(ref)
                val projected = BusinessInputProjection.incoming(delta)
                assertFalse(key in projected)
                val merged = BusinessInputReducer.merge(store.loadBusinessInputCheckpoint(), projected, boot)
                val reading = delta.readings.single { it.rawKey == key }
                assertEquals(raw.toString(), reading.rawValue)
                val telegram = TelegramOrderedInput.merge(emptyMap(), delta.readings,
                    com.bydcollector.collector.data.normalized.NormalizedSourceStamp(
                        com.bydcollector.collector.data.normalized.NormalizedSourceKind.POLL,
                        ref.sourceIdentity, boot, generation, sequence, wall, elapsed),
                    ref.pollId ?: 0L, boot, delta.continuityUpdates)
                assertEquals(raw.toString(), telegram.inputs.getValue(key).reading.rawValue)
                assertTrue(store.completeBusinessInputs(listOf(ref), merged.updates, delta.continuityUpdates))
            }
            assertEquals(setOf(BusinessInputKind.CALLBACK, BusinessInputKind.GET), kinds)
            store.close()
            store = newStore(context, name)
            assertNull(store.nextPendingBusinessInput())
            assertFalse(key in store.loadBusinessInputCheckpoint())
            assertEquals(1L, count(context, name, "raw_callback_events"))
            assertEquals(2L, count(context, name, "business_input_receipts"))
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun mainCallbackImportQueuesOnceAndRemainsSeparateFromNormalizationReceiptsAfterReopen() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "business_callback_${System.nanoTime()}.db"
        var store = newStore(context, databaseName)
        try {
            store.openSession()
            val batch = loadCallbackFixture()
            val digest = TelemetryCallbackBatch.digest(batch.encode())

            val firstImport = assertIs<CallbackImportResult.Committed>(
                store.importCallbackBatch(batch, digest, CallbackDelivery.REPLAY)
            )
            assertFalse(firstImport.duplicate)
            assertEquals(batch.events.size, firstImport.eventCount)
            val businessEventCount = relevantCallbackCount(batch)
            assertEquals(batch.events.size.toLong(), count(context, databaseName, "raw_callback_events"))
            assertEquals(businessEventCount, store.businessInputEventHistory(limit = 32).size)

            val duplicate = assertIs<CallbackImportResult.Committed>(
                store.importCallbackBatch(batch, digest, CallbackDelivery.REPLAY)
            )
            assertTrue(duplicate.duplicate)
            assertEquals(businessEventCount, store.businessInputEventHistory(limit = 32).size)
            assertEquals(0L, count(context, databaseName, "raw_callback_normalization_receipts"))
            assertEquals(0L, count(context, databaseName, "business_input_receipts"))

            val pending = assertNotNull(store.nextPendingBusinessInput())
            assertEquals(BusinessInputKind.CALLBACK, pending.kind)
            assertEquals(BusinessInputOrigin.REPLAY, pending.origin)
            val delta = store.loadBusinessInputDelta(pending)
            val expectedEvent = relevantCallbackEvents(batch).first()
            val storedEvent = assertNotNull(delta.callbackEvent)
            assertEquals(expectedEvent.sequence, storedEvent.eventSequence)
            assertEquals(expectedEvent.nativeType, storedEvent.nativeType)
            assertEquals(expectedEvent.rawBits, storedEvent.rawBits)
            assertEquals(expectedEvent.receivedWallMs, storedEvent.receivedWallMs)
            assertEquals(expectedEvent.receivedElapsedMs, storedEvent.receivedElapsedMs)
            assertEquals(expectedEvent.quality, storedEvent.quality)

            val normalized = store.normalizePendingCallbackPage(VehicleStateNormalizer(), limit = 32)
            assertEquals(batch.events.size, normalized.processedCount)
            assertEquals(batch.events.size.toLong(), count(context, databaseName, "raw_callback_normalization_receipts"))
            assertEquals(businessEventCount, store.businessInputEventHistory(limit = 32).size)
            assertEquals(0L, count(context, databaseName, "business_input_receipts"))

            store.close()
            store = newStore(context, databaseName)
            val reopened = assertNotNull(store.nextPendingBusinessInput())
            assertEquals(pending, reopened)
            assertEquals(storedEvent.rawBits, store.loadBusinessInputDelta(reopened).callbackEvent?.rawBits)
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun getPollAndJournalCommitTogetherAndRetainRawFailureDetailsAndCheckpointAcrossReopen() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "business_get_${System.nanoTime()}.db"
        var store = newStore(context, databaseName)
        try {
            val sessionId = store.openSession()
            val parameters = store.getActiveCatalogParameters()
            val unrelatedKey = DirectFidRegistry.entries.first {
                it.key !in BusinessInputCheckpointKeys.ALL && it.key !in TelegramOrderedInput.rawSourceKeys
            }.key
            val generation = store.businessInputGeneration()
            val source = PollSampleSource("live:boot-a:generation-a:1", "boot-a", 1_000L, "generation-a", 1L)
            val pollId = store.insertPoll(
                sessionId,
                PersistedPollInput(
                    timestamp = "2026-10-26T12:00:00Z",
                    ok = true,
                    elapsedMs = 17L,
                    requestCount = 1,
                    errors = null,
                    rawResponseBody = null,
                    readings = listOf(
                        PollReading("bodywork_power_level", "1", "ON"),
                        PollReading("charging_charge_battery_volt", "4000", "400.0"),
                        PollReading("charging_charge_current", null, null),
                        PollReading(unrelatedKey, "123", null)
                    ),
                    source = source,
                    origin = PollOrigin.LIVE
                ),
                parameters
            )

            val ref = assertNotNull(store.nextPendingBusinessInput())
            assertEquals(BusinessInputKind.GET, ref.kind)
            assertEquals(pollId, ref.pollId)
            assertEquals(pollId, ref.rawRefId)
            assertEquals(source.identity, ref.sourceIdentity)
            assertEquals(source.bootId, ref.bootId)
            assertEquals(source.generatorId, ref.generation)
            assertEquals(source.sequence, ref.sequence)
            assertEquals(source.capturedElapsedMs, ref.acquiredElapsedMs)
            assertEquals(BusinessInputOrigin.LIVE, ref.origin)
            val delta = store.loadBusinessInputDelta(ref)
            assertEquals(
                listOf("bodywork_power_level", "charging_charge_battery_volt", "charging_charge_current"),
                ref.readingKeys
            )
            assertEquals(ref.readingKeys, delta.readings.map { it.rawKey })
            assertEquals("ON", delta.readings.single { it.rawKey == "bodywork_power_level" }.descValue)
            assertEquals("4000", delta.sourceInputs.getValue("charging_charge_battery_volt").reading.rawValue)
            assertNull(delta.readings.single { it.rawKey == "charging_charge_current" }.rawValue)
            assertEquals(3, delta.sourceInputs.size)
            val persistedUnrelatedValue = context.openOrCreateDatabase(databaseName, 0, null).use { db ->
                db.rawQuery(
                    "SELECT \"${PollValueColumns.raw(unrelatedKey)}\" FROM poll_values WHERE poll_id = ?",
                    arrayOf(pollId.toString())
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(0)
                }
            }
            assertEquals("123", persistedUnrelatedValue)

            val pollCountBeforeDuplicateKey = count(context, databaseName, "polls")
            assertFailsWith<IllegalArgumentException> {
                store.insertPoll(
                    sessionId,
                    PersistedPollInput(
                        timestamp = "2026-10-26T12:00:00.500Z",
                        ok = true,
                        elapsedMs = 9L,
                        requestCount = 1,
                        errors = null,
                        rawResponseBody = null,
                        readings = listOf(
                            PollReading("bodywork_power_level", "1", "ON"),
                            PollReading("bodywork_power_level", "0", "OFF")
                        ),
                        source = PollSampleSource(
                            "live:boot-a:generation-a:duplicate-key", "boot-a", 1_500L, "generation-a", 2L
                        ),
                        origin = PollOrigin.LIVE
                    ),
                    parameters
                )
            }
            assertEquals(pollCountBeforeDuplicateKey, count(context, databaseName, "polls"))
            assertEquals(1L, count(context, databaseName, "business_input_journal"))

            val failedSource = PollSampleSource("live:boot-a:generation-a:2", "boot-a", 2_000L, "generation-a", 2L)
            val failedPollId = store.insertPoll(
                sessionId,
                PersistedPollInput(
                    timestamp = "2026-10-26T12:00:01Z",
                    ok = false,
                    elapsedMs = 12L,
                    requestCount = 1,
                    errors = "http: service returned 503",
                    errorCategory = "http",
                    errorMessage = "service returned 503",
                    rawResponseBody = "{\"bodywork_power_level\":\"raw\"}",
                    readings = emptyList(),
                    source = failedSource,
                    origin = PollOrigin.LIVE
                ),
                parameters
            )
            val failedRef = store.businessInputEventHistory(afterId = ref.id, limit = 1).single()
            assertEquals(failedPollId, failedRef.pollId)
            assertFalse(failedRef.ok)
            assertTrue(failedRef.readingKeys.isEmpty())
            assertEquals("http", failedRef.errorCategory)
            assertEquals("service returned 503", failedRef.errorMessage)
            assertEquals(
                "{\"bodywork_power_level\":\"raw\"}",
                store.loadBusinessInputDelta(failedRef).rawResponseBody
            )
            assertFailsWith<IllegalStateException> {
                store.completeBusinessInput(failedRef, emptyMap())
            }

            val pollCountBeforeDuplicate = count(context, databaseName, "polls")
            assertFailsWith<SQLiteConstraintException> {
                store.insertPoll(
                    sessionId,
                    PersistedPollInput(
                        timestamp = "2026-10-26T12:00:02Z",
                        ok = true,
                        elapsedMs = 8L,
                        requestCount = 1,
                        errors = null,
                        rawResponseBody = null,
                        readings = listOf(PollReading("bodywork_power_level", "0", "OFF")),
                        source = source,
                        origin = PollOrigin.LIVE
                    ),
                    parameters
                )
            }
            assertEquals(pollCountBeforeDuplicate, count(context, databaseName, "polls"))
            assertEquals(2L, count(context, databaseName, "business_input_journal"))

            val sparseSource = PollSampleSource("live:boot-a:generation-a:3", "boot-a", 3_000L, "generation-a", 3L)
            store.insertPoll(
                sessionId,
                PersistedPollInput(
                    timestamp = "2026-10-26T12:00:03Z",
                    ok = true,
                    elapsedMs = 10L,
                    requestCount = 1,
                    errors = null,
                    rawResponseBody = null,
                    readings = listOf(PollReading("charging_charge_current", "-200", "-20.0")),
                    source = sparseSource,
                    origin = PollOrigin.LIVE
                ),
                parameters
            )
            assertEquals(3L, count(context, databaseName, "business_input_journal"))

            store.close()
            store = newStore(context, databaseName)
            assertEquals(generation, store.businessInputGeneration())
            val reopened = assertNotNull(store.nextPendingBusinessInput())
            assertEquals(ref, reopened)
            val reopenedDelta = store.loadBusinessInputDelta(reopened)
            assertTrue(store.completeBusinessInput(reopened, reopenedDelta.sourceInputs))
            val checkpoint = store.loadBusinessInputCheckpoint()
            assertEquals("1", checkpoint.getValue("bodywork_power_level").reading.rawValue)
            assertEquals("4000", checkpoint.getValue("charging_charge_battery_volt").reading.rawValue)
            assertFalse(store.completeBusinessInput(reopened, reopenedDelta.sourceInputs))

            val next = assertNotNull(store.nextPendingBusinessInput())
            assertEquals(failedRef, next)
            assertTrue(store.completeBusinessInput(next, emptyMap()))
            val sparseRef = assertNotNull(store.nextPendingBusinessInput())
            val sparseDelta = store.loadBusinessInputDelta(sparseRef)
            assertEquals(listOf("charging_charge_current"), sparseRef.readingKeys)
            assertEquals(sparseRef.readingKeys, sparseDelta.readings.map { it.rawKey })
            assertEquals(setOf("charging_charge_current"), sparseDelta.sourceInputs.keys)
            assertNull(sparseDelta.readings.firstOrNull { it.rawKey == "bodywork_power_level" }?.rawValue)
            assertTrue(store.completeBusinessInput(sparseRef, sparseDelta.sourceInputs))
            assertNull(store.nextPendingBusinessInput())
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun sparseGetAttemptsInvalidateOnlyFailedKeysAndReceivedCountsIgnoreNullPlaceholders() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "business_sparse_failure_${System.nanoTime()}.db"
        val store = newStore(context, databaseName)
        try {
            val sessionId = store.openSession()
            val catalog = store.getActiveCatalogParameters()
            val bootId = "boot-sparse-failure"
            val generation = "generation-sparse-failure"

            fun persist(
                sequence: Long,
                readings: List<PollReading>,
                ok: Boolean = true,
                errorCategory: String? = null,
                errorMessage: String? = null,
                rawBody: String? = null
            ): Pair<Long, PollSampleSource> {
                val source = PollSampleSource(
                    "live:$bootId:$generation:$sequence", bootId, sequence * 1_000L, generation, sequence
                )
                val timestamp = "2026-10-26T12:00:${sequence.toString().padStart(2, '0')}Z"
                val pollId = store.insertPoll(
                    sessionId,
                    PersistedPollInput(
                        timestamp = timestamp,
                        ok = ok,
                        elapsedMs = 10L,
                        requestCount = 1,
                        errors = errorMessage?.let { "${errorCategory ?: "poll_error"}: $it" },
                        errorCategory = errorCategory,
                        errorMessage = errorMessage,
                        rawResponseBody = rawBody,
                        readings = readings,
                        source = source,
                        origin = PollOrigin.LIVE
                    ),
                    catalog.filter { parameter -> readings.any { it.rawKey == parameter.key } }
                )
                return pollId to source
            }

            val batteryKey = "charging_charge_battery_volt"
            val currentKey = "charging_charge_current"
            val powerKey = "bodywork_power_level"
            val (baselinePoll, _) = persist(
                1L,
                listOf(
                    PollReading(batteryKey, "4000", "400.0"),
                    PollReading(currentKey, "150", "15.0"),
                    PollReading(powerKey, "1", "ON")
                )
            )
            val baselineRef = assertNotNull(store.nextPendingBusinessInput())
            assertEquals(baselinePoll, baselineRef.pollId)
            val baselineDelta = store.loadBusinessInputDelta(baselineRef)
            var checkpoint = BusinessInputReducer.merge(
                emptyMap(), BusinessInputProjection.incoming(baselineDelta), bootId
            ).inputs
            assertTrue(store.completeBusinessInput(baselineRef, checkpoint))

            val (partialPoll, partialSource) = persist(
                2L,
                listOf(PollReading(batteryKey, null, null), PollReading(powerKey, "1", "ON")),
                errorCategory = "autoservice_partial_failure",
                errorMessage = "$batteryKey:status=7 error=controlled error payload"
            )
            val partialRef = assertNotNull(store.nextPendingBusinessInput())
            assertEquals(listOf(batteryKey, powerKey), partialRef.readingKeys)
            val partialDelta = store.loadBusinessInputDelta(partialRef)
            assertNull(partialDelta.readings.single { it.rawKey == batteryKey }.rawValue)
            val partialMerge = BusinessInputReducer.merge(
                checkpoint, BusinessInputProjection.incoming(partialDelta), bootId
            )
            checkpoint = partialMerge.inputs
            assertNull(checkpoint.getValue(batteryKey).reading.rawValue)
            assertEquals("150", checkpoint.getValue(currentKey).reading.rawValue)
            assertTrue(store.completeBusinessInput(partialRef, partialMerge.updates))

            val persistedCounts = context.openOrCreateDatabase(databaseName, 0, null).use { db ->
                db.rawQuery(
                    "SELECT requested_parameter_count, received_parameter_count, missing_parameter_count " +
                        "FROM polls WHERE id = ?",
                    arrayOf(partialPoll.toString())
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    Triple(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2))
                }
            }
            assertEquals(Triple(2, 1, 1), persistedCounts)

            store.applySourcePollNormalization(
                partialPoll,
                "2026-10-26T12:00:02Z",
                partialSource,
                partialDelta.readings,
                VehicleStateNormalizer()
            )
            val normalizedBattery = context.openOrCreateDatabase(databaseName, 0, null).use { db ->
                db.rawQuery(
                    "SELECT raw_value FROM normalized_source_inputs WHERE source_key = ?",
                    arrayOf(batteryKey)
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    if (cursor.isNull(0)) null else cursor.getString(0)
                }
            }
            assertNull(normalizedBattery)

            val (restorePoll, _) = persist(3L, listOf(PollReading(batteryKey, "4100", "410.0")))
            val restoreRef = assertNotNull(store.nextPendingBusinessInput())
            assertEquals(restorePoll, restoreRef.pollId)
            val restoreDelta = store.loadBusinessInputDelta(restoreRef)
            val restoreMerge = BusinessInputReducer.merge(
                checkpoint, BusinessInputProjection.incoming(restoreDelta), bootId
            )
            checkpoint = restoreMerge.inputs
            assertTrue(store.completeBusinessInput(restoreRef, restoreMerge.updates))
            assertEquals("4100", checkpoint.getValue(batteryKey).reading.rawValue)

            val failedBody = "{\"field\":\"$batteryKey\",\"status\":7,\"error\":\"controlled error payload\"}"
            val (failedBatteryPoll, _) = persist(
                4L,
                listOf(PollReading(batteryKey, null, null)),
                ok = false,
                errorCategory = "autoservice_snapshot_empty",
                errorMessage = "$batteryKey:status=7 error=controlled error payload",
                rawBody = failedBody
            )
            val failedBatteryRef = assertNotNull(store.nextPendingBusinessInput())
            assertFalse(failedBatteryRef.ok)
            assertEquals(listOf(batteryKey), failedBatteryRef.readingKeys)
            val failedBatteryDelta = store.loadBusinessInputDelta(failedBatteryRef)
            assertEquals(failedBody, failedBatteryDelta.rawResponseBody)
            assertEquals(listOf(batteryKey), failedBatteryDelta.readings.map { it.rawKey })
            assertNull(failedBatteryDelta.readings.single().rawValue)
            assertEquals(setOf(batteryKey), BusinessInputProjection.incoming(failedBatteryDelta).keys)
            val failedValuesRows = context.openOrCreateDatabase(databaseName, 0, null).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM poll_values WHERE poll_id = ?", arrayOf(failedBatteryPoll.toString()))
                    .use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getLong(0) }
            }
            assertEquals(0L, failedValuesRows)
            val failedMerge = BusinessInputReducer.merge(
                checkpoint, BusinessInputProjection.incoming(failedBatteryDelta), bootId
            )
            checkpoint = failedMerge.inputs
            assertNull(checkpoint.getValue(batteryKey).reading.rawValue)
            assertEquals("150", checkpoint.getValue(currentKey).reading.rawValue)
            assertEquals("1", checkpoint.getValue(powerKey).reading.rawValue)
            assertTrue(store.completeBusinessInput(failedBatteryRef, failedMerge.updates))

            val unrelatedKey = DirectFidRegistry.entries.first {
                it.key !in BusinessInputCheckpointKeys.ALL && it.key !in TelegramOrderedInput.rawSourceKeys
            }.key
            val journalCount = count(context, databaseName, "business_input_journal")
            val (unrelatedPoll, _) = persist(
                5L,
                listOf(PollReading(unrelatedKey, null, null)),
                ok = false,
                errorCategory = "autoservice_snapshot_empty",
                errorMessage = "$unrelatedKey:status=7 error=controlled error payload",
                rawBody = "{\"field\":\"$unrelatedKey\",\"status\":7}"
            )
            assertEquals(journalCount, count(context, databaseName, "business_input_journal"))
            assertNull(store.nextPendingBusinessInput())
            assertEquals(checkpoint, store.loadBusinessInputCheckpoint())
            assertEquals(
                Triple(1, 0, 1),
                context.openOrCreateDatabase(databaseName, 0, null).use { db ->
                    db.rawQuery(
                        "SELECT requested_parameter_count, received_parameter_count, missing_parameter_count " +
                            "FROM polls WHERE id = ?",
                        arrayOf(unrelatedPoll.toString())
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        Triple(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2))
                    }
                }
            )
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun pendingPagesCompleteOnlyTheOrderedPrefixAndCommitOneFinalCheckpoint() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "business_page_${System.nanoTime()}.db"
        val store = newStore(context, databaseName)
        try {
            val sessionId = store.openSession()
            val parameters = store.getActiveCatalogParameters()
            val firstPollId = store.insertPoll(
                sessionId,
                PersistedPollInput(
                    timestamp = "2026-10-26T12:00:00Z",
                    ok = true,
                    elapsedMs = 10L,
                    requestCount = 1,
                    errors = null,
                    rawResponseBody = null,
                    readings = listOf(PollReading("bodywork_power_level", "1", "ON")),
                    source = PollSampleSource("live:boot-page:generation-page:1", "boot-page", 1_000L, "generation-page", 1L),
                    origin = PollOrigin.LIVE
                ),
                parameters
            )
            val secondPollId = store.insertPoll(
                sessionId,
                PersistedPollInput(
                    timestamp = "2026-10-26T12:00:01Z",
                    ok = true,
                    elapsedMs = 10L,
                    requestCount = 1,
                    errors = null,
                    rawResponseBody = null,
                    readings = listOf(PollReading("bodywork_power_level", "0", "OFF")),
                    source = PollSampleSource("live:boot-page:generation-page:2", "boot-page", 2_000L, "generation-page", 2L),
                    origin = PollOrigin.LIVE
                ),
                parameters
            )
            val timerId = store.enqueueEvaluation(
                BusinessInputTimerSource(
                    identity = "timer:boot-page:generation-page:3",
                    bootId = "boot-page",
                    generation = "generation-page",
                    sequence = 3L,
                    wallMs = 3_000L,
                    elapsedMs = 3_000L
                ),
                sessionId
            )

            val page = store.pendingBusinessInputs(limit = 2)
            assertEquals(listOf(firstPollId, secondPollId), page.map { it.pollId })
            assertEquals(page.first(), store.nextPendingBusinessInput())
            assertFailsWith<IllegalStateException> { store.completeBusinessInputs(listOf(page.last()), emptyMap()) }
            assertEquals(0L, count(context, databaseName, "business_input_receipts"))
            assertTrue(store.loadBusinessInputCheckpoint().isEmpty())

            val finalDelta = store.loadBusinessInputDelta(page.last())
            assertTrue(store.completeBusinessInputs(page, finalDelta.sourceInputs))
            assertFalse(store.completeBusinessInputs(page, finalDelta.sourceInputs))
            assertEquals(2L, count(context, databaseName, "business_input_receipts"))
            assertEquals("0", store.loadBusinessInputCheckpoint().getValue("bodywork_power_level").reading.rawValue)

            val timer = assertNotNull(store.nextPendingBusinessInput())
            assertEquals(timerId, timer.id)
            assertEquals(BusinessInputKind.TIMER, timer.kind)
            assertTrue(store.completeBusinessInput(timer, emptyMap()))
            assertNull(store.nextPendingBusinessInput())
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun upgradingLegacyVersion15SchemaCreatesAnEmptyJournalAndSeedsCurrentSources() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "business_legacy_upgrade_${System.nanoTime()}.db"
        createLegacyVersion15Database(context, databaseName)
        val helper = TelemetryDatabaseHelper(context, databaseName)
        val store = newStore(context, helper)
        try {
            assertNull(store.nextPendingBusinessInput())
            assertEquals(0L, count(context, databaseName, "business_input_journal"))
            assertEquals(1L, count(context, databaseName, "raw_callback_events"))
            val checkpoint = store.loadBusinessInputCheckpoint()
            assertEquals(setOf("bodywork_power_level"), checkpoint.keys)
            assertEquals("legacy-source", checkpoint.getValue("bodywork_power_level").stamp.identity)
            assertEquals(checkpoint, store.loadBusinessInputInitialCheckpoint())
            assertTrue(store.businessInputGeneration().isNotBlank())
        } finally {
            store.close()
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun upgradingVersion16RetainsJournalIdsReceiptsAndPendingWorkThenAcceptsContinuity() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "business_v16_upgrade_${System.nanoTime()}.db"
        createLegacyVersion15Database(context, databaseName)
        // Controlled old schema/data exercise SQLiteOpenHelper's real upgrade, not a mocked result.
        context.openOrCreateDatabase(databaseName, 0, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("""
                CREATE TABLE business_input_journal (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    input_kind TEXT NOT NULL CHECK(input_kind IN ('callback', 'get', 'timer')),
                    raw_ref_id INTEGER,
                    reading_keys_json TEXT,
                    source_identity TEXT NOT NULL UNIQUE,
                    source_boot_id TEXT,
                    source_generation TEXT,
                    source_sequence INTEGER,
                    acquired_wall_ms INTEGER,
                    acquired_elapsed_ms INTEGER,
                    session_id INTEGER,
                    poll_id INTEGER,
                    origin TEXT NOT NULL CHECK(origin IN ('live', 'replay', 'timer')),
                    ok INTEGER NOT NULL CHECK(ok IN (0, 1)),
                    quality TEXT NOT NULL,
                    error_category TEXT,
                    error_message TEXT,
                    created_at_ms INTEGER NOT NULL,
                    CHECK((input_kind = 'callback' AND raw_ref_id IS NOT NULL AND poll_id IS NULL) OR
                          (input_kind = 'get' AND raw_ref_id IS NOT NULL AND poll_id = raw_ref_id) OR
                          (input_kind = 'timer' AND raw_ref_id IS NULL AND poll_id IS NULL))
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE business_input_receipts (
                    input_id INTEGER PRIMARY KEY,
                    completed_at_ms INTEGER NOT NULL,
                    FOREIGN KEY(input_id) REFERENCES business_input_journal(id) ON DELETE CASCADE
                ) WITHOUT ROWID
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE business_input_meta (
                    singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                    generation TEXT NOT NULL,
                    completed_input_id INTEGER NOT NULL DEFAULT 0 CHECK(completed_input_id >= 0)
                )
            """.trimIndent())
            db.execSQL("INSERT INTO business_input_meta VALUES(1, 'v16-journal', 11)")
            db.execSQL("""
                INSERT INTO business_input_journal(
                    id, input_kind, raw_ref_id, source_identity, source_boot_id, source_generation,
                    source_sequence, acquired_wall_ms, acquired_elapsed_ms, origin, ok, quality, created_at_ms
                ) VALUES(11, 'callback', 1, 'v16-callback', 'legacy-callback-boot',
                         'legacy-callback-generation', 1, 1700000000000, 1000, 'live', 1, 'callback', 1700000000100)
            """.trimIndent())
            db.execSQL("""
                INSERT INTO business_input_journal(
                    id, input_kind, source_identity, source_boot_id, source_generation,
                    source_sequence, acquired_wall_ms, acquired_elapsed_ms, origin, ok, quality, created_at_ms
                ) VALUES(19, 'timer', 'v16-timer', 'legacy-callback-boot',
                         'v16-timer-generation', 1, 1700000001000, 2000, 'timer', 1, 'timer', 1700000001100)
            """.trimIndent())
            db.execSQL("INSERT INTO business_input_receipts VALUES(11, 1700000000200)")
            db.execSQL("CREATE UNIQUE INDEX idx_business_input_raw_ref ON business_input_journal(input_kind, raw_ref_id) WHERE raw_ref_id IS NOT NULL")
            db.execSQL("CREATE INDEX idx_business_input_boot_elapsed_id ON business_input_journal(source_boot_id, acquired_elapsed_ms, id)")
            db.version = 16
        }
        val helper = TelemetryDatabaseHelper(context, databaseName)
        val store = newStore(context, helper)
        try {
            val pending = assertNotNull(store.nextPendingBusinessInput())
            assertEquals(19L, pending.id)
            assertEquals("v16-timer", pending.sourceIdentity)
            assertEquals(2_000L, pending.acquiredElapsedMs)
            assertEquals("v16-journal", store.businessInputGeneration())
            assertEquals(listOf(11L, 19L), store.businessInputEventHistory(limit = 10).map { it.id })
            val db = helper.readableDatabase
            assertEquals(TelemetryDatabaseHelper.DATABASE_VERSION, db.version)
            db.rawQuery("SELECT input_id, completed_at_ms FROM business_input_receipts", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(11L, it.getLong(0))
                assertEquals(1_700_000_000_200L, it.getLong(1))
                assertFalse(it.moveToNext())
            }
            assertEquals(1L, count(context, databaseName, "raw_callback_events"))
            assertTrue(store.completeBusinessInput(pending, emptyMap()))
            assertNull(store.nextPendingBusinessInput())

            val boundary = TelemetryCallbackBatch.SourceBoundary(
                2L, TelemetryCallbackBatch.SourceBoundary.SCOPE_STREAM,
                TelemetryCallbackBatch.SourceBoundary.STATE_LOST, 0, 0, 0,
                1_700_000_002_000L, 3_000L, "source_disconnected", "controlled boundary input",
                null, null, null
            )
            val batch = TelemetryCallbackBatch(
                "legacy-callback-boot", "legacy-callback-generation", 1, 1L, 2L, 1L,
                emptyList(), listOf(boundary)
            )
            val encoded = batch.encode()
            assertIs<CallbackImportResult.Committed>(store.importCallbackBatch(
                TelemetryCallbackBatch.decode(encoded), TelemetryCallbackBatch.digest(encoded), CallbackDelivery.REPLAY
            ))
            val continuity = assertNotNull(store.nextPendingBusinessInput())
            assertTrue(continuity.id > pending.id)
            assertEquals(BusinessInputKind.CONTINUITY, continuity.kind)
            assertNotNull(store.loadBusinessInputDelta(continuity).continuityBoundary)
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        } finally {
            store.close()
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun capturedCurrentSurvivesQuietTimeAndReopenButKnownLossRequiresANewerObservation() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "business_continuity_${System.nanoTime()}.db"
        val fixture = loadCallbackFixture()
        val key = "charging_charge_current"
        val entry = DirectFidRegistry.entries.single { it.key == key }
        val captured = fixture.events.filter { it.device == entry.dev && it.fid == entry.fid }
        val first = captured.first()
        val recovery = captured.last()
        var store = newStore(context, databaseName)

        fun import(batch: TelemetryCallbackBatch): CallbackImportResult.Committed {
            val encoded = batch.encode()
            return assertIs(store.importCallbackBatch(
                TelemetryCallbackBatch.decode(encoded), TelemetryCallbackBatch.digest(encoded), CallbackDelivery.REPLAY
            ))
        }

        fun drain() {
            var inputs = store.loadBusinessInputCheckpoint()
            var continuity = store.loadBusinessInputContinuityCheckpoint()
            while (true) {
                val ref = store.nextPendingBusinessInput() ?: break
                val delta = store.loadBusinessInputDelta(ref)
                val values = BusinessInputReducer.merge(inputs,
                    BusinessInputProjection.incoming(delta).filterKeys { it in BusinessInputCheckpointKeys.ALL }, fixture.bootId)
                val sources = BusinessInputReducer.mergeContinuity(continuity, delta.continuityUpdates, fixture.bootId, values.inputs)
                assertTrue(store.completeBusinessInputs(listOf(ref), values.updates, sources.updates))
                inputs = values.inputs
                continuity = sources.continuity
            }
        }

        fun heldRaw(): String? = BusinessInputProjection.freshInputs(
            store.loadBusinessInputCheckpoint(), fixture.bootId, recovery.receivedElapsedMs + 3_600_000L,
            store.loadBusinessInputContinuityCheckpoint()
        ).getValue(key).reading.rawValue

        try {
            store.openSession()
            // Original captured raw events; the loss boundary is a controlled lifecycle input.
            import(TelemetryCallbackBatch(fixture.bootId, fixture.helperGeneration, fixture.stream,
                fixture.epoch, fixture.batchSequence, fixture.sourceEpoch, listOf(first), emptyList()))
            drain()
            val original = store.loadBusinessInputCheckpoint().getValue(key)
            assertEquals(first.rawBits.toString(), heldRaw())
            store.close()
            store = newStore(context, databaseName)
            assertEquals(original, store.loadBusinessInputCheckpoint().getValue(key))
            assertEquals(first.rawBits.toString(), heldRaw())

            val lost = TelemetryCallbackBatch(fixture.bootId, fixture.helperGeneration, fixture.stream,
                fixture.epoch, fixture.batchSequence + 1L, fixture.sourceEpoch, emptyList(), listOf(
                    TelemetryCallbackBatch.SourceBoundary(
                        first.sequence + 1L, TelemetryCallbackBatch.SourceBoundary.SCOPE_ADDRESS,
                        TelemetryCallbackBatch.SourceBoundary.STATE_LOST, entry.dev, entry.fid, entry.tx,
                        first.receivedWallMs + 1L, first.receivedElapsedMs + 1L,
                        "source_disconnected", "controlled boundary input", null, null, null
                    )
                ))
            assertFalse(import(lost).duplicate)
            drain()
            assertTrue(import(lost).duplicate)
            assertNull(store.nextPendingBusinessInput())
            assertNull(heldRaw())
            assertEquals(original, store.loadBusinessInputCheckpoint().getValue(key))
            assertEquals(1L, count(context, databaseName, "raw_callback_events"))

            store.close()
            store = newStore(context, databaseName)
            assertNull(heldRaw())
            import(TelemetryCallbackBatch(fixture.bootId, fixture.helperGeneration, fixture.stream,
                fixture.epoch, fixture.batchSequence + 2L, fixture.sourceEpoch, listOf(recovery), emptyList()))
            drain()
            assertEquals(recovery.rawBits.toString(), heldRaw())
            assertEquals(recovery.receivedElapsedMs, store.loadBusinessInputCheckpoint().getValue(key).stamp.elapsedMs)
            assertEquals(2L, count(context, databaseName, "raw_callback_events"))

            // Import the availability boundary before its GET, as independent spool drains can do.
            val getSequence = recovery.sequence + 10L
            val getIdentity = "captured-current-get"
            val getWall = recovery.receivedWallMs + 1_000L
            val getElapsed = recovery.receivedElapsedMs + 1_000L
            val available = TelemetryCallbackBatch(
                fixture.bootId, fixture.helperGeneration, fixture.stream, fixture.epoch,
                fixture.batchSequence + 3L, fixture.sourceEpoch, emptyList(), listOf(
                    TelemetryCallbackBatch.SourceBoundary(
                        getSequence + 1L, TelemetryCallbackBatch.SourceBoundary.SCOPE_ADDRESS,
                        TelemetryCallbackBatch.SourceBoundary.STATE_AVAILABLE, entry.dev, entry.fid, entry.tx,
                        getWall + 1L, getElapsed + 1L, "get_seed", null, "get", getIdentity, getSequence
                    )
                )
            )
            import(available)
            drain()
            assertNull(heldRaw())
            store.close()
            store = newStore(context, databaseName)
            assertNull(heldRaw())
            val session = store.openSession()
            val lineage = com.bydcollector.collector.data.direct.DirectGetLineage(
                fixture.bootId, fixture.helperGeneration, fixture.stream, fixture.sourceEpoch,
                getIdentity, getSequence, getWall - 1L, getElapsed - 1L, getWall, getElapsed,
                recovery.sequence, recovery.sequence, false
            )
            store.insertPoll(
                session,
                PersistedPollInput(
                    timestamp = java.time.Instant.ofEpochMilli(getWall).toString(), ok = true,
                    elapsedMs = 1L, requestCount = 1, errors = null, rawResponseBody = null,
                    readings = listOf(PollReading(key, recovery.rawBits.toString(),
                        com.bydcollector.collector.data.direct.DirectValueDecoders.decode(entry, recovery.rawBits),
                        getLineage = lineage)),
                    source = PollSampleSource("app-get-capture", fixture.bootId, getElapsed, "app-get", 1L),
                    origin = PollOrigin.REPLAY
                ),
                store.getActiveCatalogParameters().filter { it.key == key }
            )
            drain()
            assertEquals(recovery.rawBits.toString(), heldRaw())
            assertEquals(getElapsed, store.loadBusinessInputCheckpoint().getValue(key).stamp.elapsedMs)
            store.close()
            store = newStore(context, databaseName)
            assertEquals(recovery.rawBits.toString(), heldRaw())

            // A late APP death notification has APP clocks, not the dead helper's acquisition clocks.
            store.recordAppObservedSourceLoss(fixture.helperGeneration, fixture.stream, listOf(key),
                "helper_binder_died", "controlled lifecycle input", fixture.bootId,
                getWall + 3_000L, getElapsed + 3_000L)
            drain()
            assertNull(heldRaw())
            val replacement = lineage.copy(helperGeneration = "replacement-helper",
                readIdentity = "replacement-get", recordSequence = 1L,
                startedWallMs = getWall + 1_999L, startedElapsedMs = getElapsed + 1_999L,
                completedWallMs = getWall + 2_000L, completedElapsedMs = getElapsed + 2_000L,
                callbackWatermarkStart = -1L, callbackWatermarkEnd = -1L)
            store.insertPoll(store.openSession(), PersistedPollInput(
                timestamp = java.time.Instant.ofEpochMilli(replacement.completedWallMs).toString(),
                ok = true, elapsedMs = 1L, requestCount = 1, errors = null, rawResponseBody = null,
                readings = listOf(PollReading(key, recovery.rawBits.toString(),
                    com.bydcollector.collector.data.direct.DirectValueDecoders.decode(entry, recovery.rawBits),
                    getLineage = replacement)),
                source = PollSampleSource("replacement-app-capture", fixture.bootId,
                    replacement.completedElapsedMs, "replacement-app", 1L), origin = PollOrigin.REPLAY
            ), store.getActiveCatalogParameters().filter { it.key == key })
            import(TelemetryCallbackBatch(fixture.bootId, replacement.helperGeneration, fixture.stream,
                0L, 1L, replacement.sourceEpoch, emptyList(), listOf(
                    TelemetryCallbackBatch.SourceBoundary(2L, TelemetryCallbackBatch.SourceBoundary.SCOPE_ADDRESS,
                        TelemetryCallbackBatch.SourceBoundary.STATE_AVAILABLE, entry.dev, entry.fid, entry.tx,
                        replacement.completedWallMs + 1L, replacement.completedElapsedMs + 1L,
                        "get_seed", null, "get", replacement.readIdentity, replacement.recordSequence)
                )))
            drain()
            assertEquals(recovery.rawBits.toString(), heldRaw())
            store.close()
            store = newStore(context, databaseName)
            assertEquals(recovery.rawBits.toString(), heldRaw())
            store.recordAppObservedSourceLoss(fixture.helperGeneration, fixture.stream, listOf(key),
                "helper_binder_died", "late repeated old-owner observation", fixture.bootId,
                getWall + 4_000L, getElapsed + 4_000L)
            drain()
            assertEquals(replacement.helperGeneration,
                store.loadBusinessInputContinuityCheckpoint().getValue(key).helperGeneration)
            assertEquals(recovery.rawBits.toString(), heldRaw())
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun newStore(context: android.content.Context, databaseName: String) = TelemetryStore(
        context = context,
        helper = TelemetryDatabaseHelper(context, databaseName),
        operationalEventJournal = OperationalEventJournal(context)
    )

    private fun newStore(context: android.content.Context, helper: TelemetryDatabaseHelper) = TelemetryStore(
        context = context,
        helper = helper,
        operationalEventJournal = OperationalEventJournal(context)
    )

    private fun createLegacyVersion15Database(context: android.content.Context, databaseName: String) {
        val db = context.openOrCreateDatabase(databaseName, 0, null)
        try {
            val legacySchema = context.assets.open(TelemetryDatabaseHelper.LEGACY_SCHEMA_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            legacySchema.split(';').forEach { statement ->
                val sql = statement.trim()
                if (sql.isNotEmpty()) db.execSQL(sql)
            }
            assertFalse(TelemetryDatabaseHelper.isCompactV2(db))
            db.insertOrThrow(
                "normalized_source_inputs",
                null,
                ContentValues().apply {
                    put("source_key", "bodywork_power_level")
                    put("raw_value", "1")
                    put("desc_value", "ON")
                    put("source_kind", "poll")
                    put("source_identity", "legacy-source")
                    put("source_boot_id", "legacy-boot")
                    put("source_generator_id", "legacy-generation")
                    put("source_sequence", 7L)
                    put("source_wall_ms", 1_700_000_000_000L)
                    put("source_elapsed_ms", 7_000L)
                    putNull("source_poll_id")
                    put("updated_at_ms", 1_700_000_000_000L)
                }
            )
            val entry = DirectFidRegistry.entries.single { it.key == "charging_charge_battery_volt" }
            val batchId = db.insertOrThrow(
                "raw_callback_batches",
                null,
                ContentValues().apply {
                    put("boot_id", "legacy-callback-boot")
                    put("helper_generation", "legacy-callback-generation")
                    put("stream", 1)
                    put("epoch", 1L)
                    put("batch_sequence", 1L)
                    put("digest", "b".repeat(64))
                    put("acquisition", "callback")
                    put("delivery", "live")
                    put("event_count", 1)
                    put("first_event_sequence", 1L)
                    put("last_event_sequence", 1L)
                    put("imported_at_ms", 1_700_000_000_000L)
                }
            )
            db.insertOrThrow(
                "raw_callback_events",
                null,
                ContentValues().apply {
                    put("batch_id", batchId)
                    put("boot_id", "legacy-callback-boot")
                    put("helper_generation", "legacy-callback-generation")
                    put("stream", 1)
                    put("epoch", 1L)
                    put("event_sequence", 1L)
                    put("device", entry.dev)
                    put("fid", entry.fid)
                    put("native_type", TelemetryCallbackBatch.TYPE_INT)
                    put("raw_bits", 1)
                    putNull("raw_bytes")
                    put("received_wall_ms", 1_700_000_000_000L)
                    put("received_elapsed_ms", 1_000L)
                    putNull("source_wall_ms")
                    put("quality", "callback")
                }
            )
            db.execSQL("PRAGMA user_version = 15")
        } finally {
            db.close()
        }
    }

    private fun count(context: android.content.Context, databaseName: String, table: String): Long =
        context.openOrCreateDatabase(databaseName, 0, null).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
            }
        }

    private fun loadCallbackFixture(): TelemetryCallbackBatch {
        val resource = checkNotNull(javaClass.classLoader?.getResourceAsStream(
            "com/bydcollector/collector/data/input/callback-batch-20260922.json"
        ))
        val root = JSONObject(resource.bufferedReader(Charsets.UTF_8).use { it.readText() })
        val batchJson = root.getJSONObject("batch")
        val eventRows = root.getJSONArray("events")
        val events = buildList {
            for (index in 0 until eventRows.length()) {
                val row = eventRows.getJSONObject(index)
                val rawBytes = if (row.isNull("raw_bytes")) null else android.util.Base64.decode(
                    row.getString("raw_bytes"), android.util.Base64.DEFAULT
                )
                add(
                    TelemetryCallbackBatch.Event(
                        row.getLong("event_sequence"), row.getInt("device"), row.getInt("fid"),
                        row.getInt("native_type"), row.getInt("raw_bits"), rawBytes,
                        row.getLong("received_wall_ms"), row.getLong("received_elapsed_ms"),
                        if (row.isNull("source_wall_ms")) null else row.getLong("source_wall_ms"),
                        row.getString("quality")
                    )
                )
            }
        }
        return TelemetryCallbackBatch(
            batchJson.getString("boot_id"), batchJson.getString("helper_generation"),
            batchJson.getInt("stream"), batchJson.getLong("epoch"), batchJson.getLong("batch_sequence"), events
        )
    }

    private fun relevantCallbackCount(batch: TelemetryCallbackBatch): Int = relevantCallbackEvents(batch).size

    private fun relevantCallbackEvents(batch: TelemetryCallbackBatch): List<TelemetryCallbackBatch.Event> {
        val neededKeys = BusinessInputCheckpointKeys.ALL + TelegramOrderedInput.rawSourceKeys
        val businessDeviceFids = DirectFidRegistry.entries
            .filter { it.key in neededKeys }
            .mapTo(mutableSetOf()) { it.dev to it.fid }
        val businessTypedAddresses = DirectFidRegistry.entries
            .filter { it.key in neededKeys }
            .mapTo(mutableSetOf()) { Triple(it.dev, it.fid, it.tx) }
        return batch.events.filter { event ->
            val typedTransaction = when (event.nativeType) {
                TelemetryCallbackBatch.TYPE_INT -> DirectFidRegistry.TX_GET_INT
                TelemetryCallbackBatch.TYPE_FLOAT -> DirectFidRegistry.TX_GET_FLOAT
                else -> null
            }
            (typedTransaction != null && Triple(event.device, event.fid, typedTransaction) in businessTypedAddresses) ||
                (event.device to event.fid) in businessDeviceFids
        }
    }
}
