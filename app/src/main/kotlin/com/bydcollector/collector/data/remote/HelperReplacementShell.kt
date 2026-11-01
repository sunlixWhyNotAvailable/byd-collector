package com.bydcollector.collector.data.remote

import com.bydcollector.collector.direct.CollectorHelperProtocol

/** Shell-owned process checks do not depend on the old helper's Binder threads or locks. */
internal object HelperReplacementShell {
    private const val ABSENT = "ABSENT"
    private val ownerPattern = Regex("[0-9a-f-]{36}:[1-9][0-9]*:[0-9]+")

    fun owner(output: String): String? {
        val value = output.trim()
        if (value == ABSENT) return null
        require(ownerPattern.matches(value)) { "Invalid helper process identity: $value" }
        return value
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun functions() = """
        helper_start() { sed 's/^.*) //' "/proc/${'$'}1/stat" | awk '{print ${'$'}20}'; }
        helper_identity() {
            [ "${'$'}(awk '/^Uid:/ {print ${'$'}2 ":" ${'$'}3}' "/proc/${'$'}1/status")" = '2000:2000' ] || return 1
            [ "${'$'}(tr '\000' '\n' < "/proc/${'$'}1/cmdline" | head -n 1)" = '${CollectorHelperProtocol.PROCESS_NAME}' ] || return 1
            helper_exe=${'$'}(readlink "/proc/${'$'}1/exe") || return 1
            case "${'$'}helper_exe" in */app_process|*/app_process32|*/app_process64) ;; *) return 1 ;; esac
        }
    """.trimIndent()

    fun snapshotCommand(apkPath: String? = null): String = functions() + "\n" + """
        set -- ${'$'}(pidof ${CollectorHelperProtocol.PROCESS_NAME} 2>/dev/null)
        [ "${'$'}#" != 0 ] || { echo ABSENT; exit 0; }
        [ "${'$'}#" = 1 ] || { echo 'HELPER_MULTIPLE_OWNERS' >&2; exit 74; }
        helper_pid=${'$'}1
        helper_identity "${'$'}helper_pid" || { echo "HELPER_IDENTITY_UNVERIFIED pid=${'$'}helper_pid" >&2; exit 74; }
        helper_boot=${'$'}(cat /proc/sys/kernel/random/boot_id) || exit 74
        helper_ticks=${'$'}(helper_start "${'$'}helper_pid") || exit 74
    """.trimIndent() + "\n" + (if (apkPath == null) "" else """
        helper_apk=${'$'}(tr '\000' '\n' < "/proc/${'$'}helper_pid/environ" | sed -n 's/^CLASSPATH=//p')
        [ "${'$'}helper_apk" = ${quote(apkPath)} ] || { echo 'HELPER_APK_IDENTITY_MISMATCH' >&2; exit 74; }
    """.trimIndent() + "\n") + """
        [ -n "${'$'}helper_ticks" ] || exit 74
        printf '%s:%s:%s\n' "${'$'}helper_boot" "${'$'}helper_pid" "${'$'}helper_ticks"
    """.trimIndent()

    fun finishStopCommand(owner: String): String {
        require(ownerPattern.matches(owner))
        val (boot, pid, ticks) = owner.split(':')
        return functions() + "\n" + """
            helper_same_owner() {
                [ -d /proc/$pid ] || return 1
                [ "${'$'}(cat /proc/sys/kernel/random/boot_id)" = '$boot' ] || return 1
                [ "${'$'}(helper_start $pid)" = '$ticks' ]
            }
            helper_wait() {
                helper_wait_left=${'$'}1
                while helper_same_owner; do
                    [ "${'$'}helper_wait_left" -gt 0 ] || return 1
                    sleep 1
                    helper_wait_left=${'$'}((helper_wait_left - 1))
                done
            }
            echo 'helper_stop owner=$owner phase=drain'
            helper_wait 35 && { echo 'helper_stop result=cooperative_or_absent'; exit 0; }
            for helper_signal in TERM KILL; do
                helper_same_owner || { echo 'helper_stop result=old_owner_gone'; exit 0; }
                helper_identity $pid || { echo 'HELPER_IDENTITY_CHANGED_BEFORE_SIGNAL' >&2; exit 74; }
                helper_same_owner || { echo 'helper_stop result=old_owner_gone'; exit 0; }
                echo "helper_stop owner=$owner signal=${'$'}helper_signal ram_tail_loss_possible=true"
                if ! kill -"${'$'}helper_signal" $pid; then
                    helper_same_owner || exit 0
                    echo 'HELPER_SIGNAL_FAILED' >&2; exit 75
                fi
                helper_wait 3 && { echo "helper_stop result=terminated signal=${'$'}helper_signal"; exit 0; }
            done
            echo 'HELPER_STOP_TIMEOUT_AFTER_KILL' >&2; exit 73
        """.trimIndent()
    }
}
