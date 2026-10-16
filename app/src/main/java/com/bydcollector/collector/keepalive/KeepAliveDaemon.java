package com.bydcollector.collector.keepalive;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.bydcollector.collector.direct.HelperWakeLockPlatform;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.BiFunction;
import java.util.function.Supplier;

//keeps selected dilink radios/service recovery alive from shell while the app process may be backgrounded
public final class KeepAliveDaemon {
    private static final String PACKAGE_NAME = "com.bydcollector.collector";
    private static final File LOG_FILE = new File(KeepAliveProtocol.LOG_PATH);
    private static final long LOG_MAX_BYTES = 1_048_576L;
    private static final int COMMAND_OUTPUT_MAX_BYTES = 65_536;
    private static final long OUTPUT_DRAIN_TIMEOUT_MS = 1_000L;
    private static final long RADIO_STATE_READ_TIMEOUT_MS = 1_500L;
    private static final long BLUETOOTH_VERIFY_DELAY_MS = 1_000L;
    private static final int BLUETOOTH_VERIFY_ATTEMPTS = 5;
    private static final String USER_SHUTDOWN_COMMAND = "settings get global bydcollector_user_shutdown";
    private static final String KEEP_USB_COMMAND = "settings get global " + KeepAliveProtocol.KEY_KEEP_USB;
    private static final String BLUETOOTH_STATE_COMMAND = "dumpsys bluetooth_manager | grep -E 'enabled:|state:'";
    private static final String VEHICLE_POWER_COMMAND = "service call autoservice 5 i32 1001 i32 315621418";
    private static final Pattern VEHICLE_POWER_REPLY = Pattern.compile(
            "Result:\\s*Parcel\\(\\s*(?:0x00000000:\\s*)?00000000\\s+([0-9a-fA-F]{8})\\s+'[^'\\r\\n]*'\\s*\\)"
    );
    private static String lastUsbSkipReason = "";
    private static final String RECOVER_COLLECTOR_COMMAND =
            "am broadcast --include-stopped-packages -a com.bydcollector.collector.action.KEEP_ALIVE_RECOVERY " +
                    "-n com.bydcollector.collector/com.bydcollector.collector.system.KeepAliveRecoveryReceiver";
    private static final ThreadPoolExecutor RADIO_STATE_READER = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            new ThreadFactory() {
                @Override public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "byd-keepalive-radio-state");
                    thread.setDaemon(true);
                    return thread;
                }
            },
            new ThreadPoolExecutor.AbortPolicy()
    );
    private static volatile RadioManagers radioManagers;
    //limits the shell delegate to fixed maintenance commands instead of accepting arbitrary app input
    private static final String[] ALLOWED_COMMANDS = new String[]{
            "settings get global bydcollector_keep_wifi",
            "settings get global bydcollector_keep_mobile_data",
            "settings get global bydcollector_keep_bluetooth",
            "settings get global bydcollector_recover_collector_service",
            KEEP_USB_COMMAND,
            USER_SHUTDOWN_COMMAND,
            "svc wifi enable",
            "svc data enable",
            "svc bluetooth enable",
            "settings put global bluetooth_disabled_profiles 202803",
            "settings put global bluetooth_disabled_profiles 0",
            BLUETOOTH_STATE_COMMAND,
            VEHICLE_POWER_COMMAND,
            UsbKeepAliveTransport.READ_ACC_COMMAND,
            UsbKeepAliveTransport.READ_BODYWORK_POWER_LEVEL_COMMAND,
            UsbKeepAliveTransport.USB_DATA_PORT_ON_COMMAND,
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
            UsbKeepAliveState usbState = UsbKeepAliveState.open(new File(KeepAliveProtocol.USB_STATE_PATH));
            UsbKeepAliveState.Snapshot usbSnapshot = usbState.snapshot();
            log("usb_state_open usable=" + usbSnapshot.usable
                    + " status=" + usbSnapshot.lastStatus
                    + " reservations_total=" + usbSnapshot.reservationCount
                    + " attempts_total=" + usbSnapshot.attemptCount
                    + " cycle_reservations=" + usbSnapshot.cycleReservations
                    + " cycle_attempts=" + usbSnapshot.cycleAttempts
                    + " history_recovered=" + usbSnapshot.historyRecovered
                    + " error=" + sanitizeUsb(usbSnapshot.error));
            log("ready");
            while (true) {
                try {
                    log("heartbeat_start");
                    runOnce(usbState);
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

    private static void runOnce(UsbKeepAliveState usbState) {
        //USB persistence or telemetry errors must not suppress the other keep-alive duties.
        try {
            maintainUsb(usbState);
        } catch (Throwable error) {
            logUsbSkip("usb_internal_error:" + error.getClass().getSimpleName(), usbState,
                    "", "", "");
        }

        //reads mirrored global flags each loop so toggles can change without restarting the delegate
        boolean keepWifi = isEnabled("settings get global bydcollector_keep_wifi");
        boolean keepMobileData = isEnabled("settings get global bydcollector_keep_mobile_data");
        boolean keepBluetooth = isEnabled("settings get global bydcollector_keep_bluetooth");
        boolean recoverCollectorService = isEnabled("settings get global bydcollector_recover_collector_service");

        runOnce(keepWifi, keepMobileData, keepBluetooth, recoverCollectorService,
                KeepAliveDaemon::readWifiEnabled, KeepAliveDaemon::readMobileDataEnabled,
                KeepAliveDaemon::recoverCollectorServiceIfNeeded, KeepAliveDaemon::run);
    }

    private static void maintainUsb(UsbKeepAliveState state) {
        UsbKeepAliveState.Snapshot snapshot = state.snapshot();
        String bootId = readBootId();
        boolean bootObserved = bootId != null && state.observeBoot(bootId);

        //Observe ACC every heartbeat, including while USB is disabled or shutdown is requested.
        UsbRead baselineAcc = readUsbAcc();
        String accRaw = baselineAcc.raw;
        if (baselineAcc.result != null && baselineAcc.result.valid) state.observeAcc(baselineAcc.result.value);
        snapshot = state.snapshot();
        if (!snapshot.usable) {
            String reason = bootId == null ? "state_unavailable:" + snapshot.error
                    : "state_unavailable_waiting_for_new_cycle_or_boot:" + snapshot.error;
            logUsbSkip(reason, state, bootId == null ? "" : bootId, "", accRaw);
            return;
        }
        if (!bootObserved) {
            logUsbSkip("boot_id_unavailable", state, bootId == null ? "" : bootId, "", accRaw);
            return;
        }

        FlagRead enabled = readFlag(KEEP_USB_COMMAND);
        FlagRead shutdown = readFlag(USER_SHUTDOWN_COMMAND);
        String disabledReason = usbDisabledReason(enabled, shutdown);
        if (disabledReason != null) {
            logUsbSkip(disabledReason, state, bootId, "", accRaw);
            return;
        }

        UsbRead power = readUsbPower();
        UsbRead freshAcc = readUsbAcc();
        accRaw = freshAcc.raw;
        if (freshAcc.result != null && freshAcc.result.valid) state.observeAcc(freshAcc.result.value);
        snapshot = state.snapshot();
        if (!snapshot.usable) {
            logUsbSkip("state_unavailable:" + snapshot.error, state, bootId, power.raw, accRaw);
            return;
        }
        if (power.result == null || !power.result.valid) {
            logUsbSkip("power_unavailable:" + usbReadError(power), state, bootId, power.raw, accRaw);
            return;
        }
        if (freshAcc.result == null || !freshAcc.result.valid) {
            logUsbSkip("acc_unavailable:" + usbReadError(freshAcc), state, bootId, power.raw, accRaw);
            return;
        }
        if (power.result.value != 0) {
            logUsbSkip("vehicle_power_not_off", state, bootId, power.raw, accRaw);
            return;
        }
        if (freshAcc.result.value != 0) {
            logUsbSkip("acc_not_off", state, bootId, power.raw, accRaw);
            return;
        }

        //Recheck both gates immediately before consuming the boot/cycle allowance.
        enabled = readFlag(KEEP_USB_COMMAND);
        shutdown = readFlag(USER_SHUTDOWN_COMMAND);
        disabledReason = usbDisabledReason(enabled, shutdown);
        if (disabledReason != null) {
            logUsbSkip(disabledReason, state, bootId, power.raw, accRaw);
            return;
        }
        String trigger = state.eligibleTrigger(bootId);
        if (trigger == null) {
            logUsbSkip("no_unused_off_cycle", state, bootId, power.raw, accRaw);
            return;
        }

        long wallMs = System.currentTimeMillis();
        long elapsedMs = SystemClock.elapsedRealtime();
        UsbKeepAliveState.Reservation reservation = state.reserveAttempt(bootId, power.result.value,
                freshAcc.result.value, power.raw, accRaw, wallMs, elapsedMs);
        if (reservation == null) {
            snapshot = state.snapshot();
            logUsbSkip(snapshot.usable ? "reservation_unavailable" : "state_unavailable:" + snapshot.error,
                    state, bootId, power.raw, accRaw);
            return;
        }
        lastUsbSkipReason = "";
        snapshot = state.snapshot();
        log("usb_attempt_reserved wall_ms=" + wallMs + " elapsed_ms=" + elapsedMs
                + " boot_id=" + bootId + " cycle=" + reservation.cycleCount
                + " trigger=" + reservation.trigger + " reservation=" + reservation.id
                + " reservations_total=" + snapshot.reservationCount + " attempts_total=" + snapshot.attemptCount
                + " cycle_reservations=" + snapshot.cycleReservations + " cycle_attempts=" + snapshot.cycleAttempts
                + " power=" + power.result.value + " acc=" + freshAcc.result.value
                + " power_raw=" + sanitizeUsb(power.raw) + " acc_raw=" + sanitizeUsb(accRaw));

        //A toggle/shutdown change after reservation consumes this cycle but never issues ON.
        enabled = readFlag(KEEP_USB_COMMAND);
        shutdown = readFlag(USER_SHUTDOWN_COMMAND);
        disabledReason = usbDisabledReason(enabled, shutdown);
        if (disabledReason != null) {
            wallMs = System.currentTimeMillis();
            elapsedMs = SystemClock.elapsedRealtime();
            boolean recorded = state.markNotDispatched(reservation, disabledReason,
                    power.result.value, freshAcc.result.value, power.raw, accRaw, wallMs, elapsedMs);
            snapshot = state.snapshot();
            log("usb_on_not_dispatched wall_ms=" + wallMs + " elapsed_ms=" + elapsedMs
                    + " boot_id=" + bootId + " cycle=" + reservation.cycleCount
                    + " trigger=" + reservation.trigger + " reservation=" + reservation.id
                    + " reservations_total=" + snapshot.reservationCount + " attempts_total=" + snapshot.attemptCount
                    + " cycle_reservations=" + snapshot.cycleReservations + " cycle_attempts=" + snapshot.cycleAttempts
                    + " status=" + snapshot.lastStatus + " recorded=" + recorded
                    + " reason=" + disabledReason + " power_raw=" + sanitizeUsb(power.raw)
                    + " acc_raw=" + sanitizeUsb(accRaw) + " state_error=" + sanitizeUsb(snapshot.error));
            return;
        }

        //The earlier OFF sample may be stale after settings reads. Verify vehicle state and identity
        //again immediately before durably marking the one-shot attempt started.
        UsbRead dispatchPower = readUsbPower();
        UsbRead dispatchAcc = readUsbAcc();
        String dispatchBootId = readBootId();
        boolean sameCycle = dispatchBootId != null && dispatchBootId.equalsIgnoreCase(reservation.bootId)
                && state.snapshot().bootId.equalsIgnoreCase(reservation.bootId)
                && state.snapshot().cycleCount == reservation.cycleCount;
        String dispatchBlockReason = !sameCycle ? "vehicle_identity_changed"
                : dispatchPower.result == null || !dispatchPower.result.valid ? "power_unavailable"
                : dispatchAcc.result == null || !dispatchAcc.result.valid ? "acc_unavailable"
                : dispatchPower.result.value != 0 ? "vehicle_power_not_off"
                : dispatchAcc.result.value != 0 ? "acc_not_off" : null;
        if (dispatchBlockReason != null) {
            wallMs = System.currentTimeMillis();
            elapsedMs = SystemClock.elapsedRealtime();
            int finalPower = dispatchPower.result != null && dispatchPower.result.valid
                    ? dispatchPower.result.value : -1;
            int finalAcc = dispatchAcc.result != null && dispatchAcc.result.valid
                    ? dispatchAcc.result.value : -1;
            boolean recorded = state.markNotDispatched(reservation, dispatchBlockReason,
                    finalPower, finalAcc, dispatchPower.raw, dispatchAcc.raw, wallMs, elapsedMs);
            if (dispatchBootId != null && !dispatchBootId.equalsIgnoreCase(reservation.bootId)) {
                state.observeBoot(dispatchBootId);
            }
            if (dispatchAcc.result != null && dispatchAcc.result.valid) {
                state.observeAcc(dispatchAcc.result.value);
            }
            snapshot = state.snapshot();
            log("usb_on_not_dispatched wall_ms=" + wallMs + " elapsed_ms=" + elapsedMs
                    + " boot_id=" + reservation.bootId + " observed_boot_id="
                    + (dispatchBootId == null ? "unavailable" : dispatchBootId)
                    + " cycle=" + reservation.cycleCount + " trigger=" + reservation.trigger
                    + " reservation=" + reservation.id + " reservations_total=" + snapshot.reservationCount
                    + " attempts_total=" + snapshot.attemptCount + " cycle_reservations="
                    + snapshot.cycleReservations + " cycle_attempts=" + snapshot.cycleAttempts
                    + " status=" + snapshot.lastStatus + " recorded=" + recorded
                    + " reason=" + dispatchBlockReason + " power=" + finalPower + " acc=" + finalAcc
                    + " power_raw=" + sanitizeUsb(dispatchPower.raw)
                    + " acc_raw=" + sanitizeUsb(dispatchAcc.raw)
                    + " state_error=" + sanitizeUsb(snapshot.error));
            return;
        }

        wallMs = System.currentTimeMillis();
        elapsedMs = SystemClock.elapsedRealtime();
        if (!state.markStarted(reservation, wallMs, elapsedMs)) {
            snapshot = state.snapshot();
            logUsbSkip(snapshot.usable ? "start_record_unavailable" : "state_unavailable:" + snapshot.error,
                    state, dispatchBootId, dispatchPower.raw, dispatchAcc.raw);
            return;
        }
        snapshot = state.snapshot();
        log("usb_on_started wall_ms=" + wallMs + " elapsed_ms=" + elapsedMs
                + " boot_id=" + bootId + " cycle=" + reservation.cycleCount
                + " trigger=" + reservation.trigger + " reservation=" + reservation.id
                + " attempts_total=" + snapshot.attemptCount + " cycle_reservations=" + snapshot.cycleReservations
                + " cycle_attempts=" + snapshot.cycleAttempts + " status=" + snapshot.lastStatus);

        ShellResult request = run(UsbKeepAliveTransport.USB_DATA_PORT_ON_COMMAND, 10_000L);
        UsbKeepAliveTransport.Result binder = request.ok
                ? UsbKeepAliveTransport.parseUsbOnReply(request.output) : null;
        wallMs = System.currentTimeMillis();
        boolean resultRecorded = state.recordResult(reservation, request.ok,
                dispatchPower.result.value, dispatchAcc.result.value, dispatchPower.raw, dispatchAcc.raw,
                request.output, request.error, binder, request.elapsedMs, wallMs);
        snapshot = state.snapshot();
        lastUsbSkipReason = "";
        String status = !request.ok ? "shell_failed"
                : binder != null && binder.valid ? "binder_result_zero" : "binder_reply_invalid";
        log("usb_on_result wall_ms=" + wallMs + " elapsed_ms=" + SystemClock.elapsedRealtime()
                + " boot_id=" + bootId + " cycle=" + reservation.cycleCount
                + " trigger=" + reservation.trigger + " reservation=" + reservation.id
                + " reservations_total=" + snapshot.reservationCount + " attempts_total=" + snapshot.attemptCount
                + " cycle_reservations=" + snapshot.cycleReservations + " cycle_attempts=" + snapshot.cycleAttempts
                + " power=" + dispatchPower.result.value
                + " acc=" + dispatchAcc.result.value + " power_raw=" + sanitizeUsb(dispatchPower.raw)
                + " acc_raw=" + sanitizeUsb(dispatchAcc.raw) + " on_raw=" + sanitizeUsb(request.output)
                + " duration_ms=" + request.elapsedMs + " shell_ok=" + request.ok
                + " binder_valid=" + snapshot.binderValid
                + " binder_exception=" + (binder == null ? "unavailable" : binder.exceptionCode)
                + " binder_return=" + (binder == null ? "unavailable" : binder.returnedInt)
                + " status=" + status + " result_recorded=" + resultRecorded
                + " shell_error=" + sanitizeUsb(request.error)
                + " binder_error=" + (binder == null ? "shell_not_successful" : sanitizeUsb(binder.error)));
    }

    private static String readBootId() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(KeepAliveProtocol.KERNEL_BOOT_ID_PATH), StandardCharsets.UTF_8))) {
            String value = reader.readLine();
            return value == null || value.trim().isEmpty() ? null : value.trim();
        } catch (Exception error) {
            return null;
        }
    }

    private static UsbRead readUsbAcc() {
        ShellResult shell = run(UsbKeepAliveTransport.READ_ACC_COMMAND, 5_000L);
        UsbKeepAliveTransport.Result result = shell.ok
                ? UsbKeepAliveTransport.parseAccReply(shell.output) : null;
        return new UsbRead(result, shell.output, shell.ok ? (result == null ? "parse_unavailable" : result.error) : shell.error,
                shell.elapsedMs);
    }

    private static UsbRead readUsbPower() {
        ShellResult shell = run(UsbKeepAliveTransport.READ_BODYWORK_POWER_LEVEL_COMMAND, 5_000L);
        UsbKeepAliveTransport.Result result = shell.ok
                ? UsbKeepAliveTransport.parseBodyworkPowerLevelReply(shell.output) : null;
        return new UsbRead(result, shell.output, shell.ok ? (result == null ? "parse_unavailable" : result.error) : shell.error,
                shell.elapsedMs);
    }

    private static FlagRead readFlag(String command) {
        ShellResult shell = run(command, 5_000L);
        if (!shell.ok) return new FlagRead(null, shell.output, shell.error);
        String value = shell.output.trim();
        if ("0".equals(value)) return new FlagRead(Boolean.FALSE, shell.output, "");
        if ("1".equals(value)) return new FlagRead(Boolean.TRUE, shell.output, "");
        return new FlagRead(null, shell.output, "invalid_setting_value");
    }

    private static String usbDisabledReason(FlagRead enabled, FlagRead shutdown) {
        if (enabled.value == null) return "keep_usb_setting_unavailable:" + enabled.error;
        if (!enabled.value) return "feature_disabled";
        if (shutdown.value == null) return "user_shutdown_setting_unavailable:" + shutdown.error;
        return shutdown.value ? "user_shutdown" : null;
    }

    private static String usbReadError(UsbRead read) {
        if (read.result != null) return read.result.error;
        return read.error;
    }

    private static void logUsbSkip(String reason, UsbKeepAliveState state, String bootId,
                                   String powerRaw, String accRaw) {
        if (reason.equals(lastUsbSkipReason)) return;
        lastUsbSkipReason = reason;
        UsbKeepAliveState.Snapshot snapshot = state.snapshot();
        log("usb_skip wall_ms=" + System.currentTimeMillis() + " elapsed_ms=" + SystemClock.elapsedRealtime()
                + " reason=" + sanitizeUsb(reason) + " boot_id=" + sanitizeUsb(bootId)
                + " cycle=" + snapshot.cycleCount + " reservations_total=" + snapshot.reservationCount
                + " attempts_total=" + snapshot.attemptCount + " cycle_reservations=" + snapshot.cycleReservations
                + " cycle_attempts=" + snapshot.cycleAttempts + " status=" + snapshot.lastStatus
                + " power_raw=" + sanitizeUsb(powerRaw) + " acc_raw=" + sanitizeUsb(accRaw)
                + " state_error=" + sanitizeUsb(snapshot.error));
    }

    private static String sanitizeUsb(String value) {
        String sanitized = sanitize(value == null ? "" : value);
        return sanitized.length() <= 1_024 ? sanitized : sanitized.substring(0, 1_024);
    }

    private static final class UsbRead {
        private final UsbKeepAliveTransport.Result result;
        private final String raw;
        private final String error;
        private final long elapsedMs;

        private UsbRead(UsbKeepAliveTransport.Result result, String raw, String error, long elapsedMs) {
            this.result = result;
            this.raw = raw == null ? "" : raw;
            this.error = error == null ? "" : error;
            this.elapsedMs = elapsedMs;
        }
    }

    private static final class FlagRead {
        private final Boolean value;
        private final String raw;
        private final String error;

        private FlagRead(Boolean value, String raw, String error) {
            this.value = value;
            this.raw = raw == null ? "" : raw;
            this.error = error == null ? "" : error;
        }
    }

    static void runOnce(boolean keepWifi, boolean keepMobileData, boolean keepBluetooth,
                        boolean recoverCollectorService, Supplier<Boolean> wifiState,
                        Supplier<Boolean> mobileDataState, Runnable serviceRecovery,
                        BiFunction<String, Long, ShellResult> commandRunner) {
        if (keepWifi) {
            keepRadioAlive("wifi", "svc wifi enable", wifiState.get(), commandRunner);
        }

        if (keepMobileData) {
            keepRadioAlive("mobile_data", "svc data enable", mobileDataState.get(), commandRunner);
        }

        if (keepBluetooth) {
            keepBluetoothAlive();
        }

        if (recoverCollectorService) {
            serviceRecovery.run();
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
        runAndLog(event, command, KeepAliveDaemon::run);
    }

    private static void runAndLog(String event, String command,
                                  BiFunction<String, Long, ShellResult> commandRunner) {
        ShellResult result = commandRunner.apply(command, 10_000L);
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

    private static void keepRadioAlive(String name, String command, Boolean enabled,
                                       BiFunction<String, Long, ShellResult> commandRunner) {
        if (!shouldEnableRadio(enabled)) {
            log(name + "_already_enabled");
            return;
        }
        if (enabled == null) log(name + "_state_unavailable; retaining enable recovery");

        //requires a fresh successful power read before each network enable request
        ShellResult vehiclePower = commandRunner.apply(VEHICLE_POWER_COMMAND, 5_000L);
        Boolean vehiclePoweredOff = vehiclePower.ok ? parseVehiclePoweredOff(vehiclePower.output) : null;
        if (!Boolean.TRUE.equals(vehiclePoweredOff)) {
            log(Boolean.FALSE.equals(vehiclePoweredOff)
                    ? name + "_enable_skipped_vehicle_on"
                    : name + "_vehicle_power_state_unavailable ok=" + vehiclePower.ok
                            + " elapsed_ms=" + vehiclePower.elapsedMs
                            + " output=" + sanitize(vehiclePower.output)
                            + " error=" + sanitize(vehiclePower.error));
            return;
        }

        runAndLog(name + "_enable_requested", command, commandRunner);
    }

    static Boolean readWifiEnabled() {
        return readRadioState(KeepAliveDaemon::queryWifiEnabled);
    }

    private static Boolean queryWifiEnabled() throws Exception {
        RadioManagers managers = radioManagers();
        return managers.wifiManager == null ? null : managers.wifiManager.isWifiEnabled();
    }

    static Boolean readMobileDataEnabled() {
        return readRadioState(KeepAliveDaemon::queryMobileDataEnabled);
    }

    private static Boolean queryMobileDataEnabled() throws Exception {
        RadioManagers managers = radioManagers();
        if (managers.telephonyManager == null) return null;
        int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null;
        TelephonyManager defaultDataManager = managers.telephonyManager.createForSubscriptionId(subId);
        return defaultDataManager.isDataEnabled();
    }

    static Boolean readRadioState(Callable<Boolean> readEnabled) {
        final Future<Boolean> read;
        try {
            read = RADIO_STATE_READER.submit(() -> readStateOrUnavailable(readEnabled));
        } catch (RejectedExecutionException error) {
            log("radio_state_read_unavailable reason=reader_busy");
            return null;
        }

        try {
            return read.get(RADIO_STATE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            read.cancel(true);
            log("radio_state_read_unavailable reason=timeout");
            return null;
        } catch (InterruptedException error) {
            read.cancel(true);
            Thread.currentThread().interrupt();
            log("radio_state_read_unavailable reason=interrupted");
            return null;
        } catch (ExecutionException error) {
            Throwable failure = error.getCause();
            log("radio_state_read_unavailable reason=" +
                    (failure == null ? "reader_failed" : failure.getClass().getSimpleName()));
            return null;
        }
    }

    static Boolean readStateOrUnavailable(Callable<Boolean> readEnabled) {
        try {
            return readEnabled.call();
        } catch (Exception error) {
            return null;
        }
    }

    static boolean shouldEnableRadio(Boolean enabled) {
        return !Boolean.TRUE.equals(enabled);
    }

    private static RadioManagers radioManagers() throws Exception {
        RadioManagers current = radioManagers;
        if (current != null) return current;
        synchronized (KeepAliveDaemon.class) {
            current = radioManagers;
            if (current == null) {
                Context context = HelperWakeLockPlatform.createShellContext();
                current = new RadioManagers(
                        context,
                        context.getSystemService(WifiManager.class),
                        context.getSystemService(TelephonyManager.class)
                );
                radioManagers = current;
            }
        }
        return current;
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

    static final class ShellResult {
        private final boolean ok;
        private final String output;
        private final String error;
        private final long elapsedMs;

        ShellResult(boolean ok, String output, String error, long elapsedMs) {
            this.ok = ok;
            this.output = output == null ? "" : output;
            this.error = error == null ? "" : error;
            this.elapsedMs = elapsedMs;
        }
    }

    private static final class RadioManagers {
        //Keep the attribution context alive with the cached system-service managers.
        private final Context shellContext;
        private final WifiManager wifiManager;
        private final TelephonyManager telephonyManager;

        private RadioManagers(Context shellContext, WifiManager wifiManager, TelephonyManager telephonyManager) {
            this.shellContext = shellContext;
            this.wifiManager = wifiManager;
            this.telephonyManager = telephonyManager;
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
