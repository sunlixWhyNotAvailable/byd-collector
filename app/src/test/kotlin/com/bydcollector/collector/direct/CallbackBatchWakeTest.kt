package com.bydcollector.collector.direct

import android.app.Application
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.bydcollector.collector.data.callback.CallbackDrainKind
import com.bydcollector.collector.data.callback.CallbackDrainResult
import com.bydcollector.collector.data.callback.CallbackIntakeWorker
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.bydcollector.collector.direct.CollectorHelperProtocol as P

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CallbackBatchWakeTest {
    @Test
    fun waitingIntakeSleepsThenDrainsEveryBatchAndGracefullyStopsWhileWaiting() = withTransport { transport ->
        val arms = LinkedBlockingQueue<Unit>()
        val imported = LinkedBlockingQueue<Int>()
        val calls = AtomicInteger()
        val worker = CallbackIntakeWorker("test-batch-wake", drain = {
            calls.incrementAndGet()
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                CallbackSpoolBinder.writeDescriptor(data, null)
                data.writeLong(0); data.writeInt(CallbackSpool.MAX_SLICE_BYTES)
                dispatch(transport, P.TX_CALLBACK_PENDING_PAGE, 1, data, reply)
                assertEquals(P.STATUS_OK, reply.readInt()); assertNull(reply.readString())
                val descriptor = CallbackSpoolBinder.readDescriptor(reply)
                reply.readLong()
                val bytes = reply.createByteArray()!!
                if (descriptor == null) result(CallbackDrainKind.EMPTY) else {
                    val batch = TelemetryCallbackBatch.decode(bytes)
                    batch.events.forEach { imported.add(it.rawBits) }
                    val ack = Parcel.obtain(); val ackReply = Parcel.obtain()
                    try {
                        CallbackSpoolBinder.writeDescriptor(ack, descriptor)
                        dispatch(transport, P.TX_CALLBACK_ACK, 1, ack, ackReply)
                        assertEquals(P.STATUS_OK, ackReply.readInt())
                        assertEquals(1, ackReply.readInt())
                    } finally { ack.recycle(); ackReply.recycle() }
                    result(CallbackDrainKind.PROGRESS)
                }
            } finally { data.recycle(); reply.recycle() }
        }, armBatchReady = { wake ->
            val listener = listener(wake)
            if (waitAction(transport, P.TX_CALLBACK_AWAIT, 1, listener)) wake()
            arms.add(Unit)
            AutoCloseable { waitAction(transport, P.TX_CALLBACK_CANCEL_WAIT, 1, listener) }
        })
        try {
            worker.start()
            assertNotNull(arms.poll(2, TimeUnit.SECONDS))
            val idleCalls = calls.get()
            Thread.sleep(750)
            assertEquals(idleCalls, calls.get(), "no 250-ms empty polling")
            repeat(3) { assertEquals(CallbackSpool.AppendResult.SUCCESS, transport.deliver(batch(1, it), true)) }
            assertEquals(listOf(0, 1, 2), List(3) { imported.poll(2, TimeUnit.SECONDS) })
            assertNotNull(arms.poll(2, TimeUnit.SECONDS))
            worker.requestStopAfterCurrentBatch()
            assertTrue(worker.awaitStopped(1_000), "graceful stop must wake a sleeping intake")
        } finally { worker.stopAndJoin(2_000) }
    }

    @Test
    fun armRechecksDiskBacklogAndOldCancellationCannotCancelAnotherStreamOrNewWaiter() = withTransport { transport ->
        val wakes = LinkedBlockingQueue<String>()
        val old = listener { wakes.add("old") }
        val current = listener { wakes.add("current") }
        val secondary = listener { wakes.add("secondary") }
        assertFalse(waitAction(transport, P.TX_CALLBACK_AWAIT, 1, old))
        assertFalse(waitAction(transport, P.TX_CALLBACK_AWAIT, 1, current))
        assertFalse(waitAction(transport, P.TX_CALLBACK_AWAIT, 2, secondary))
        waitAction(transport, P.TX_CALLBACK_CANCEL_WAIT, 1, old)
        assertEquals(CallbackSpool.AppendResult.SUCCESS, transport.deliver(batch(1, 1), false))
        assertEquals("current", wakes.poll(2, TimeUnit.SECONDS))
        assertNull(wakes.poll())
        // The arrival happened before this arm; no further producer event is needed to drain it.
        assertTrue(waitAction(transport, P.TX_CALLBACK_AWAIT, 1, old))
        assertEquals(CallbackSpool.AppendResult.SUCCESS, transport.deliver(batch(2, 2), true))
        assertEquals("secondary", wakes.poll(2, TimeUnit.SECONDS))
        assertNull(wakes.poll())
    }

    private fun listener(wake: () -> Unit) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            assertEquals(P.CALLBACK_TX_READY, code)
            data.enforceInterface(P.CALLBACK_LISTENER_DESCRIPTOR)
            assertEquals(0, data.dataAvail())
            assertEquals(IBinder.FLAG_ONEWAY, flags)
            wake()
            return true
        }
    }

    private fun waitAction(transport: CallbackSpoolBinder, code: Int, stream: Int, listener: IBinder): Boolean {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeStrongBinder(listener)
            dispatch(transport, code, stream, data, reply)
            assertEquals(P.STATUS_OK, reply.readInt()); assertNull(reply.readString())
            val ready = reply.readInt()
            assertEquals(0, reply.dataAvail())
            return ready == 1
        } finally { data.recycle(); reply.recycle() }
    }

    private fun dispatch(transport: CallbackSpoolBinder, code: Int, stream: Int, data: Parcel, reply: Parcel) {
        data.setDataPosition(0)
        assertTrue(transport.onTransact(code, stream, data, reply))
        reply.setDataPosition(0)
    }

    private fun batch(stream: Int, sequence: Int) = TelemetryCallbackBatch("boot", "helper", stream, 1, sequence.toLong(),
        listOf(TelemetryCallbackBatch.Event(sequence.toLong(), 1001, 42, TelemetryCallbackBatch.TYPE_INT,
            sequence, null, 10_000L + sequence, 2_000L + sequence, null, "callback")))

    private fun result(kind: CallbackDrainKind) = CallbackDrainResult(true, 0, 0, 0, 0, kind = kind)

    private fun withTransport(block: (CallbackSpoolBinder) -> Unit) {
        val root = Files.createTempDirectory("batch-wake").toFile()
        try {
            CallbackSpoolBinder(CallbackSpool.openForTest(File(root, "main"), 8L * 1024 * 1024),
                CallbackSpool.openForTest(File(root, "secondary"), 8L * 1024 * 1024)).use(block)
        } finally { root.deleteRecursively() }
    }
}
