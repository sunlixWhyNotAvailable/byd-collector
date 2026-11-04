package com.bydcollector.collector.keepalive;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.os.FileObserver;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.IOException;
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
    private volatile boolean settingsRetry = true;
    private ContentResolver settingsResolver;
    private final ContentObserver settingsObserver = new ContentObserver(null) {
        @Override public void onChange(boolean selfChange) { invalidateSettings(); }
    };
    private long epoch;
    private volatile long attempts;
    private final OwnedShellProcess child = new OwnedShellProcess();
    private final OemWakeDiscovery discovery;
    private String bootId = "unavailable";

    CellularApnRecovery(OemWakeDiscovery discovery) { this.discovery = discovery; }

    // The existing heartbeat also covers OEM builds that reject ContentObserver registration.
    void heartbeat() { if (settingsRetry) invalidateSettings(); }

    private void invalidateSettings() {
        synchronized (lock) {
            if (stopping) return;
            settingsDirty = true;
            lock.notifyAll();
        }
    }

    private void reconcileSettings() {
        try {
            ContentResolver resolver = KeepAliveDaemon.shellContext().getContentResolver();
            if (settingsResolver == null) {
                try {
                    resolver.registerContentObserver(Settings.Global.getUriFor(KeepAliveProtocol.KEY_KEEP_MOBILE_DATA),
                            false, settingsObserver);
                    resolver.registerContentObserver(Settings.Global.getUriFor(KeepAliveProtocol.KEY_USER_SHUTDOWN),
                            false, settingsObserver);
                    settingsResolver = resolver;
                    log("settings_observer_registered", "uid=" + android.os.Process.myUid());
                } catch (Exception | LinkageError error) {
                    try { resolver.unregisterContentObserver(settingsObserver); }
                    catch (Exception | LinkageError cleanup) { error.addSuppressed(cleanup); }
                    log("settings_observer_error", KeepAliveDaemon.diagnosticDetail(error, "stage=register_settings_observer"));
                }
            }
            boolean enabled = readFlag(resolver, KeepAliveProtocol.KEY_KEEP_MOBILE_DATA);
            boolean shutdown = readFlag(resolver, KeepAliveProtocol.KEY_USER_SHUTDOWN);
            synchronized (lock) {
                if (stopping || settingsDirty) return;
                schedule.ownedBacklight(Files.exists(BacklightRecoveryState.PATH));
                schedule.settingsChanged(enabled, shutdown);
                settingsRetry = settingsResolver == null;
                log("settings_reconciled", schedule.describe(SystemClock.elapsedRealtime()));
            }
        } catch (Exception | LinkageError error) {
            synchronized (lock) {
                schedule.settingsChanged(false, true);
                settingsRetry = true;
            }
            log("settings_read_error", KeepAliveDaemon.diagnosticDetail(error, "stage=read_recovery_settings"));
        }
    }

    private static boolean readFlag(ContentResolver resolver, String key) throws IOException {
        String value = Settings.Global.getString(resolver, key);
        if ("1".equals(value)) return true;
        if ("0".equals(value)) return false;
        throw new IOException("Unknown global flag " + key + "=" + value);
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
            if (settingsResolver != null) {
                try { settingsResolver.unregisterContentObserver(settingsObserver); }
                catch (Exception | LinkageError error) {
                    log("settings_observer_error", KeepAliveDaemon.diagnosticDetail(error, "stage=unregister_settings_observer"));
                }
            }
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
