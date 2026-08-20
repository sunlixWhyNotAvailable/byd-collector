package com.bydcollector.collector.util

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OperationalEventDispatcherTest {
    @Test
    fun executorQueuesAsynchronouslyInFifoOrder() {
        val executor = Executors.newSingleThreadExecutor()
        val caller = Thread.currentThread().id
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<Int>())
        var worker: Long = caller
        try {
            dispatchOperationalEvent(executor) {
                worker = Thread.currentThread().id
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
                order += 1
            }
            dispatchOperationalEvent(executor) { order += 2 }

            assertTrue(started.await(2, TimeUnit.SECONDS))
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            assertNotEquals(caller, worker)
            assertEquals(listOf(1, 2), order)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun rejectedExecutorDoesNotRunActionOrThrow() {
        val executor = Executors.newSingleThreadExecutor()
        executor.shutdownNow()
        var ran = false

        dispatchOperationalEvent(executor) { ran = true }

        assertFalse(ran)
    }
}
