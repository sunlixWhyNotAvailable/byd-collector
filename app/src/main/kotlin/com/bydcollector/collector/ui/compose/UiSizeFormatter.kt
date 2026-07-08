package com.bydcollector.collector.ui.compose

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object UiSizeFormatter {
    fun bytes(bytes: Long, strings: UiStrings): String {
        if (bytes <= 0L) return "0 ${strings.megabytesUnit}"
        val megabytes = bytes / 1024.0 / 1024.0
        return if (megabytes < 1024.0) {
            "${formatNumber(megabytes)} ${strings.megabytesUnit}"
        } else {
            "${formatNumber(megabytes / 1024.0)} ${strings.gigabytesUnit}"
        }
    }

    fun gigabytes(value: Int, strings: UiStrings): String {
        return "$value ${strings.gigabytesUnit}"
    }

    private fun formatNumber(value: Double): String {
        val rounded = value.roundToLong()
        return if (abs(value - rounded) < 0.05) {
            rounded.toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }
}
