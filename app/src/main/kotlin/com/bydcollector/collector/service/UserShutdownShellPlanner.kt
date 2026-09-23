package com.bydcollector.collector.service

import com.bydcollector.collector.keepalive.KeepAliveShellPlanner

/** Exact-process shell operations used only by explicit user shutdown. */
internal object UserShutdownShellPlanner {
    const val HELPER_PROCESS = "bydcollector_helper"
    const val KEEP_ALIVE_PROCESS = "bydcollector_keepalive"
    const val HELPER_CLASS = "com.bydcollector.collector.direct.CollectorHelperDaemon"
    const val KEEP_ALIVE_CLASS = "com.bydcollector.collector.keepalive.KeepAliveDaemon"
    const val EVIDENCE_PREFIX = "/data/local/tmp/bydcollector_shutdown_"
    const val FINALIZER_WAIT_MS = 12_000
    const val RETIRED_MARKER = "BYDCOLLECTOR_FINALIZER_RETIRED"
    private const val GENERATION_KEY = "bydcollector_user_shutdown_generation"

    /** Mirrors suppression and sends TERM only to exact owned delegates; local settings stay untouched. */
    fun beginGracefulStopCommand(logcatOwnerEnv: String, packageName: String, apkPath: String, token: String): String {
        requireToken(token)
        require(logcatOwnerEnv.startsWith("BYDCOLLECTOR_LOGCAT_OWNER=") && !logcatOwnerEnv.contains('\n'))
        require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
        require(apkPath.startsWith("/") && !apkPath.contains('\n'))
        return buildString {
            append("umask 077; ")
            append("settings put global bydcollector_keep_wifi 0 || exit 71; ")
            append("settings put global bydcollector_keep_mobile_data 0 || exit 71; ")
            append("settings put global bydcollector_keep_bluetooth 0 || exit 71; ")
            append("settings put global bydcollector_recover_collector_service 0 || exit 71; ")
            append("settings put global bydcollector_user_shutdown 1 || exit 71; ")
            append("settings put global $GENERATION_KEY '$token' || exit 71; ")
            append(functions(logcatOwnerEnv, packageName, apkPath))
            append("shutdown_signal_named_process '$KEEP_ALIVE_PROCESS' '$KEEP_ALIVE_CLASS' TERM || exit 72; ")
            append("shutdown_signal_owned_logcat TERM || exit 73; ")
            append("echo BYDCOLLECTOR_SHUTDOWN_GRACEFUL_SIGNAL_SENT")
        }
    }

    /**
     * Force-cleans exact owned processes over the foreground ADB shell, then starts a detached
     * finalizer which re-verifies absence, force-stops this package, and writes shell-side evidence.
     */
    fun detachedFinalizerCommand(
        packageName: String,
        token: String,
        logcatOwnerEnv: String,
        apkPath: String,
        gracefulWaitMs: Long = 0L
    ): String {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
        requireToken(token)
        require(logcatOwnerEnv.startsWith("BYDCOLLECTOR_LOGCAT_OWNER=") && !logcatOwnerEnv.contains('\n'))
        require(apkPath.startsWith("/") && !apkPath.contains('\n'))
        require(gracefulWaitMs in 0..16_000L)
        val evidencePath = "$EVIDENCE_PREFIX$token.txt"
        val functionText = functions(logcatOwnerEnv, packageName, apkPath)
        val detachedScript = boundedFinalizerScript(token, detachedScript(packageName, token, evidencePath, functionText))
        return buildString {
            append("umask 077; ")
            append("command -v timeout >/dev/null 2>&1 || { echo BYDCOLLECTOR_TIMEOUT_UNAVAILABLE; exit 97; }; ")
            append(functionText)
            append("${generationCurrent(token)} || { echo BYDCOLLECTOR_USER_REOPENED_DURING_GRACE; exit 96; }; ")
            append("shutdown_app_snapshot=\$(shutdown_package_snapshot '$packageName') || exit 94; ")
            append("[ -n \"\$shutdown_app_snapshot\" ] || exit 95; export shutdown_app_snapshot; ")
            if (gracefulWaitMs > 0L) append("sleep ${shellSeconds(gracefulWaitMs)}; ")
            append("${generationCurrent(token)} || { echo BYDCOLLECTOR_USER_REOPENED_DURING_GRACE; exit 96; }; ")
            append("shutdown_forced_helper=0; shutdown_forced_keepalive=0; shutdown_forced_logcat=0; ")
            append("shutdown_has_named_process '$HELPER_PROCESS' '$HELPER_CLASS' || exit 81; ")
            append("shutdown_forced_helper=\$shutdown_named_present; ")
            append("shutdown_signal_named_process '$HELPER_PROCESS' '$HELPER_CLASS' KILL || exit 82; ")
            append("shutdown_wait_named_absent '$HELPER_PROCESS' '$HELPER_CLASS' || exit 83; ")
            append("shutdown_has_named_process '$KEEP_ALIVE_PROCESS' '$KEEP_ALIVE_CLASS' || exit 84; ")
            append("shutdown_forced_keepalive=\$shutdown_named_present; ")
            append("shutdown_signal_named_process '$KEEP_ALIVE_PROCESS' '$KEEP_ALIVE_CLASS' KILL || exit 83; ")
            append("shutdown_wait_named_absent '$KEEP_ALIVE_PROCESS' '$KEEP_ALIVE_CLASS' || exit 85; ")
            append("shutdown_owned_logcat_present || exit 86; shutdown_forced_logcat=\$shutdown_owned_logcat_found; ")
            append("shutdown_signal_owned_logcat KILL || exit 87; ")
            append("shutdown_wait_owned_logcat_absent || exit 88; ")
            append("shutdown_verify_named_absent '$HELPER_PROCESS' '$HELPER_CLASS' || exit 89; ")
            append("shutdown_verify_named_absent '$KEEP_ALIVE_PROCESS' '$KEEP_ALIVE_CLASS' || exit 90; ")
            append("shutdown_verify_owned_logcat_absent || exit 91; ")
            // Restore only after the delegate is absent, so it cannot reapply its policy.
            append("${KeepAliveShellPlanner.bluetoothProfilesRestoreCommand()} || exit 98; ")
            append("[ \"\$(settings get global bluetooth_disabled_profiles)\" = 0 ] || exit 98; ")
            append("${generationCurrent(token)} || exit 96; ")
            append("printf 'phase=queued token=$token\\n' > '$evidencePath' || exit 92; ")
            append("chmod 600 '$evidencePath' || exit 93; ")
            append("setsid sh -c ")
            append(shellQuote(detachedScript))
            append(" </dev/null >/dev/null 2>&1 & ")
            append("shutdown_finalizer_pid=\$!; [ -n \"\$shutdown_finalizer_pid\" ] || exit 94; ")
            append("for shutdown_i in 1 2 3 4 5 6 7 8 9 10; do ")
            append("grep -Fqx 'phase=started token=$token' '$evidencePath' 2>/dev/null && break; sleep 0.1; done; ")
            append("grep -Fqx 'phase=started token=$token' '$evidencePath' 2>/dev/null || exit 95; ")
            append("echo \"SHUTDOWN_FINALIZER_HANDOFF=$token helper_forced=\$shutdown_forced_helper keepalive_forced=\$shutdown_forced_keepalive logcat_forced=\$shutdown_forced_logcat\"")
        }
    }

    /** Android timeout signals its direct child only: the session leader must terminate its whole group. */
    internal fun boundedFinalizerScript(token: String, body: String): String {
        requireToken(token)
        val path = "$EVIDENCE_PREFIX$token.txt"
        val session = "trap 'kill -KILL -- -\$\$' TERM; sh -c ${shellQuote(body)} & wait \$!"
        return "umask 077; timeout -s TERM -k 1 8 setsid sh -c ${shellQuote(session)}; shutdown_exit=\$?; " +
            "if [ \"\$shutdown_exit\" != 0 ]; then printf 'result=error phase=finalizer_exit status=%s\\n' \"\$shutdown_exit\" >> '$path'; fi; " +
            "printf 'finalizer_finished=$token\\n' >> '$path'; exit \"\$shutdown_exit\""
    }

    /** Reopening must invalidate AND retire the old work before admitting a new runtime. */
    fun awaitFinalizerCommand(token: String?, cancel: Boolean): String {
        if (token != null) requireToken(token)
        return buildString {
            if (cancel) {
                append("shutdown_cancel_generation=\$(settings get global $GENERATION_KEY) || exit 121; ")
                append("case \"\$shutdown_cancel_generation\" in '${token.orEmpty()}'|null|'') ;; *) echo BYDCOLLECTOR_NEWER_SHUTDOWN_ACTIVE; exit 123 ;; esac; ")
                append("settings delete global $GENERATION_KEY >/dev/null || exit 121; ")
                append("settings put global bydcollector_user_shutdown 0 || exit 121; ")
            }
            if (token != null) {
                val path = "$EVIDENCE_PREFIX$token.txt"
                append("if [ -f '$path' ]; then shutdown_wait=0; ")
                append("while ! grep -Fqx 'finalizer_finished=$token' '$path'; do ")
                append("[ \"\$shutdown_wait\" -lt 100 ] || { cat '$path'; exit 122; }; ")
                append("sleep 0.1; shutdown_wait=\$((shutdown_wait + 1)); done; cat '$path'; fi; ")
            }
            append("echo $RETIRED_MARKER")
        }
    }

    private fun requireToken(token: String) = require(token.matches(Regex("[A-Fa-f0-9]{32}")))

    private fun generationCurrent(token: String): String =
        "[ \"\$(settings get global $GENERATION_KEY 2>/dev/null)\" = '$token' ] && " +
            "[ \"\$(settings get global bydcollector_user_shutdown 2>/dev/null)\" = 1 ]"

    private fun detachedScript(
        packageName: String,
        token: String,
        evidencePath: String,
        functions: String
    ): String = buildString {
        append("umask 077; ")
        append(functions)
        append("shutdown_evidence='$evidencePath'; shutdown_token='$token'; shutdown_package='$packageName'; ")
        append("shutdown_write_evidence() { printf '%s\\n' \"\$1\" >> \"\$shutdown_evidence\" && chmod 600 \"\$shutdown_evidence\"; }; ")
        append("shutdown_write_evidence 'phase=started token=$token' || exit 101; ")
        append("sleep 1; ")
        append("shutdown_verify_named_absent '$HELPER_PROCESS' '$HELPER_CLASS' && shutdown_verify_named_absent '$KEEP_ALIVE_PROCESS' '$KEEP_ALIVE_CLASS' && shutdown_verify_owned_logcat_absent || { shutdown_write_evidence 'result=error phase=pre_force_stop_unverified'; exit 102; }; ")
        append("shutdown_write_evidence 'phase=pre_force_stop_verified' || exit 103; ")
        append("shutdown_package_generation_matches || { shutdown_write_evidence 'result=error phase=app_generation_changed'; exit 110; }; ")
        append("${generationCurrent(token)} || { shutdown_write_evidence 'result=cancelled phase=explicit_reopen_before_force_stop'; exit 0; }; ")
        append("am force-stop '$packageName' >/dev/null 2>&1 || { shutdown_write_evidence 'result=error phase=force_stop_command_failed'; exit 104; }; ")
        append("for shutdown_i in 1 2 3 4 5; do pidof \"\$shutdown_package\" >/dev/null 2>&1 || break; sleep 0.2; done; ")
        append("if pidof \"\$shutdown_package\" >/dev/null 2>&1; then ")
        append("if ! { ${generationCurrent(token)}; }; then shutdown_write_evidence 'result=cancelled phase=explicit_reopen_after_force_stop'; exit 0; fi; ")
        append("shutdown_write_evidence 'result=error phase=app_process_still_present'; exit 105; fi; ")
        append("${generationCurrent(token)} || { shutdown_write_evidence 'result=cancelled phase=explicit_reopen_after_force_stop'; exit 0; }; ")
        // Re-scan after force-stop to catch a logcat launch that was already in flight at the first scan.
        append("shutdown_signal_owned_logcat KILL || { shutdown_write_evidence 'result=error phase=late_logcat_identity_unverified'; exit 106; }; ")
        append("shutdown_wait_owned_logcat_absent || { shutdown_write_evidence 'result=error phase=late_logcat_still_present'; exit 107; }; ")
        append("shutdown_verify_named_absent '$HELPER_PROCESS' '$HELPER_CLASS' && shutdown_verify_named_absent '$KEEP_ALIVE_PROCESS' '$KEEP_ALIVE_CLASS' && shutdown_verify_owned_logcat_absent || { shutdown_write_evidence 'result=error phase=post_force_stop_unverified'; exit 108; }; ")
        append("pidof \"\$shutdown_package\" >/dev/null 2>&1 && { shutdown_write_evidence 'result=error phase=app_generation_reappeared'; exit 112; }; ")
        append("shutdown_write_evidence 'result=verified phase=app_absent owned_helpers_absent owned_keepalive_absent owned_logcat_absent' || exit 109; ")
        append("exit 0")
    }

    private fun functions(logcatOwnerEnv: String, packageName: String, apkPath: String): String {
        val quotedOwner = shellQuote(logcatOwnerEnv)
        val quotedClasspath = shellQuote(apkPath)
        val classpathPattern = "/data/app/*/$packageName-*/base.apk|/data/app/$packageName-*/base.apk"
        return buildString {
            append("shutdown_proc_start() { [ -r \"/proc/\$1/stat\" ] || return 1; sed 's/^.*) //' \"/proc/\$1/stat\" | awk '{print \$20}'; }; ")
            append("shutdown_app_process_identity() { ")
            append("shutdown_name=\"\$1\"; shutdown_class=\"\$2\"; shutdown_pid=\"\$3\"; ")
            // ART may rename the main thread to "main"; process identity is argv/UID/exe/APK, not comm.
            append("[ -r \"/proc/\$shutdown_pid/cmdline\" ] && [ -r \"/proc/\$shutdown_pid/stat\" ] || return 1; ")
            append("shutdown_exe=\$(readlink \"/proc/\$shutdown_pid/exe\" 2>/dev/null) || return 1; ")
            append("case \"\$shutdown_exe\" in */app_process|*/app_process32|*/app_process64) ;; *) return 1 ;; esac; ")
            append("shutdown_status=\$(awk '\$1 == \"Uid:\" {print \$2}' \"/proc/\$shutdown_pid/status\" 2>/dev/null) || return 1; ")
            append("[ \"\$shutdown_status\" = \"\$(id -u 2>/dev/null)\" ] || return 1; ")
            append("shutdown_cmd=\$(tr '\\000' ' ' < \"/proc/\$shutdown_pid/cmdline\" 2>/dev/null) || return 1; ")
            append("case \" \$shutdown_cmd \" in *\" \$shutdown_class \"*|*\" --nice-name=\$shutdown_name \"*|*\" \$shutdown_name \"*) ;; *) return 1 ;; esac; ")
            append("[ -r \"/proc/\$shutdown_pid/environ\" ] || return 1; shutdown_env=\$(tr '\\000' '\\n' < \"/proc/\$shutdown_pid/environ\" 2>/dev/null) || return 1; ")
            append("shutdown_classpath=\$(printf '%s\\n' \"\$shutdown_env\" | sed -n 's/^CLASSPATH=//p') || return 1; ")
            append("case \"\$shutdown_classpath\" in $quotedClasspath|$classpathPattern) ;; *) return 1 ;; esac; return 0; }; ")
            append("shutdown_package_snapshot() { shutdown_snapshot_package=\"\$1\"; shutdown_snapshot_pids=\$(pidof \"\$shutdown_snapshot_package\" 2>/dev/null) || return 1; [ -n \"\$shutdown_snapshot_pids\" ] || return 1; for shutdown_pid in \$shutdown_snapshot_pids; do case \"\$shutdown_pid\" in ''|*[!0-9]*) return 1 ;; esac; [ -r \"/proc/\$shutdown_pid/cmdline\" ] && [ -r \"/proc/\$shutdown_pid/stat\" ] || return 1; shutdown_cmd0=\$(tr '\\000' '\\n' < \"/proc/\$shutdown_pid/cmdline\" 2>/dev/null | sed -n '1p') || return 1; case \"\$shutdown_cmd0\" in \"\$shutdown_snapshot_package\"|\"\$shutdown_snapshot_package\":*) ;; *) return 1 ;; esac; shutdown_start=\$(shutdown_proc_start \"\$shutdown_pid\") || return 1; [ -n \"\$shutdown_start\" ] || return 1; printf '%s:%s\\n' \"\$shutdown_pid\" \"\$shutdown_start\"; done | sort -n; }; ")
            append("shutdown_package_generation_matches() { shutdown_current_snapshot=\$(shutdown_package_snapshot \"\$shutdown_package\") || return 1; [ \"\$shutdown_current_snapshot\" = \"\$shutdown_app_snapshot\" ]; }; ")
            append("shutdown_logcat_identity() { ")
            append("shutdown_pid=\"\$1\"; [ -r \"/proc/\$shutdown_pid/comm\" ] && [ -r \"/proc/\$shutdown_pid/cmdline\" ] && [ -r \"/proc/\$shutdown_pid/status\" ] && [ -r \"/proc/\$shutdown_pid/stat\" ] || return 1; ")
            append("shutdown_comm=\$(cat \"/proc/\$shutdown_pid/comm\" 2>/dev/null) || return 1; [ \"\$shutdown_comm\" = logcat ] || return 1; ")
            append("shutdown_uid=\$(awk '\$1 == \"Uid:\" {print \$2}' \"/proc/\$shutdown_pid/status\" 2>/dev/null) || return 1; [ -n \"\$shutdown_uid\" ] || return 1; ")
            append("[ \"\$shutdown_uid\" = \"\$(id -u 2>/dev/null)\" ] || return 2; ")
            append("[ -r \"/proc/\$shutdown_pid/environ\" ] || return 1; ")
            append("shutdown_env=\$(tr '\\000' '\\n' < \"/proc/\$shutdown_pid/environ\" 2>/dev/null) || return 1; ")
            append("printf '%s\\n' \"\$shutdown_env\" | grep -Fxq $quotedOwner || return 2; ")
            append("shutdown_cmd0=\$(tr '\\000' '\\n' < \"/proc/\$shutdown_pid/cmdline\" 2>/dev/null | sed -n '1p') || return 1; ")
            append("case \"\$shutdown_cmd0\" in logcat|*/logcat) ;; *) return 1 ;; esac; return 0; }; ")
            append("shutdown_has_named_process() { shutdown_named_present=0; for shutdown_pid in \$(pidof \"\$1\" 2>/dev/null); do shutdown_app_process_identity \"\$1\" \"\$2\" \"\$shutdown_pid\" || return 1; shutdown_named_present=1; done; return 0; }; ")
            append("shutdown_verify_named_absent() { shutdown_has_named_process \"\$1\" \"\$2\" && [ \"\$shutdown_named_present\" = 0 ]; }; ")
            append("shutdown_wait_named_absent() { for shutdown_i in 1 2 3 4 5 6 7 8; do shutdown_verify_named_absent \"\$1\" \"\$2\" && return 0; sleep 0.1; done; return 1; }; ")
            append("shutdown_wait_owned_logcat_absent() { for shutdown_i in 1 2 3 4 5 6 7 8; do shutdown_verify_owned_logcat_absent && return 0; sleep 0.1; done; return 1; }; ")
            append("shutdown_signal_named_process() { ")
            append("shutdown_has_named_process \"\$1\" \"\$2\" || return 1; ")
            append("for shutdown_pid in \$(pidof \"\$1\" 2>/dev/null); do ")
            append("shutdown_app_process_identity \"\$1\" \"\$2\" \"\$shutdown_pid\" || return 1; ")
            append("shutdown_start=\$(shutdown_proc_start \"\$shutdown_pid\") || return 1; [ -n \"\$shutdown_start\" ] || return 1; ")
            append("shutdown_app_process_identity \"\$1\" \"\$2\" \"\$shutdown_pid\" || return 1; ")
            append("shutdown_current_start=\$(shutdown_proc_start \"\$shutdown_pid\") || return 1; [ \"\$shutdown_start\" = \"\$shutdown_current_start\" ] || return 1; ")
            append("kill -\"\$3\" \"\$shutdown_pid\" 2>/dev/null || { [ ! -d \"/proc/\$shutdown_pid\" ] || return 1; }; done; return 0; }; ")
            append("shutdown_owned_logcat_present() { shutdown_owned_logcat_found=0; for shutdown_pid in \$(pidof logcat 2>/dev/null); do ")
            append("shutdown_logcat_identity \"\$shutdown_pid\"; shutdown_identity=\$?; ")
            append("if [ \"\$shutdown_identity\" = 1 ]; then return 1; elif [ \"\$shutdown_identity\" = 0 ]; then shutdown_owned_logcat_found=1; fi; done; return 0; }; ")
            append("shutdown_verify_owned_logcat_absent() { shutdown_owned_logcat_present && [ \"\$shutdown_owned_logcat_found\" = 0 ]; }; ")
            append("shutdown_signal_owned_logcat() { ")
            append("shutdown_owned_logcat_present || return 1; ")
            append("for shutdown_pid in \$(pidof logcat 2>/dev/null); do ")
            append("shutdown_logcat_identity \"\$shutdown_pid\"; shutdown_identity=\$?; ")
            append("if [ \"\$shutdown_identity\" = 0 ]; then ")
            append("shutdown_start=\$(shutdown_proc_start \"\$shutdown_pid\") || return 1; [ -n \"\$shutdown_start\" ] || return 1; ")
            append("shutdown_logcat_identity \"\$shutdown_pid\"; [ \"\$?\" = 0 ] || return 1; ")
            append("shutdown_current_start=\$(shutdown_proc_start \"\$shutdown_pid\") || return 1; [ \"\$shutdown_start\" = \"\$shutdown_current_start\" ] || return 1; ")
            append("kill -\"\$1\" \"\$shutdown_pid\" 2>/dev/null || { [ ! -d \"/proc/\$shutdown_pid\" ] || return 1; }; fi; done; return 0; }; ")
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun shellSeconds(milliseconds: Long): String =
        "${milliseconds / 1_000}.${(milliseconds % 1_000).toString().padStart(3, '0')}"
}
