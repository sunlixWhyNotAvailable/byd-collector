package com.bydcollector.collector.location

import com.bydcollector.collector.data.normalized.NormalizedQuality
import com.bydcollector.collector.data.normalized.NormalizedCategory
import com.bydcollector.collector.data.normalized.NormalizedFieldCatalog
import com.bydcollector.collector.ha.HaIntegrationCategories
import com.bydcollector.collector.mqtt.HaMqttConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationNormalizerTest {
    @Test
    fun emitsLocationFieldsWithoutVehiclePollId() {
        val observations = LocationNormalizer.observations(
            GpsLocationSample("2026-08-20T12:00:00Z", 1_000L, 1_000_000_000L, "boot", "segment", 50.0, 30.0, 5.0, 10.0, null, null),
            nowMs = 2_000L
        )
        assertEquals(9, observations.size)
        assertEquals("location", observations.first().field.category.mqttKey)
        assertNull(observations.first().sourcePollId)
        assertEquals(36.0, observations.first { it.field.fieldKey == "location_speed_kmh" }.value.number)
        assertEquals(1_000.0, observations.first { it.field.fieldKey == "location_fix_age_ms" }.value.number)
        assertEquals(NormalizedQuality.OK, observations.first().quality)
        assertEquals("2026-08-20T12:00:00Z", observations.first { it.field.fieldKey == "location_quality" }.observedAt)
    }

    @Test
    fun locationCategoryIsVisibleAfterSafetyButOffByDefault() {
        assertEquals("location", NormalizedCategory.LOCATION.mqttKey)
        assertEquals(
            listOf("battery", "motion", "body", "climate", "safety", "location"),
            HaIntegrationCategories.visible
        )
        assertEquals(false, HaMqttConfig.DEFAULT_CATEGORIES.contains("location"))
        assertTrue(NormalizedFieldCatalog.fields.filter { it.category == NormalizedCategory.LOCATION }.all { it.mqttDefaultEnabled })
    }

    @Test
    fun gapMarksAllLocationFieldsMissing() {
        val gap = LocationNormalizer.gap("2026-08-20T12:00:00Z", "kernel_reboot")
        assertEquals(9, gap.size)
        assertEquals(9, gap.count { it.quality == NormalizedQuality.MISSING })
    }
}
