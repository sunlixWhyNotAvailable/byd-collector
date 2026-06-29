package com.bydcollector.collector.ha

import com.bydcollector.collector.data.normalized.NormalizedCategory

object HaIntegrationCategories {
    val visible: List<String> = listOf(
        NormalizedCategory.BATTERY.mqttKey,
        NormalizedCategory.MOTION.mqttKey,
        NormalizedCategory.BODY.mqttKey,
        NormalizedCategory.CLIMATE.mqttKey,
        NormalizedCategory.SAFETY.mqttKey
    )

    val defaults: Set<String> = setOf(
        NormalizedCategory.BATTERY.mqttKey,
        NormalizedCategory.MOTION.mqttKey,
        NormalizedCategory.BODY.mqttKey,
        NormalizedCategory.CLIMATE.mqttKey,
        NormalizedCategory.SAFETY.mqttKey
    )
}
