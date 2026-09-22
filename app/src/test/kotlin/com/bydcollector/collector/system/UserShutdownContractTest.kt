package com.bydcollector.collector.system

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserShutdownContractTest {

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
