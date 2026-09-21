package com.bydcollector.collector.data.local

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InfluxCursorInitializerTest {
    @Test
    fun `known fields cause no repeated initialization and existing positions survive`() {
        val cache = InfluxCursorInitializer()
        val fields = (1..105).map { "field_$it" }.toSet()
        val cursors = mutableMapOf("field_1" to 41L)
        var inserts = 0
        val initialize: (String) -> Unit = { field ->
            inserts++
            cursors.putIfAbsent(field, 0L)
        }

        cache.ensure(fields, initialize)
        assertEquals(105, inserts)
        inserts = 0
        repeat(6) { cache.ensure(fields, initialize) }
        assertEquals(0, inserts)
        assertEquals(41L, cursors["field_1"])
    }

    @Test
    fun `new categories initialize only missing fields`() {
        val cache = InfluxCursorInitializer()
        val inserted = mutableListOf<String>()
        cache.ensure(setOf("soc")) { inserted += it }
        cache.ensure(setOf("soc", "speed_kmh")) { inserted += it }
        cache.ensure(emptySet()) { error("empty set must not initialize") }
        assertEquals(listOf("soc", "speed_kmh"), inserted)
    }

    @Test
    fun `failed initialization retries without repeating earlier successful fields`() {
        val cache = InfluxCursorInitializer()
        val attempts = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            cache.ensure(linkedSetOf("soc", "speed_kmh")) {
                attempts += it
                if (it == "speed_kmh") error("insert not confirmed")
            }
        }
        cache.ensure(setOf("soc", "speed_kmh")) { attempts += it }
        cache.ensure(setOf("speed_kmh")) { error("already ensured") }
        assertEquals(listOf("soc", "speed_kmh", "speed_kmh"), attempts)
    }

    @Test
    fun `replacement store and close invalidation never reuse old initialization`() {
        val first = InfluxCursorInitializer()
        var inserts = 0
        first.ensure(setOf("soc")) { inserts++ }
        first.clear()
        first.ensure(setOf("soc")) { inserts++ }
        InfluxCursorInitializer().ensure(setOf("soc")) { inserts++ }
        assertEquals(3, inserts)
    }

    @Test
    fun `concurrent callers initialize each field once`() {
        val cache = InfluxCursorInitializer()
        val fields = (1..105).map { "field_$it" }.toSet()
        val inserts = AtomicInteger()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..12).map {
                executor.submit { cache.ensure(fields) { inserts.incrementAndGet() } }
            }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(105, inserts.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
