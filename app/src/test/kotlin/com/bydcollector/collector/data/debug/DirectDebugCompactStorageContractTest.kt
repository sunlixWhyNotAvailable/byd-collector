package com.bydcollector.collector.data.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectDebugCompactStorageContractTest {
    @Test
    fun rawRepresentationsAreDerivedWithoutPersistedDuplicates() {
        val minusOne = DirectDebugObserved(status = 0, rawPresent = true, raw = -1, error = null)
        val floatBits = DirectDebugObserved(
            status = 0,
            rawPresent = true,
            raw = 12.5f.toRawBits(),
            error = null
        )
        val missing = DirectDebugObserved(status = -1, rawPresent = false, raw = null, error = "missing")

        assertEquals("0xffffffff", minusOne.rawHex)
        assertEquals(12.5, floatBits.rawFloat)
        assertNull(missing.rawHex)
        assertNull(missing.rawFloat)
    }

}
