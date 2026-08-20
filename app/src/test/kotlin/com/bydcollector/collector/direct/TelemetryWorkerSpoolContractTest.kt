package com.bydcollector.collector.direct

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TelemetryWorkerSpoolContractTest {
    @Test
    fun identityIsStableAndRejectsIncompleteKeys() {
        val first = TelemetryWorkerSampleIdentity("boot-a", "generation-a", 42)
        val same = TelemetryWorkerSampleIdentity("boot-a", "generation-a", 42)

        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertFailsWith<IllegalArgumentException> { TelemetryWorkerSampleIdentity("", "generation-a", 42) }
        assertFailsWith<IllegalArgumentException> { TelemetryWorkerSampleIdentity("boot-a", "", 42) }
        assertFailsWith<IllegalArgumentException> { TelemetryWorkerSampleIdentity("boot-a", "generation-a", -1) }
    }

    @Test
    fun schemaUsesImmutableIdentityAndReadOnlyTransactions() {
        assertEquals(1, TelemetryWorkerSpool.SCHEMA_VERSION)
        assertTrue(
            TelemetryWorkerSpool.CREATE_SAMPLE_TABLE.contains(
                "PRIMARY KEY(boot_id, helper_generation, poll_sequence)"
            )
        )
        assertTrue(
            TelemetryWorkerSpool.CREATE_VALUE_TABLE.contains(
                "FOREIGN KEY(boot_id, helper_generation, poll_sequence)"
            )
        )
        assertTrue(TelemetryWorkerSpool.CREATE_VALUE_TABLE.contains("CHECK(tx IN (5,7))"))
        assertTrue(TelemetryWorkerSpool.CREATE_SAMPLE_TABLE.contains("acknowledged_at_ms INTEGER"))
    }

    @Test
    fun sampleRejectsDuplicateFieldIndexesAndNonReadTransactions() {
        val identity = TelemetryWorkerSampleIdentity("boot-a", "generation-a", 1)
        val first = TelemetryWorkerSpool.Value(0, CollectorHelperProtocol.AUTO_TX_INT, 1001, 11, 0, 2, null)
        val duplicate = TelemetryWorkerSpool.Value(0, CollectorHelperProtocol.AUTO_TX_FLOAT, 1013, 12, 0, 3, null)

        assertFailsWith<IllegalArgumentException> {
            TelemetryWorkerSpool.Sample(
                identity,
                "catalog-v1",
                100,
                90,
                10,
                0,
                CollectorHelperProtocol.MODE_NATIVE,
                true,
                0,
                null,
                listOf(first, duplicate)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TelemetryWorkerSpool.Value(0, 8, 1001, 11, 0, 2, null)
        }
    }
}
