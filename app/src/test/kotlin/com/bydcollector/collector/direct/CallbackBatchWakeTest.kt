package com.bydcollector.collector.direct

import android.app.Application
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.bydcollector.collector.direct.CollectorHelperProtocol as P

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class CallbackBatchWakeTest {
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

    private fun withTransport(block: (CallbackSpoolBinder) -> Unit) {
        val root = Files.createTempDirectory("batch-wake").toFile()
        try {
            CallbackSpoolBinder(CallbackSpool.openForTest(File(root, "main"), 8L * 1024 * 1024),
                CallbackSpool.openForTest(File(root, "secondary"), 8L * 1024 * 1024)).use(block)
        } finally { root.deleteRecursively() }
    }
}
