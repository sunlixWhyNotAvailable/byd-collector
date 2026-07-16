package com.bydcollector.collector.keepalive

//builds the only shell commands the keep-alive supervisor is allowed to send over local adb
object KeepAliveShellPlanner {
    fun mirrorSettingsCommands(config: KeepAliveConfig, userShutdown: Boolean): List<String> {
        return listOf(
            "settings put global bydcollector_keep_wifi ${flag(config.keepWifi)}",
            "settings put global bydcollector_keep_mobile_data ${flag(config.keepMobileData)}",
            "settings put global bydcollector_keep_bluetooth ${flag(config.keepBluetooth)}",
            "settings put global bydcollector_recover_collector_service ${flag(config.recoverCollectorService)}",
            "settings put global bydcollector_user_shutdown ${flag(userShutdown)}"
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
        //uses app_process so the same apk contains the shell-side daemon class
        return "if ! pidof bydcollector_keepalive >/dev/null 2>&1; then " +
            "CLASSPATH=$quotedApk setsid app_process /system/bin --nice-name=bydcollector_keepalive " +
            "com.bydcollector.collector.keepalive.KeepAliveDaemon </dev/null " +
            ">>/data/local/tmp/bydcollector_keepalive.log 2>&1 & fi; sleep 1"
    }

    private fun flag(value: Boolean): Int = if (value) 1 else 0

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
