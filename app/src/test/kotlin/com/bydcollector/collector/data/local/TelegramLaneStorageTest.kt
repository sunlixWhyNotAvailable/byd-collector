package com.bydcollector.collector.data.local

import android.app.Application
import android.os.Parcel
import com.bydcollector.collector.direct.HelperTelegramLane
import com.bydcollector.collector.telegram.TelegramHelperAttemptKey
import com.bydcollector.collector.telegram.TelegramHelperAmbiguity
import com.bydcollector.collector.telegram.TelegramHelperProtocol
import com.bydcollector.collector.telegram.TelegramHelperRemoteFailure
import com.bydcollector.collector.telegram.TelegramHelperState
import com.bydcollector.collector.telegram.TelegramSendFailureKind
import com.bydcollector.collector.telegram.TelegramSendMessage
import com.bydcollector.collector.telegram.TelegramSendResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class TelegramLaneStorageTest {
    @Test fun damagedTimingRetainsIndependentStateOutboxAndOriginalEvidenceAcrossReopen() {
        val context = RuntimeEnvironment.getApplication()
        val name = "tg_state_recovery_${System.nanoTime()}.db"
        fun openStore() = TelegramStore(context, TelegramDatabaseHelper(context, name))
        val raw = """{"initialized":true,"tripId":"trip-kept","chargeGunConnected":true,
            "fullSent":true,"detectorTiming":{"version":999,"reported_power":"power-kept"}}"""
        try {
            openStore().use { store ->
                store.commitTelegramEvents(listOf(TelegramOutboxMessage("kept", "test", "payload")), raw)
                var failure: Exception? = null
                var evidenceName: String? = null
                val recovered = store.loadTelegramRuntimeState { error, evidence ->
                    failure = error
                    evidenceName = evidence
                }
                assertIs<IllegalArgumentException>(failure)
                assertEquals("trip-kept", recovered.tripId)
                assertEquals(true, recovered.chargeGunConnected)
                assertTrue(recovered.fullSent)
                assertEquals("power-kept", recovered.detectorTiming.reportedPowerSessionId)
                val evidence = File(File(store.databaseFile().parentFile, "telegram-state-recovery"), assertNotNull(evidenceName))
                assertEquals(raw, org.json.JSONObject(evidence.readText()).getString("state"))
            }
            openStore().use { store ->
                assertEquals("trip-kept", store.loadTelegramRuntimeState { _, _ -> error("Already recovered") }.tripId)
                assertEquals(1L, store.diagnosticSnapshot().outboxTotal)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun missingAndCorruptLedgerRecoverWithoutSendingAndAllowTheNextAttempt() {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "tg_recovery_${System.nanoTime()}")
        val request = TelegramSendMessage("invalid!token", "chat", "body")
        var key = TelegramHelperAttemptKey(UUID.randomUUID(), UUID.randomUUID(), 2L,
            TelegramHelperProtocol.requestDigest(request), TelegramHelperProtocol.destinationDigest(request), sha("event"))
        var lane = HelperTelegramLane(directory)
        try {
            assertEquals(TelegramHelperAmbiguity.LEDGER_MISSING, lane.telegramQuery(key).ambiguity)
            assertEquals(TelegramHelperAmbiguity.LEDGER_RECOVERED, lane.telegramRecover(key).ambiguity)
            assertTrue(lane.closeAfterDrain())
            lane = HelperTelegramLane(directory)
            assertEquals(TelegramHelperAmbiguity.LEDGER_RECOVERED, lane.telegramQuery(key).ambiguity)
            assertNull(lane.telegramQuery(key).result)
            assertEquals(TelegramHelperState.ACKED, lane.telegramAck(key).state)

            key = key.copy(attemptId = UUID.randomUUID(), sequence = 3L)
            lane.telegramSubmit(key, request)
            assertTrue(lane.awaitHttpIdle(5_000L))
            assertEquals(TelegramSendFailureKind.CONFIGURATION,
                assertIs<TelegramSendResult.Failure>(lane.telegramQuery(key).result).kind)
            // Real corrupt bytes on disk, not an injected Binder/HTTP outcome.
            File(directory, "${key.ownerId}.json").writeText("{broken-ledger")
            assertEquals(TelegramHelperAmbiguity.LEDGER_CORRUPT, lane.telegramQuery(key).ambiguity)
            val recovered = lane.telegramRecover(key)
            assertEquals(TelegramHelperAmbiguity.LEDGER_RECOVERED, recovered.ambiguity)
            assertTrue(assertNotNull(recovered.diagnostic).contains("JSONException"))
            assertTrue(File(directory, "recovery-${key.attemptId}").listFiles()!!.any { it.readText() == "{broken-ledger" })
            assertEquals(TelegramHelperState.ACKED, lane.telegramAck(key).state)
            assertTrue(lane.closeAfterDrain())
            lane = HelperTelegramLane(directory)
            key = key.copy(attemptId = UUID.randomUUID(), sequence = 4L)
            lane.telegramSubmit(key, request)
            assertTrue(lane.awaitHttpIdle(5_000L))
            assertEquals(TelegramSendFailureKind.CONFIGURATION,
                assertIs<TelegramSendResult.Failure>(lane.telegramQuery(key).result).kind)
        } finally {
            lane.awaitHttpIdle(5_000L)
            lane.closeAfterDrain()
            directory.deleteRecursively()
        }
    }

    @Test fun overflowEvictsWholeDependencyGroupAndRepairsOldOrphans() {
        val context = RuntimeEnvironment.getApplication()
        val name = "tg_groups_${System.nanoTime()}.db"
        val helper = TelegramDatabaseHelper(context, name)
        val store = TelegramStore(context, helper)
        try {
            store.commitTelegramEvents(listOf(
                TelegramOutboxMessage("leg:summary", "trip_summary", "summary"),
                TelegramOutboxMessage("leg:location", "trip_summary", "location", "leg:summary")), null, 1000L)
            val result = store.commitTelegramEvents((1..999).map {
                TelegramOutboxMessage("extra:$it", "trip_summary", "message $it")
            }, null, 2000L)
            assertEquals(2, result.sumOf { it.overflowCount })
            assertNull(store.telegramMessageByDedupeKey("leg:summary"))
            assertNull(store.telegramMessageByDedupeKey("leg:location"))
            // A database created by the previous eviction algorithm may contain this orphan.
            helper.writableDatabase.execSQL("INSERT INTO telegram_outbox " +
                "(dedupe_key,event_type,payload,created_at_ms,next_attempt_at_ms,waits_for_summary_key) " +
                "VALUES ('old:location','trip_summary','location',0,0,'old:summary')")
            assertEquals(0 to 1, store.repairTelegramDependencies())
            assertNull(store.telegramMessageByDedupeKey("old:location"))
            val protected = assertNotNull(store.telegramMessageByDedupeKey("extra:1"))
            store.commitTelegramEvents(listOf(
                TelegramOutboxMessage("extra:1:location", "trip_summary", "location", "extra:1"),
                TelegramOutboxMessage("tail", "trip_summary", "tail")), null, 3000L,
                protectedTelegramIds = setOf(protected.id))
            assertNotNull(store.telegramMessageByDedupeKey("extra:1"))
            assertNotNull(store.telegramMessageByDedupeKey("extra:1:location"))
            assertNull(store.telegramMessageByDedupeKey("extra:2"))
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun apiRejectionKeepsItsDescriptionAndRedactsSecretsAcrossIpc() {
        val request = TelegramSendMessage("123:private_token", "private-chat", "private payload")
        val parsed = com.bydcollector.collector.telegram.TelegramHttpClient().classify(400,
            org.json.JSONObject().put("ok", false).put("error_code", 400)
                .put("description", "Bad Request: chat not found ${request.chatId} ${request.botToken} ${request.text}").toString(), request)
        val failure = assertIs<TelegramSendResult.Failure>(parsed)
        assertEquals(400, failure.telegramErrorCode)
        val diagnostic = assertNotNull(failure.diagnostic)
        assertTrue(diagnostic.contains("Bad Request: chat not found"))
        listOf(request.botToken, request.chatId, request.text).forEach { assertFalse(diagnostic.contains(it)) }
        val parcel = Parcel.obtain()
        try {
            TelegramHelperProtocol.writeSuccessReply(parcel, com.bydcollector.collector.telegram.TelegramHelperOutcome(
                TelegramHelperState.RESULT, failure, 123L))
            parcel.setDataPosition(0)
            assertEquals(failure, TelegramHelperProtocol.readReply(parcel).result)
        } finally { parcel.recycle() }
    }

    @Test
    fun reopen() {
        val context = RuntimeEnvironment.getApplication()
        val name = "tg_${System.nanoTime()}.db"
        val directory = File(context.cacheDir, "tg_${System.nanoTime()}")
        fun openStore() = TelegramStore(context, TelegramDatabaseHelper(context, name))
        var store = openStore()
        var lane = HelperTelegramLane(ledgerDirectory = directory)
        try {
            // The real client rejects this token before opening a connection. No fabricated HTTP result.
            val request = TelegramSendMessage("invalid!token-storage-check", "private-chat-check", "private-text-check")
            val message = TelegramOutboxMessage("trip:location", "trip_location", request.text)
            store.commitTelegramEvents(listOf(message), null, nowMs = 1_000L)
            val entry = assertNotNull(store.oldestDueTelegramMessage(1_000L))
            fun prepare() = store.prepareTelegramDeliveryAttempt(
                assertNotNull(store.telegramMessageByDedupeKey(message.dedupeKey)),
                TelegramHelperProtocol.requestDigest(request),
                TelegramHelperProtocol.destinationDigest(request),
                sha(message.dedupeKey), sha(request.botToken), 1_001L
            )
            val attempt = prepare()
            val key = TelegramHelperAttemptKey(
                UUID.fromString(attempt.ownerId), UUID.fromString(attempt.attemptId), attempt.sequence,
                attempt.requestDigest, attempt.destinationDigest, attempt.dedupeRef
            )
            assertEquals(attempt, prepare())
            store.close()
            store = openStore()
            assertEquals(attempt, store.activeTelegramDeliveryAttempt())
            assertEquals(setOf(entry.id), store.protectedTelegramOutboxIds())

            // Retention must protect a durable attempt without an APP-local inFlight flag.
            store.commitTelegramEvents((1..TelegramDatabaseHelper.MAX_PENDING.toInt()).map {
                TelegramOutboxMessage("extra:$it", "trip_location", "extra message $it")
            }, null, nowMs = 2_000L)
            assertNotNull(store.telegramMessageByDedupeKey(message.dedupeKey))

            assertEquals(TelegramHelperState.RUNNING, lane.telegramSubmit(key, request).state)
            assertTrue(lane.awaitHttpIdle(5_000L))
            val outcome = lane.telegramQuery(key)
            val failure = assertIs<TelegramSendResult.Failure>(outcome.result)
            assertEquals(TelegramSendFailureKind.CONFIGURATION, failure.kind)
            val parcel = Parcel.obtain()
            try {
                TelegramHelperProtocol.writeSuccessReply(parcel, outcome)
                parcel.setDataPosition(0)
                assertEquals(outcome, TelegramHelperProtocol.readReply(parcel))
            } finally { parcel.recycle() }
            assertTrue(lane.closeAfterDrain())
            lane = HelperTelegramLane(ledgerDirectory = directory)
            assertEquals(outcome, lane.telegramQuery(key))
            assertEquals(outcome, lane.telegramSubmit(key, request))
            val ledger = directory.listFiles()!!.single { it.extension == "json" }.readText()
            listOf(request.botToken, request.chatId, request.text).forEach { assertFalse(ledger.contains(it)) }

            store.settleTelegramDeliveryFailure(attempt.attemptId, failure.kind.name,
                assertNotNull(outcome.confirmedAtMs), nextAttemptAtMs = null)
            store.close()
            store = openStore()
            assertTrue(assertNotNull(store.activeTelegramDeliveryAttempt()).localSettled)
            val blocked = assertNotNull(store.telegramMessageByDedupeKey(message.dedupeKey))
            assertTrue(blocked.blocked)
            assertEquals(1, blocked.attemptCount)
            assertEquals(1, blocked.failureCount)
            assertEquals(TelegramHelperState.ACKED, lane.telegramAck(key).state)
            assertTrue(lane.closeAfterDrain())
            lane = HelperTelegramLane(ledgerDirectory = directory)
            assertEquals(TelegramHelperState.ACKED, lane.telegramQuery(key).state)
            assertEquals(TelegramHelperState.ACKED, lane.telegramSubmit(key, request).state)
            store.acknowledgeTelegramDeliveryAttempt(attempt.attemptId)
            assertNull(store.activeTelegramDeliveryAttempt())

            store.unblockTelegramMessages(3_000L)
            val next = prepare()
            assertEquals(attempt.ownerId, next.ownerId)
            assertEquals(attempt.sequence + 1, next.sequence)
            assertTrue(store.discardPreparedTelegramDeliveryAttempt(next.attemptId))
            val nextPrepared = prepare()
            assertEquals(next.sequence, nextPrepared.sequence)
            val nextKey = TelegramHelperAttemptKey(
                UUID.fromString(nextPrepared.ownerId), UUID.fromString(nextPrepared.attemptId), nextPrepared.sequence,
                nextPrepared.requestDigest, nextPrepared.destinationDigest, nextPrepared.dedupeRef
            )
            assertEquals(TelegramHelperState.ABSENT, lane.telegramQuery(nextKey).state)
            assertEquals(TelegramHelperState.RUNNING, lane.telegramSubmit(nextKey, request).state)
            assertTrue(lane.awaitHttpIdle(5_000L))
            assertEquals(TelegramSendFailureKind.CONFIGURATION,
                assertIs<TelegramSendResult.Failure>(lane.telegramQuery(nextKey).result).kind)
            assertEquals(TelegramHelperState.ACKED, lane.telegramAck(nextKey).state)
            assertEquals(TelegramHelperState.ACKED, lane.telegramSubmit(key, request).state)

            val blockedDirectory = File(directory, "not-a-directory").apply { writeText("file") }
            val brokenLane = HelperTelegramLane(ledgerDirectory = blockedDirectory)
            try {
                val original = assertFails { brokenLane.telegramQuery(key) }
                val reply = Parcel.obtain()
                try {
                    TelegramHelperProtocol.writeFailureReply(reply, "query", original)
                    reply.setDataPosition(0)
                    val remote = assertFailsWith<TelegramHelperRemoteFailure> {
                        TelegramHelperProtocol.readReply(reply)
                    }
                    assertEquals(original.javaClass.name, remote.exceptionType)
                    assertTrue(remote.diagnostic.contains("not-a-directory"))
                    assertTrue(remote.diagnostic.contains(original.javaClass.name))
                } finally { reply.recycle() }
            } finally { brokenLane.closeAfterDrain() }
        } finally {
            lane.awaitHttpIdle(5_000L)
            lane.closeAfterDrain()
            store.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }

    private fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
