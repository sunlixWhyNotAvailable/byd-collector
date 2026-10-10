package com.bydcollector.collector.keepalive;

import android.content.Context;
import android.os.Process;

import com.bydcollector.collector.direct.HelperWakeLockPlatform;

/** Read-only app_process probe for the shell-attributed radio-state APIs. */
public final class KeepAliveRadioStateProbe {
    private KeepAliveRadioStateProbe() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            if (Process.myUid() != 2000) {
                throw new SecurityException("probe must run as shell UID 2000");
            }
            Context shellContext = HelperWakeLockPlatform.createShellContext();
            if (!"com.android.shell".equals(shellContext.getOpPackageName())
                    || shellContext.getApplicationInfo().uid != 2000) {
                throw new SecurityException("probe did not obtain shell-attributed context");
            }

            Boolean wifiEnabled = KeepAliveDaemon.readWifiEnabled();
            // The zero-backlog reader may still be returning to its idle handoff after get().
            Thread.sleep(50L);
            Boolean mobileDataEnabled = KeepAliveDaemon.readMobileDataEnabled();
            System.out.println("KEEPALIVE_RADIO_STATE_PROBE"
                    + " uid=" + Process.myUid()
                    + " op_package=" + shellContext.getOpPackageName()
                    + " wifi=" + valueOrUnknown(wifiEnabled)
                    + " mobile_data=" + valueOrUnknown(mobileDataEnabled));
        } catch (Throwable error) {
            System.err.println("KEEPALIVE_RADIO_STATE_PROBE_FAILED "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
            error.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    private static String valueOrUnknown(Boolean value) {
        return value == null ? "unknown" : value.toString();
    }
}
