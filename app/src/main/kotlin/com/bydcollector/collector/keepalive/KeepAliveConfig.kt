package com.bydcollector.collector.keepalive

data class KeepAliveConfig(
    val keepWifi: Boolean,
    val keepMobileData: Boolean,
    val keepBluetooth: Boolean,
    val recoverCollectorService: Boolean,
    val keepUsb: Boolean = false
) {
    val anyEnabled: Boolean
        get() = keepWifi || keepMobileData || keepBluetooth || recoverCollectorService || keepUsb
}
