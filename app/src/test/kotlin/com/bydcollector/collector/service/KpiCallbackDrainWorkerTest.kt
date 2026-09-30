package com.bydcollector.collector.service

import com.bydcollector.collector.data.direct.*
import com.bydcollector.collector.direct.CollectorHelperProtocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class KpiCallbackDrainWorkerTest {
    @Test fun `failed subscription backs off instead of spinning and close cancels retry`() {
        val attempted = CountDownLatch(1)
        val attempts = AtomicInteger()
        val helper = object : FakeHelper() {
            override fun subscribeKpi(onInvalidated: (DirectKpiInvalidation) -> Unit): DirectKpiSubscriptionResult {
                attempts.incrementAndGet()
                attempted.countDown()
                return DirectKpiSubscriptionResult(-1, error = "unavailable")
            }
        }
        val worker = worker(helper)
        try {
            worker.start()
            worker.setActive(true)
            assertTrue(attempted.await(2, TimeUnit.SECONDS))
            Thread.sleep(80)
            assertEquals(1, attempts.get())
            assertTrue(worker.closeAndJoin(1_000))
            assertEquals(1, attempts.get())
        } finally { worker.closeAndJoin(1_000) }
    }

    @Test fun `burst invalidations coalesce while mailbox is blocked without invoking getters`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val sequence = AtomicLong()
        val calls = AtomicInteger()
        val observed = AtomicLong()
        val helper = object : FakeHelper() {
            override fun drainKpi(subscriptionId: Long): DirectKpiMailboxSnapshot {
                calls.incrementAndGet()
                started.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                return snapshot(sequence.get())
            }
        }
        val worker = worker(helper) { observed.set(it.sequence); delivered.countDown() }
        try {
            worker.start()
            worker.setActive(true)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            repeat(140) { n ->
                sequence.set(n + 1L)
                helper.notify(DirectKpiInvalidation(1, "boot", "helper", n + 1L))
            }
            release.countDown()
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            Thread.sleep(50)
            assertEquals(140L, observed.get())
            assertEquals(1, calls.get())
            assertTrue(worker.closeAndJoin(1_000))
        } finally { release.countDown(); worker.closeAndJoin(1_000) }
    }

    @Test fun `leaving live mode discards in flight mailbox and unsubscribes`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val unsubscribed = CountDownLatch(1)
        val deliveries = AtomicInteger()
        val helper = object : FakeHelper() {
            override fun drainKpi(subscriptionId: Long): DirectKpiMailboxSnapshot {
                started.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                return snapshot(1)
            }
            override fun unsubscribeKpi(subscriptionId: Long): DirectKpiActionResult {
                unsubscribed.countDown()
                return DirectKpiActionResult(0)
            }
        }
        val worker = worker(helper) { deliveries.incrementAndGet() }
        try {
            worker.start()
            worker.setActive(true)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            worker.setActive(false)
            release.countDown()
            assertTrue(unsubscribed.await(2, TimeUnit.SECONDS))
            assertEquals(0, deliveries.get())
            assertTrue(worker.closeAndJoin(1_000))
        } finally { release.countDown(); worker.closeAndJoin(1_000) }
    }

    private fun worker(helper: DirectVehicleHelper, consume: (DirectKpiMailboxSnapshot) -> Unit = {}) =
        KpiCallbackDrainWorker(helper, {}, consume, {}, {})

    private open class FakeHelper : DirectVehicleHelper {
        lateinit var notify: (DirectKpiInvalidation) -> Unit
        override fun isAlive() = true
        override fun read(entry: DirectFidEntry): DirectHelperReadResult = error("drain lane must not read getters")
        override fun subscribeKpi(onInvalidated: (DirectKpiInvalidation) -> Unit): DirectKpiSubscriptionResult {
            notify = onInvalidated
            return DirectKpiSubscriptionResult(0, 1, "boot", "helper", CollectorHelperProtocol.KPI_LISTENER_READY)
        }
        override fun drainKpi(subscriptionId: Long) = snapshot(0)
        override fun unsubscribeKpi(subscriptionId: Long) = DirectKpiActionResult(0)
        fun snapshot(sequence: Long) = DirectKpiMailboxSnapshot(0, 1, "boot", "helper", sequence,
            CollectorHelperProtocol.KPI_LISTENER_READY)
    }
}
