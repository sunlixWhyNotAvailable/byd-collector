package com.bydcollector.collector.data.local

import android.app.Application
import android.os.Parcel
import com.bydcollector.collector.direct.HelperTelegramLane
import com.bydcollector.collector.telegram.TelegramHelperAttemptKey
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
