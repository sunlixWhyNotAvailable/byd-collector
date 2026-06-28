package com.bydcollector.collector.data.normalized

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NormalizedStateStoreTest {
    @Test
    fun retiredCurrentKeyIsDeletedOnlyWhenNotActive() {
        assertEquals(
            setOf("hv_battery_current_a"),
            retiredNormalizedFieldKeysToDelete(activeFieldKeys = setOf("charge_current_a", "battery_power_kw"))
        )
        assertEquals(
            emptySet(),
            retiredNormalizedFieldKeysToDelete(activeFieldKeys = setOf("hv_battery_current_a"))
        )
    }

    @Test
    fun retiredCleanupKeepsCatalogRowsReferencedByHistory() {
        val source = normalizedStateStoreSource()

        assertTrue(source.contains("db.delete(\"vehicle_state_current\""))
        assertFalse(
            source.contains("db.delete(\"normalized_field_catalog\""),
            "retired cleanup must keep catalog rows because vehicle_state_history has a foreign key to normalized_field_catalog"
        )
    }

    private fun normalizedStateStoreSource(): String {
        return listOf(
            File("app/src/main/kotlin/com/bydcollector/collector/data/normalized/NormalizedStateStore.kt"),
            File("src/main/kotlin/com/bydcollector/collector/data/normalized/NormalizedStateStore.kt")
        ).first(File::exists).readText()
    }
}
