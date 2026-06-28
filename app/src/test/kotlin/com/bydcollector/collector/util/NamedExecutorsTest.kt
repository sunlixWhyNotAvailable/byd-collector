package com.bydcollector.collector.util

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class NamedExecutorsTest {
    @Test
    fun createsSingleThreadWithRequestedName() {
        val executor = namedSingleThreadExecutor("byd-test")
        try {
            val name = executor.submit<String> { Thread.currentThread().name }.get(2, TimeUnit.SECONDS)
            assertEquals("byd-test", name)
        } finally {
            executor.shutdownNow()
        }
    }
}
