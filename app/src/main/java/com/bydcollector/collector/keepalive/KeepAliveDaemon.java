package com.bydcollector.collector.keepalive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//keeps selected dilink radios/service recovery alive from shell while the app process may be backgrounded
public final class KeepAliveDaemon {
    private static final String PACKAGE_NAME = "com.bydcollector.collector";
    private static final File LOG_FILE = new File(KeepAliveProtocol.LOG_PATH);
    private static final long LOG_MAX_BYTES = 1_048_576L;
    private static final int COMMAND_OUTPUT_MAX_BYTES = 65_536;
    private static final long OUTPUT_DRAIN_TIMEOUT_MS = 1_000L;
    private static final long BLUETOOTH_VERIFY_DELAY_MS = 1_000L;
    private static final int BLUETOOTH_VERIFY_ATTEMPTS = 5;
    private static final String USER_SHUTDOWN_COMMAND = "settings get global bydcollector_user_shutdown";
    private static final String BLUETOOTH_STATE_COMMAND = "dumpsys bluetooth_manager | grep -E 'enabled:|state:'";
    private static final String VEHICLE_POWER_COMMAND = "service call autoservice 5 i32 1001 i32 315621418";
    private static final Pattern VEHICLE_POWER_REPLY = Pattern.compile(
            "Result:\\s*Parcel\\(\\s*(?:0x00000000:\\s*)?00000000\\s+([0-9a-fA-F]{8})\\s+'[^'\\r\\n]*'\\s*\\)"
    );
    private static final String RECOVER_COLLECTOR_COMMAND =
            "am broadcast --include-stopped-packages -a com.bydcollector.collector.action.KEEP_ALIVE_RECOVERY " +
                    "-n com.bydcollector.collector/com.bydcollector.collector.system.KeepAliveRecoveryReceiver";
    //limits the shell delegate to fixed maintenance commands instead of accepting arbitrary app input
    private static final String[] ALLOWED_COMMANDS = new String[]{
            "settings get global bydcollector_keep_wifi",
            "settings get global bydcollector_keep_mobile_data",
            "settings get global bydcollector_keep_bluetooth",
            "settings get global bydcollector_recover_collector_service",
            USER_SHUTDOWN_COMMAND,
            "svc wifi enable",
            "svc data enable",
            "svc bluetooth enable",
            "settings put global bluetooth_disabled_profiles 202803",
            "settings put global bluetooth_disabled_profiles 0",
            BLUETOOTH_STATE_COMMAND,
            VEHICLE_POWER_COMMAND,
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
        //reads mirrored global flags each loop so toggles can change without restarting the delegate
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
            //keeps bluetooth usable during sleep by disabling only the profiles dilink tends to suspend
            runAndLog(
                    "bluetooth_sleep_keepalive_requested",
                    "settings put global bluetooth_disabled_profiles 202803"
            );
        } else {
            //restores normal profile policy while awake so the keep-alive path is not permanently invasive
            runAndLog(
                    "bluetooth_profiles_restored_awake",
                    "settings put global bluetooth_disabled_profiles 0"
            );
        }

        Boolean enabled = readBluetoothEnabled();
        if (Boolean.TRUE.equals(enabled)) {
            log("bluetooth_already_enabled");
            return;
        }
        if (enabled == null) {
            log("bluetooth_state_unavailable");
            return;
        }

        //reads vehicle power immediately before enabling, independently of app lifetime or screen state
        ShellResult vehiclePower = run(VEHICLE_POWER_COMMAND, 5_000L);
        Boolean vehiclePoweredOff = vehiclePower.ok ? parseVehiclePoweredOff(vehiclePower.output) : null;
        if (!Boolean.TRUE.equals(vehiclePoweredOff)) {
            log(Boolean.FALSE.equals(vehiclePoweredOff)
                    ? "bluetooth_enable_skipped_vehicle_on"
                    : "bluetooth_vehicle_power_state_unavailable ok=" + vehiclePower.ok
                            + " elapsed_ms=" + vehiclePower.elapsedMs
                            + " output=" + sanitize(vehiclePower.output)
                            + " error=" + sanitize(vehiclePower.error));
            return;
        }

        ShellResult request = run("svc bluetooth enable", 10_000L);
        log("bluetooth_enable_requested ok=" + request.ok
                + " elapsed_ms=" + request.elapsedMs
                + " output=" + sanitize(request.output)
                + " error=" + sanitize(request.error));
        for (int attempt = 1; attempt <= BLUETOOTH_VERIFY_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(BLUETOOTH_VERIFY_DELAY_MS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                log("bluetooth_enable_verification_interrupted");
                return;
            }
            if (Boolean.TRUE.equals(readBluetoothEnabled())) {
                log("bluetooth_enable_confirmed attempt=" + attempt + " request_ok=" + request.ok);
                return;
            }
        }
        log("bluetooth_enable_not_confirmed request_ok=" + request.ok + " request_error=" + sanitize(request.error));
    }

    private static Boolean readBluetoothEnabled() {
        ShellResult state = run(BLUETOOTH_STATE_COMMAND, 5_000L);
        return state.ok ? parseBluetoothEnabled(state.output) : null;
    }

    static Boolean parseVehiclePoweredOff(String output) {
        Matcher reply = VEHICLE_POWER_REPLY.matcher(output == null ? "" : output.trim());
        if (!reply.matches()) return null;
        int raw = (int) Long.parseLong(reply.group(1), 16);
        return raw < 0 ? null : raw == 0;
    }

    static Boolean parseBluetoothEnabled(String output) {
        String normalized = output == null ? "" : output.toLowerCase(Locale.ROOT);
        Boolean enabled = null;
        Boolean stateOn = null;
        for (String line : normalized.split("\\R")) {
            String value = line.trim();
            if (enabled == null && value.startsWith("enabled:")) {
                enabled = value.equals("enabled: true") ? Boolean.TRUE
                        : value.equals("enabled: false") ? Boolean.FALSE
                        : null;
            } else if (stateOn == null && value.startsWith("state:")) {
                stateOn = value.equals("state: on") ? Boolean.TRUE
                        : value.equals("state: off") ? Boolean.FALSE
                        : null;
            }
        }
        if (Boolean.TRUE.equals(enabled) && Boolean.TRUE.equals(stateOn)) return Boolean.TRUE;
        if (Boolean.FALSE.equals(enabled) && Boolean.FALSE.equals(stateOn)) return Boolean.FALSE;
        return null;
    }

    private static void recoverCollectorServiceIfNeeded() {
        if (isEnabled(USER_SHUTDOWN_COMMAND)) {
            log("collector_recovery_blocked_user_shutdown");
            return;
        }
        ShellResult process = run("pidof " + PACKAGE_NAME, 5_000L);
        log(process.ok && !process.output.isEmpty() ? "collector_process_present" : "collector_process_missing");

        ShellResult service = run("dumpsys activity services com.bydcollector.collector/.service.CollectorService", 5_000L);
        log(service.ok && service.output.contains("com.bydcollector.collector/.service.CollectorService")
                ? "collector_service_record_present"
                : "collector_service_record_missing");

        runAndLog("collector_service_recovery_broadcast_requested", RECOVER_COLLECTOR_COMMAND);
    }

    private static void runAndLog(String event, String command) {
        ShellResult result = run(command, 10_000L);
        log(event + " ok=" + result.ok + " elapsed_ms=" + result.elapsedMs + " output=" + sanitize(result.output));
        if (!result.error.isEmpty()) {
            log(event + "_error " + sanitize(result.error));
        }
    }

    private static ShellResult run(String command, long timeoutMs) {
        //fails closed if a future code path tries to run a non-whitelisted shell command
        if (!isAllowedCommand(command)) {
            return new ShellResult(false, "", "command_rejected", 0);
        }
        long startedAt = System.currentTimeMillis();
        Process process = null;
        InputStream processOutput = null;
        FutureTask<String> outputTask = null;
        try {
            process = new ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            processOutput = process.getInputStream();
            final InputStream drainInput = processOutput;
            outputTask = new FutureTask<>(() -> drainOutput(drainInput, COMMAND_OUTPUT_MAX_BYTES));
            Thread outputDrainer = new Thread(outputTask, "byd-keepalive-command-output");
            outputDrainer.setDaemon(true);
            outputDrainer.start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
            String output;
            boolean outputDrainTimedOut = false;
            try {
                output = outputTask.get(OUTPUT_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS).trim();
            } catch (TimeoutException error) {
                outputDrainTimedOut = true;
                processOutput.close();
                outputTask.cancel(true);
                output = "";
            }
            if (!finished) {
                return new ShellResult(false, output, "timeout", System.currentTimeMillis() - startedAt);
            }
            if (outputDrainTimedOut) {
                return new ShellResult(false, output, "output_drain_timeout", System.currentTimeMillis() - startedAt);
            }
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
            if (processOutput != null) {
                try {
                    processOutput.close();
                } catch (Exception ignored) {
                }
            }
            if (outputTask != null && !outputTask.isDone()) {
                outputTask.cancel(true);
            }
            if (process != null) {
                process.destroy();
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
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

    static String drainOutput(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            int remaining = maxBytes - output.size();
            if (remaining > 0) {
                output.write(buffer, 0, Math.min(count, remaining));
            }
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static String sanitize(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void log(String message) {
        //stdout is inherited with O_APPEND, so truncating the same file keeps future writes at the new EOF
        truncateLogIfNeeded(LOG_FILE, LOG_MAX_BYTES);
        System.out.println(System.currentTimeMillis() + " " + message);
        System.out.flush();
    }

    static boolean truncateLogIfNeeded(File logFile, long maxBytes) {
        if (!logFile.isFile() || logFile.length() < maxBytes) {
            return false;
        }
        try (RandomAccessFile writable = new RandomAccessFile(logFile, "rw")) {
            writable.setLength(0L);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static OwnerLock acquireSingleOwnerLock() {
        try {
            //keeps one daemon loop active so repeated reconciles do not stack shell work
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
