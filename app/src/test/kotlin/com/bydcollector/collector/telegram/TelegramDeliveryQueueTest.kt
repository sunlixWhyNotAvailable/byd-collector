package com.bydcollector.collector.telegram

import com.bydcollector.collector.data.local.TelegramDeliveryStore
import com.bydcollector.collector.data.local.TelegramOutboxEntry
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramDeliveryQueueTest {
    @Test
    fun dueSelectionDoesNotLetAnOlderFutureRowBlockANewerDueRow() {
        val store = FakeDeliveryStore(
            entries = listOf(
                entry(id = 1, dedupeKey = "old-future", nextAttemptAtMs = 10_000L),
                entry(id = 2, dedupeKey = "new-due", nextAttemptAtMs = 0L)
            )
        )
        val sent = mutableListOf<TelegramSendMessage>()
        val queue = queue(store, send = { request -> sent += request; TelegramSendResult.Success })

        queue.flush("ordinary")

        assertEquals(listOf("payload-2"), sent.map { it.text })
        assertTrue(store.rows.containsKey(1L))
        assertFalse(store.rows.containsKey(2L))
    }

    @Test
    fun pendingDeadlineIsTheMinimumEligibleLocalAttemptTime() {
        val store = FakeDeliveryStore(
            entries = listOf(
                entry(id = 1, dedupeKey = "later", nextAttemptAtMs = 8_000L),
                entry(id = 2, dedupeKey = "earlier", nextAttemptAtMs = 2_000L),
                entry(id = 3, dedupeKey = "blocked", nextAttemptAtMs = 1_000L, blocked = true),
                entry(id = 4, dedupeKey = "dependent", nextAttemptAtMs = 0L, waitsForSummaryKey = "summary")
            )
        )
        val queue = queue(store)

        assertEquals(2_000L, queue.pendingDeadline())
    }

    @Test
    fun successfulAtomicCommitReleasesLocationOnlyAfterSummaryDelivery() {
        val store = FakeDeliveryStore(
            entries = listOf(
                entry(id = 1, dedupeKey = "trip:summary", eventType = "trip_summary", payload = "summary:payload"),
                entry(
                    id = 2,
                    dedupeKey = "trip:location",
                    eventType = "trip_location",
                    payload = "location:payload",
                    waitsForSummaryKey = "trip:summary"
                )
            )
        )
        val sent = mutableListOf<TelegramSendMessage>()
        val queue = queue(store, send = { request -> sent += request; TelegramSendResult.Success })

        queue.flush("power_off")

        assertEquals(listOf("summary:payload"), sent.map { it.text })
        assertNull(store.rows[2L]?.waitsForSummaryKey)

        queue.flush("ordinary")

        assertEquals(listOf("summary:payload", "location:payload"), sent.map { it.text })
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun blockedSummaryDoesNotAuthorizeItsDependentLocation() {
        val store = FakeDeliveryStore(
            entries = listOf(
                entry(id = 1, dedupeKey = "trip:summary", blocked = true),
                entry(id = 2, dedupeKey = "trip:location", waitsForSummaryKey = "trip:summary")
            )
        )
        val sent = mutableListOf<TelegramSendMessage>()
        val queue = queue(store, send = { request -> sent += request; TelegramSendResult.Success })

        assertNull(queue.flush("ordinary"))
        assertNull(queue.flush("power_off", priorityKey = "trip:summary", expediteLocal = true))
        assertNull(queue.flush("power_off", priorityKey = "trip:location", expediteLocal = true))

        assertTrue(sent.isEmpty())
        assertTrue(store.rows[1L]?.blocked == true)
        assertEquals("trip:summary", store.rows[2L]?.waitsForSummaryKey)
    }

    @Test
    fun retryFailureMovesThatRowToTheFutureButLeavesAnotherDueRowAvailable() {
        val store = FakeDeliveryStore(
            entries = listOf(
                entry(id = 1, dedupeKey = "first", payload = "first payload"),
                entry(id = 2, dedupeKey = "second", payload = "second payload")
            )
        )
        val sent = mutableListOf<TelegramSendMessage>()
        val queue = queue(
            store,
            send = { request ->
                sent += request
                if (sent.size == 1) {
                    TelegramSendResult.Failure(TelegramSendFailureKind.NETWORK_ERROR)
                } else {
                    TelegramSendResult.Success
                }
            }
        )

        queue.flush("ordinary")

        assertEquals(30_000L, store.rows[1L]?.nextAttemptAtMs)
        assertEquals(0L, queue.pendingDeadline())
        assertEquals("first payload", store.rows[1L]?.payload)
        assertTrue(store.rows.containsKey(2L))

        queue.flush("ordinary")

        assertEquals(listOf("first payload", "second payload"), sent.map { it.text })
        assertTrue(store.rows.containsKey(1L))
        assertFalse(store.rows.containsKey(2L))
    }

    @Test
    fun cumulativeAttemptsDoNotKeepAResetFailureStreakOnTheLongBackoffPath() {
        val store = FakeDeliveryStore(
            entries = listOf(
                entry(
                    id = 1,
                    attemptCount = 9,
                    failureCount = 0,
                    lastError = null
                )
            )
        )
        val queue = queue(
            store,
            send = { TelegramSendResult.Failure(TelegramSendFailureKind.NETWORK_ERROR) }
        )

        queue.flush("ordinary")

        val retried = store.rows.getValue(1L)
        assertEquals(10, retried.attemptCount)
        assertEquals(1, retried.failureCount)
        assertEquals(30_000L, retried.nextAttemptAtMs)
        assertEquals("network_error", retried.lastError)
    }

    @Test
    fun rateLimitServerWaitCannotBeBypassedByPriorityOrdinaryOrConnectionTest() {
        val store = FakeDeliveryStore(entries = listOf(entry(id = 1)))
        var now = 0L
        val sent = mutableListOf<TelegramSendMessage>()
        val queue = queue(
            store,
            now = { now },
            send = { request ->
                sent += request
                TelegramSendResult.Failure(
                    kind = TelegramSendFailureKind.RATE_LIMITED,
                    retryAfterSeconds = 120L
                )
            }
        )
        val request = TelegramSendMessage("123:token", "chat", "manual")

        queue.flush("ordinary")
        assertEquals(120_000L, store.telegramServerNotBefore(TelegramDeliveryQueue.botScope("123:token")))

        assertEquals(120_000L, queue.flush("power_off", priorityKey = "message-1", expediteLocal = true))
        assertEquals(120_000L, queue.flush("ordinary"))
        assertEquals(120_000L, queue.recover("vehicle_on"))
        assertEquals(120_000L, queue.recover("network_change"))
        listOf("on", "network", "manual", "off").forEach { trigger ->
            assertEquals(120_000L, queue.recover(trigger))
        }
        val testResult = queue.testConnection(request)

        val testFailure = assertIs<TelegramSendResult.Failure>(testResult)
        assertEquals(TelegramSendFailureKind.RATE_LIMITED, testFailure.kind)
        assertEquals(1, sent.size)
        assertEquals(120L, testFailure.retryAfterSeconds)

        val newQueue = queue(store, now = { now }, send = { sent += it; TelegramSendResult.Success })
        assertEquals(120_000L, newQueue.flush("startup"))
        assertEquals(1, sent.size)
    }

    @Test
    fun serverWaitIsScopedToNumericBotIdAndSurvivesTokenRotation() {
        val store = FakeDeliveryStore(entries = listOf(entry(id = 1), entry(id = 2, payload = "other")))
        var config = TelegramDeliveryCredentials(enabled = true, token = "123:old", chatId = "chat")
        val sent = mutableListOf<TelegramSendMessage>()
        val firstQueue = queue(
            store,
            credentials = { config },
            send = { request ->
                sent += request
                if (sent.size == 1) {
                    TelegramSendResult.Failure(
                        kind = TelegramSendFailureKind.RATE_LIMITED,
                        retryAfterSeconds = 60L
                    )
                } else {
                    TelegramSendResult.Success
                }
            }
        )

        firstQueue.flush("ordinary")
        config = config.copy(token = "123:new")
        assertEquals(60_000L, firstQueue.flush("ordinary"))
        assertEquals(60_000L, store.telegramServerNotBefore(TelegramDeliveryQueue.botScope("123:new")))
        assertEquals(0L, store.telegramServerNotBefore(TelegramDeliveryQueue.botScope("456:new")))

        config = config.copy(token = "456:new")
        val differentBotQueue = queue(store, credentials = { config }, send = { request -> sent += request; TelegramSendResult.Success })
        differentBotQueue.flush("ordinary")

        assertEquals(listOf("payload-1", "other"), sent.map { it.text })
    }

    @Test
    fun connectionTestWorksWithIntegrationDisabledWithoutDrainingTheOutbox() {
        val store = FakeDeliveryStore(entries = listOf(entry(id = 1)))
        val config = TelegramDeliveryCredentials(enabled = false, token = "", chatId = "")
        val sent = mutableListOf<TelegramSendMessage>()
        val queue = queue(store, credentials = { config }, send = { request -> sent += request; TelegramSendResult.Success })

        assertEquals(TelegramSendResult.Success, queue.testConnection(TelegramSendMessage("123:token", "chat", "manual")))

        assertEquals(1, sent.size)
        assertTrue(store.rows.containsKey(1L))
        assertNull(queue.flush("manual"))
        assertTrue(store.rows.containsKey(1L))
    }

    @Test
    fun commitExceptionPropagatesAndDoesNotRecordDeliveryOrReleaseLocation() {
        val store = FakeDeliveryStore(
            entries = listOf(
                entry(id = 1, dedupeKey = "trip:summary"),
                entry(id = 2, dedupeKey = "trip:location", waitsForSummaryKey = "trip:summary")
            )
        )
        val records = mutableListOf<DeliveryRecord>()
        val queue = queue(
            store,
            records = records,
            commit = { _, _ -> throw IllegalStateException("commit failed") },
            send = { TelegramSendResult.Success }
        )

        assertFailsWith<IllegalStateException> { queue.flush("ordinary") }

        assertTrue(store.rows.containsKey(1L))
        assertEquals("trip:summary", store.rows[2L]?.waitsForSummaryKey)
        assertEquals(listOf("attempt"), records.map { it.kind })
    }

    @Test
    fun interruptedThreadDoesNotCallTheSender() {
        Thread.interrupted()
        val store = FakeDeliveryStore(entries = listOf(entry(id = 1)))
        val sent = mutableListOf<TelegramSendMessage>()
        val queue = queue(store, send = { request -> sent += request; TelegramSendResult.Success })

        try {
            Thread.currentThread().interrupt()
            queue.flush("ordinary")
            assertTrue(sent.isEmpty())
            assertTrue(store.rows.containsKey(1L))
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun hugeRetryAfterSaturatesAtLongMaxWithoutShorteningTheServerWait() {
        val now = Long.MAX_VALUE - 5_000L
        val store = FakeDeliveryStore(entries = listOf(entry(id = 1, nextAttemptAtMs = now)))
        val queue = queue(
            store,
            now = { now },
            send = {
                TelegramSendResult.Failure(
                    kind = TelegramSendFailureKind.RATE_LIMITED,
                    retryAfterSeconds = Long.MAX_VALUE
                )
            }
        )

        queue.flush("ordinary")

        val scope = TelegramDeliveryQueue.botScope("123:token")
        assertEquals(Long.MAX_VALUE, store.telegramServerNotBefore(scope))
        assertEquals(Long.MAX_VALUE, store.rows[1L]?.nextAttemptAtMs)
        assertEquals(Long.MAX_VALUE, queue.pendingDeadline())
    }

    @Test
    fun elapsedHttpTimeIsAddedBeforeApplyingRetryAfterServerDeadline() {
        var now = 0L
        val store = FakeDeliveryStore(entries = listOf(entry(id = 1)))
        val queue = queue(
            store,
            now = { now },
            send = {
                now += 10_000L
                TelegramSendResult.Failure(
                    kind = TelegramSendFailureKind.RATE_LIMITED,
                    retryAfterSeconds = 30L
                )
            }
        )

        queue.flush("ordinary")

        assertEquals(40_000L, store.telegramServerNotBefore(TelegramDeliveryQueue.botScope("123:token")))
        assertEquals(40_000L, store.rows[1L]?.nextAttemptAtMs)
    }

    @Test
    fun realisticSep4FutureRetryIsRecoveredImmediatelyBySameProcessOn() {
        // Captured Kyiv timestamps, converted to UTC; same process observes ON.
        val off = java.time.Instant.parse("2026-09-04T13:16:09.331Z").toEpochMilli()
        val now = java.time.Instant.parse("2026-09-04T14:30:40.099Z").toEpochMilli()
        val oldDeadline = java.time.Instant.parse("2026-09-04T14:47:20.391Z").toEpochMilli()
        val store = FakeDeliveryStore(
            entries = listOf(
                entry(
                    id = 1,
                    createdAtMs = off,
                    nextAttemptAtMs = oldDeadline,
                    attemptCount = 8,
                    failureCount = 8,
                    lastError = "network_error:java.net.UnknownHostException"
                )
            )
        )
        val sent = mutableListOf<TelegramSendMessage>()
        val queue = queue(store, now = { now }, send = { request -> sent += request; TelegramSendResult.Success })

        assertEquals(oldDeadline, queue.flush("tick"))
        assertTrue(sent.isEmpty())
        queue.recover("vehicle_on")

        assertEquals(listOf("payload-1"), sent.map { it.text })
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun sep5NewerDueRowIsNotBlockedByOlderFutureRowAndReleasesItsLocationDependency() {
        val sep5 = LocalDate.of(2026, 9, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val now = sep5 + 12L * 60L * 60L * 1_000L
        val store = FakeDeliveryStore(
            entries = listOf(
                entry(
                    id = 1,
                    dedupeKey = "old-future",
                    payload = "old-future-payload",
                    nextAttemptAtMs = now + 30L * 60L * 1_000L,
                    lastError = "network_error:java.net.UnknownHostException"
                ),
                entry(id = 2, dedupeKey = "trip:summary", payload = "summary-payload"),
                entry(
                    id = 3,
                    dedupeKey = "trip:location",
                    payload = "location-payload",
                    waitsForSummaryKey = "trip:summary"
                )
            )
        )
        val sent = mutableListOf<TelegramSendMessage>()
        val queue = queue(store, now = { now }, send = { request -> sent += request; TelegramSendResult.Success })

        queue.flush("on")

        assertEquals(listOf("summary-payload"), sent.map { it.text })
        assertNull(store.rows[3L]?.waitsForSummaryKey)
        assertTrue(store.rows.containsKey(1L))
        assertEquals(now, store.rows[1L]?.nextAttemptAtMs)
        queue.flush("tick")
        queue.flush("tick")
        assertEquals(listOf("summary-payload", "old-future-payload", "location-payload"), sent.map { it.text })
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun targetedOffSuccessReleasesOlderNetworkRetriesAfterAtomicCommit() {
        val now = LocalDate.of(2026, 9, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val store = FakeDeliveryStore(
            entries = listOf(
                entry(
                    id = 1,
                    dedupeKey = "older-network",
                    payload = "older-network-payload",
                    nextAttemptAtMs = now + 30L * 60L * 1_000L,
                    attemptCount = 7,
                    failureCount = 3,
                    lastError = "network_error"
                ),
                entry(id = 2, dedupeKey = "trip:summary", payload = "summary-payload"),
                entry(
                    id = 3,
                    dedupeKey = "trip:location",
                    payload = "location-payload",
                    waitsForSummaryKey = "trip:summary"
                )
            )
        )
        val sent = mutableListOf<TelegramSendMessage>()
        val queue = queue(store, now = { now }, send = { request -> sent += request; TelegramSendResult.Success })

        queue.flush("off", priorityKey = "trip:summary")

        assertEquals(listOf("summary-payload"), sent.map { it.text })
        assertEquals(now, store.rows[1L]?.nextAttemptAtMs)
        assertEquals(0, store.rows[1L]?.failureCount)
        assertEquals(7, store.rows[1L]?.attemptCount)
        assertNull(store.rows[3L]?.waitsForSummaryKey)

        queue.flush("ordinary")

        assertEquals(listOf("summary-payload", "older-network-payload"), sent.map { it.text })
    }

    @Test
    fun recoveryOnlyExpeditesNetworkFailuresAcrossOnNetworkManualAndOffTriggers() {
        listOf("on", "network", "manual", "off").forEach { trigger ->
            val now = 10_000L
            val future = now + 30L * 60L * 1_000L
            val store = FakeDeliveryStore(
                entries = listOf(
                    entry(id = 1, nextAttemptAtMs = future, failureCount = 2, lastError = "network_error"),
                    entry(id = 2, nextAttemptAtMs = future, failureCount = 2, lastError = "rate_limited:429"),
                    entry(id = 3, nextAttemptAtMs = future, failureCount = 2, lastError = "server_error:500"),
                    entry(id = 4, nextAttemptAtMs = future, failureCount = 2, lastError = "network_error", blocked = true),
                    entry(id = 5, nextAttemptAtMs = future, failureCount = 2, lastError = "NETWORK_ERROR")
                )
            )
            val queue = queue(store, now = { now }, send = { TelegramSendResult.Success })

            queue.recover(trigger)

            assertFalse(store.rows.containsKey(1L), "network row should be delivered for trigger=$trigger")
            assertEquals(future, store.rows[2L]?.nextAttemptAtMs, "429 row changed for trigger=$trigger")
            assertEquals(future, store.rows[3L]?.nextAttemptAtMs, "server row changed for trigger=$trigger")
            assertEquals(future, store.rows[4L]?.nextAttemptAtMs, "blocked row changed for trigger=$trigger")
            assertEquals(future, store.rows[5L]?.nextAttemptAtMs, "case-variant row changed for trigger=$trigger")
        }
    }

    @Test
    fun recoveryResetsFailureStreakWithoutResettingTotalAttemptHistory() {
        val now = 10_000L
        val store = FakeDeliveryStore(
            entries = listOf(
                entry(id = 1, nextAttemptAtMs = now + 30_000L, attemptCount = 12, failureCount = 4, lastError = "network_error"),
                entry(id = 2, nextAttemptAtMs = now + 30_000L, attemptCount = 8, failureCount = 3, lastError = "network_error")
            )
        )
        val queue = queue(store, now = { now }, send = { TelegramSendResult.Success })

        queue.recover("on")

        assertFalse(store.rows.containsKey(1L))
        assertEquals(now, store.rows[2L]?.nextAttemptAtMs)
        assertEquals(0, store.rows[2L]?.failureCount)
        assertEquals(8, store.rows[2L]?.attemptCount)
    }

    @Test
    fun repeatedSuccessesInOneDueDrainCannotRearmAIdThatAlreadyFailed() {
        val now = 0L
        val store = FakeDeliveryStore(
            entries = listOf(
                entry(id = 1, lastError = null),
                entry(id = 2),
                entry(id = 3)
            )
        )
        val sent = mutableListOf<TelegramSendMessage>()
        val queue = queue(
            store,
            now = { now },
            send = { request ->
                sent += request
                if (request.text == "payload-1") {
                    TelegramSendResult.Failure(TelegramSendFailureKind.NETWORK_ERROR)
                } else {
                    TelegramSendResult.Success
                }
            }
        )

        queue.flush("drain")
        queue.flush("drain")
        queue.flush("drain")

        assertEquals(listOf("payload-1", "payload-2", "payload-3"), sent.map { it.text })
        assertEquals(30_000L, store.rows[1L]?.nextAttemptAtMs)
        assertEquals(1, store.rows[1L]?.failureCount)
    }

    @Test
    fun attemptedIdsAreClearedOnlyAfterNoDueWorkRemains() {
        var now = 0L
        val store = FakeDeliveryStore(
            entries = listOf(
                entry(id = 1),
                entry(id = 2)
            )
        )
        val queue = queue(
            store,
            now = { now },
            send = { request ->
                if (request.text == "payload-1") TelegramSendResult.Failure(TelegramSendFailureKind.NETWORK_ERROR)
                else TelegramSendResult.Success
            }
        )

        queue.flush("drain")
        queue.flush("drain")
        store.add(entry(id = 3))
        queue.flush("drain")

        assertEquals(now, store.rows[1L]?.nextAttemptAtMs)
        assertEquals(0, store.rows[1L]?.failureCount)
    }

    @Test
    fun successfulEnabledConnectionTestWakesBacklogButDoesNotSendQueuedPayload() {
        var now = 10_000L
        val store = FakeDeliveryStore(
            entries = listOf(
                entry(id = 1, payload = "queued-payload", nextAttemptAtMs = now + 30_000L, failureCount = 2, lastError = "network_error", attemptCount = 5)
            )
        )
        val sent = mutableListOf<TelegramSendMessage>()
        val queue = queue(store, now = { now }, send = { request -> sent += request; TelegramSendResult.Success })

        assertEquals(TelegramSendResult.Success, queue.testConnection(TelegramSendMessage("123:token", "chat", "manual-payload")))

        assertEquals(listOf("manual-payload"), sent.map { it.text })
        assertEquals(now, store.rows[1L]?.nextAttemptAtMs)
        assertEquals(0, store.rows[1L]?.failureCount)
        assertEquals(5, store.rows[1L]?.attemptCount)

        queue.flush("scheduled")

        assertEquals(listOf("manual-payload", "queued-payload"), sent.map { it.text })
    }

    @Test
    fun disabledRecoveryDoesNotWakeBacklogUntilIntegrationIsReenabled() {
        var config = TelegramDeliveryCredentials(enabled = false, token = "", chatId = "")
        val now = 10_000L
        val future = now + 30_000L
        val store = FakeDeliveryStore(
            entries = listOf(entry(id = 1, nextAttemptAtMs = future, failureCount = 2, lastError = "network_error"))
        )
        val sent = mutableListOf<TelegramSendMessage>()
        val queue = queue(store, credentials = { config }, now = { now }, send = { request -> sent += request; TelegramSendResult.Success })

        assertNull(queue.recover("off"))
        assertEquals(future, store.rows[1L]?.nextAttemptAtMs)
        assertTrue(sent.isEmpty())

        config = TelegramDeliveryCredentials(enabled = true, token = "123:token", chatId = "chat")
        queue.recover("on")

        assertEquals(listOf("payload-1"), sent.map { it.text })
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun commitFailureDoesNotWakeOtherNetworkRetries() {
        val now = 10_000L
        val future = now + 30_000L
        val store = FakeDeliveryStore(
            entries = listOf(
                entry(id = 1, nextAttemptAtMs = now),
                entry(id = 2, nextAttemptAtMs = future, failureCount = 2, lastError = "network_error")
            )
        )
        val records = mutableListOf<DeliveryRecord>()
        val queue = queue(
            store,
            now = { now },
            records = records,
            commit = { _, _ -> throw IllegalStateException("commit failed") },
            send = { TelegramSendResult.Success }
        )

        assertFailsWith<IllegalStateException> { queue.flush("ordinary") }

        assertTrue(store.rows.containsKey(1L))
        assertEquals(future, store.rows[2L]?.nextAttemptAtMs)
        assertEquals(2, store.rows[2L]?.failureCount)
        assertEquals(listOf("attempt"), records.map { it.kind })
    }

    private fun queue(
        store: FakeDeliveryStore,
        credentials: () -> TelegramDeliveryCredentials = {
            TelegramDeliveryCredentials(enabled = true, token = "123:token", chatId = "chat")
        },
        now: () -> Long = { 0L },
        records: MutableList<DeliveryRecord> = mutableListOf(),
        commit: (TelegramOutboxEntry, Long) -> Unit = { entry, _ -> store.commit(entry) },
        send: (TelegramSendMessage) -> TelegramSendResult = { TelegramSendResult.Success }
    ): TelegramDeliveryQueue {
        return TelegramDeliveryQueue(
            store = store,
            credentials = credentials,
            send = send,
            commitDelivery = commit,
            record = { kind, entry, detail -> records += DeliveryRecord(kind, entry?.id, detail) },
            nowMs = now
        )
    }

    private fun entry(
        id: Long,
        dedupeKey: String = "message-$id",
        eventType: String = "event",
        payload: String = "payload-$id",
        attemptCount: Int = 0,
        nextAttemptAtMs: Long = 0L,
        blocked: Boolean = false,
        waitsForSummaryKey: String? = null,
        createdAtMs: Long = 0L,
        failureCount: Int = attemptCount,
        lastError: String? = null
    ) = TelegramOutboxEntry(
        id = id,
        dedupeKey = dedupeKey,
        eventType = eventType,
        payload = payload,
        attemptCount = attemptCount,
        nextAttemptAtMs = nextAttemptAtMs,
        blocked = blocked,
        waitsForSummaryKey = waitsForSummaryKey,
        createdAtMs = createdAtMs,
        failureCount = failureCount,
        lastError = lastError
    )

    private data class DeliveryRecord(val kind: String, val entryId: Long?, val detail: String)

    private class FakeDeliveryStore(entries: List<TelegramOutboxEntry>) : TelegramDeliveryStore {
        val rows = linkedMapOf<Long, TelegramOutboxEntry>()
        private val serverNotBeforeByScope = mutableMapOf<String, Long>()

        init {
            entries.forEach { rows[it.id] = it }
        }

        override fun oldestDueTelegramMessage(nowMs: Long, eventType: String?): TelegramOutboxEntry? = rows.values
            .asSequence()
            .filter { !it.blocked && it.waitsForSummaryKey == null && it.nextAttemptAtMs <= nowMs }
            .filter { eventType == null || it.eventType == eventType }
            .minByOrNull { it.id }

        override fun nextTelegramAttemptAtMs(eventType: String?): Long? = rows.values
            .asSequence()
            .filter { !it.blocked && it.waitsForSummaryKey == null }
            .filter { eventType == null || it.eventType == eventType }
            .map { it.nextAttemptAtMs }
            .minOrNull()

        override fun telegramMessageByDedupeKey(dedupeKey: String): TelegramOutboxEntry? = rows.values
            .firstOrNull { it.dedupeKey == dedupeKey }

        override fun markTelegramRetry(id: Long, error: String, attemptedAtMs: Long, nextAttemptAtMs: Long) {
            val current = rows.getValue(id)
            rows[id] = current.copy(
                attemptCount = current.attemptCount + 1,
                failureCount = current.failureCount + 1,
                nextAttemptAtMs = nextAttemptAtMs,
                blocked = false,
                lastError = error
            )
        }

        override fun markTelegramBlocked(id: Long, error: String, attemptedAtMs: Long) {
            val current = rows.getValue(id)
            rows[id] = current.copy(
                attemptCount = current.attemptCount + 1,
                failureCount = current.failureCount + 1,
                blocked = true,
                lastError = error
            )
        }

        override fun telegramServerNotBefore(botScope: String): Long = serverNotBeforeByScope[botScope] ?: 0L

        override fun extendTelegramServerNotBefore(botScope: String, notBeforeMs: Long) {
            serverNotBeforeByScope[botScope] = maxOf(telegramServerNotBefore(botScope), notBeforeMs)
        }

        override fun expediteNetworkRetries(nowMs: Long, excludeIds: Set<Long>): Int {
            var expedited = 0
            rows.entries.toList().forEach { (id, current) ->
                if (!current.blocked && id !in excludeIds &&
                    (current.nextAttemptAtMs > nowMs || current.failureCount > 0) &&
                    TelegramDeliveryQueue.isNetworkFailure(current.lastError)
                ) {
                    rows[id] = current.copy(nextAttemptAtMs = nowMs, failureCount = 0)
                    expedited += 1
                }
            }
            return expedited
        }

        fun add(entry: TelegramOutboxEntry) {
            rows[entry.id] = entry
        }

        fun commit(entry: TelegramOutboxEntry) {
            check(rows.remove(entry.id) != null) { "row disappeared" }
            rows.entries.toList().forEach { (id, current) ->
                if (current.waitsForSummaryKey == entry.dedupeKey) {
                    rows[id] = current.copy(waitsForSummaryKey = null)
                }
            }
        }
    }
}
