package com.bydcollector.collector.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompactStorageSchemaContractTest {
    @Test
    fun rawHelperValuesRoundTripAsSignedInt() {
        assertEquals(Int.MIN_VALUE, PollReading("min", Int.MIN_VALUE.toString()).rawInt)
        assertEquals(Int.MAX_VALUE, PollReading("max", Int.MAX_VALUE.toString()).rawInt)
        assertEquals(-1, PollReading("float_bits", "-1").rawInt)
        assertNull(PollReading("missing", null).rawInt)
        assertNull(PollReading("invalid", "not-an-int").rawInt)
    }

}
