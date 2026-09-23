package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateHintCreationRetryTest {
    @Test
    fun actualAttemptCallbacksRetryAtOneThenThreeSecondsAndExhaustAfterThreeAttempts() {
        val queue = FakeQueue()
        val tokens = mutableListOf<Long>()
        lateinit var retry: UpdateHintCreationRetry
        retry = UpdateHintCreationRetry(
            nowElapsedMs = { queue.now },
            postDelayed = queue::post,
            removeCallbacks = queue::remove,
            isEligible = { true },
            startAttempt = { resultId, token ->
                tokens += token
                assertTrue(retry.onTechnicalFailure(resultId, token))
            }
        )

        retry.offer(7L)
        assertEquals(listOf(1L), tokens)
        assertEquals(1_000L, queue.nextDelay())
        retry.offer(7L) // A lifecycle/access notification cannot bypass the pending deadline.
        queue.advanceBy(999L)
        assertEquals(listOf(1L), tokens)
        queue.advanceBy(1L)
        assertEquals(listOf(1L, 2L), tokens)
        assertEquals(3_000L, queue.nextDelay())
        queue.advanceBy(3_000L)
        assertEquals(listOf(1L, 2L, 3L), tokens)
        assertTrue(queue.tasks.isEmpty())

        retry.offer(7L)
        assertEquals(3, tokens.size)
        retry.offer(8L)
        assertEquals(4, tokens.size) // Only a genuinely new result gets a fresh budget.
    }

    @Test
    fun accessCancellationPreservesDeadlineAndFencesStaleRunnableAndAttemptCallbacks() {
        val queue = FakeQueue()
        var eligible = true
        val tokens = mutableListOf<Long>()
        val retry = UpdateHintCreationRetry(
            nowElapsedMs = { queue.now },
            postDelayed = queue::post,
            removeCallbacks = queue::remove,
            isEligible = { eligible },
            startAttempt = { _, token -> tokens += token }
        )
        retry.offer(21L)
        assertEquals(listOf(1L), tokens)
        assertTrue(retry.onTechnicalFailure(21L, 1L))
        val staleRunnable = queue.tasks.keys.single()

        retry.cancelPending()
        eligible = false
        retry.offer(21L)
        staleRunnable.run() // Simulate a Handler callback already dequeued during cancellation.
        assertEquals(listOf(1L), tokens)
        assertFalse(retry.onTechnicalFailure(21L, 1L))
        assertFalse(retry.onAttached(21L, 1L))

        queue.now = 500L
        eligible = true
        retry.offer(21L)
        assertEquals(listOf(1L), tokens)
        assertEquals(500L, queue.nextDelay())
        queue.advanceBy(500L)
        assertEquals(listOf(1L, 2L), tokens)
    }

    @Test
    fun ineligiblePrecreationDoesNotSpendAnAttemptAndAttachmentDoesNotConsumeTheOffer() {
        val queue = FakeQueue()
        var eligible = false
        val tokens = mutableListOf<Long>()
        val presentation = UpdateResultPresentation()
        val info = UpdateInfo("2.8.3", "https://example.test/update.apk", "notes")
        val snapshot = UpdateCheckSession.Snapshot(
            uiState = UpdateUiState.Available(info), inFlight = false, revision = 1L, availableResultId = 31L
        )
        val retry = UpdateHintCreationRetry(
            nowElapsedMs = { queue.now },
            postDelayed = queue::post,
            removeCallbacks = queue::remove,
            isEligible = { eligible },
            startAttempt = { _, token -> tokens += token }
        )

        retry.offer(31L)
        assertTrue(tokens.isEmpty())
        eligible = true
        retry.offer(31L)
        assertEquals(listOf(1L), tokens)
        assertTrue(retry.onAttached(31L, tokens.single()))
        retry.offer(31L)
        assertEquals(1, tokens.size)
        assertTrue(retry.isCurrentAttachedAttempt(31L, tokens.single()))
        eligible = false // A consumed presentation gate must not invalidate an already attached window.
        retry.offer(31L)
        assertTrue(retry.isCurrentAttachedAttempt(31L, tokens.single()))
        assertTrue(presentation.canPresentHint(snapshot, ownUiVisible = false, hintEnabled = true))

        val lifetime = UpdateHintLifetime()
        lifetime.begin(31L)
        assertEquals(0L, lifetime.expiresAtElapsedMs)
        assertTrue(presentation.markPresented(snapshot, 31L)) // The first-draw path owns consumption.
        lifetime.shown(nowElapsedMs = 2_000L)
        assertEquals(12_000L, lifetime.expiresAtElapsedMs)
        assertFalse(presentation.canPresentHint(snapshot, ownUiVisible = false, hintEnabled = true))
    }

    @Test
    fun nontechnicalCancellationRefundsPendingAttemptButKeepsSameResultBudgetAndDeadline() {
        val queue = FakeQueue()
        val tokens = mutableListOf<Long>()
        val retry = UpdateHintCreationRetry(
            nowElapsedMs = { queue.now },
            postDelayed = queue::post,
            removeCallbacks = queue::remove,
            isEligible = { true },
            startAttempt = { _, token -> tokens += token }
        )

        retry.offer(44L)
        assertTrue(retry.onSkippedBeforeCreation(44L, tokens[0]))
        retry.offer(44L)
        assertEquals(listOf(1L, 2L), tokens)
        retry.cancelAttempt(44L, tokens[1]) // Coordinator/access cancellation before attachment is not a failure.
        retry.offer(44L)
        assertEquals(listOf(1L, 2L, 3L), tokens)
        assertTrue(retry.onTechnicalFailure(44L, tokens[2]))

        queue.now = 400L
        retry.cancelPending() // Sleep/wake may change session generation without changing this result ID.
        retry.offer(44L)
        assertEquals(600L, queue.nextDelay()) // Same result keeps the original backoff deadline.
        queue.advanceBy(599L)
        assertEquals(3, tokens.size)
        queue.advanceBy(1L)
        assertEquals(listOf(1L, 2L, 3L, 4L), tokens)
        assertTrue(retry.onTechnicalFailure(44L, tokens[3]))
        assertEquals(3_000L, queue.nextDelay())
        queue.advanceBy(3_000L)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), tokens)
        assertTrue(retry.onTechnicalFailure(44L, tokens[4]))

        retry.cancelPending()
        retry.offer(44L)
        assertEquals(5, tokens.size) // Lifecycle cannot clear per-result exhaustion.
        retry.offer(45L)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), tokens)
    }

    @Test
    fun diagnosticsTrackRetryExhaustionAndSingleCancellationWithoutAffectingScheduling() {
        val queue = FakeQueue()
        val attempts = mutableListOf<Long>()
        val events = mutableListOf<Pair<String, String>>()
        val retry = UpdateHintCreationRetry(
            nowElapsedMs = { queue.now },
            postDelayed = queue::post,
            removeCallbacks = queue::remove,
            isEligible = { true },
            startAttempt = { _, token -> attempts += token },
            onEvent = { name, detail -> events += name to detail }
        )

        retry.offer(88L)
        retry.cancelPending("screen_off")
        retry.cancelPending("screen_off") // Repeated lifecycle notification has no live work to log.
        retry.offer(88L)
        assertTrue(retry.onTechnicalFailure(88L, attempts.last(), "preparation", "IllegalStateException"))
        retry.cancelAttempt(88L, attempts.last(), "window_failed") // Cleanup must leave the retry scheduled.
        assertEquals(1_000L, queue.nextDelay())
        queue.advanceBy(1_000L)
        assertTrue(retry.onTechnicalFailure(88L, attempts.last(), "attachment", "BadTokenException"))
        retry.cancelAttempt(88L, attempts.last(), "placement_failed")
        assertEquals(3_000L, queue.nextDelay())
        queue.advanceBy(3_000L)
        assertTrue(retry.onTechnicalFailure(88L, attempts.last(), "attachment", "BadTokenException"))
        retry.cancelPending("shutdown") // Exhausted work is no longer live and must not log cancellation.

        assertEquals(listOf(1L, 2L, 3L, 4L), attempts)
        assertEquals(
            listOf(
                "hint_creation_attempt",
                "hint_creation_cancelled",
                "hint_creation_attempt",
                "hint_creation_retry_scheduled",
                "hint_creation_attempt",
                "hint_creation_retry_scheduled",
                "hint_creation_attempt",
                "hint_creation_exhausted"
            ),
            events.map { it.first }
        )
        assertTrue(events[1].second.contains("reason=screen_off"))
        assertTrue(events[3].second.contains("phase=preparation error_class=IllegalStateException"))
        assertTrue(events[3].second.contains("attempts=1/3 delay_ms=1000 retry_at_elapsed_ms=1000"))
        assertTrue(events[5].second.contains("phase=attachment error_class=BadTokenException"))
        assertTrue(events[5].second.contains("attempts=2/3 delay_ms=3000 retry_at_elapsed_ms=4000"))
        assertTrue(events[7].second.contains("phase=attachment error_class=BadTokenException"))
        assertTrue(events[7].second.contains("attempts=3/3 reason=technical_failure"))

        val loggingFailureQueue = FakeQueue()
        val loggingFailureAttempts = mutableListOf<Long>()
        lateinit var loggingFailureRetry: UpdateHintCreationRetry
        loggingFailureRetry = UpdateHintCreationRetry(
            nowElapsedMs = { loggingFailureQueue.now },
            postDelayed = loggingFailureQueue::post,
            removeCallbacks = loggingFailureQueue::remove,
            isEligible = { true },
            startAttempt = { resultId, token ->
                loggingFailureAttempts += token
                if (token == 1L) {
                    assertTrue(loggingFailureRetry.onTechnicalFailure(resultId, token, "preparation", "IllegalStateException"))
                }
            },
            onEvent = { _, _ -> error("diagnostic sink failure") }
        )
        loggingFailureRetry.offer(99L)
        assertEquals(1_000L, loggingFailureQueue.nextDelay())
        loggingFailureQueue.advanceBy(1_000L)
        assertEquals(listOf(1L, 2L), loggingFailureAttempts)
        assertTrue(loggingFailureRetry.onAttached(99L, 2L))
    }

    private class FakeQueue {
        var now = 0L
        val tasks = linkedMapOf<Runnable, Long>()

        fun post(task: Runnable, delayMs: Long) {
            tasks[task] = now + delayMs
        }

        fun remove(task: Runnable) {
            tasks.remove(task)
        }

        fun nextDelay(): Long = (tasks.values.minOrNull() ?: error("No pending retry")) - now

        fun advanceBy(deltaMs: Long) {
            now += deltaMs
            while (true) {
                val next = tasks.entries.filter { it.value <= now }.minByOrNull { it.value } ?: return
                tasks.remove(next.key)
                next.key.run()
            }
        }
    }
}
