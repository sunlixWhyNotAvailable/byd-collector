package com.bydcollector.collector.system

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.FutureTask
import com.bydcollector.collector.service.awaitShutdownWorkers
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserShutdownContractTest {

    @Test
    fun shutdownReadinessUsesActualOutcomesAndOneDeadlineForEveryWorker() {
        fun completed(value: Boolean) = FutureTask { value }.apply { run() }
        val neverStarted = FutureTask { true }
        val failed = FutureTask<Boolean> { error("flush failed") }.apply { run() }
        var clockReads = 0
        val elapsed = { clockReads++; 100L }
        assertTrue(awaitShutdownWorkers(listOf(completed(true), completed(true)), 100L, elapsed))
        clockReads = 0
        assertFalse(awaitShutdownWorkers(listOf(completed(false), completed(true), neverStarted), 100L, elapsed))
        kotlin.test.assertEquals(3, clockReads) // A false result must not skip the other workers.
        assertFalse(awaitShutdownWorkers(listOf(failed), 100L, elapsed))
        assertFalse(awaitShutdownWorkers(listOf(null), 100L, elapsed))

        val start = System.nanoTime() / 1_000_000
        assertFalse(awaitShutdownWorkers(listOf(neverStarted, FutureTask { true }), start + 30) {
            System.nanoTime() / 1_000_000
        })
        assertTrue(System.nanoTime() / 1_000_000 - start < 1_000L)
    }

    @Test
    fun serializedStopRunsAfterCurrentWorkAndDoesNotDeadlockOnItsOwnWorker() {
        val releaseCurrent = CountDownLatch(1)
        val currentStarted = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "byd-influx") }
        try {
            executor.execute {
                currentStarted.countDown()
                releaseCurrent.await()
            }
            assertTrue(currentStarted.await(1, TimeUnit.SECONDS))
            var result = false
            val waiter = thread {
                result = com.bydcollector.collector.service.awaitSerializedExecutorAction(
                    executor,
                    "byd-influx",
                    2_000L
                ) { stopped.set(true) }
            }
            assertFalse(stopped.get())
            releaseCurrent.countDown()
            waiter.join(2_000L)
            assertFalse(waiter.isAlive)
            assertTrue(result)
            assertTrue(stopped.get())

            stopped.set(false)
            val sameWorker = executor.submit<Boolean> {
                com.bydcollector.collector.service.awaitSerializedExecutorAction(
                    executor,
                    "byd-influx",
                    100L
                ) { stopped.set(true) }
            }
            assertTrue(sameWorker.get(1, TimeUnit.SECONDS))
            assertTrue(stopped.get())
        } finally {
            releaseCurrent.countDown()
            executor.shutdownNow()
        }
    }

}
