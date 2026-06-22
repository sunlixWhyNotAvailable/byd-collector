package com.bydcollector.collector.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateVersionComparatorTest {
    @Test
    fun newerRemoteVersionBeatsLocalSuffixVersion() {
        assertTrue(UpdateVersionComparator.isNewer("v1.0.6", "1.0.5-direct-soc-dashboard"))
    }

    @Test
    fun equalVersionIsNotNewer() {
        assertFalse(UpdateVersionComparator.isNewer("v1.0.5", "1.0.5"))
    }

    @Test
    fun suffixDigitsDoNotChangeSemanticComparison() {
        assertFalse(UpdateVersionComparator.isNewer("1.0.5-direct-999", "1.0.5-direct-001"))
    }

    @Test
    fun olderRemoteVersionIsNotNewer() {
        assertFalse(UpdateVersionComparator.isNewer("0.9.9", "1.0.5-direct-soc-dashboard"))
    }
}
