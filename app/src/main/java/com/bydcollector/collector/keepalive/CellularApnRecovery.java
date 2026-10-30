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
    private int power = -1;
    private long epoch, nextCheck;
    private long attempts;
    private final OwnedShellProcess child = new OwnedShellProcess();
    private final OemWakeDiscovery discovery;
    private String bootId = "unavailable";

    CellularApnRecovery(OemWakeDiscovery discovery) { this.discovery = discovery; }

    void start() {
        try {
            bootId = new String(Files.readAllBytes(Paths.get(KeepAliveProtocol.KERNEL_BOOT_ID_PATH)),
                    StandardCharsets.UTF_8).trim();
        } catch (Exception error) { log("boot_id_error", KeepAliveDaemon.diagnosticDetail(error, "stage=boot_id")); }
        powerObserver.startWatching();
        monitor.setDaemon(true);
        monitor.start();
    }

    private void powerChanged(int value) {
        synchronized (lock) {
            if (stopping || power == value) return;
            power = value;
            epoch++;
            nextCheck = 0L;
            // Let a bounded OFF call persist its result before the ON cleanup checks ownership.
            if (value <= 0) child.cancel();
            log("power_transition", "power=" + value + " epoch=" + epoch + " source=telemetry_callback");
            lock.notifyAll();
        }
    }

    private void monitor() {
        log("monitor_started", "off_interval_ms=" + KeepAliveProtocol.LOOP_INTERVAL_MS);
        try {
            while (true) {
                long checkEpoch;
                synchronized (lock) {
                    while (!stopping && nextCheck > SystemClock.elapsedRealtime()) {
                        if (nextCheck == Long.MAX_VALUE) lock.wait();
                        else lock.wait(Math.max(1L, nextCheck - SystemClock.elapsedRealtime()));
                    }
                    if (stopping) return;
                    checkEpoch = epoch;
                }
                try { runCheck(checkEpoch); }
                catch (InterruptedException error) { throw error; }
                catch (Exception error) { log("check_error", KeepAliveDaemon.diagnosticDetail(error, "stage=check_process")); }
                synchronized (lock) {
                    if (epoch == checkEpoch) nextCheck = power > 0 &&
                            !Files.exists(BacklightRecoveryState.PATH) ? Long.MAX_VALUE
                            : SystemClock.elapsedRealtime() + KeepAliveProtocol.LOOP_INTERVAL_MS;
                }
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        finally { log("monitor_stopped", "no_sleep_requested=true"); }
    }

    private void runCheck(long checkEpoch) throws Exception {
        long token;
        synchronized (lock) {
            if (stopping || epoch != checkEpoch) return;
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
                    power = line.endsWith(" 0") ? 0 : 1;
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
            epoch++;
            child.cancel();
            lock.notifyAll();
        }
        monitor.interrupt();
    }
}
