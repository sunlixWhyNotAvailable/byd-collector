package com.bydcollector.collector.keepalive;

import android.os.Process;
import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One independent, zero-backlog monitor; OEM Binder stalls must not stall radio/app recovery. */
final class CellularApnRecovery implements AutoCloseable {
    private static final long CHECK_BUDGET_MS = 5_000L;
    private final String generation = UUID.randomUUID().toString();
    private final ThreadPoolExecutor reader = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "byd-apn2-platform");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final Thread monitor = new Thread(this::monitor, "byd-apn2-monitor");
    private volatile boolean stopping;
    private volatile long attempts;
    private volatile String stage = "startup";
    private String bootId = "unavailable";

    void start() {
        monitor.setDaemon(true);
        monitor.start();
    }

    private void monitor() {
        try {
            bootId = new String(Files.readAllBytes(Paths.get(KeepAliveProtocol.KERNEL_BOOT_ID_PATH)),
                    StandardCharsets.UTF_8).trim();
        } catch (Exception error) {
            log("boot_id_error", KeepAliveDaemon.diagnosticDetail(error, "stage=apn2_boot_id"));
        }
        log("monitor_started", "uid=" + Process.myUid() + " interval_ms=" + KeepAliveProtocol.LOOP_INTERVAL_MS);
        while (!stopping && !Thread.currentThread().isInterrupted()) {
            long started = SystemClock.elapsedRealtime();
            Future<?> task = null;
            try {
                task = reader.submit(() -> {
                    try {
                        check(started + CHECK_BUDGET_MS);
                    } catch (Exception error) {
                        // Also retain failures returned after cancellation of a timed-out Binder call.
                        log("check_error", KeepAliveDaemon.diagnosticDetail(error, "stage=apn2_" + stage));
                    }
                });
                task.get(CHECK_BUDGET_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                log("check_timeout", "remote_completion_unknown=true "
                        + KeepAliveDaemon.diagnosticDetail(error, "stage=apn2_" + stage));
            } catch (RejectedExecutionException error) {
                log("check_busy", KeepAliveDaemon.diagnosticDetail(error, "stage=apn2_submit_no_queue"));
            } catch (ExecutionException error) {
                log("check_error", KeepAliveDaemon.diagnosticDetail(error, "stage=apn2_" + stage));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                if (task != null && !task.isDone()) task.cancel(true);
            }
            if (stopping || Thread.currentThread().isInterrupted()) break;
            try {
                // Count from the start, skip missed time, and never enqueue catch-up work.
                Thread.sleep(Math.max(1L, KeepAliveProtocol.LOOP_INTERVAL_MS
                        - (SystemClock.elapsedRealtime() - started)));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        reader.shutdownNow();
        log("monitor_stopped", "no_sleep_requested=true");
    }

    private void check(long deadline) throws Exception {
        stage = "shell_context";
        CellularApnPlatform platform = new CellularApnPlatform(KeepAliveDaemon.shellContext(), deadline);
        stage = "settings";
        String disabled = platform.disabledReason();
        if (disabled != null) {
            log("check_skipped", "reason=" + disabled);
            return;
        }
        stage = "vehicle_power";
        int power = platform.vehiclePower();
        if (power != 0) {
            log("check_skipped", "reason=vehicle_on power=" + power);
            return;
        }
        stage = "network";
        CellularApnPlatform.ApnState apn = platform.apn2();
        if (apn.ready) {
            log("ready", "power=0 " + apn);
            return;
        }
        stage = "display";
        CellularApnPlatform.DisplayState display = platform.display();
        log("observation", "power=0 " + apn + " " + display);
        if (!display.blackAsleep()) {
            log("wake_skipped", "reason=display_not_asleep_under_black_cover " + display);
            return;
        }

        stage = "network_recheck";
        apn = platform.apn2();
        if (apn.ready) {
            log("ready", "before_dispatch=true " + apn);
            return;
        }
        stage = "guards_recheck";
        disabled = platform.disabledReason();
        if (disabled != null) {
            log("wake_skipped", "reason=" + disabled);
            return;
        }
        power = platform.vehiclePower();
        display = platform.display();
        if (power != 0 || !display.blackAsleep()) {
            log("wake_skipped", "reason=guards_changed power=" + power + " " + display);
            return;
        }
        disabled = platform.disabledReason();
        if (disabled != null) {
            log("wake_skipped", "reason=" + disabled);
            return;
        }
        platform.checkActive();
        if (stopping) return;
        stage = "wake_dispatch";
        attempts++;
        log("wake_attempt", "power=0 reason=13 " + apn + " " + display);
        platform.wake();
        log("wake_returned", "apn2_not_yet_verified=true");
        stage = "display_verify";
        display = platform.display();
        log("display_verification", "logical_black_on=" + display.blackAwake() + " " + display
                + " apn2_verify_on_next_cycle=true");
    }

    private void log(String event, String detail) {
        KeepAliveDaemon.log("apn2_" + event + " elapsed_ms=" + SystemClock.elapsedRealtime()
                + " boot_id=" + bootId + " generation=" + generation + " pid=" + Process.myPid()
                + " attempts=" + attempts + " " + detail);
    }

    @Override public void close() {
        stopping = true;
        monitor.interrupt();
        reader.shutdownNow();
    }
}
