package com.bydcollector.collector.update

/** Persistent appearance settings for the short-lived update overlay. */
data class UpdateHintAppearance(
    val transparencyPercent: Int = 0,
    val cornerRadiusDp: Int = 18,
    val borderWidthDp: Int = 1,
    val borderArgb: Int = 0xFF54D898.toInt(),
    val sizePercent: Int = 100
) {
    val alpha: Float get() = 1f - transparencyPercent.coerceIn(TRANSPARENCY_RANGE) / 100f
    val scale: Float get() = sizePercent.coerceIn(SIZE_RANGE) / 100f

    fun normalized() = copy(
        transparencyPercent = transparencyPercent.coerceIn(TRANSPARENCY_RANGE),
        cornerRadiusDp = cornerRadiusDp.coerceIn(CORNER_RANGE),
        borderWidthDp = borderWidthDp.coerceIn(BORDER_RANGE),
        borderArgb = borderArgb or 0xFF000000.toInt(),
        sizePercent = sizePercent.coerceIn(SIZE_RANGE)
    )

    companion object {
        val TRANSPARENCY_RANGE = 0..100
        val CORNER_RANGE = 0..40
        val BORDER_RANGE = 0..16
        val SIZE_RANGE = 50..150
    }
}
