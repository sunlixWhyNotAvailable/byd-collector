package com.bydcollector.collector.data.callback

import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class CallbackWorkersTest {
    @Test fun `graceful intake stop finishes active commit and ACK without draining backlog`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val operations = CopyOnWriteArrayList<String>()
        val statuses = CopyOnWriteArrayList<CallbackIntakeStatus>()
        val worker = CallbackIntakeWorker("test-graceful-intake", drain = {
            operations += "begin"
            entered.countDown()
            release.await() // interruption here would lose the active transaction
            operations += "commit"
            operations += "ack"
            progressResult() // more backlog exists; Shutdown must not drain it all
        }, onStatus = { statuses += it })
        try {
            assertTrue(worker.start())
            await(entered, "No active intake batch")
            worker.requestStopAfterCurrentBatch()
            assertFalse(worker.awaitStopped(10), "Blocked batch is not finished")
            assertFalse(worker.start(), "Must not overlap a closing batch")
            assertEquals(listOf("begin"), operations.toList())
            release.countDown()
            assertTrue(worker.awaitStopped(2_000))
            assertEquals(listOf("begin", "commit", "ack"), operations.toList())
            assertEquals(1L, statuses.last().persistedEvents)
            assertEquals(CallbackIntakeCondition.STOPPED, statuses.last().condition)
        } finally {
            release.countDown()
            worker.stopAndJoin(2_000)
        }
    }

    @Test fun `graceful normalization stop completes one active page without another page`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val committed = AtomicInteger()
        val worker = CallbackNormalizationWorker("test-graceful-normalization", drainPage = {
            entered.countDown()
            release.await()
            committed.incrementAndGet()
            true
        })
        try {
            assertTrue(worker.start())
            await(entered, "No active normalization page")
            worker.requestStopAfterCurrentPage()
            worker.signal() // late producer must not restart normalization during shutdown
            assertFalse(worker.awaitStopped(10))
            assertEquals(0, committed.get())
            release.countDown()
            assertTrue(worker.awaitStopped(2_000))
            assertEquals(1, committed.get())
        } finally {
            release.countDown()
            worker.stopAndJoin(2_000)
        }
    }

    @Test fun `intake gates helper access and uses bounded pending backoff reset by progress`() {
        val ready = AtomicBoolean(false)
        val calls = AtomicInteger()
        val pendingSleeps = CopyOnWriteArrayList<Long>()
        val statuses = CopyOnWriteArrayList<CallbackIntakeStatus>()
        val waitingForCredentials = CountDownLatch(1)
        val credentialsAvailable = CountDownLatch(1)
        val reachedBackoffEnd = CountDownLatch(1)
        val holdWorker = CountDownLatch(1)
        val worker = CallbackIntakeWorker(
            threadName = "test-callback-main",
            ready = ready::get,
            drain = {
                when (calls.getAndIncrement()) {
                    in 0..3 -> pendingResult(status = -915, detail = "barrier")
                    4 -> progressResult()
                    5 -> pendingResult(status = -916, detail = "stale token")
                    else -> emptyResult()
                }
            },
            onStatus = { statuses.add(it) },
            sleepMs = { delay ->
                if (!ready.get()) {
                    waitingForCredentials.countDown()
                    credentialsAvailable.await()
                } else {
                    pendingSleeps.add(delay)
                    if (pendingSleeps.size == 5) {
                        reachedBackoffEnd.countDown()
                        holdWorker.await()
                    }
                }
            }
        )

        try {
            assertTrue(worker.start())
            await(waitingForCredentials, "worker did not wait for credentials")
            assertEquals(0, calls.get(), "transport must not run before credentials exist")
            assertTrue(statuses.any { it.condition == CallbackIntakeCondition.WAITING })
            ready.set(true)
            credentialsAvailable.countDown()
            await(reachedBackoffEnd, "worker did not reach the post-progress pending result")

            assertEquals(listOf(100L, 250L, 500L, 1_000L, 100L), pendingSleeps.toList())
            assertEquals(1L, statuses.filter { it.condition == CallbackIntakeCondition.PENDING }
                .first { it.kind == CallbackDrainKind.PENDING }.pendingPasses)
            assertTrue(statuses.any { it.condition == CallbackIntakeCondition.RUNNING && it.kind == CallbackDrainKind.PROGRESS })
            assertEquals(2, statuses.count { it.condition == CallbackIntakeCondition.PENDING })
        } finally {
            worker.stopAndJoin(1_000)
            credentialsAvailable.countDown()
            holdWorker.countDown()
        }
        assertTrue(statuses.any { it.condition == CallbackIntakeCondition.STOPPED })
    }

    @Test fun `empty and progress do not create per-packet status transitions`() {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val statuses = CopyOnWriteArrayList<CallbackIntakeStatus>()
        val waitingAtThirtySeconds = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clockAdvanced = AtomicBoolean(false)
        val aggregateReported = CountDownLatch(1)
        val worker = CallbackIntakeWorker(
            threadName = "test-callback-summary",
            drain = {
                when (calls.getAndIncrement()) {
                    0 -> emptyResult()
                    1 -> progressResult()
                    else -> emptyResult()
                }
            },
            onStatus = {
                statuses.add(it)
                if (it.condition == CallbackIntakeCondition.RUNNING && it.emptyPasses >= 2 && it.progressPasses >= 1) {
                    aggregateReported.countDown()
                }
            },
            sleepMs = { delay ->
                if (delay == CallbackIntakeWorker.EMPTY_WAIT_MS && calls.get() >= 3 &&
                    clockAdvanced.compareAndSet(false, true)
                ) {
                    clock.set(30_000_000_000L)
                    waitingAtThirtySeconds.countDown()
                    release.await()
                }
            },
            monotonicNanos = clock::get
        )

        try {
            assertTrue(worker.start())
            await(waitingAtThirtySeconds, "worker did not reach the aggregate boundary")
            assertEquals(1, statuses.size, "EMPTY/PROGRESS changes are routine within the aggregate interval")
            assertTrue(statuses.single().stateTransition)
            release.countDown()
            await(aggregateReported, "30-second aggregate status was not emitted")
            assertEquals(2, statuses.size)
            assertFalse(statuses.last().stateTransition)
        } finally {
            worker.stopAndJoin(1_000)
            release.countDown()
        }
    }

    @Test fun `distinct intake faults report immediately while repeats aggregate`() {
        val first = IllegalStateException("first failure")
        val second = IOException("different failure")
        val calls = AtomicInteger()
        val statuses = CopyOnWriteArrayList<CallbackIntakeStatus>()
        val blockedAtEnd = CountDownLatch(1)
        val keepBlocked = CountDownLatch(1)
        val worker = CallbackIntakeWorker(
            threadName = "test-callback-faults",
            drain = {
                when (calls.getAndIncrement()) {
                    0, 1 -> faultResult(first)
                    2 -> faultResult(second)
                    else -> emptyResult()
                }
            },
            onStatus = { statuses.add(it) },
            sleepMs = { _ ->
                if (calls.get() >= 3) {
                    blockedAtEnd.countDown()
                    keepBlocked.await()
                }
            }
        )

        try {
            assertTrue(worker.start())
            await(blockedAtEnd, "worker did not observe both fault identities")
            val faultStatuses = statuses.filter { it.condition == CallbackIntakeCondition.FAULT }
            assertEquals(2, faultStatuses.size)
            assertSame(first, faultStatuses[0].fault)
            assertSame(second, faultStatuses[1].fault)
            assertTrue(faultStatuses.all { it.stateTransition })
            assertTrue(faultStatuses[0].detail.contains("fault_passes=1"))
            assertTrue(faultStatuses[1].detail.contains("fault_passes=3"))
        } finally {
            worker.stopAndJoin(1_000)
            keepBlocked.countDown()
        }
    }

    @Test fun `intake stop suppresses a late transport exception and permits restart after join`() {
        val firstDrainEntered = CountDownLatch(1)
        val firstDrainInterrupted = CountDownLatch(1)
        val releaseLateFailure = CountDownLatch(1)
        val secondDrainEntered = CountDownLatch(1)
        val drainCalls = AtomicInteger()
        val stoppedCalls = AtomicInteger()
        val statuses = CopyOnWriteArrayList<CallbackIntakeStatus>()
        val worker = CallbackIntakeWorker(
            threadName = "test-callback-cancel",
            drain = {
                if (drainCalls.getAndIncrement() == 0) {
                    firstDrainEntered.countDown()
                    try {
                        releaseLateFailure.await()
                    } catch (_: InterruptedException) {
                        firstDrainInterrupted.countDown()
                        releaseLateFailure.await()
                    }
                    throw IOException("binder returned after cancellation")
                }
                secondDrainEntered.countDown()
                emptyResult()
            },
            onStatus = { statuses.add(it) },
            onStopped = { stoppedCalls.incrementAndGet() }
        )

        assertTrue(worker.start())
        await(firstDrainEntered, "first drain did not begin")
        assertFalse(worker.stopAndJoin(1))
        await(firstDrainInterrupted, "stop did not interrupt the first drain")
        assertFalse(worker.isRunning())
        assertTrue(worker.isStopping())
        assertFalse(worker.start(), "a live stopping worker must not be replaced")
        releaseLateFailure.countDown()
        assertTrue(worker.stopAndJoin(2_000))
        assertFalse(statuses.any { it.condition == CallbackIntakeCondition.FAULT })
        assertEquals(1, stoppedCalls.get())

        assertTrue(worker.start())
        await(secondDrainEntered, "worker did not restart after the prior thread exited")
        assertTrue(worker.stopAndJoin(2_000))
        assertEquals(2, stoppedCalls.get())
    }

    @Test fun `intake reports last observed head age and clears it only after empty`() {
        val calls = AtomicInteger()
        val statuses = CopyOnWriteArrayList<CallbackIntakeStatus>()
        val observedEmpty = CountDownLatch(1)
        val keepWorker = CountDownLatch(1)
        val worker = CallbackIntakeWorker(
            threadName = "test-callback-head-age",
            drain = {
                when (calls.getAndIncrement()) {
                    0 -> progressResult(
                        oldestObservedWallMs = 1_000L,
                        lastRawCommitWallMs = 1_100L,
                        lastProgressWallMs = 1_200L
                    )
                    1 -> pendingResult(status = -915, detail = "retry unknown")
                    else -> emptyResult()
                }
            },
            onStatus = { statuses.add(it) },
            sleepMs = { _ ->
                if (calls.get() >= 3) {
                    observedEmpty.countDown()
                    keepWorker.await()
                }
            },
            wallTimeMs = { 5_000L }
        )

        try {
            assertTrue(worker.start())
            await(observedEmpty, "worker did not report the confirmed empty queue")

            val progress = statuses[0]
            assertEquals(1_000L, progress.lastObservedHeadWallMs)
            assertEquals(4_000L, progress.lastObservedHeadAgeMs)
            assertEquals(1_100L, progress.lastRawCommitWallMs)
            assertEquals(1_200L, progress.lastProgressWallMs)
            assertTrue(progress.detail.contains("last_observed_head_age_ms=4000"))

            val pending = statuses[1]
            assertEquals(1_000L, pending.lastObservedHeadWallMs, "retry status retains the last known head")
            assertEquals(4_000L, pending.lastObservedHeadAgeMs)
            assertEquals(1_100L, pending.lastRawCommitWallMs)
            assertEquals(1_200L, pending.lastProgressWallMs)

            val empty = statuses[2]
            assertTrue(empty.stateTransition)
            assertNull(empty.lastObservedHeadWallMs)
            assertNull(empty.lastObservedHeadAgeMs)
            assertEquals(1_100L, empty.lastRawCommitWallMs)
            assertEquals(1_200L, empty.lastProgressWallMs)
        } finally {
            worker.stopAndJoin(1_000)
            keepWorker.countDown()
        }
    }

    @Test fun `normalizer scans on start and coalesces wakeups without losing pages`() {
        val pageCalls = AtomicInteger()
        val firstPageEntered = CountDownLatch(1)
        val releaseFirstPage = CountDownLatch(1)
        val afterCoalescedSignals = CountDownLatch(1)
        val afterRestart = CountDownLatch(1)
        val worker = CallbackNormalizationWorker(
            threadName = "test-callback-normalizer-signal",
            drainPage = {
                when (pageCalls.incrementAndGet()) {
                    1 -> {
                        firstPageEntered.countDown()
                        releaseFirstPage.await()
                        true
                    }
                    2 -> false
                    3 -> { afterCoalescedSignals.countDown(); false }
                    4 -> { afterRestart.countDown(); false }
                    else -> false
                }
            }
        )

        try {
            assertTrue(worker.start())
            await(firstPageEntered, "start did not scan existing receipts")
            repeat(1_000) { worker.signal() }
            releaseFirstPage.countDown()
            await(afterCoalescedSignals, "coalesced wakeup was lost")
            assertEquals(3, pageCalls.get(), "one initial scan, continuation page, and one coalesced rescan expected")
            assertTrue(worker.stopAndJoin(2_000))

            assertTrue(worker.start())
            await(afterRestart, "restart did not scan existing receipts")
        } finally {
            releaseFirstPage.countDown()
            worker.stopAndJoin(2_000)
        }
        assertEquals(4, pageCalls.get())
    }

    @Test fun `normalizer retries failures with capped delay and reports distinct causes`() {
        val repeated = IllegalStateException("database locked")
        val distinct = IOException("database unavailable")
        val calls = AtomicInteger()
        val retries = CopyOnWriteArrayList<Long>()
        val reported = CopyOnWriteArrayList<Throwable>()
        val complete = CountDownLatch(1)
        val worker = CallbackNormalizationWorker(
            threadName = "test-callback-normalizer-retry",
            drainPage = {
                when (calls.getAndIncrement()) {
                    0, 1 -> throw repeated
                    2, 3 -> throw distinct
                    else -> { complete.countDown(); false }
                }
            },
            onFault = { reported.add(it) },
            sleepMs = { retries.add(it) }
        )

        try {
            assertTrue(worker.start())
            await(complete, "normalizer did not retry through persistent failures")
            assertEquals(listOf(100L, 250L, 500L, 1_000L), retries.toList())
            assertEquals(listOf(repeated, distinct), reported.toList())
        } finally {
            assertTrue(worker.stopAndJoin(2_000))
        }
    }

    @Test fun `normalizer suppresses a post-stop failure and restarts with a new scan`() {
        val firstPageEntered = CountDownLatch(1)
        val firstPageInterrupted = CountDownLatch(1)
        val releaseLateFailure = CountDownLatch(1)
        val restartScan = CountDownLatch(1)
        val calls = AtomicInteger()
        val reported = CopyOnWriteArrayList<Throwable>()
        val worker = CallbackNormalizationWorker(
            threadName = "test-callback-normalizer-cancel",
            drainPage = {
                if (calls.getAndIncrement() == 0) {
                    firstPageEntered.countDown()
                    try {
                        releaseLateFailure.await()
                    } catch (_: InterruptedException) {
                        firstPageInterrupted.countDown()
                        releaseLateFailure.await()
                    }
                    throw IOException("database returned after cancellation")
                }
                restartScan.countDown()
                false
            },
            onFault = { reported.add(it) }
        )

        assertTrue(worker.start())
        await(firstPageEntered, "initial normalization scan did not start")
        assertFalse(worker.stopAndJoin(1))
        await(firstPageInterrupted, "stop did not interrupt normalization")
        assertFalse(worker.isRunning())
        assertTrue(worker.isStopping())
        assertFalse(worker.start(), "a live stopping worker must not be replaced")
        releaseLateFailure.countDown()
        assertTrue(worker.stopAndJoin(2_000))
        assertTrue(reported.isEmpty(), "post-stop error must not be reported as a normalization fault")

        assertTrue(worker.start())
        await(restartScan, "restart did not scan existing receipts")
        assertTrue(worker.stopAndJoin(2_000))
    }

    @Test fun `both streams commit five thousand arrivals while peer and normalization are blocked`() {
        val batchCount = 5_000
        val mainArrivals = ArrayBlockingQueue<Int>(32)
        val secondaryArrivals = ArrayBlockingQueue<Int>(32)
        val committedRaw = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val mainCommitted = AtomicInteger()
        val secondaryCommitted = AtomicInteger()
        val normalized = AtomicInteger()
        val mainComplete = CountDownLatch(1)
        val secondaryComplete = CountDownLatch(1)
        val mainProducerDone = CountDownLatch(1)
        val secondaryProducerDone = CountDownLatch(1)
        val secondaryBlocked = CountDownLatch(1)
        val releaseSecondary = CountDownLatch(1)
        val normalizationBlocked = CountDownLatch(1)
        val releaseNormalization = CountDownLatch(1)
        val normalizationComplete = CountDownLatch(1)
        val normalizationIdle = CountDownLatch(1)
        val firstNormalizationPage = AtomicBoolean(true)
        val firstSecondaryBatch = AtomicBoolean(true)

        val normalizer = CallbackNormalizationWorker(
            threadName = "test-callback-normalizer-load",
            drainPage = {
                if (firstNormalizationPage.compareAndSet(true, false)) {
                    normalizationBlocked.countDown()
                    releaseNormalization.await()
                }
                var pageCount = 0
                while (pageCount < 128) {
                    if (committedRaw.poll() == null) break
                    pageCount++
                    if (normalized.incrementAndGet() == batchCount * 2) normalizationComplete.countDown()
                }
                val hasMore = committedRaw.isNotEmpty()
                if (!hasMore) normalizationIdle.countDown()
                hasMore
            }
        )

        fun producer(name: String, queue: ArrayBlockingQueue<Int>, done: CountDownLatch) =
            Thread({
                try {
                    repeat(batchCount) { queue.put(it) }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    done.countDown()
                }
            }, name).apply { isDaemon = true; start() }

        val mainProducer = producer("test-main-callback-producer", mainArrivals, mainProducerDone)
        val secondaryProducer = producer("test-secondary-callback-producer", secondaryArrivals, secondaryProducerDone)
        val mainWorker = CallbackIntakeWorker(
            threadName = "test-main-callback-intake",
            drain = {
                val batch = mainArrivals.poll()
                if (batch == null) emptyResult() else {
                    committedRaw.add(batch)
                    normalizer.signal()
                    if (mainCommitted.incrementAndGet() == batchCount) mainComplete.countDown()
                    progressResult()
                }
            }
        )
        val secondaryWorker = CallbackIntakeWorker(
            threadName = "test-secondary-callback-intake",
            drain = {
                val batch = secondaryArrivals.poll()
                if (batch == null) emptyResult() else {
                    if (firstSecondaryBatch.compareAndSet(true, false)) {
                        secondaryBlocked.countDown()
                        releaseSecondary.await()
                    }
                    committedRaw.add(batch)
                    normalizer.signal()
                    if (secondaryCommitted.incrementAndGet() == batchCount) secondaryComplete.countDown()
                    progressResult()
                }
            }
        )

        try {
            assertTrue(normalizer.start())
            await(normalizationBlocked, "normalizer did not begin its blocked page")
            assertTrue(mainWorker.start())
            assertTrue(secondaryWorker.start())
            await(secondaryBlocked, "secondary stream did not reach its blocking operation")

            await(mainComplete, "Main stream did not drain all arrivals independently")
            assertEquals(batchCount, mainCommitted.get())
            assertEquals(0, secondaryCommitted.get(), "blocked Secondary must not hold Main intake")
            assertEquals(batchCount, committedRaw.size, "blocked normalization must not block raw commits")
            assertEquals(0, normalized.get(), "normalizer gate should still be holding all receipts")

            releaseSecondary.countDown()
            await(secondaryComplete, "Secondary stream did not drain after its gate opened")
            await(mainProducerDone, "Main arrivals did not finish")
            await(secondaryProducerDone, "Secondary arrivals did not finish")
            assertEquals(batchCount * 2, committedRaw.size)

            releaseNormalization.countDown()
            await(normalizationComplete, "normalizer did not process every committed event")
            await(normalizationIdle, "normalizer did not reach an empty receipt page")
            assertEquals(batchCount * 2, normalized.get())
        } finally {
            releaseSecondary.countDown()
            releaseNormalization.countDown()
            mainWorker.stopAndJoin(2_000)
            secondaryWorker.stopAndJoin(2_000)
            normalizer.stopAndJoin(2_000)
            mainProducer.interrupt()
            secondaryProducer.interrupt()
            mainProducer.join(2_000)
            secondaryProducer.join(2_000)
        }
    }

    private fun await(latch: CountDownLatch, message: String) {
        assertTrue(latch.await(20, TimeUnit.SECONDS), message)
    }

    private fun emptyResult() = CallbackDrainResult(
        drained = true,
        persistedEvents = 0,
        replayedEvents = 0,
        duplicateBatches = 0,
        quarantinedBatches = 0,
        kind = CallbackDrainKind.EMPTY
    )

    private fun progressResult(
        oldestObservedWallMs: Long? = null,
        lastRawCommitWallMs: Long? = null,
        lastProgressWallMs: Long? = null
    ) = CallbackDrainResult(
        drained = false,
        persistedEvents = 1,
        replayedEvents = 0,
        duplicateBatches = 0,
        quarantinedBatches = 0,
        kind = CallbackDrainKind.PROGRESS,
        oldestObservedWallMs = oldestObservedWallMs,
        lastRawCommitWallMs = lastRawCommitWallMs,
        lastProgressWallMs = lastProgressWallMs
    )

    private fun pendingResult(status: Int, detail: String) = CallbackDrainResult(
        drained = false,
        persistedEvents = 0,
        replayedEvents = 0,
        duplicateBatches = 0,
        quarantinedBatches = 0,
        blockedReason = detail,
        retryable = true,
        status = status,
        kind = CallbackDrainKind.PENDING
    )

    private fun faultResult(error: Throwable) = CallbackDrainResult(
        drained = false,
        persistedEvents = 0,
        replayedEvents = 0,
        duplicateBatches = 0,
        quarantinedBatches = 0,
        blockedReason = error.message,
        retryable = true,
        fault = error,
        kind = CallbackDrainKind.FAULT
    )
}
