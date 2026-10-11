package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.local.TelegramDatabaseHelper
import com.bydcollector.collector.data.local.TelegramOutboxMessage
import com.bydcollector.collector.data.local.TelegramStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramDeliveryQueueTest {
    @Test
    fun retryAndRetainedSuccessSurviveOwnerReplacement() {
        val context = RuntimeEnvironment.getApplication()
        // Keep Robolectric's method-scoped SQLite/WAL paths below Windows MAX_PATH.
        val databaseName = "t23.db"
        val nowMs = AtomicLong(10_000L)
        var store = TelegramStore(
            context,
            TelegramDatabaseHelper(context, databaseName),
            clockMs = nowMs::get
        )
        store.commitTelegramEvents(
            listOf(TelegramOutboxMessage("trip:summary", "trip_summary", "Trip summary body")),
            stateJson = null,
            nowMs = nowMs.get()
        )
        val requestCount = AtomicInteger()
        val successHttpEntered = CountDownLatch(1)
        val successHttpRelease = CountDownLatch(1)
        val firstOwnerFailed = CountDownLatch(1)
        val replacementOwnerReady = CountDownLatch(1)
        val retryDeadlines = LinkedBlockingQueue<Long>()
        val runtime = TelegramDeliveryRuntime(send = {
            when (requestCount.incrementAndGet()) {
                1 -> TelegramSendResult.Failure(
                    TelegramSendFailureKind.NETWORK_ERROR,
                    exceptionClass = "SocketTimeoutException"
                )
                2 -> TelegramSendResult.Failure(
                    TelegramSendFailureKind.RATE_LIMITED,
                    httpStatus = 429,
                    telegramErrorCode = 429,
                    retryAfterSeconds = 90L
                )
                3 -> {
                    successHttpEntered.countDown()
                    check(successHttpRelease.await(3, TimeUnit.SECONDS))
                    TelegramSendResult.Success(123L)
                }
                else -> error("unexpected Telegram HTTP send")
            }
        }, nowMs = nowMs::get)
        val firstQueue = queue(store, enabled = true, nowMs = nowMs::get) { _, _, _ ->
            error("controlled local receipt failure after successful HTTP response")
        }
        val firstAttempt = assertNotNull(firstQueue.beginAttempt("automatic_retry"))
        val firstOwner = Any()
        val replacementOwner = Any()

        try {
            runtime.attach(
                token = firstOwner,
                onReady = { deadline -> deadline?.let(retryDeadlines::offer) },
                onFailure = { firstOwnerFailed.countDown() },
                onPendingResult = { outcome, acknowledge ->
                    firstQueue.settleReceivedAttempt(
                        outcome.attempt,
                        outcome.result,
                        outcome.confirmedAtMs,
                        acknowledge
                    )
                }
            )
            runtime.executor.submit { runtime.dispatchSend(firstAttempt) }.get(1, TimeUnit.SECONDS)

            val firstRetryAt = assertNotNull(retryDeadlines.poll(2, TimeUnit.SECONDS))
            assertEquals(40_000L, firstRetryAt, "temporary failure uses the first local retry delay")
            val afterTemporaryFailure = assertNotNull(store.telegramMessageByDedupeKey("trip:summary"))
            assertEquals(1, afterTemporaryFailure.failureCount)
            assertEquals("network_error:SocketTimeoutException", afterTemporaryFailure.lastError)
            assertNull(store.telegramDeliveryReceipt("trip:summary"))
            assertNull(firstQueue.beginAttempt("scheduled_before_local_deadline"))
            assertEquals(1, requestCount.get(), "retry is not sent before its due time")

            nowMs.set(firstRetryAt)
            val secondAttempt = assertNotNull(firstQueue.beginAttempt("scheduled_retry"))
            runtime.executor.submit { runtime.dispatchSend(secondAttempt) }.get(1, TimeUnit.SECONDS)
            val serverRetryAt = assertNotNull(retryDeadlines.poll(2, TimeUnit.SECONDS))
            assertEquals(130_000L, serverRetryAt, "the 90-second server wait exceeds the local exponential backoff")
            val botScope = TelegramDeliveryQueue.botScope("123:token")
            assertEquals(serverRetryAt, store.telegramServerNotBefore(botScope))
            assertEquals(serverRetryAt, firstQueue.pendingDeadline())

            nowMs.set(serverRetryAt - 1L)
            assertNull(
                firstQueue.beginAttempt(
                    trigger = "priority_retry_before_server_deadline",
                    priorityKey = "trip:summary",
                    expediteLocal = true
                ),
                "priority and local-network expedite must not bypass Telegram's server retry_after"
            )
            assertEquals(2, requestCount.get(), "server cooldown must prevent another HTTP request")

            nowMs.set(serverRetryAt)
            val successAttempt = assertNotNull(firstQueue.beginAttempt("scheduled_server_retry"))
            runtime.executor.submit { runtime.dispatchSend(successAttempt) }.get(1, TimeUnit.SECONDS)
            assertTrue(successHttpEntered.await(1, TimeUnit.SECONDS))
            assertEquals(setOf(successAttempt.entry!!.id), runtime.protectedTelegramIds)
            successHttpRelease.countDown()

            assertTrue(firstOwnerFailed.await(2, TimeUnit.SECONDS))
            assertTrue(runtime.hasPendingResult)
            assertEquals(3, requestCount.get())
            assertEquals(setOf(successAttempt.entry!!.id), runtime.protectedTelegramIds)
            assertNull(store.telegramDeliveryReceipt("trip:summary"))
            assertEquals("trip:summary", store.telegramMessageByDedupeKey("trip:summary")?.dedupeKey)

            val replacementQueue = queue(store, enabled = false, nowMs = nowMs::get) { entry, messageId, confirmedAt ->
                store.markTelegramDelivered(entry, stateJson = null, telegramMessageId = messageId, deliveredAtMs = confirmedAt)
            }
            assertNull(replacementQueue.beginAttempt("ordinary_disabled_sender"))
            runtime.attach(
                token = replacementOwner,
                onReady = { replacementOwnerReady.countDown() },
                onFailure = { throw it },
                onPendingResult = { outcome, acknowledge ->
                    replacementQueue.settleReceivedAttempt(
                        outcome.attempt,
                        outcome.result,
                        outcome.confirmedAtMs,
                        acknowledge
                    )
                }
            )
            runtime.executor.submit {}.get(1, TimeUnit.SECONDS)

            assertTrue(replacementOwnerReady.await(1, TimeUnit.SECONDS))
            assertEquals(3, requestCount.get(), "owner reattach must commit the retained HTTP result, not send again")
            assertEquals("trip_summary", store.telegramDeliveryReceipt("trip:summary")?.eventType)
            assertEquals(123L, store.telegramDeliveryReceipt("trip:summary")?.telegramMessageId)
            assertNull(store.telegramMessageByDedupeKey("trip:summary"))
            assertFalse(runtime.hasPendingResult)
            assertFalse(runtime.hasInFlightDelivery)

            store.close()
            store = TelegramStore(context, TelegramDatabaseHelper(context, databaseName), clockMs = nowMs::get)
            assertEquals("trip_summary", store.telegramDeliveryReceipt("trip:summary")?.eventType)
            assertEquals(123L, store.telegramDeliveryReceipt("trip:summary")?.telegramMessageId)
            assertNull(store.telegramMessageByDedupeKey("trip:summary"))
            assertEquals(3, requestCount.get(), "durable receipt survives SQLite reopen without another send")
        } finally {
            successHttpRelease.countDown()
            runtime.close()
            store.close()
        }
    }

    private fun queue(
        store: TelegramStore,
        enabled: Boolean,
        nowMs: () -> Long = { 2_000L },
        commit: (com.bydcollector.collector.data.local.TelegramOutboxEntry, Long?, Long) -> Unit
    ) = TelegramDeliveryQueue(
        store = store,
        credentials = { TelegramDeliveryCredentials(enabled, "123:token", "chat") },
        commitDelivery = commit,
        record = { _, _, _ -> },
        nowMs = nowMs
    )
}
