package com.bydcollector.collector.keepalive;

import android.os.FileObserver;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

/** POWER edges schedule checks; ON restores only a backlight previously darkened by us. */
final class CellularApnRecovery implements AutoCloseable {
    private static final long CHECK_BUDGET_MS = 5_000L;
    private final Object lock = new Object();
    private final CellularApnSchedule schedule = new CellularApnSchedule();
    private final CellularNetworkObserver networks = new CellularNetworkObserver(this::networkLost, this::log);
    private final String generation = UUID.randomUUID().toString();
    private final Thread monitor = new Thread(this::monitor, "byd-apn2-monitor");
    private final FileObserver powerObserver = new FileObserver(VehiclePowerSignal.DIRECTORY,
            FileObserver.MOVED_TO | FileObserver.DELETE) {
        @Override public void onEvent(int event, String path) {
            if (KeepAliveProtocol.SETTINGS_CHANGED_NAME.equals(path)) {
                invalidateSettings();
                return;
            }
            if (!VehiclePowerSignal.NAME.equals(path)) return;
            try { powerChanged(VehiclePowerSignal.read(bootId)); }
            catch (Exception error) {
                log("power_signal_error", KeepAliveDaemon.diagnosticDetail(error, "stage=power_signal"));
                powerChanged(-1);
            }
        }
    };
    private boolean stopping;
    private boolean settingsDirty = true;
    private long epoch;
    private volatile long attempts;
    private final OwnedShellProcess child = new OwnedShellProcess();
    private final OemWakeDiscovery discovery;
    private String bootId = "unavailable";

    CellularApnRecovery(OemWakeDiscovery discovery) { this.discovery = discovery; }

    // Covers missed file notifications without a second timer or shell ContentProvider identity.
    void heartbeat() { invalidateSettings(); }

    private void invalidateSettings() {
        synchronized (lock) {
            if (stopping) return;
            settingsDirty = true;
            lock.notifyAll();
        }
    }

    private void reconcileSettings() {
        synchronized (lock) {
            if (stopping) return;
            schedule.ownedBacklight(Files.exists(BacklightRecoveryState.PATH));
        }
        Boolean shutdown = null;
        try {
            long deadline = SystemClock.elapsedRealtime() + CHECK_BUDGET_MS;
            shutdown = KeepAliveDaemon.readGlobalFlag(KeepAliveProtocol.KEY_USER_SHUTDOWN,
                    deadline - SystemClock.elapsedRealtime());
            boolean enabled = KeepAliveDaemon.readGlobalFlag(KeepAliveProtocol.KEY_KEEP_MOBILE_DATA,
                    deadline - SystemClock.elapsedRealtime());
            synchronized (lock) {
                if (stopping || settingsDirty) return;
                schedule.settingsChanged(enabled, shutdown);
                log("settings_reconciled", schedule.describe(SystemClock.elapsedRealtime()));
            }
        } catch (Exception | LinkageError error) {
            synchronized (lock) {
                if (Boolean.TRUE.equals(shutdown)) schedule.settingsChanged(false, true);
                else schedule.settingsUnavailable();
            }
            log("settings_read_error", KeepAliveDaemon.diagnosticDetail(error, "stage=read_recovery_settings"));
        }
    }

    void start() {
        try {
            bootId = new String(Files.readAllBytes(Paths.get(KeepAliveProtocol.KERNEL_BOOT_ID_PATH)),
                    StandardCharsets.UTF_8).trim();
        } catch (Exception error) { log("boot_id_error", KeepAliveDaemon.diagnosticDetail(error, "stage=boot_id")); }
        powerObserver.startWatching();
        try {
            if (Files.exists(Paths.get(VehiclePowerSignal.DIRECTORY, VehiclePowerSignal.NAME)))
                powerChanged(VehiclePowerSignal.read(bootId));
        } catch (Exception error) { log("power_signal_error", KeepAliveDaemon.diagnosticDetail(error, "stage=initial_power_signal")); }
        monitor.setDaemon(true);
        monitor.start();
        networks.start();
    }

    private void networkLost(int reason, String detail) {
        synchronized (lock) {
            if (stopping) return;
            String before = schedule.describe(SystemClock.elapsedRealtime());
            boolean scheduled = schedule.networkLost(reason);
            log("network_lost", "trigger=" + CellularApnSchedule.reason(reason) + " scheduled=" + scheduled
                    + " " + detail + " " + before);
            if (scheduled) lock.notifyAll();
        }
    }

    private void powerChanged(int value) {
        synchronized (lock) {
            if (stopping || !schedule.powerChanged(value, SystemClock.elapsedRealtime())) return;
            epoch++;
            // Let a bounded OFF call persist its result before the ON cleanup checks ownership.
            if (value <= 0) child.cancel();
            log("power_transition", "power=" + value + " epoch=" + epoch + " source=telemetry_callback");
            lock.notifyAll();
        }
    }

    private void monitor() {
        log("monitor_started", "off_fast_interval_ms=5000 off_fast_window_ms=30000 off_interval_ms=30000");
        try {
            while (true) {
                long checkEpoch;
                int trigger;
                boolean reconcile;
                synchronized (lock) {
                    while (!stopping && !settingsDirty && schedule.dueAt() > SystemClock.elapsedRealtime()) {
                        if (schedule.dueAt() == Long.MAX_VALUE) lock.wait();
                        else lock.wait(Math.max(1L, schedule.dueAt() - SystemClock.elapsedRealtime()));
                    }
                    if (stopping) return;
                    reconcile = settingsDirty;
                    settingsDirty = false;
                    trigger = reconcile ? 0 : schedule.beginCheck(SystemClock.elapsedRealtime());
                    checkEpoch = epoch;
                    if (trigger != 0) log("check_started", "epoch=" + checkEpoch + " trigger=" + CellularApnSchedule.reason(trigger)
                            + " " + schedule.describe(SystemClock.elapsedRealtime()));
                }
                if (reconcile) { reconcileSettings(); continue; }
                if (trigger == 0) continue;
                try { runCheck(checkEpoch); }
                catch (InterruptedException error) { throw error; }
                catch (Exception error) { log("check_error", KeepAliveDaemon.diagnosticDetail(error, "stage=check_process")); }
                synchronized (lock) {
                    schedule.finishCheck(SystemClock.elapsedRealtime(), Files.exists(BacklightRecoveryState.PATH));
                }
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        finally {
            log("monitor_stopped", "no_sleep_requested=true");
        }
    }

    private void runCheck(long checkEpoch) throws Exception {
        long token;
        synchronized (lock) {
            if (stopping || settingsDirty || epoch != checkEpoch || !schedule.canCheck()) return;
            token = child.token();
        }
        WakeDiscoveryState.Selection selected = discovery.selection();
        try {
            OwnedShellProcess.Result result = child.run(token, CellularApnCheck.class.getName(),
                    "bydcollector_apn2_check", SystemClock.elapsedRealtime() + CHECK_BUDGET_MS,
                    Integer.toString(selected.reason), selected.source);
            recordOutput(checkEpoch, result.output);
            log("check_finished", "epoch=" + checkEpoch + " exit_code=" + result.exitCode);
        } catch (OwnedShellProcess.Failure error) {
            // A dispatched wake remains an attempt even if ON/cancellation interrupts confirmation.
            recordOutput(checkEpoch, error.output);
            throw error;
        }
    }

    private void recordOutput(long checkEpoch, String output) {
        for (String line : output.split("\\r?\\n")) {
            if (line.isEmpty()) continue;
            synchronized (lock) {
                if (line.startsWith("APN2 wake_attempt ")) attempts++;
                if (!stopping && epoch == checkEpoch && line.matches("APN2 power [0-4]"))
                    schedule.observePower(line.endsWith(" 0") ? 0 : 1, SystemClock.elapsedRealtime());
                log("check_output", "epoch=" + checkEpoch + " " + line);
            }
        }
    }
    private void log(String event, String detail) {
        KeepAliveDaemon.log("apn2_" + event + " elapsed_ms=" + SystemClock.elapsedRealtime()
                + " boot_id=" + bootId + " generation=" + generation + " attempts=" + attempts + " " + detail);
    }

    @Override public void close() {
        powerObserver.stopWatching();
        synchronized (lock) {
            stopping = true;
            schedule.stop();
            epoch++;
            child.cancel();
            lock.notifyAll();
        }
        monitor.interrupt();
        networks.close();
    }
}
