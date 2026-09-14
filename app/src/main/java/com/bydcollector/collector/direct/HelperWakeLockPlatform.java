package com.bydcollector.collector.direct;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.PowerManager;
import android.os.Process;

import java.lang.reflect.Method;

/** Android attribution adapter for the shell-owned autonomous helper wake lock. */
final class HelperWakeLockPlatform {
    private static final int SHELL_UID = 2000;
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final String WAKE_LOCK_TAG = "BYDCollector:autonomous_spool";

    private HelperWakeLockPlatform() {
    }

    static HelperWakeLockController.HeldLock acquireShellPartialWakeLock() throws Throwable {
        if (Process.myUid() != SHELL_UID) {
            throw new SecurityException("autonomous helper must run as shell UID 2000");
        }

        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Object thread = threadClass.getMethod("currentActivityThread").invoke(null);
        if (thread == null) thread = threadClass.getMethod("systemMain").invoke(null);
        Context systemContext = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
        ApplicationInfo shellInfo = systemContext.getPackageManager().getApplicationInfo(SHELL_PACKAGE, 0);
        if (shellInfo.uid != SHELL_UID || shellInfo.uid != Process.myUid()) {
            throw new SecurityException("shell package UID does not match this process");
        }

        Class<?> compatibilityClass = Class.forName("android.content.res.CompatibilityInfo");
        Object compatibility = compatibilityClass.getField("DEFAULT_COMPATIBILITY_INFO").get(null);
        Object loadedPackage = threadClass.getMethod(
            "getPackageInfoNoCheck",
            ApplicationInfo.class,
            compatibilityClass
        ).invoke(thread, shellInfo, compatibility);
        Method createContext = Class.forName("android.app.ContextImpl").getDeclaredMethod(
            "createAppContext",
            threadClass,
            Class.forName("android.app.LoadedApk")
        );
        createContext.setAccessible(true);
        Context shellContext = (Context) createContext.invoke(null, thread, loadedPackage);
        final Object opPackageName;
        try {
            Method getOpPackageName = Context.class.getMethod("getOpPackageName");
            opPackageName = getOpPackageName.invoke(shellContext);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("shell op-package attribution unavailable", error);
        }
        if (!SHELL_PACKAGE.equals(opPackageName)) {
            throw new SecurityException("unexpected shell context attribution");
        }

        PowerManager powerManager = shellContext.getSystemService(PowerManager.class);
        if (powerManager == null) throw new IllegalStateException("power service unavailable");
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        );
        wakeLock.setReferenceCounted(false);
        try {
            wakeLock.acquire();
            if (!wakeLock.isHeld()) throw new IllegalStateException("wake lock was not held after acquire");
        } catch (Throwable error) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (Throwable releaseError) {
                error.addSuppressed(releaseError);
            }
            throw error;
        }
        return new HelperWakeLockController.HeldLock() {
            @Override public boolean isHeld() {
                return wakeLock.isHeld();
            }

            @Override public void release() {
                if (wakeLock.isHeld()) wakeLock.release();
            }
        };
    }
}
