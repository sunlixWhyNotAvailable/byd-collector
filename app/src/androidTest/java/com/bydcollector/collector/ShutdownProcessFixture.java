package com.bydcollector.collector;

/** Test-APK-only delegate for host-side process-identity/termination checks. No vehicle APIs. */
public final class ShutdownProcessFixture {
    public static void main(String[] args) throws InterruptedException {
        if (!android.os.Build.MODEL.startsWith("sdk_gphone") &&
            !android.os.Build.FINGERPRINT.contains("generic")) {
            throw new SecurityException("Shutdown fixture requires an emulator");
        }
        while (true) Thread.sleep(60_000L);
    }
}
