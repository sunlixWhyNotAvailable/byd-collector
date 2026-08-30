package com.bydcollector.collector.diagnostics

import java.security.MessageDigest

/** Stable redaction used to correlate trip and Telegram records without exposing identifiers. */
internal fun diagnosticSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun diagnosticSafeText(value: String?, maxLength: Int = 256): String = value
    ?.replace('\r', ' ')
    ?.replace('\n', ' ')
    ?.replace('\t', ' ')
    ?.take(maxLength)
    .orEmpty()
