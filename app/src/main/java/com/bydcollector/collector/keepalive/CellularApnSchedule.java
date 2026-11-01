package com.bydcollector.collector.keepalive;

/** Monotonic schedule, owned under CellularApnRecovery's lock. No queued check executions. */
final class CellularApnSchedule {
    static final long FAST_INTERVAL_MS = 5_000L;
    static final long FAST_WINDOW_MS = 30_000L;
    static final long NORMAL_INTERVAL_MS = 30_000L;
    static final int STARTUP = 1, POWER = 2, APN_LOST = 4, WIFI_LOST = 8, TIMER = 16;
    private int power = -1;
    private long offStarted = -1, nextCheck;
    private int pending = STARTUP;
    private boolean checking, stopped;

    boolean powerChanged(int value, long now) {
        if (stopped || power == value) return false;
        observePower(value, now);
        pending |= POWER;
        return true;
    }

    void observePower(int value, long now) {
        if (stopped) return;
        if (value < -1 || value > 1) throw new IllegalArgumentException("Invalid POWER state: " + value);
        power = value;
        if (value > 0) {
            offStarted = -1;
            pending &= ~(APN_LOST | WIFI_LOST);
        } else if (value == 0 && offStarted < 0) {
            offStarted = now;
        }
    }

    boolean networkLost(int reason) {
        if (reason != APN_LOST && reason != WIFI_LOST) throw new IllegalArgumentException("Invalid loss reason");
        if (stopped || power != 0) return false;
        pending |= reason;
        return true;
    }

    long dueAt() {
        if (stopped || checking) return Long.MAX_VALUE;
        return pending != 0 ? 0L : nextCheck;
    }

    int beginCheck(long now) {
        if (stopped || checking || dueAt() > now) return 0;
        checking = true;
        int reason = pending == 0 ? TIMER : pending;
        pending = 0;
        return reason;
    }

    void finishCheck(long now, boolean ownedBacklightPending) {
        checking = false;
        if (stopped) return;
        if (power > 0 && !ownedBacklightPending) nextCheck = Long.MAX_VALUE;
        else if (power != 0 || offStarted < 0) nextCheck = now + NORMAL_INTERVAL_MS;
        else {
            long age = Math.max(0L, now - offStarted);
            // Skip missed slots; never replay a backlog after a slow check.
            nextCheck = age < FAST_WINDOW_MS
                    ? offStarted + (age / FAST_INTERVAL_MS + 1) * FAST_INTERVAL_MS
                    : offStarted + FAST_WINDOW_MS + ((age - FAST_WINDOW_MS) / NORMAL_INTERVAL_MS + 1) * NORMAL_INTERVAL_MS;
        }
    }

    String describe(long now) {
        long age = offStarted < 0 ? -1 : Math.max(0L, now - offStarted);
        return "power=" + power + " off_age_ms=" + age + " cadence_ms="
                + (power == 0 && age >= 0 && age < FAST_WINDOW_MS ? FAST_INTERVAL_MS : NORMAL_INTERVAL_MS)
                + " pending=" + pending + " checking=" + checking;
    }

    static String reason(int mask) {
        StringBuilder out = new StringBuilder();
        if ((mask & STARTUP) != 0) out.append("startup|");
        if ((mask & POWER) != 0) out.append("power_transition|");
        if ((mask & APN_LOST) != 0) out.append("apn2_lost|");
        if ((mask & WIFI_LOST) != 0) out.append("wifi_lost|");
        if ((mask & TIMER) != 0) out.append("timer|");
        return out.length() == 0 ? "none" : out.substring(0, out.length() - 1);
    }

    void stop() { stopped = true; pending = 0; }
}
