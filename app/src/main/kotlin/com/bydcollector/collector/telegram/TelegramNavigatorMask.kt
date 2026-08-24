package com.bydcollector.collector.telegram

object TelegramNavigatorMask {
    const val NONE = 0
    const val GOOGLE = 1
    const val WAZE = 2
    const val APPLE = 4
    const val OSM = 8
    const val ALL = 15

    fun sanitize(mask: Int): Int = mask and ALL
}
