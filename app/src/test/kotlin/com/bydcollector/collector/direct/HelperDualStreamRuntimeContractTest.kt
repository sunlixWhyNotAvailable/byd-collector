package com.bydcollector.collector.direct

import java.util.ArrayDeque
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

class HelperDualStreamRuntimeContractTest {
    @Test
    fun schedulerRunsMainBeforeQueuedSecondaryOnOwnerThread() {
        val ownerQueue = ArrayDeque<Runnable>()
        val order = mutableListOf<String>()
        val scheduler = HelperDualStreamRuntime.VendorReadScheduler { runnable ->
            synchronized(ownerQueue) {
                ownerQueue += runnable
            }
        }
        val secondary = thread(name = "secondary-caller", isDaemon = true) {
            scheduler.runForTest(false) { order += "secondary@${Thread.currentThread().name}" }
        }
        val main = thread(name = "main-caller", isDaemon = true) {
            scheduler.runForTest(true) { order += "main@${Thread.currentThread().name}" }
        }

        val ownerThread = Thread.currentThread().name
        try {
            // Both calls must reach the scheduler before its first owner drain runs.
            val queuedDeadline = System.nanoTime() + 5_000_000_000L
            while ((secondary.state != Thread.State.WAITING || main.state != Thread.State.WAITING) &&
                System.nanoTime() < queuedDeadline) Thread.yield()
            assertTrue(secondary.state == Thread.State.WAITING && main.state == Thread.State.WAITING)
            val drainedDeadline = System.nanoTime() + 5_000_000_000L
            while ((secondary.isAlive || main.isAlive) && System.nanoTime() < drainedDeadline) {
                val task = synchronized(ownerQueue) { ownerQueue.pollFirst() }
                if (task != null) task.run() else Thread.yield()
            }
            assertTrue(!secondary.isAlive && !main.isAlive, "scheduler callers did not finish")
        } finally {
            scheduler.close()
            secondary.join(1_000)
            main.join(1_000)
        }

        assertTrue(order.first() == "main@$ownerThread", order.toString())
        assertTrue(order.last() == "secondary@$ownerThread", order.toString())
    }

}
