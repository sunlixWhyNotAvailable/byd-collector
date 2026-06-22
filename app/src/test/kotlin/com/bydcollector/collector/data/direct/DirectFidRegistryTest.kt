package com.bydcollector.collector.data.direct

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DirectFidRegistryTest {
    @Test
    fun registryContainsAllWidePollDynamicReadFields() {
        val entries = DirectFidRegistry.entries

        assertEquals(80, entries.size)
        assertEquals(entries.size, entries.map { it.key }.distinct().size)
        assertEquals(
            entries.size,
            entries.map { Triple(it.dev, it.fid, it.tx) }.distinct().size
        )
        assertEquals("autoservice-fid-direct-20260622-curated-80-v1", DirectFidRegistry.CATALOG_VERSION)
        assertNotNull(entries.firstOrNull { it.key == "statistic_1014_1134559272_5" && it.dev == 1014 && it.fid == 1134559272 && it.tx == 5 })
        assertNotNull(entries.firstOrNull { it.key == "statistic_1014_1145045040_5" && it.dev == 1014 && it.fid == 1145045040 && it.tx == 5 })
        assertNotNull(entries.firstOrNull { it.key == "charging_charge_battery_volt" && it.dev == 1009 && it.fid == 1145045000 && it.tx == 5 })
        assertNotNull(entries.firstOrNull { it.key == "charging_charging_charge_current_not_convert" && it.dev == 1009 && it.fid == -1002438632 && it.tx == 7 })
        assertNotNull(entries.firstOrNull { it.key == "charging_1009_1231032336_5" && it.dev == 1009 && it.fid == 1231032336 && it.tx == 5 })
        assertNotNull(entries.firstOrNull { it.key == "tyre_1016_-1728052957_5" && it.dev == 1016 && it.fid == -1728052957 && it.tx == 5 })
        assertNotNull(entries.firstOrNull { it.key == "statistic_remaining_battery_power" && it.dev == 1014 && it.fid == 1148190760 && it.tx == 5 })
        assertNotNull(entries.firstOrNull { it.key == "charging_1009_89128973_5" && it.dev == 1009 && it.fid == 89128973 && it.tx == 5 })
        assertNotNull(entries.firstOrNull { it.key == "statistic_1014_877658120_5" && it.dev == 1014 && it.fid == 877658120 && it.tx == 5 })
        assertNotNull(entries.firstOrNull { it.key == "statistic_1014_1145045032_5" && it.dev == 1014 && it.fid == 1145045032 && it.tx == 5 })
        assertTrue(entries.none { it.key.startsWith("adas_") })
        assertTrue(entries.none { it.key == "charging_1009_842006544_5" })
        assertTrue(entries.none { it.key == "charging_1009_842006552_7" })
        assertTrue(entries.none { it.key == "ac_cycle_mode" })
        assertTrue(entries.none { it.key == "wiper_front_wiper_level" })
        assertEquals(DirectValueDecoder.FLOAT_RAW, entries.first { it.key == "charging_charging_charge_current_not_convert" }.decoder)
        assertTrue(entries.all { it.tx == DirectFidRegistry.TX_GET_INT || it.tx == DirectFidRegistry.TX_GET_FLOAT })
        assertTrue(entries.all { it.source.isNotBlank() })
        assertTrue(entries.all { it.prodCategory.isNotBlank() })
    }

    @Test
    fun decoderPreservesRawSentinelsButSuppressesDecodedValue() {
        val entry = DirectFidRegistry.entries.first { it.key == "statistic_highest_battery_voltage" }

        assertEquals("65535", DirectValueDecoders.rawString(65535))
        assertEquals(null, DirectValueDecoders.decode(entry, 65535))
        assertEquals("3.21", DirectValueDecoders.decode(entry, 3210))
    }

    @Test
    fun decoderParsesFloatBitsLikeBydMate() {
        val current = DirectFidRegistry.entries.first { it.key == "charging_charging_charge_current_not_convert" }
        val bits = java.lang.Float.floatToIntBits(82.5f)

        assertEquals("82.5", DirectValueDecoders.decode(current, bits))
        assertEquals(null, DirectValueDecoders.decode(current, java.lang.Float.floatToIntBits(Float.NaN)))
    }

    @Test
    fun referenceDecodersScaleBatteryTempVoltageAndTirePressure() {
        val lowestTemp = DirectFidRegistry.entries.first { it.key == "statistic_1014_1148190736_5" }
        val tirePressure = DirectFidRegistry.entries.first { it.key == "tyre_1016_-1728052956_5" }
        val soc = DirectFidRegistry.entries.first { it.key == "statistic_1014_1134559272_5" }
        val displaySoc = DirectFidRegistry.entries.first { it.key == "statistic_1014_1145045040_5" }
        val soh = DirectFidRegistry.entries.first { it.key == "statistic_1014_1145045032_5" }

        assertEquals("25", DirectValueDecoders.decode(lowestTemp, 65))
        assertEquals("260", DirectValueDecoders.decode(tirePressure, 260))
        assertEquals("87", DirectValueDecoders.decode(soc, 87))
        assertEquals(null, DirectValueDecoders.decode(soc, 255))
        assertEquals("96", DirectValueDecoders.decode(displaySoc, 96))
        assertEquals(null, DirectValueDecoders.decode(displaySoc, 255))
        assertEquals("95", DirectValueDecoders.decode(soh, 95))
        assertEquals(null, DirectValueDecoders.decode(soh, 255))
    }
}
