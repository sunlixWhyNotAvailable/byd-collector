package com.bydcollector.collector.direct;

import java.util.UUID;

/** Pure synchronized ownership/lease state shared by the daemon's two telemetry streams. */
final class HelperStreamRuntimeState {
    static final long LEASE_MS = 2_000L;
    static final int DESIRED_MAIN = 1;
    static final int DESIRED_SECONDARY = 2;

    private final StreamState main = new StreamState();
    private final StreamState secondary = new StreamState();
    private String sessionNonce;
    private long controllerToken;

    synchronized ControlResult claim(String nonce, int desiredMask, long nowMs) {
        if (!validNonce(nonce) || desiredMask < 0 || desiredMask > 3) {
            return result(CollectorHelperProtocol.STATUS_INVALID_REQUEST, 0, nowMs, "invalid claim");
        }
        if (nonce.equals(sessionNonce) && controllerToken != 0L) {
            return result(CollectorHelperProtocol.STATUS_OK, 0, nowMs, null);
        }
        sessionNonce = nonce;
        controllerToken = newToken();
        replace(main, (desiredMask & DESIRED_MAIN) != 0, nowMs);
        replace(secondary, (desiredMask & DESIRED_SECONDARY) != 0, nowMs);
        return result(CollectorHelperProtocol.STATUS_OK, 0, nowMs, null);
    }

    synchronized ControlResult setDesired(
        long token, int stream, long expectedEpoch, int value, long nowMs
    ) {
        StreamState state = stream(stream);
        ControlResult invalid = validate(token, state, stream, expectedEpoch, nowMs, false);
        if (invalid != null) return invalid;
        if (value != 0 && value != 1) return result(
            CollectorHelperProtocol.STATUS_INVALID_REQUEST, stream, nowMs, "desired must be 0 or 1");
        boolean desired = value == 1;
        if (state.desired == desired) return result(CollectorHelperProtocol.STATUS_OK, stream, nowMs, null);
        state.desired = desired;
        state.paused = false;
        state.pausePending = false;
        state.epoch = nextEpoch(state.epoch);
        state.workGeneration = nextEpoch(state.workGeneration);
        state.leaseExpiresMs = desired ? saturatedAdd(nowMs, LEASE_MS) : 0L;
        return result(CollectorHelperProtocol.STATUS_OK, stream, nowMs, null);
    }

    synchronized ControlResult renew(long token, int stream, long expectedEpoch, long nowMs) {
        StreamState state = stream(stream);
        ControlResult invalid = validate(token, state, stream, expectedEpoch, nowMs, false);
        if (invalid != null) return invalid;
        if (!state.desired) return result(
            CollectorHelperProtocol.STATUS_INVALID_REQUEST, stream, nowMs, "stream is stopped");
        if (nowMs >= state.leaseExpiresMs) state.workGeneration = nextEpoch(state.workGeneration);
        state.leaseExpiresMs = saturatedAdd(nowMs, LEASE_MS);
        return result(CollectorHelperProtocol.STATUS_OK, stream, nowMs, null);
    }

    synchronized ControlResult beginPause(long token, int stream, long expectedEpoch, long nowMs) {
        StreamState state = stream(stream);
        ControlResult invalid = validate(token, state, stream, expectedEpoch, nowMs, true);
        if (invalid != null) return invalid;
        if (state.paused) return result(CollectorHelperProtocol.STATUS_OK, stream, nowMs, null);
        if (state.pausePending) return result(
            CollectorHelperProtocol.STATUS_INVALID_REQUEST, stream, nowMs, "pause already pending");
        state.pausePending = true;
        state.workGeneration = nextEpoch(state.workGeneration);
        return result(CollectorHelperProtocol.STATUS_OK, stream, nowMs, null);
    }

    synchronized ControlResult completePause(long token, int stream, long expectedEpoch, long nowMs) {
        StreamState state = stream(stream);
        expire(state, nowMs);
        ControlResult invalid = validate(token, state, stream, expectedEpoch, nowMs, true);
        if (invalid != null) return invalid;
        if (!state.pausePending) {
            return state.paused
                ? result(CollectorHelperProtocol.STATUS_OK, stream, nowMs, null)
                : result(CollectorHelperProtocol.STATUS_INVALID_REQUEST, stream, nowMs, "pause is not pending");
        }
        state.pausePending = false;
        state.paused = true;
        state.epoch = nextEpoch(state.epoch);
        return result(CollectorHelperProtocol.STATUS_OK, stream, nowMs, null);
    }

    synchronized void cancelPause(long token, int stream, long epoch) {
        StreamState state = stream(stream);
        if (state != null && token == controllerToken && epoch == state.epoch) state.pausePending = false;
    }

    synchronized ControlResult fenceFailure(
        long token, int stream, long expectedEpoch, long nowMs, String reason
    ) {
        StreamState state = stream(stream);
        ControlResult invalid = validate(token, state, stream, expectedEpoch, nowMs, true);
        if (invalid != null) return invalid;
        return result(CollectorHelperProtocol.STATUS_INVALID_REQUEST, stream, nowMs, reason);
    }

    synchronized ControlResult resume(long token, int stream, long expectedEpoch, long nowMs) {
        StreamState state = stream(stream);
        ControlResult invalid = validate(token, state, stream, expectedEpoch, nowMs, true);
        if (invalid != null) return invalid;
        if (!state.paused && !state.pausePending) {
            return result(CollectorHelperProtocol.STATUS_OK, stream, nowMs, null);
        }
        state.paused = false;
        state.pausePending = false;
        state.epoch = nextEpoch(state.epoch);
        state.workGeneration = nextEpoch(state.workGeneration);
        return result(CollectorHelperProtocol.STATUS_OK, stream, nowMs, null);
    }

    synchronized int authorizeLive(long token, int stream, long epoch, long nowMs) {
        StreamState state = stream(stream);
        ControlResult invalid = validate(token, state, stream, epoch, nowMs, true);
        if (invalid != null) return invalid.status;
        if (!state.desired || nowMs >= state.leaseExpiresMs) return CollectorHelperProtocol.STATUS_LEASE_EXPIRED;
        if (state.paused || state.pausePending) return CollectorHelperProtocol.STATUS_REPLAY_PENDING;
        return CollectorHelperProtocol.STATUS_OK;
    }

    /** Allows credentialed replay while a live-capture pause fence is held for archival. */
    synchronized int authorizeReplay(long token, int stream, long epoch, long nowMs) {
        StreamState state = stream(stream);
        ControlResult invalid = validate(token, state, stream, epoch, nowMs, true);
        if (invalid != null) return invalid.status;
        return state.desired && nowMs < state.leaseExpiresMs
            ? CollectorHelperProtocol.STATUS_OK
            : CollectorHelperProtocol.STATUS_LEASE_EXPIRED;
    }

    synchronized boolean replayAllowed(int stream, long nowMs) {
        StreamState state = stream(stream);
        if (state == null) return false;
        expire(state, nowMs);
        return controllerToken != 0L && state.desired && nowMs < state.leaseExpiresMs;
    }

    synchronized boolean fallbackAllowed(int stream, long nowMs) {
        StreamState state = stream(stream);
        if (state == null) return false;
        expire(state, nowMs);
        return controllerToken != 0L && state.desired && !state.paused && !state.pausePending &&
            nowMs >= state.leaseExpiresMs;
    }

    /** Holds post-fence callback publication while capture continues into the bounded next-epoch queue. */
    synchronized boolean callbackPublishingHeld(int stream, long nowMs) {
        StreamState state = stream(stream);
        if (state == null) return false;
        expire(state, nowMs);
        return controllerToken != 0L && state.desired && nowMs < state.leaseExpiresMs &&
            (state.paused || state.pausePending);
    }

    synchronized long workGeneration(int stream, long nowMs) {
        StreamState state = stream(stream);
        if (state == null) return -1L;
        expire(state, nowMs);
        return state.workGeneration;
    }

    synchronized ControlResult snapshot(int stream, long nowMs) {
        return result(CollectorHelperProtocol.STATUS_OK, stream, nowMs, null);
    }

    synchronized StreamView streamView(int stream, long nowMs) {
        StreamState value = stream(stream);
        if (value == null) throw new IllegalArgumentException("invalid stream");
        expire(value, nowMs);
        return new StreamView(value.desired, value.paused || value.pausePending, value.epoch,
            value.desired && nowMs < value.leaseExpiresMs);
    }

    private ControlResult validate(
        long token, StreamState state, int stream, long expectedEpoch, long nowMs, boolean requireActiveLease
    ) {
        if (state == null) return result(
            CollectorHelperProtocol.STATUS_INVALID_REQUEST, stream, nowMs, "invalid stream");
        expire(state, nowMs);
        if (controllerToken == 0L || token != controllerToken) return result(
            CollectorHelperProtocol.STATUS_STALE_TOKEN, stream, nowMs, "stale controller token");
        if (expectedEpoch != state.epoch) return result(
            CollectorHelperProtocol.STATUS_STALE_TOKEN, stream, nowMs, "stale stream epoch");
        if (requireActiveLease && (!state.desired || nowMs >= state.leaseExpiresMs)) return result(
            CollectorHelperProtocol.STATUS_LEASE_EXPIRED, stream, nowMs, "stream lease expired");
        return null;
    }

    private void replace(StreamState state, boolean desired, long nowMs) {
        state.desired = desired;
        state.paused = false;
        state.pausePending = false;
        state.epoch = nextEpoch(state.epoch);
        state.workGeneration = nextEpoch(state.workGeneration);
        state.leaseExpiresMs = desired ? saturatedAdd(nowMs, LEASE_MS) : 0L;
    }

    private void expire(StreamState state, long nowMs) {
        if (state.desired && nowMs >= state.leaseExpiresMs && (state.paused || state.pausePending)) {
            state.paused = false;
            state.pausePending = false;
            state.workGeneration = nextEpoch(state.workGeneration);
        }
    }

    private ControlResult result(int status, int selectedStream, long nowMs, String error) {
        expire(main, nowMs);
        expire(secondary, nowMs);
        StreamState selected = stream(selectedStream);
        long expiry = selected != null && selected.desired ? selected.leaseExpiresMs : 0L;
        return new ControlResult(status, controllerToken, main.epoch, secondary.epoch, expiry, error);
    }

    private StreamState stream(int stream) {
        if (stream == CollectorHelperProtocol.STREAM_MAIN) return main;
        if (stream == CollectorHelperProtocol.STREAM_SECONDARY) return secondary;
        return null;
    }

    private static boolean validNonce(String nonce) {
        return nonce != null && !nonce.isEmpty() && nonce.length() <= 64;
    }

    private static long nextEpoch(long current) {
        return current == Long.MAX_VALUE ? 1L : current + 1L;
    }

    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static long newToken() {
        long token;
        do {
            UUID value = UUID.randomUUID();
            token = (value.getMostSignificantBits() ^ value.getLeastSignificantBits()) & Long.MAX_VALUE;
        } while (token == 0L);
        return token;
    }

    private static final class StreamState {
        boolean desired;
        boolean paused;
        boolean pausePending;
        long epoch = 1L;
        long workGeneration = 1L;
        long leaseExpiresMs;
    }

    static final class ControlResult {
        final int status;
        final long controllerToken;
        final long mainEpoch;
        final long secondaryEpoch;
        final long leaseExpiresElapsedMs;
        final String error;

        ControlResult(
            int status, long controllerToken, long mainEpoch, long secondaryEpoch,
            long leaseExpiresElapsedMs, String error
        ) {
            this.status = status;
            this.controllerToken = controllerToken;
            this.mainEpoch = mainEpoch;
            this.secondaryEpoch = secondaryEpoch;
            this.leaseExpiresElapsedMs = leaseExpiresElapsedMs;
            this.error = error;
        }
    }

    static final class StreamView {
        final boolean desired;
        final boolean capturePaused;
        final long epoch;
        final boolean activeLease;

        StreamView(boolean desired, boolean capturePaused, long epoch, boolean activeLease) {
            this.desired = desired;
            this.capturePaused = capturePaused;
            this.epoch = epoch;
            this.activeLease = activeLease;
        }
    }
}
