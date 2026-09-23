package com.bydcollector.collector.diagnostics;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** Fixed-memory process CPU and poll-duration window shared by app and helper diagnostics. */
public final class BoundedProcessWindow {
    public static final long WINDOW_MS = 30_000L;
    public static final int SAMPLE_CAPACITY = 64;
    public static final int STREAM_MAIN = 1;
    public static final int STREAM_SECONDARY = 2;

    private final LongSupplier elapsedRealtimeMs;
    private final LongSupplier processCpuMs;
    private final long[][] samples = new long[2][SAMPLE_CAPACITY];
    private final int[] retained = new int[2];
    private final int[] nextIndex = new int[2];
    private final long[] totals = new long[2];
    private final long[] maxima = new long[2];
    private final boolean[] hasMaximum = new boolean[2];

    private long windowStartedElapsedMs;
    private long windowStartedCpuMs;
    private boolean active;
    private Snapshot terminalSnapshot;

    public BoundedProcessWindow(LongSupplier elapsedRealtimeMs, LongSupplier processCpuMs) {
        if (elapsedRealtimeMs == null || processCpuMs == null) {
            throw new IllegalArgumentException("process window clocks are required");
        }
        this.elapsedRealtimeMs = elapsedRealtimeMs;
        this.processCpuMs = processCpuMs;
        reset();
    }

    /** Clears samples and establishes a fresh elapsed-time and process-CPU baseline. */
    public synchronized void reset() {
        clearSamples();
        windowStartedElapsedMs = elapsedRealtimeMs.getAsLong();
        windowStartedCpuMs = readProcessCpuMs();
        terminalSnapshot = null;
        active = true;
    }

    /** Returns true only for the first sample for this stream in the current window. */
    public synchronized boolean recordPollDuration(int stream, long durationMs) {
        if (durationMs < 0L) throw new IllegalArgumentException("poll duration must not be negative");
        int index = streamIndex(stream);
        if (!active) return false;

        boolean first = totals[index] == 0L;
        totals[index] = saturatedIncrement(totals[index]);
        if (retained[index] < SAMPLE_CAPACITY) {
            samples[index][retained[index]++] = durationMs;
        } else {
            samples[index][nextIndex[index]] = durationMs;
        }
        nextIndex[index] = (nextIndex[index] + 1) % SAMPLE_CAPACITY;
        if (!hasMaximum[index] || durationMs > maxima[index]) {
            maxima[index] = durationMs;
            hasMaximum[index] = true;
        }
        return first;
    }

    /** Emits and rolls the window only after it has lasted at least 30 seconds. */
    public synchronized Snapshot snapshotIfDue() {
        if (!active) return null;
        long nowElapsedMs = elapsedRealtimeMs.getAsLong();
        long elapsedMs = elapsedSinceStart(nowElapsedMs);
        if (elapsedMs < WINDOW_MS) return null;

        long currentCpuMs = readProcessCpuMs();
        Snapshot snapshot = snapshot(elapsedMs, currentCpuMs, false);
        clearSamples();
        windowStartedElapsedMs = nowElapsedMs;
        windowStartedCpuMs = currentCpuMs;
        return snapshot;
    }

    /** Cheap scheduler guard; does not allocate, copy samples, or rotate the window. */
    public synchronized boolean isDue() {
        if (!active) return false;
        return elapsedSinceStart(elapsedRealtimeMs.getAsLong()) >= WINDOW_MS;
    }

    /** Emits a terminal snapshot even for a short window and stops accepting samples. */
    public synchronized Snapshot finishWindow() {
        if (!active) return terminalSnapshot;
        long nowElapsedMs = elapsedRealtimeMs.getAsLong();
        long elapsedMs = elapsedSinceStart(nowElapsedMs);
        terminalSnapshot = snapshot(elapsedMs, readProcessCpuMs(), true);
        active = false;
        return terminalSnapshot;
    }

    /** Reads current bounded metrics without rotating or resetting the interval. */
    public synchronized Snapshot currentSnapshot() {
        if (!active) return terminalSnapshot;
        long nowElapsedMs = elapsedRealtimeMs.getAsLong();
        return snapshot(elapsedSinceStart(nowElapsedMs), readProcessCpuMs(), false);
    }

    private Snapshot snapshot(long elapsedMs, long currentCpuMs, boolean terminal) {
        Long cpuDeltaMs = windowStartedCpuMs >= 0L && currentCpuMs >= windowStartedCpuMs
            ? currentCpuMs - windowStartedCpuMs
            : null;
        return new Snapshot(
            elapsedMs,
            cpuDeltaMs,
            terminal,
            elapsedMs < WINDOW_MS,
            streamSnapshot(0),
            streamSnapshot(1)
        );
    }

    private StreamSnapshot streamSnapshot(int index) {
        int count = retained[index];
        long[] ordered = Arrays.copyOf(samples[index], count);
        Arrays.sort(ordered);
        return new StreamSnapshot(
            totals[index],
            count,
            totals[index] >= count ? totals[index] - count : Long.MAX_VALUE,
            hasMaximum[index] ? maxima[index] : null,
            percentile(ordered, 50),
            percentile(ordered, 95)
        );
    }

    private long elapsedSinceStart(long nowElapsedMs) {
        return nowElapsedMs >= windowStartedElapsedMs
            ? nowElapsedMs - windowStartedElapsedMs
            : 0L;
    }

    private long readProcessCpuMs() {
        try {
            return processCpuMs.getAsLong();
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private void clearSamples() {
        Arrays.fill(retained, 0);
        Arrays.fill(nextIndex, 0);
        Arrays.fill(totals, 0L);
        Arrays.fill(maxima, 0L);
        Arrays.fill(hasMaximum, false);
    }

    private static Long percentile(long[] sorted, int percentile) {
        if (sorted.length == 0) return null;
        int rank = (percentile * sorted.length + 99) / 100;
        return sorted[rank - 1];
    }

    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? value : value + 1L;
    }

    private static int streamIndex(int stream) {
        if (stream == STREAM_MAIN) return 0;
        if (stream == STREAM_SECONDARY) return 1;
        throw new IllegalArgumentException("unknown stream: " + stream);
    }

    public static final class Snapshot {
        private final long windowElapsedMs;
        private final Long processCpuDeltaMs;
        private final boolean terminal;
        private final boolean shortWindow;
        private final StreamSnapshot main;
        private final StreamSnapshot secondary;

        private Snapshot(
            long windowElapsedMs,
            Long processCpuDeltaMs,
            boolean terminal,
            boolean shortWindow,
            StreamSnapshot main,
            StreamSnapshot secondary
        ) {
            this.windowElapsedMs = windowElapsedMs;
            this.processCpuDeltaMs = processCpuDeltaMs;
            this.terminal = terminal;
            this.shortWindow = shortWindow;
            this.main = main;
            this.secondary = secondary;
        }

        public long windowElapsedMs() { return windowElapsedMs; }
        public Long processCpuDeltaMs() { return processCpuDeltaMs; }
        public boolean terminal() { return terminal; }
        public boolean shortWindow() { return shortWindow; }
        public StreamSnapshot main() { return main; }
        public StreamSnapshot secondary() { return secondary; }

        public JSONObject toJson() throws Exception {
            JSONObject streams = new JSONObject();
            streams.put("main", main.toJson());
            streams.put("secondary", secondary.toJson());
            JSONObject json = new JSONObject();
            json.put("window_elapsed_ms", windowElapsedMs);
            json.put("window_terminal", terminal);
            json.put("window_short", shortWindow);
            json.put("process_cpu_delta_ms", processCpuDeltaMs == null ? JSONObject.NULL : processCpuDeltaMs);
            json.put("percentile_basis", "retained_last_64_samples_or_all_if_fewer");
            json.put("streams", streams);
            return json;
        }
    }

    public static final class StreamSnapshot {
        private final long totalSamples;
        private final int retainedSamples;
        private final long overflowSamples;
        private final Long maxMs;
        private final Long p50Ms;
        private final Long p95Ms;

        private StreamSnapshot(
            long totalSamples,
            int retainedSamples,
            long overflowSamples,
            Long maxMs,
            Long p50Ms,
            Long p95Ms
        ) {
            this.totalSamples = totalSamples;
            this.retainedSamples = retainedSamples;
            this.overflowSamples = overflowSamples;
            this.maxMs = maxMs;
            this.p50Ms = p50Ms;
            this.p95Ms = p95Ms;
        }

        public long totalSamples() { return totalSamples; }
        public int retainedSamples() { return retainedSamples; }
        public long overflowSamples() { return overflowSamples; }
        public Long maxMs() { return maxMs; }
        public Long p50Ms() { return p50Ms; }
        public Long p95Ms() { return p95Ms; }

        private JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("total_samples", totalSamples);
            json.put("retained_samples", retainedSamples);
            json.put("overflow_samples", overflowSamples);
            json.put("max_ms", maxMs == null ? JSONObject.NULL : maxMs);
            json.put("p50_ms", p50Ms == null ? JSONObject.NULL : p50Ms);
            json.put("p95_ms", p95Ms == null ? JSONObject.NULL : p95Ms);
            return json;
        }
    }
}
