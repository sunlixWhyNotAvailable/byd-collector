package com.bydcollector.collector.data.normalized

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NormalizedStateStoreTest {
    @Test
    fun compactQualityCodesAreStableAndRoundTrip() {
        val expected = mapOf(
            NormalizedQuality.OK to 0,
            NormalizedQuality.STALE to 1,
            NormalizedQuality.MISSING to 2,
            NormalizedQuality.INVALID to 3,
            NormalizedQuality.UNSUPPORTED to 4
        )

        expected.forEach { (quality, code) ->
            assertEquals(code, quality.storageCode)
            assertEquals(quality, NormalizedQuality.fromStorageCode(code))
        }
        assertFailsWith<IllegalStateException> { NormalizedQuality.fromStorageCode(5) }
    }

    @Test
    fun compactHistoryTimeUsesEpochMillisecondsAndUtcIsoAtBoundary() {
        val epochMs = normalizedEpochMillis("2026-08-06T12:34:56.789+03:00")

        assertEquals(1_786_008_896_789L, epochMs)
        assertEquals("2026-08-06T09:34:56.789Z", normalizedIsoTime(epochMs))
    }

    @Test
    fun retiredCurrentKeyIsDeletedOnlyWhenNotActive() {
        assertEquals(
            setOf("hv_battery_current_a", "charging_state"),
            retiredNormalizedFieldKeysToDelete(activeFieldKeys = setOf("charge_current_a", "battery_power_kw"))
        )
        assertEquals(
            setOf("charging_state"),
            retiredNormalizedFieldKeysToDelete(activeFieldKeys = setOf("hv_battery_current_a"))
        )
    }

    @Test
    fun retiredCleanupDeletesOnlyCurrentRows() {
        val source = normalizedStateStoreSource()

        assertTrue(source.contains("db.delete(\"vehicle_state_current\""))
        assertTrue(source.contains("deleteIncompatibleCurrentRow(db, field)"))
        assertTrue(source.contains("storedType != field.valueType.name || storedUnit != field.unit"))
        assertFalse(
            source.contains("db.delete(\"normalized_field_catalog\""),
            "retired cleanup must preserve catalog metadata"
        )
    }

    @Test
    fun compactHistoryUsesImmutableMetadataIdentity() {
        val source = normalizedStateStoreSource()

        assertTrue(source.contains("FROM normalized_history_field_catalog"))
        assertTrue(source.contains("field_key = ? AND category = ? AND value_type = ? AND unit = ? AND source_keys = ?"))
        assertTrue(source.contains("AND normalizer_id = ? AND catalog_version = ?"))
        assertTrue(source.contains("db.insertOrThrow(\n            \"normalized_history_field_catalog\""))
    }

    private fun normalizedStateStoreSource(): String {
        return listOf(
            File("app/src/main/kotlin/com/bydcollector/collector/data/normalized/NormalizedStateStore.kt"),
            File("src/main/kotlin/com/bydcollector/collector/data/normalized/NormalizedStateStore.kt")
        ).first(File::exists).readText()
    }
}
