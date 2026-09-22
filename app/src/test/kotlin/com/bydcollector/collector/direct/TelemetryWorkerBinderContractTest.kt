package com.bydcollector.collector.direct

import com.bydcollector.collector.data.direct.DirectHelperOwnerMode
import com.bydcollector.collector.data.direct.DirectHelperStopResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryWorkerBinderContractTest {
    @Test
    fun unknownOwnerCannotBecomeAnAppOrSpoolOwner() {
        assertEquals(DirectHelperOwnerMode.APP,
            DirectHelperOwnerMode.fromProtocolValue(CollectorHelperProtocol.OWNER_MODE_APP))
        assertEquals(DirectHelperOwnerMode.APP_GAP_SPOOL,
            DirectHelperOwnerMode.fromProtocolValue(CollectorHelperProtocol.OWNER_MODE_APP_GAP_SPOOL))
        assertNull(DirectHelperOwnerMode.fromProtocolValue(-1))
    }

    @Test
    fun stopRequiresBothSuccessfulStatusAndOwnerAcceptance() {
        assertTrue(DirectHelperStopResult(CollectorHelperProtocol.STATUS_OK, accepted = true).ok)
        assertFalse(DirectHelperStopResult(CollectorHelperProtocol.STATUS_OK, accepted = false).ok)
        assertFalse(DirectHelperStopResult(-1, accepted = true).ok)
        assertFalse(DirectHelperStopResult(-1, accepted = false).ok)
    }
}
