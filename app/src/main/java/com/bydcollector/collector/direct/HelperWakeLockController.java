package com.bydcollector.collector.direct;

/** Owns the autonomous helper wake-lock lifecycle without depending on Android in policy tests. */
final class HelperWakeLockController {
    static final long RETRY_INTERVAL_MS = 30_000L;

    interface Clock {
        long elapsedRealtime();
    }

    interface Acquirer {
        HeldLock acquire() throws Throwable;
    }

    interface HeldLock {
        boolean isHeld() throws Throwable;
        void release() throws Throwable;
    }

    interface Logger {
        void log(String message);
    }

    private final Clock clock;
    private final Acquirer acquirer;
    private final Logger logger;
    private boolean autonomousMode;
    private long nextAttemptAtMs = Long.MIN_VALUE;
    private long nextStateErrorLogAtMs = Long.MIN_VALUE;
    private HeldLock heldLock;

    HelperWakeLockController(Clock clock, Acquirer acquirer, Logger logger) {
        this.clock = clock;
        this.acquirer = acquirer;
        this.logger = logger;
    }

    void enterAutonomousMode() {
        autonomousMode = true;
        maintain();
    }

    void maintain() {
        if (!autonomousMode) return;
        if (heldLock != null) {
            try {
                if (heldLock.isHeld()) return;
                heldLock = null;
                safeLog("WARN: autonomous helper wake lock was lost");
            } catch (Throwable error) {
                long now = clock.elapsedRealtime();
                if (now >= nextStateErrorLogAtMs) {
                    nextStateErrorLogAtMs = saturatedAdd(now, RETRY_INTERVAL_MS);
                    safeLog("WARN: autonomous helper wake lock state check failed: " + describe(error));
                }
                return;
            }
        }
        long now = clock.elapsedRealtime();
        if (now < nextAttemptAtMs) return;
        nextAttemptAtMs = saturatedAdd(now, RETRY_INTERVAL_MS);
        try {
            HeldLock acquired = acquirer.acquire();
            if (acquired == null) throw new IllegalStateException("acquirer returned no lock");
            heldLock = acquired;
            safeLog("INFO: autonomous helper wake lock acquired");
        } catch (Throwable error) {
            safeLog("WARN: autonomous helper wake lock acquire failed: " + describe(error));
        }
    }

    void exitAutonomousMode() {
        autonomousMode = false;
        HeldLock releasing = heldLock;
        if (releasing == null) return;
        try {
            releasing.release();
            heldLock = null;
            safeLog("INFO: autonomous helper wake lock released");
        } catch (Throwable error) {
            try {
                if (!releasing.isHeld()) heldLock = null;
            } catch (Throwable stateError) {
                error.addSuppressed(stateError);
            }
            safeLog("WARN: autonomous helper wake lock release failed: " + describe(error));
        }
    }

    boolean isHeld() {
        return heldLock != null;
    }

    boolean isAutonomousMode() {
        return autonomousMode;
    }

    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static String describe(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getClass().getSimpleName() + ": " +
            (current.getMessage() == null ? "no message" : current.getMessage());
    }

    private void safeLog(String message) {
        try {
            logger.log(message);
        } catch (Throwable ignored) {
            // Diagnostics must never break wake-lock maintenance or helper teardown.
        }
    }
}
