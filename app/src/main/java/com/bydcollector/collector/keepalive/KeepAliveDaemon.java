package com.bydcollector.collector.keepalive;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.TimeUnit;

public final class KeepAliveDaemon {
    private static final String PACKAGE_NAME = "com.bydcollector.collector";
    private static final String RECOVER_COLLECTOR_COMMAND =
            "am broadcast -a com.bydcollector.collector.action.KEEP_ALIVE_RECOVERY " +
                    "-n com.bydcollector.collector/com.bydcollector.collector.system.KeepAliveRecoveryReceiver";
    private static final String[] ALLOWED_COMMANDS = new String[]{
            "settings get global bydcollector_keep_wifi",
            "settings get global bydcollector_keep_mobile_data",
            "settings get global bydcollector_keep_bluetooth",
            "settings get global bydcollector_recover_collector_service",
            "svc wifi enable",
            "svc data enable",
            "svc bluetooth enable",
            "settings put global bluetooth_disabled_profiles 202803",
            "settings put global bluetooth_disabled_profiles 0",
            "dumpsys power | grep mWakefulness",
            "pidof com.bydcollector.collector",
            "dumpsys activity services com.bydcollector.collector/.service.CollectorService",
            RECOVER_COLLECTOR_COMMAND
    };

    private KeepAliveDaemon() {
    }

    public static void main(String[] args) throws Throwable {
        OwnerLock ownerLock = acquireSingleOwnerLock();
        if (ownerLock == null) {
            System.out.println("ALREADY_RUNNING");
            System.exit(0);
            return;
        }

        try {
            log("ready");
            while (true) {
                try {
                    log("heartbeat_start");
                    runOnce();
                    log("heartbeat_end");
                } catch (Throwable error) {
                    log("loop_error " + error.getClass().getSimpleName() + ": " + sanitize(error.getMessage() == null ? "no message" : error.getMessage()));
                }
                Thread.sleep(KeepAliveProtocol.LOOP_INTERVAL_MS);
            }
        } catch (Throwable fatal) {
            log("fatal_error " + fatal.getClass().getSimpleName() + ": " + sanitize(fatal.getMessage() == null ? "no message" : fatal.getMessage()));
            throw fatal;
        } finally {
            ownerLock.close();
        }
    }

    private static void runOnce() {
        boolean keepWifi = isEnabled("settings get global bydcollector_keep_wifi");
        boolean keepMobileData = isEnabled("settings get global bydcollector_keep_mobile_data");
        boolean keepBluetooth = isEnabled("settings get global bydcollector_keep_bluetooth");
        boolean recoverCollectorService = isEnabled("settings get global bydcollector_recover_collector_service");

        if (keepWifi) {
            runAndLog("wifi_enable_requested", "svc wifi enable");
        }

        if (keepMobileData) {
            runAndLog("mobile_data_enable_requested", "svc data enable");
        }

        if (keepBluetooth) {
            keepBluetoothAlive();
        }

        if (recoverCollectorService) {
            recoverCollectorServiceIfNeeded();
        }
    }

    private static boolean isEnabled(String command) {
        ShellResult result = run(command, 5_000L);
        return result.output.trim().equals("1");
    }

    private static void keepBluetoothAlive() {
        ShellResult power = run("dumpsys power | grep mWakefulness", 5_000L);
        if (power.output.contains("Asleep")) {
            runAndLog(
                    "bluetooth_sleep_keepalive_requested",
                    "settings put global bluetooth_disabled_profiles 202803"
            );
            runAndLog("bluetooth_enable_requested", "svc bluetooth enable");
        } else {
            runAndLog(
                    "bluetooth_profiles_restored_awake",
                    "settings put global bluetooth_disabled_profiles 0"
            );
            runAndLog("bluetooth_enable_requested", "svc bluetooth enable");
        }
    }

    private static void recoverCollectorServiceIfNeeded() {
        ShellResult service = run("dumpsys activity services com.bydcollector.collector/.service.CollectorService", 5_000L);
        if (service.ok && service.output.contains("com.bydcollector.collector/.service.CollectorService")) {
            log("collector_service_alive");
            return;
        }

        runAndLog(
                "collector_service_recovery_requested",
                RECOVER_COLLECTOR_COMMAND
        );
    }

    private static void runAndLog(String event, String command) {
        ShellResult result = run(command, 10_000L);
        log(event + " ok=" + result.ok + " elapsed_ms=" + result.elapsedMs + " output=" + sanitize(result.output));
        if (!result.error.isEmpty()) {
            log(event + "_error " + sanitize(result.error));
        }
    }

    private static ShellResult run(String command, long timeoutMs) {
        if (!isAllowedCommand(command)) {
            return new ShellResult(false, "", "command_rejected", 0);
        }
        long startedAt = System.currentTimeMillis();
        Process process = null;
        try {
            process = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
                return new ShellResult(false, "", "timeout", System.currentTimeMillis() - startedAt);
            }
            String output = readFully(process.getInputStream()).trim();
            int exitCode = process.exitValue();
            return new ShellResult(exitCode == 0, output, exitCode == 0 ? "" : "exit_code=" + exitCode, System.currentTimeMillis() - startedAt);
        } catch (Exception error) {
            return new ShellResult(
                    false,
                    "",
                    error.getClass().getSimpleName() + ": " + (error.getMessage() == null ? "no message" : error.getMessage()),
                    System.currentTimeMillis() - startedAt
            );
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static boolean isAllowedCommand(String command) {
        for (String allowedCommand : ALLOWED_COMMANDS) {
            if (allowedCommand.equals(command)) {
                return true;
            }
        }
        return false;
    }

    private static String readFully(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static String sanitize(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void log(String message) {
        System.out.println(System.currentTimeMillis() + " " + message);
        System.out.flush();
    }

    private static OwnerLock acquireSingleOwnerLock() {
        try {
            RandomAccessFile file = new RandomAccessFile(KeepAliveProtocol.LOCK_PATH, "rw");
            FileChannel channel = file.getChannel();
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                file.close();
                return null;
            }
            return new OwnerLock(file, channel, lock);
        } catch (Exception error) {
            System.err.println("WARN: keep-alive lock unavailable: " + error.getMessage());
            return null;
        }
    }

    private static final class ShellResult {
        private final boolean ok;
        private final String output;
        private final String error;
        private final long elapsedMs;

        private ShellResult(boolean ok, String output, String error, long elapsedMs) {
            this.ok = ok;
            this.output = output == null ? "" : output;
            this.error = error == null ? "" : error;
            this.elapsedMs = elapsedMs;
        }
    }

    private static final class OwnerLock {
        private final RandomAccessFile file;
        private final FileChannel channel;
        private final FileLock lock;

        private OwnerLock(RandomAccessFile file, FileChannel channel, FileLock lock) {
            this.file = file;
            this.channel = channel;
            this.lock = lock;
        }

        private void close() {
            try {
                if (lock != null) lock.release();
            } catch (Exception ignored) {
            }
            try {
                if (channel != null) channel.close();
            } catch (Exception ignored) {
            }
            try {
                if (file != null) file.close();
            } catch (Exception ignored) {
            }
        }
    }
}
