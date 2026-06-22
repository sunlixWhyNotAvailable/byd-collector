package com.bydcollector.collector.data.remote

import com.bydcollector.collector.data.local.CatalogParameter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DiPlusTemplateBuilderTest {
    @Test
    fun buildsOneReadOnlyFullCatalogTemplate() {
        val request = DiPlusTemplateBuilder().build(
            listOf(
                parameter("SOC", "电量百分比", includeDesc = true),
                parameter("Speed", "车速", includeDesc = true),
                parameter("SeatbeltRL", "二排左安全带", includeDesc = false)
            )
        )

        assertEquals(1, request.requestCount)
        assertContains(request.url, "direct://autoservice/read")
        assertContains(request.template, "SOC:{电量百分比}")
        assertContains(request.template, "Speed_desc:[车速]")
        assertContains(request.template, "SeatbeltRL:{二排左安全带}")
        assertFalse(request.template.contains("SeatbeltRL_desc"))
    }

    private fun parameter(key: String, name: String, includeDesc: Boolean): CatalogParameter {
        return CatalogParameter(
            id = 1L,
            catalogVersionId = 1L,
            sourceId = null,
            key = key,
            name = name,
            groupName = "all",
            includeDesc = includeDesc,
            note = null
        )
    }
}
