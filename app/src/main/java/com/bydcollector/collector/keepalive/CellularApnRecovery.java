package com.bydcollector.collector.keepalive;

import android.os.FileObserver;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** POWER edges schedule checks; ON cancels all OFF work without stopping the observer. */
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
    private Process active;
    private int activePid;
    private String bootId = "unavailable";

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
            nextCheck = value > 0 ? Long.MAX_VALUE : 0L;
            stopActiveLocked();
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
                    if (epoch == checkEpoch) nextCheck = power > 0 ? Long.MAX_VALUE
                            : SystemClock.elapsedRealtime() + KeepAliveProtocol.LOOP_INTERVAL_MS;
                }
            }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        finally { log("monitor_stopped", "no_sleep_requested=true"); }
    }

    private void runCheck(long checkEpoch) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + CHECK_BUDGET_MS;
        Process child;
        synchronized (lock) {
            if (stopping || epoch != checkEpoch) return;
            if (active != null && active.isAlive()) {
                stopActiveLocked();
                throw new IOException("Previous APN2 process has not exited; no new process started");
            }
            ProcessBuilder builder = new ProcessBuilder("/system/bin/setsid", "/system/bin/sh", "-c",
                    "echo APN2_PID=$$; exec app_process /system/bin --nice-name=bydcollector_apn2_check \"$@\"",
                    "apn2", CellularApnCheck.class.getName(), Long.toString(deadline)).redirectErrorStream(true);
            if (System.getenv("CLASSPATH") == null) throw new IOException("APN2 child CLASSPATH is missing");
            child = builder.start();
            active = child;
            activePid = 0;
        }
        FutureTask<String> output = new FutureTask<>(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                if (header == null || !header.matches("APN2_PID=[1-9][0-9]*")) {
                    throw new IOException("APN2 process identity missing: " + header);
                }
                int pid = Integer.parseInt(header.substring("APN2_PID=".length()));
                synchronized (lock) {
                    if (!CellularApnCheck.ownsGroup(pid)) throw new IOException("APN2 child process group mismatch: " + pid);
                    if (active != child) throw new IOException("APN2 child ownership changed");
                    activePid = pid;
                    if (stopping || epoch != checkEpoch) stopActiveLocked();
                    else {
                        child.getOutputStream().write("start\n".getBytes(StandardCharsets.UTF_8));
                        child.getOutputStream().flush();
                    }
                }
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[1024];
                int count;
                boolean truncated = false;
                while ((count = reader.read(buffer)) >= 0) {
                    int keep = Math.min(count, Math.max(0, 65_536 - text.length()));
                    text.append(buffer, 0, keep);
                    truncated |= keep != count;
                }
                if (truncated) text.append("\n[output truncated]");
                return text.toString();
            }
        });
        Thread drain = new Thread(output, "byd-apn2-check-output");
        drain.setDaemon(true);
        drain.start();
        try {
            boolean completed = child.waitFor(Math.max(1L, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
            if (!completed) log("check_timeout", "remote_completion_unknown=true epoch=" + checkEpoch);
        } finally {
            synchronized (lock) { if (active == child) stopActiveLocked(); }
            boolean exited = child.waitFor(1L, TimeUnit.SECONDS);
            try {
                String captured = output.get(1L, TimeUnit.SECONDS);
                for (String line : captured.split("\\r?\\n")) {
                    if (line.isEmpty()) continue;
                    synchronized (lock) {
                        if (line.startsWith("APN2 wake_attempt ")) attempts++;
                        if (!stopping && epoch == checkEpoch && line.matches("APN2 power [0-4]")) {
                            power = line.endsWith(" 0") ? 0 : 1;
                        }
                        log("check_output", "epoch=" + checkEpoch + " " + line);
                    }
                }
            } catch (Exception error) { log("output_error", KeepAliveDaemon.diagnosticDetail(error, "stage=check_output")); }
            finally {
                output.cancel(true);
                child.getInputStream().close();
                synchronized (lock) {
                    log("check_finished", "epoch=" + checkEpoch + " exited=" + exited
                            + " exit_code=" + (exited ? child.exitValue() : "pending"));
                    if (active == child && exited) { active = null; activePid = 0; }
                }
            }
        }
    }

    private void stopActiveLocked() {
        if (active == null) return;
        try {
            // The child's EOF watcher also kills its group if this daemon is killed.
            active.getOutputStream().close();
        } catch (Exception error) { log("stop_pipe_error", KeepAliveDaemon.diagnosticDetail(error, "stage=check_pipe_close")); }
        try {
            if (activePid > 0) Os.kill(-activePid, OsConstants.SIGKILL);
            else if (active.isAlive()) active.destroyForcibly();
        } catch (android.system.ErrnoException error) {
            if (error.errno != OsConstants.ESRCH) log("stop_error", KeepAliveDaemon.diagnosticDetail(error, "stage=check_group_stop"));
        } catch (Exception error) { log("stop_error", KeepAliveDaemon.diagnosticDetail(error, "stage=check_stop")); }
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
            stopActiveLocked();
            lock.notifyAll();
        }
        monitor.interrupt();
    }
}
