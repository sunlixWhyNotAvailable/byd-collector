package com.bydcollector.collector.util

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

internal data class BoundedUtf8Text(
    val text: String,
    val truncated: Boolean
)

internal fun readBoundedUtf8(input: InputStream?, maxChars: Int): BoundedUtf8Text? {
    if (input == null || maxChars <= 0) return null
    return InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
        val output = StringBuilder(minOf(maxChars, 512))
        val buffer = CharArray(512)
        while (output.length <= maxChars) {
            val read = reader.read(buffer, 0, minOf(buffer.size, maxChars + 1 - output.length))
            if (read <= 0) break
            output.append(buffer, 0, read)
        }
        BoundedUtf8Text(
            text = output.substring(0, minOf(output.length, maxChars)),
            truncated = output.length > maxChars
        )
    }
}
