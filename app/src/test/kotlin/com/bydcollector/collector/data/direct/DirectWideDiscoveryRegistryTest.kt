package com.bydcollector.collector.data.direct

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectWideDiscoveryRegistryTest {
    @Test
    fun candidatesExpandCurrentProdSeedsWithoutUsingWriteTransactions() {
        val candidates = DirectWideDiscoveryRegistry.candidates

        assertTrue(candidates.size > DirectFidRegistry.entries.size * 10)
        assertTrue(candidates.size <= DirectWideDiscoveryRegistry.MAX_CANDIDATES)
        assertEquals(candidates.size, candidates.map { Triple(it.dev, it.fid, it.tx) }.distinct().size)
        assertTrue(candidates.all { it.tx == DirectFidRegistry.TX_GET_INT || it.tx == DirectFidRegistry.TX_GET_FLOAT })
        assertTrue(candidates.any { it.dev == 1009 && it.fid == 1145045016 && it.tx == DirectFidRegistry.TX_GET_FLOAT })
        assertTrue(candidates.any { it.dev == 1001 && it.fid == 692060168 && it.tx == DirectFidRegistry.TX_GET_INT })
    }
}
