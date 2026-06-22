package com.bydcollector.collector.keepalive

object KeepAliveShellPlanner {
    fun mirrorSettingsCommands(config: KeepAliveConfig): List<String> {
        return listOf(
            "settings put global bydcollector_keep_wifi ${flag(config.keepWifi)}",
            "settings put global bydcollector_keep_mobile_data ${flag(config.keepMobileData)}",
            "settings put global bydcollector_keep_bluetooth ${flag(config.keepBluetooth)}",
            "settings put global bydcollector_recover_collector_service ${flag(config.recoverCollectorService)}"
        )
    }

    fun daemonStatusCommand(): String = "pidof bydcollector_keepalive"

    fun daemonStatusRetryCommand(): String =
        "for i in 1 2 3; do pidof bydcollector_keepalive && exit 0; sleep 1; done; exit 1"

    fun daemonLogTailCommand(): String =
        "tail -n 40 /data/local/tmp/bydcollector_keepalive.log 2>/dev/null || true"

    fun daemonStopCommand(): String =
        "pidof bydcollector_keepalive >/dev/null 2>&1 && kill -TERM \$(pidof bydcollector_keepalive) 2>/dev/null || true"

    fun daemonLaunchCommand(apkPath: String): String {
        val quotedApk = shellQuote(apkPath)
        return "pidof bydcollector_keepalive >/dev/null 2>&1 || " +
            "CLASSPATH=$quotedApk setsid app_process /system/bin --nice-name=bydcollector_keepalive " +
            "com.bydcollector.collector.keepalive.KeepAliveDaemon </dev/null " +
            ">>/data/local/tmp/bydcollector_keepalive.log 2>&1 & sleep 1"
    }

    private fun flag(value: Boolean): Int = if (value) 1 else 0

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
