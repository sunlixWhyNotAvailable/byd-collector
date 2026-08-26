package com.bydcollector.collector.util

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundedUtf8ReaderTest {
    @Test
    fun boundedPrefixDoesNotReturnAnUnmatchedHighSurrogate() {
        val value = "a😀b"

        val result = readBoundedUtf8(
            ByteArrayInputStream(value.toByteArray(Charsets.UTF_8)),
            maxChars = 2
        ) ?: error("missing bounded response")

        assertEquals("a", result.text)
        assertTrue(result.truncated)
        assertTrue(result.text.none(Char::isHighSurrogate))
    }
}
