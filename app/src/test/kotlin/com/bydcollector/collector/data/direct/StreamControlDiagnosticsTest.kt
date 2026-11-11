package com.bydcollector.collector.data.direct

import android.app.Application
import com.bydcollector.collector.direct.CollectorHelperProtocol as P
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

/** Uses actual host ServiceManager absence and controller lifecycle; no prepared Binder response. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class StreamControlDiagnosticsTest {
    @Test fun absentServiceRetainsItsActualFailureAndShutdownIsCancellation() {
        val actual = DirectVehicleHelperClient().streamControl(P.CONTROL_CLAIM, "diagnostic", 0, 0, 0, P.STREAM_MAIN)
        assertFalse(actual.ok)
        assertFalse(actual.error.isNullOrEmpty())
        val controller = AppStreamController()
        controller.configureDesired(main = true, secondary = false)
        val failed = assertFailsWith<DirectStreamControlException> { controller.ensureReady(P.STREAM_MAIN) }
        assertEquals("claim", failed.stage)
        assertEquals(P.STREAM_MAIN, failed.stream)
        assertFalse(failed.cancelled)
        assertEquals(actual.status, failed.reply?.status)
        assertEquals(actual.error, failed.reply?.error)
        assertTrue(failed.message.orEmpty().contains(actual.error!!))
        val diagnostics = mutableListOf<String>()
        controller.setDiagnostic { _, detail -> diagnostics += detail }
        assertFalse(controller.observeControl { controller.setConsumerReady(P.STREAM_MAIN, true) })
        assertTrue(diagnostics.last().contains(actual.error!!))
        controller.releaseApp()
        val cancelled = assertFailsWith<InterruptedException> {
            controller.observeReadiness { controller.ensureReady(P.STREAM_MAIN) }
        }
        val origin = assertIs<DirectStreamControlException>(cancelled.cause)
        assertTrue(origin.cancelled)
        assertNull(origin.reply)
    }
}
