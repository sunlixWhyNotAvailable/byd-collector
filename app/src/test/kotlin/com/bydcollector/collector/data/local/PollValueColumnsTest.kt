package com.bydcollector.collector.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PollValueColumnsTest {
    @Test
    fun directKeysWithNegativeFidsBecomeSQLiteSafeColumnNames() {
        val key = "pm2p5_1008_-1728053202_5"

        assertEquals("pm2p5_1008_neg_1728053202_5_raw", PollValueColumns.raw(key))
        assertEquals("pm2p5_1008_neg_1728053202_5_desc", PollValueColumns.desc(key))
        assertTrue(PollValueColumns.isSafeIdentifier(PollValueColumns.raw(key)))
        assertTrue(PollValueColumns.isSafeIdentifier(PollValueColumns.desc(key)))
    }
}
