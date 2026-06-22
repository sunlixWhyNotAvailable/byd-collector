package com.bydcollector.collector.ui

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DisplayTimeFormatter {
    private val outputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun formatNullable(value: String?): String? {
        val text = value?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).format(outputFormatter)
        }.recoverCatching {
            Instant.parse(text).atZone(ZoneId.systemDefault()).format(outputFormatter)
        }.recoverCatching {
            LocalDateTime.parse(text.replace(' ', 'T')).atZone(ZoneId.systemDefault()).format(outputFormatter)
        }.getOrDefault(text)
    }
}
