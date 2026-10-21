package com.bydcollector.collector.keepalive

//builds the only shell commands the keep-alive supervisor is allowed to send over local adb
object KeepAliveShellPlanner {
    fun mirrorSettingsCommands(config: KeepAliveConfig, userShutdown: Boolean): List<String> {
        return listOf(
            "settings put global bydcollector_keep_wifi ${flag(config.keepWifi)}",
            "settings put global bydcollector_keep_mobile_data ${flag(config.keepMobileData)}",
            "settings put global bydcollector_keep_bluetooth ${flag(config.keepBluetooth)}",
            "settings put global bydcollector_keep_usb ${flag(config.keepUsb)}",
            "settings put global bydcollector_recover_collector_service ${flag(config.recoverCollectorService)}",
            "settings put global bydcollector_user_shutdown ${flag(userShutdown)}"
        )
    }

    fun daemonStatusCommand(): String = "pidof bydcollector_keepalive"

    fun daemonStatusRetryCommand(): String =
        "for i in 1 2 3; do pidof bydcollector_keepalive && exit 0; sleep 1; done; exit 1"

    fun daemonLogTailCommand(): String =
        "tail -n 40 /data/local/tmp/bydcollector_keepalive.log 2>/dev/null || true"

    fun bluetoothProfilesRestoreCommand(): String =
        "settings put global bluetooth_disabled_profiles 0"

    fun daemonStopCommand(): String =
        // setsid gives the daemon its own group; a PID-only stop can orphan an in-flight cmd.
        "keepalive_groups=\$(pidof bydcollector_keepalive); " +
            "for keepalive_pid in \$keepalive_groups; do " +
            "keepalive_pgid=\$(ps -o PGID= -p \"\$keepalive_pid\" | tr -d '[:space:]'); " +
            "[ \"\$keepalive_pgid\" = \"\$keepalive_pid\" ] || { echo keepalive_stop_group_unverified; exit 1; }; done; " +
            "for keepalive_pid in \$keepalive_groups; do kill -TERM -- -\$keepalive_pid 2>/dev/null; done; " +
            "for keepalive_i in 1 2 3 4; do keepalive_alive=0; " +
            "for keepalive_pid in \$keepalive_groups; do " +
            "if kill -0 -- -\$keepalive_pid 2>/dev/null; then keepalive_alive=1; " +
            "[ \"\$keepalive_i\" != 3 ] || kill -KILL -- -\$keepalive_pid 2>/dev/null; fi; done; " +
            "[ \"\$keepalive_alive\" != 0 ] || exit 0; sleep 1; done; " +
            "echo keepalive_stop_group_still_alive; exit 1"

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
