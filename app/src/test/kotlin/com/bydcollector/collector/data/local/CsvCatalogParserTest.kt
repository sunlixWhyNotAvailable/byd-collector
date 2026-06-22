package com.bydcollector.collector.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CsvCatalogParserTest {
    @Test
    fun parsesIncludeDescFlagsAndUtf8Names() {
        val rows = CsvCatalogParser.parse(
            """
            id,key,name,group,include_desc,note
            33,SOC,电量百分比,all,true,
            74,SeatbeltRL,二排左安全带,all,false,raw only
            """.trimIndent()
        )

        assertEquals(2, rows.size)
        assertEquals("电量百分比", rows[0].name)
        assertTrue(rows[0].includeDesc)
        assertFalse(rows[1].includeDesc)
    }
}
