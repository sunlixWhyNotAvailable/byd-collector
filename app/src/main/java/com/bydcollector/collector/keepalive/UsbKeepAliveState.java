package com.bydcollector.collector.keepalive;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/** Persistent single-owner USB attempt allowance; failed writes always block dispatch. */
public final class UsbKeepAliveState {
    private static final int MAX_FILE_BYTES = 16_384;
    private static final int MAX_DETAIL_CHARS = 1_024;
    private static final String NONE = "NONE", RESERVED = "RESERVED", NOT_DISPATCHED = "NOT_DISPATCHED";
    private static final String STARTED = "STARTED", RESULT = "RESULT";
    private static final Set<String> ROOT_KEYS = new HashSet<>(Arrays.asList(
            "format", "boot_id", "cycle_count", "acc_on_seen", "reservation_count", "attempt_count",
            "initial_trigger", "history_recovered", "last_attempt"));
    private static final Set<String> ATTEMPT_KEYS = new HashSet<>(Arrays.asList(
            "status", "reservation", "attempt", "trigger", "boot_id", "cycle_count",
            "reserved_wall_ms", "reserved_elapsed_ms", "started_wall_ms", "started_elapsed_ms",
            "completed_wall_ms", "duration_ms", "power_value", "acc_value", "power_reply",
            "acc_reply", "on_reply", "shell_ok", "shell_error", "binder_valid",
            "binder_exception", "binder_return", "binder_error"));

    private final File file;
    private final File temp;
    private JSONObject data = fresh();
    private boolean usable = true, persisted, recoverable;
    private boolean historyRecovered;
    private String error = "";

    private UsbKeepAliveState(File file) {
        this.file = file.getAbsoluteFile();
        this.temp = new File(this.file.getPath() + ".tmp");
    }

    public static UsbKeepAliveState open(File file) {
        UsbKeepAliveState state = new UsbKeepAliveState(file);
        JSONObject parsed = null;
        try {
            boolean target = Files.exists(state.file.toPath(), LinkOption.NOFOLLOW_LINKS);
            if (!target && !Files.exists(state.temp.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                // shortcut: absent history is indistinguishable from first install; no OS source restores deleted state.
                return state;
            }
            if (target && Files.isRegularFile(state.file.toPath(), LinkOption.NOFOLLOW_LINKS)
                    && Files.size(state.file.toPath()) <= MAX_FILE_BYTES) {
                parsed = parseJson(Files.readAllBytes(state.file.toPath()));
            }
            if (Files.exists(state.temp.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return state.corrupt("stale_temp_file");
            }
            if (!target || !Files.isRegularFile(state.file.toPath(), LinkOption.NOFOLLOW_LINKS)
                    || Files.size(state.file.toPath()) <= 0 || Files.size(state.file.toPath()) > MAX_FILE_BYTES) {
                return state.corrupt("state_file_invalid");
            }
            validate(parsed);
            state.data = parsed;
            state.historyRecovered = parsed.getBoolean("history_recovered");
            state.persisted = true;
            return state;
        } catch (Exception failure) {
            return state.corrupt("state_read_failed:" + failure.getClass().getSimpleName());
        }
    }

    public synchronized Snapshot snapshot() {
        JSONObject attempt = data.optJSONObject("last_attempt");
        boolean current = attempt != null && !NONE.equals(attempt.optString("status"))
                && get(attempt, "boot_id").equals(get(data, "boot_id"))
                && number(attempt, "cycle_count") == number(data, "cycle_count");
        return new Snapshot(usable, error, get(data, "boot_id"), number(data, "cycle_count"),
                number(data, "reservation_count"), number(data, "attempt_count"),
                attempt == null ? NONE : attempt.optString("status"),
                attempt == null ? "" : attempt.optString("trigger"),
                attempt != null && attempt.optBoolean("binder_valid"), historyRecovered,
                current ? 1 : 0, current && number(attempt, "attempt") > 0 ? 1 : 0);
    }

    /** Corrupt history is first blocked on this boot; only a later boot or ACC 1->0 releases it. */
    public synchronized boolean observeBoot(String bootId) {
        if (!isBootId(bootId)) return false;
        if (!usable) return recoverable && establishBlockedBaseline(bootId);
        String previous = get(data, "boot_id");
        if (previous.equalsIgnoreCase(bootId)) return true;
        put(data, "boot_id", bootId);
        put(data, "acc_on_seen", false);
        if (!previous.isEmpty() && NONE.equals(status(data))) put(data, "initial_trigger", "new_boot");
        return persist(false);
    }

    /** Invalid telemetry is ignored and never changes the observed cycle. */
    public synchronized boolean observeAcc(int acc) {
        if (acc != 0 && acc != 1) return false;
        if (!usable) return false;
        if (get(data, "boot_id").isEmpty()) return false;
        boolean seen = data.optBoolean("acc_on_seen");
        if (acc == 1) {
            if (seen) return true;
            put(data, "acc_on_seen", true);
            return persist(false);
        }
        if (!seen) return true;
        if (number(data, "cycle_count") == Long.MAX_VALUE) return fail("cycle_counter_overflow");
        put(data, "acc_on_seen", false);
        put(data, "cycle_count", number(data, "cycle_count") + 1);
        if (NONE.equals(status(data)) && "history_blocked".equals(get(data, "initial_trigger"))) {
            put(data, "initial_trigger", "acc_cycle");
        }
        return persist(false);
    }

    public synchronized String eligibleTrigger(String bootId) {
        if (!usable || !isBootId(bootId) || !get(data, "boot_id").equalsIgnoreCase(bootId)) return null;
        JSONObject attempt = data.optJSONObject("last_attempt");
        if (attempt == null || NONE.equals(attempt.optString("status"))) {
            String trigger = get(data, "initial_trigger");
            return "history_blocked".equals(trigger) ? null : trigger;
        }
        if (!get(attempt, "boot_id").equalsIgnoreCase(bootId)) return "new_boot";
        return number(data, "cycle_count") > number(attempt, "cycle_count") ? "acc_cycle" : null;
    }

    /** Writes the cycle reservation durably before the caller can start the ON command. */
    public synchronized Reservation reserveAttempt(String bootId, int power, int acc,
                                                     String powerRaw, String accRaw,
                                                     long wallMs, long elapsedMs) {
        String trigger = eligibleTrigger(bootId);
        long count = number(data, "reservation_count");
        if (trigger == null || power != 0 || acc != 0 || count == Long.MAX_VALUE) return null;
        long reservation = count + 1;
        JSONObject attempt = new JSONObject();
        try {
            attempt.put("status", RESERVED).put("reservation", reservation).put("attempt", 0)
                    .put("trigger", trigger).put("boot_id", bootId).put("cycle_count", number(data, "cycle_count"))
                    .put("reserved_wall_ms", nonNegative(wallMs)).put("reserved_elapsed_ms", nonNegative(elapsedMs))
                    .put("started_wall_ms", 0).put("started_elapsed_ms", 0).put("completed_wall_ms", 0)
                    .put("duration_ms", 0).put("power_value", 0).put("acc_value", 0)
                    .put("power_reply", bounded(powerRaw)).put("acc_reply", bounded(accRaw)).put("on_reply", "")
                    .put("shell_ok", false).put("shell_error", "").put("binder_valid", false)
                    .put("binder_exception", 0).put("binder_return", 0).put("binder_error", "");
            put(data, "reservation_count", reservation);
            put(data, "last_attempt", attempt);
        } catch (JSONException failure) {
            fail("state_update_failed");
            return null;
        }
        return persist(false) ? new Reservation(reservation, trigger, bootId, number(data, "cycle_count")) : null;
    }

    public synchronized boolean markNotDispatched(Reservation reservation, String reason,
                                                   int power, int acc, String powerRaw, String accRaw,
                                                   long wallMs, long elapsedMs) {
        JSONObject attempt = currentAttempt(reservation, RESERVED);
        if (attempt == null) return false;
        put(attempt, "status", NOT_DISPATCHED);
        put(attempt, "shell_error", bounded(reason));
        put(attempt, "power_value", power); put(attempt, "acc_value", acc);
        put(attempt, "power_reply", bounded(powerRaw)); put(attempt, "acc_reply", bounded(accRaw));
        put(attempt, "completed_wall_ms", nonNegative(wallMs));
        put(attempt, "duration_ms", elapsedSince(number(attempt, "reserved_elapsed_ms"), elapsedMs));
        return persist(false);
    }

    /** STARTED is fsynced before returning; false means the caller must not dispatch ON. */
    public synchronized boolean markStarted(Reservation reservation, long wallMs, long elapsedMs) {
        JSONObject attempt = currentAttempt(reservation, RESERVED);
        long count = number(data, "attempt_count");
        if (attempt == null || count == Long.MAX_VALUE) return false;
        put(data, "attempt_count", count + 1); put(attempt, "attempt", count + 1);
        put(attempt, "status", STARTED); put(attempt, "started_wall_ms", nonNegative(wallMs));
        put(attempt, "started_elapsed_ms", nonNegative(elapsedMs));
        return persist(false);
    }

    public synchronized boolean recordResult(Reservation reservation, boolean commandOk,
                                             int power, int acc, String powerRaw, String accRaw,
                                             String rawReply, String commandError,
                                             UsbKeepAliveTransport.Result binderResult,
                                             long duration, long wallMs) {
        JSONObject attempt = currentAttempt(reservation, STARTED);
        if (attempt == null) return false;
        put(attempt, "status", RESULT); put(attempt, "shell_ok", commandOk);
        put(attempt, "shell_error", bounded(commandError)); put(attempt, "power_value", power);
        put(attempt, "acc_value", acc); put(attempt, "power_reply", bounded(powerRaw));
        put(attempt, "acc_reply", bounded(accRaw)); put(attempt, "on_reply", bounded(rawReply));
        put(attempt, "binder_valid", commandOk && binderResult != null && binderResult.valid);
        put(attempt, "binder_exception", binderResult == null ? -1 : binderResult.exceptionCode);
        put(attempt, "binder_return", binderResult == null ? -1 : binderResult.returnedInt);
        put(attempt, "binder_error", binderResult == null ? "shell_not_successful" : bounded(binderResult.error));
        put(attempt, "completed_wall_ms", nonNegative(wallMs)); put(attempt, "duration_ms", nonNegative(duration));
        return persist(false);
    }

    private JSONObject currentAttempt(Reservation reservation, String status) {
        JSONObject attempt = data.optJSONObject("last_attempt");
        return usable && reservation != null && attempt != null
                && reservation.id == number(attempt, "reservation")
                && reservation.trigger.equals(get(attempt, "trigger"))
                && status.equals(get(attempt, "status")) ? attempt : null;
    }

    private boolean establishBlockedBaseline(String bootId) {
        data = fresh(); usable = true; error = "";
        put(data, "boot_id", bootId); put(data, "initial_trigger", "history_blocked");
        put(data, "history_recovered", true);
        if (!persist(true)) return false;
        historyRecovered = true;
        recoverable = false;
        return true;
    }

    private boolean persist(boolean recovering) {
        if (!usable) return false;
        try {
            boolean target = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
            if (!recovering && persisted != target) return fail("state_file_presence_changed");
            if (target && !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return fail("state_not_regular_file");
            File parent = file.getParentFile();
            if (parent == null || !parent.isDirectory()) return fail("state_parent_unavailable");
            if (Files.exists(temp.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                if (!recovering) return fail("stale_temp_file");
                Files.delete(temp.toPath());
            }
            // Diagnostic sharing sanitizes JSON records one line at a time.
            byte[] bytes = (data.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FILE_BYTES) return fail("state_size_limit");
            try (FileChannel channel = FileChannel.open(temp.toPath(), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            persisted = true;
            return true;
        } catch (Exception failure) {
            return fail("state_write_failed:" + failure.getClass().getSimpleName());
        }
    }

    private boolean fail(String reason) {
        usable = false; recoverable = false; error = bounded(reason); return false;
    }

    private UsbKeepAliveState corrupt(String reason) {
        usable = false; recoverable = true; error = bounded(reason);
        return this;
    }

    private static void validate(JSONObject root) throws JSONException {
        if (root == null) throw new JSONException("state_missing");
        exactKeys(root, ROOT_KEYS);
        if (root.getInt("format") != 1 || !bootOrEmpty(root.getString("boot_id"))) throw new JSONException("state_header_invalid");
        long cycles = nonNegative(root.get("cycle_count"));
        long reservations = nonNegative(root.get("reservation_count"));
        long attempts = nonNegative(root.get("attempt_count"));
        booleanValue(root.get("acc_on_seen")); booleanValue(root.get("history_recovered"));
        String initial = root.getString("initial_trigger");
        if (!isInitialTrigger(initial)) throw new JSONException("state_trigger_invalid");
        JSONObject last = root.getJSONObject("last_attempt"); exactKeys(last, ATTEMPT_KEYS);
        String status = last.getString("status");
        long reservation = nonNegative(last.get("reservation"));
        long attempt = nonNegative(last.get("attempt"));
        long lastCycle = nonNegative(last.get("cycle_count"));
        for (String key : new String[]{"reserved_wall_ms", "reserved_elapsed_ms", "started_wall_ms",
                "started_elapsed_ms", "completed_wall_ms", "duration_ms"}) nonNegative(last.get(key));
        long exception = signed(last.get("binder_exception"));
        long returned = signed(last.get("binder_return"));
        int power = integer(last.get("power_value")); int acc = integer(last.get("acc_value"));
        booleanValue(last.get("shell_ok")); booleanValue(last.get("binder_valid"));
        for (String key : new String[]{"power_reply", "acc_reply", "on_reply", "shell_error", "binder_error"}) {
            if (last.getString(key).length() > MAX_DETAIL_CHARS) throw new JSONException("state_detail_too_long");
        }
        if (attempts > reservations || reservation > reservations || attempt > attempts || lastCycle > cycles
                || power < -1 || power > 4 || acc < -1 || acc > 1
                || exception < -1 || exception > 0xFFFF_FFFFL || returned < -1 || returned > 0xFFFF_FFFFL) {
            throw new JSONException("state_values_invalid");
        }
        if (NONE.equals(status)) {
            if (reservation != 0 || attempt != 0 || !last.getString("trigger").isEmpty()
                    || !last.getString("boot_id").isEmpty()) throw new JSONException("state_empty_attempt_invalid");
        } else {
            if (!(RESERVED.equals(status) || NOT_DISPATCHED.equals(status) || STARTED.equals(status) || RESULT.equals(status))
                    || reservation == 0 || !isTrigger(last.getString("trigger"))
                    || !isBootId(last.getString("boot_id"))
                    || (!NOT_DISPATCHED.equals(status) && (power != 0 || acc != 0))
                    || ((STARTED.equals(status) || RESULT.equals(status)) != (attempt > 0))) {
                throw new JSONException("state_attempt_invalid");
            }
        }
    }

    private static void exactKeys(JSONObject object, Set<String> expected) throws JSONException {
        Set<String> actual = new HashSet<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) actual.add(keys.next());
        if (!actual.equals(expected)) throw new JSONException("state_keys_invalid");
    }

    private static JSONObject fresh() {
        try {
            JSONObject last = new JSONObject().put("status", NONE).put("reservation", 0).put("attempt", 0)
                    .put("trigger", "").put("boot_id", "").put("cycle_count", 0)
                    .put("reserved_wall_ms", 0).put("reserved_elapsed_ms", 0).put("started_wall_ms", 0)
                    .put("started_elapsed_ms", 0).put("completed_wall_ms", 0).put("duration_ms", 0)
                    .put("power_value", -1).put("acc_value", -1).put("power_reply", "").put("acc_reply", "")
                    .put("on_reply", "").put("shell_ok", false).put("shell_error", "")
                    .put("binder_valid", false).put("binder_exception", -1).put("binder_return", -1)
                    .put("binder_error", "");
            return new JSONObject().put("format", 1).put("boot_id", "").put("cycle_count", 0)
                    .put("acc_on_seen", false).put("reservation_count", 0).put("attempt_count", 0)
                    .put("initial_trigger", "first_enable_off").put("history_recovered", false)
                    .put("last_attempt", last);
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static JSONObject parseJson(byte[] bytes) throws JSONException, CharacterCodingException {
        JSONTokener tokener = new JSONTokener(decode(bytes));
        Object value = tokener.nextValue();
        if (!(value instanceof JSONObject) || tokener.nextClean() != 0) {
            throw new JSONException("state_json_invalid");
        }
        return (JSONObject) value;
    }

    private static long nonNegative(Object value) throws JSONException {
        long number = integral(value);
        if (number < 0) throw new JSONException("state_number_invalid");
        return number;
    }

    private static long signed(Object value) throws JSONException { return integral(value); }

    private static long integral(Object value) throws JSONException {
        if (!(value instanceof Number)) throw new JSONException("state_number_invalid");
        try { return Long.parseLong(value.toString()); }
        catch (NumberFormatException failure) {
            JSONException invalid = new JSONException("state_number_invalid");
            invalid.initCause(failure);
            throw invalid;
        }
    }

    private static int integer(Object value) throws JSONException {
        long number = integral(value);
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw new JSONException("state_integer_invalid");
        return (int) number;
    }

    private static boolean booleanValue(Object value) throws JSONException {
        if (!(value instanceof Boolean)) throw new JSONException("state_boolean_invalid");
        return (Boolean) value;
    }

    private static String get(JSONObject object, String key) { return object.optString(key, ""); }
    private static long number(JSONObject object, String key) { return object.optLong(key, -1); }
    private static String status(JSONObject object) { return get(object.optJSONObject("last_attempt"), "status"); }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (JSONException failure) { throw new IllegalStateException(failure); }
    }
    private static String bounded(String value) {
        if (value == null) return "";
        return value.length() <= MAX_DETAIL_CHARS ? value : value.substring(0, MAX_DETAIL_CHARS);
    }
    private static long nonNegative(long value) { return Math.max(0, value); }
    private static long elapsedSince(long start, long end) { return end < start ? 0 : end - start; }
    private static boolean bootOrEmpty(String value) { return value.isEmpty() || isBootId(value); }
    private static boolean isBootId(String value) {
        if (value == null || value.length() != 36) return false;
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException ignored) { return false; }
    }
    private static boolean isTrigger(String value) {
        return "first_enable_off".equals(value) || "new_boot".equals(value) || "acc_cycle".equals(value);
    }
    private static boolean isInitialTrigger(String value) {
        return isTrigger(value) || "history_blocked".equals(value);
    }

    public static final class Reservation {
        public final long id;
        public final String trigger;
        public final String bootId;
        public final long cycleCount;
        private Reservation(long id, String trigger, String bootId, long cycleCount) {
            this.id = id; this.trigger = trigger; this.bootId = bootId; this.cycleCount = cycleCount;
        }
    }

    public static final class Snapshot {
        public final boolean usable, binderValid, historyRecovered;
        public final String error, bootId, lastStatus, lastTrigger;
        public final long cycleCount, reservationCount, attemptCount;
        public final int cycleReservations, cycleAttempts;
        private Snapshot(boolean usable, String error, String bootId, long cycleCount,
                         long reservationCount, long attemptCount, String lastStatus,
                         String lastTrigger, boolean binderValid, boolean historyRecovered,
                         int cycleReservations, int cycleAttempts) {
            this.usable = usable; this.error = error; this.bootId = bootId; this.cycleCount = cycleCount;
            this.reservationCount = reservationCount; this.attemptCount = attemptCount;
            this.lastStatus = lastStatus; this.lastTrigger = lastTrigger; this.binderValid = binderValid;
            this.historyRecovered = historyRecovered; this.cycleReservations = cycleReservations;
            this.cycleAttempts = cycleAttempts;
        }
    }
}
