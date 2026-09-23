package com.bydcollector.collector.direct

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CallbackTransportIsolationTest {
    @Test fun stalledDiskInEitherStreamDoesNotStopTheOtherStream() {
        for (blocked in 1..2) {
            val root = Files.createTempDirectory("callback-isolation-").toFile()
            val directories = listOf(root.resolve("main"), root.resolve("secondary"))
            val spools = directories.map { CallbackSpool.openForTest(it, 128 * 1024L) }
            val error = AtomicReference<Throwable?>()
            var publisher: Thread? = null
            try {
                CallbackSpoolBinder(spools[0], spools[1]).use { transport ->
                    synchronized(CallbackSpool.persistenceLock(directories[blocked - 1])) {
                        publisher = thread(name = "blocked-callback-publication") {
                            try {
                                assertEquals(CallbackSpool.AppendResult.SUCCESS, transport.deliver(batch(blocked), false))
                            } catch (failure: Throwable) { error.set(failure) }
                        }
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        while (publisher!!.state != Thread.State.BLOCKED && publisher!!.isAlive && System.nanoTime() < deadline) {
                            Thread.sleep(1)
                        }
                        assertEquals(Thread.State.BLOCKED, publisher!!.state)
                        val free = 3 - blocked
                        CompletableFuture.runAsync {
                            assertEquals(CallbackSpool.AppendResult.SUCCESS, transport.deliver(batch(free), true))
                            assertEquals(CallbackSpool.AppendResult.SUCCESS, transport.spill(free))
                            val spool = spools[free - 1]
                            val descriptor = checkNotNull(spool.oldest())
                            assertContentEquals(batch(free).encode(), spool.readSlice(descriptor, 0, CallbackSpool.MAX_SLICE_BYTES))
                            assertEquals(CallbackSpool.AckResult.RELEASED, spool.acknowledge(descriptor))
                        }.get(2, TimeUnit.SECONDS)
                    }
                    publisher!!.join(2_000)
                    assertFalse(publisher!!.isAlive)
                    error.get()?.let { throw it }
                    val blockedSpool = spools[blocked - 1]
                    val retained = checkNotNull(blockedSpool.oldest())
                    assertContentEquals(batch(blocked).encode(), blockedSpool.readSlice(retained, 0, CallbackSpool.MAX_SLICE_BYTES))
                }
            } finally {
                publisher?.join(2_000)
                root.deleteRecursively()
            }
        }
    }

    private fun batch(stream: Int) = TelemetryCallbackBatch("boot", "helper", stream, 1, 0,
        listOf(TelemetryCallbackBatch.Event(1, 1001, 42, TelemetryCallbackBatch.TYPE_INT,
            stream * 11, null, 10000, 2000, null, "ok")))
}
