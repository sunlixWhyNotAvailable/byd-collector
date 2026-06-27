package com.bydcollector.collector.data.normalized

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
