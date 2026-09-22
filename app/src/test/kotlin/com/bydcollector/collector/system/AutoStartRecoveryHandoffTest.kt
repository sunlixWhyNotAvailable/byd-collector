package com.bydcollector.collector.system

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoStartRecoveryHandoffTest {
    @Test
    fun slowReadinessDoesNotBlockSubmissionOrLoseTheRecoveryRequest() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var recovered: AutoStartRecoveryRequest? = null
        try {
            val runner = AutoStartRecoveryRunner(executor) { request ->
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                recovered = request
            }
            val request = AutoStartRecoveryRequest("boot", 3)

            assertTrue(runner.submit(request, finished::countDown))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(20, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals(request, recovered)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun rejectedWorkerLeavesCompletionAvailableForForegroundFallback() {
        var finished = false
        val runner = AutoStartRecoveryRunner(
            Executor { throw RejectedExecutionException("injected") }
        ) { error("must not run") }

        assertFalse(runner.submit(AutoStartRecoveryRequest("boot", 0)) { finished = true })
        assertFalse(finished)
    }

}
