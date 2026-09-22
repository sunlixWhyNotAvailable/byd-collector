package com.bydcollector.collector.direct;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Helper-owned, passive spool for complete secondary-catalog cycles.
 *
 * <p>The producer always supplies a complete, ordinal-ordered frame. This class owns the
 * in-memory FULL/DELTA baseline and advances it only after an atomic durable append. Records are
 * immutable once renamed to {@code .ready}; consumers page bytes and acknowledge an exact
 * identity/length/SHA-256 tuple.</p>
 */
public final class SecondaryTelemetrySpool implements AutoCloseable {
    public static final String SPOOL_DIRECTORY_PATH = "/data/local/tmp/bydcollector_secondary_spool";
    public static final long MAX_SPOOL_BYTES = 128L * 1024L * 1024L;
    public static final int EXPECTED_FIELD_COUNT = TelemetryCatalogPolicy.SECONDARY_ACTIVE_COUNT;
    public static final int MAX_SLICE_BYTES = 224 * 1024;
    public static final int RECORD_VERSION = 2;
    public static final int LEGACY_RECORD_VERSION = 1;
    public static final int MAX_IDENTIFIER_CHARS = 256;

    private static final String READY_SUFFIX = ".ready";
    private static final String TMP_SUFFIX = ".tmp";
    private static final String BAD_SUFFIX = ".bad";
    private static final String POISON_SUFFIX = ".bad.poison";
    private static final String LOSS_FILE = "secondary-loss.json";
    private static final String LOSS_TMP = "secondary-loss.tmp";
    private static final int LOSS_RESERVE_BYTES = 8 * 1024;
    private static final int MAX_DIAGNOSTIC_TEXT = 512;
    private static final FailureInjector NO_FAILURES = (operation, file) -> { };
    private static final DiagnosticListener NO_DIAGNOSTICS = new DiagnosticListener() { };

    private final File directory;
    private final long maxBytes;
    private final int expectedFieldCount;
    private final long lossReserveBytes;
    private final FailureInjector failures;
    private DiagnosticListener diagnostics = NO_DIAGNOSTICS;
    private List<Value> baseline;
    private CycleIdentity baselineIdentity;
    private String baselineCatalogVersion;
    private long nextOrder;
    private boolean forceFull = true;
    private boolean closed;

    public static SecondaryTelemetrySpool open() {
        return open(new File(SPOOL_DIRECTORY_PATH), MAX_SPOOL_BYTES, EXPECTED_FIELD_COUNT, NO_FAILURES);
    }

    static SecondaryTelemetrySpool openForTest(File directory, long maxBytes, int expectedFieldCount) {
        return open(directory, maxBytes, expectedFieldCount, NO_FAILURES);
    }

    static SecondaryTelemetrySpool openForTest(
        File directory,
        long maxBytes,
        int expectedFieldCount,
        FailureInjector failures
    ) {
        return open(directory, maxBytes, expectedFieldCount, failures);
    }

    private static SecondaryTelemetrySpool open(
        File directory,
        long maxBytes,
        int expectedFieldCount,
        FailureInjector failures
    ) {
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IllegalStateException("cannot create secondary spool directory: " + directory);
        }
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        if (expectedFieldCount <= 0) throw new IllegalArgumentException("expectedFieldCount must be positive");
        if (failures == null) throw new IllegalArgumentException("failures is required");
        return new SecondaryTelemetrySpool(directory, maxBytes, expectedFieldCount, failures);
    }

    private SecondaryTelemetrySpool(File directory, long maxBytes, int expectedFieldCount, FailureInjector failures) {
        this.directory = directory;
        this.maxBytes = maxBytes;
        this.expectedFieldCount = expectedFieldCount;
        this.failures = failures;
        this.lossReserveBytes = Math.min(LOSS_RESERVE_BYTES, Math.max(512L, maxBytes / 8L));
        recoverTemporaries();
        this.nextOrder = findNextOrder(directory);
    }

    /** Called before use, while the daemon holds its exclusive process-owner lock. */
    private void recoverTemporaries() {
        File[] temporaryFiles = directory.listFiles((dir, name) ->
            name.equals(LOSS_TMP) || name.matches("s[0-9]{20}_[0-9a-f]{64}\\.tmp"));
        if (temporaryFiles == null) throw new IllegalStateException("cannot list secondary spool: " + directory);
        for (File temporary : temporaryFiles) {
            try {
                if (temporary.getName().equals(LOSS_TMP)) {
                    try {
                        if (temporary.length() > lossReserveBytes) throw new IllegalArgumentException("oversize loss temporary");
                        LossSummary.decode(new JSONObject(new String(readBytes(temporary), StandardCharsets.UTF_8)));
                    } catch (IllegalArgumentException | JSONException malformed) {
                        quarantineFile(temporary, "incomplete loss temporary", false);
                        continue;
                    }
                    Files.move(temporary.toPath(), new File(directory, LOSS_FILE).toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    continue;
                }
                Record record;
                try {
                    if (temporary.length() > maxBytes) throw new IllegalArgumentException("oversize record temporary");
                    record = Codec.decode(readBytes(temporary));
                    if (!temporary.getName().equals(fileStem(record.spoolOrder, record.identity) + TMP_SUFFIX)) {
                        throw new IllegalArgumentException("temporary identity mismatch");
                    }
                } catch (IllegalArgumentException malformed) {
                    // Preserve incomplete raw evidence and poison dependent DELTAs, rather than
                    // leaving an invisible temporary permanently charged to the ready queue.
                    quarantineFile(temporary, "incomplete record temporary", true);
                    continue;
                }
                File ready = new File(directory, fileStem(record.spoolOrder, record.identity) + READY_SUFFIX);
                if (!ready.exists()) {
                    Files.move(temporary.toPath(), ready.toPath(), StandardCopyOption.ATOMIC_MOVE);
                } else if (sha256(ready).equals(sha256(temporary))) {
                    Files.delete(temporary.toPath());
                } else {
                    quarantineFile(temporary, "temporary conflicts with ready record", true);
                }
            } catch (IOException error) {
                throw new IllegalStateException("cannot recover secondary temporary: " + temporary.getName(), error);
            }
        }
        // Never rebuild a producer delta baseline from recovered files. The next append is FULL.
    }

    public synchronized void setDiagnosticListener(DiagnosticListener listener) {
        ensureOpen();
        diagnostics = listener == null ? NO_DIAGNOSTICS : listener;
    }

    /** Append one complete logical cycle. No disk work is performed by another callback/lock. */
    public synchronized AppendResult append(Cycle cycle) {
        synchronized (CallbackSpool.persistenceLock(directory)) {
            return appendShared(cycle);
        }
    }

    private AppendResult appendShared(Cycle cycle) {
        ensureOpen();
        validateCycle(cycle, expectedFieldCount);
        if (findReady(cycle.identity) != null) return AppendResult.DUPLICATE;

        LossSummary loss = readLoss();
        boolean full = forceFull || loss != null || baseline == null ||
            !cycle.identity.sameEpoch(baselineIdentity) ||
            !cycle.catalogVersion.equals(baselineCatalogVersion) ||
            cycle.identity.sequence != baselineIdentity.sequence + 1L;
        List<Value> persistedValues = full ? cycle.values : changes(baseline, cycle.values);
        int okCount = 0;
        for (Value value : cycle.values) if (value.status == 0 && value.rawPresent) okCount++;
        Record record = new Record(
            nextOrder,
            full ? Kind.FULL : Kind.DELTA,
            cycle.identity,
            cycle.catalogVersion,
            cycle.capturedWallMs,
            cycle.capturedElapsedMs,
            cycle.cycleElapsedMs,
            cycle.batchStatus,
            cycle.batchMode,
            cycle.nativeAvailable,
            cycle.nativeGroupCount,
            cycle.fallbackGroupCount,
            cycle.fallbackReadCount,
            cycle.groupFailureCount,
            cycle.error,
            expectedFieldCount,
            okCount,
            expectedFieldCount - okCount,
            persistedValues,
            loss,
            full ? null : baselineIdentity
        );
        byte[] bytes = Codec.encode(record);
        long footprint = footprintBytes();
        // Keep space for both the current loss sidecar and its atomic replacement temporary.
        long ordinaryLimit = Math.max(0L, maxBytes - (2L * lossReserveBytes));
        if (footprint > ordinaryLimit || bytes.length > ordinaryLimit - footprint) {
            LossSummary updated = LossSummary.include(loss, cycle.identity, cycle.capturedWallMs, cycle.capturedElapsedMs);
            baseline = null;
            baselineIdentity = null;
            baselineCatalogVersion = null;
            forceFull = true;
            persistLoss(updated);
            notifyLoss(updated);
            return AppendResult.CAP_REACHED;
        }

        String stem = fileStem(record.spoolOrder, record.identity);
        File temporary = new File(directory, stem + TMP_SUFFIX);
        File ready = new File(directory, stem + READY_SUFFIX);
        try {
            failures.before("append", temporary);
            writeDurably(temporary, bytes);
            failures.before("append_publish", temporary);
            Files.move(temporary.toPath(), ready.toPath(), StandardCopyOption.ATOMIC_MOVE);
            baseline = copyValues(cycle.values);
            baselineIdentity = cycle.identity;
            baselineCatalogVersion = cycle.catalogVersion;
            forceFull = false;
            nextOrder++;
            if (loss != null && !new File(directory, LOSS_FILE).delete()) {
                notifyPersistenceFailure("clear_loss", new IOException("cannot delete persisted loss summary"));
            }
            notifyAppend(record, bytes.length);
            return full ? AppendResult.FULL : AppendResult.DELTA;
        } catch (IOException error) {
            if (temporary.isFile()) temporary.delete();
            baseline = null;
            baselineIdentity = null;
            baselineCatalogVersion = null;
            forceFull = true;
            try {
                LossSummary updated = LossSummary.include(loss, cycle.identity, cycle.capturedWallMs, cycle.capturedElapsedMs);
                persistLoss(updated);
                notifyLoss(updated);
            } catch (Throwable lossError) {
                error.addSuppressed(lossError);
            }
            notifyPersistenceFailure("append", error);
            throw new IllegalStateException("cannot persist secondary cycle", error);
        }
    }

    /** Returns the oldest valid immutable record descriptor, quarantining corrupt evidence. */
    public synchronized Descriptor oldest() {
        ensureOpen();
        File[] files = replayChainFiles();
        java.util.Arrays.sort(files, Comparator.comparing(File::getName));
        boolean orphaned = false;
        List<File> poisonMarkers = new ArrayList<>();
        for (File file : files) {
            if (file.getName().contains(POISON_SUFFIX)) {
                invalidateBaseline();
                orphaned = true;
                poisonMarkers.add(file);
                continue;
            }
            Record record;
            try {
                record = Codec.decode(readBytes(file));
                if (!file.getName().equals(fileStem(record.spoolOrder, record.identity) + READY_SUFFIX)) {
                    throw new IllegalArgumentException("record filename mismatch");
                }
            } catch (Exception error) {
                invalidateBaseline();
                File marker = quarantineFile(file, "malformed: " + safeText(error.getMessage()), true);
                poisonMarkers.add(marker);
                orphaned = true;
                continue;
            }
            if (orphaned && record.kind == Kind.DELTA) {
                File marker = quarantineFile(file, "orphaned after malformed predecessor", true);
                poisonMarkers.add(marker);
                continue;
            }
            if (record.kind == Kind.FULL) clearPoisonMarkers(poisonMarkers);
            try {
                return new Descriptor(
                    record.spoolOrder, record.identity, record.kind, record.capturedWallMs,
                    record.capturedElapsedMs, file.getName(), file.length(), sha256(file)
                );
            } catch (IOException error) {
                notifyPersistenceFailure("describe", error);
                throw new IllegalStateException("cannot describe secondary record", error);
            }
        }
        return null;
    }

    public synchronized byte[] readSlice(Descriptor descriptor, long offset, int limit) {
        ensureOpen();
        validateDescriptorRequest(descriptor);
        if (offset < 0 || offset > descriptor.length) throw new IllegalArgumentException("invalid offset");
        if (limit < 1 || limit > MAX_SLICE_BYTES) throw new IllegalArgumentException("invalid slice limit");
        File ready = exactReady(descriptor);
        validateExactFile(ready, descriptor);
        int count = (int) Math.min((long) limit, descriptor.length - offset);
        byte[] page = new byte[count];
        try (RandomAccessFile input = new RandomAccessFile(ready, "r")) {
            input.seek(offset);
            input.readFully(page);
            return page.clone();
        } catch (IOException error) {
            notifyPersistenceFailure("read_slice", error);
            throw new IllegalStateException("cannot read secondary record slice", error);
        }
    }

    public synchronized AckResult acknowledge(Descriptor descriptor) {
        ensureOpen();
        validateDescriptorRequest(descriptor);
        File ready = exactReady(descriptor);
        if (!ready.isFile()) return AckResult.NOT_FOUND;
        try {
            validateExactFile(ready, descriptor);
            Record record = Codec.decode(readBytes(ready));
            if (!record.identity.equals(descriptor.identity) || record.spoolOrder != descriptor.spoolOrder) {
                return AckResult.REJECTED;
            }
            return ready.delete() ? AckResult.RELEASED : AckResult.REJECTED;
        } catch (Exception error) {
            notifyPersistenceFailure("acknowledge", error);
            return AckResult.REJECTED;
        }
    }

    /**
     * Retains a rejected record as evidence and quarantines dependent DELTAs
     * until the next FULL, preventing a later consumer from silently applying an orphan chain.
     */
    public synchronized int quarantine(Descriptor descriptor, String reason) {
        ensureOpen();
        validateDescriptorRequest(descriptor);
        File exact = exactReady(descriptor);
        validateExactFile(exact, descriptor);
        List<DecodedFile> files = decodedFiles();
        Collections.sort(files, Comparator.comparingLong(value -> value.record.spoolOrder));
        boolean poison = false;
        List<File> poisonMarkers = new ArrayList<>();
        int count = 0;
        for (DecodedFile value : files) {
            if (value.record.spoolOrder == descriptor.spoolOrder && value.record.identity.equals(descriptor.identity)) {
                poison = true;
                invalidateBaseline();
            } else if (poison && value.record.kind == Kind.FULL) {
                clearPoisonMarkers(poisonMarkers);
                poison = false;
            }
            if (poison) {
                poisonMarkers.add(quarantineFile(value.file, reason, true));
                count++;
            }
        }
        return count;
    }

    public synchronized Status status() {
        ensureOpen();
        long bytes = footprintBytes();
        File[] ready = directory.listFiles((dir, name) -> name.endsWith(READY_SUFFIX));
        File[] bad = directory.listFiles((dir, name) -> name.contains(BAD_SUFFIX));
        LossSummary loss = readLoss();
        return new Status(bytes, ready == null ? 0 : ready.length, bad == null ? 0 : bad.length, loss);
    }

    @Override public synchronized void close() {
        closed = true;
        baseline = null;
    }

    private List<DecodedFile> decodedFiles() {
        List<DecodedFile> result = new ArrayList<>();
        for (File file : readyFiles()) {
            try {
                Record record = Codec.decode(readBytes(file));
                if (!file.getName().equals(fileStem(record.spoolOrder, record.identity) + READY_SUFFIX)) {
                    throw new IllegalArgumentException("record filename mismatch");
                }
                result.add(new DecodedFile(file, record));
            } catch (Exception error) {
                invalidateBaseline();
                quarantineFile(file, "malformed: " + safeText(error.getMessage()), true);
            }
        }
        return result;
    }

    private File[] readyFiles() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(READY_SUFFIX));
        if (files == null) throw new IllegalStateException("cannot list secondary spool: " + directory);
        return files;
    }

    private File[] replayChainFiles() {
        File[] files = directory.listFiles((dir, name) ->
            name.endsWith(READY_SUFFIX) || name.contains(POISON_SUFFIX));
        if (files == null) throw new IllegalStateException("cannot list secondary spool: " + directory);
        return files;
    }

    private File findReady(CycleIdentity identity) {
        String suffix = "_" + identityKey(identity) + READY_SUFFIX;
        for (File file : readyFiles()) {
            if (file.getName().endsWith(suffix)) return file;
        }
        return null;
    }

    private File exactReady(Descriptor descriptor) {
        File file = new File(directory, descriptor.fileName);
        try {
            if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())) {
                throw new IllegalArgumentException("invalid record path");
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("invalid record path", error);
        }
        String expected = fileStem(descriptor.spoolOrder, descriptor.identity) + READY_SUFFIX;
        if (!expected.equals(descriptor.fileName)) throw new IllegalArgumentException("descriptor path mismatch");
        return file;
    }

    private void validateExactFile(File file, Descriptor descriptor) {
        if (!file.isFile()) throw new IllegalArgumentException("record not found");
        if (file.length() != descriptor.length) throw new IllegalArgumentException("record length changed");
        try {
            if (!sha256(file).equals(descriptor.sha256)) throw new IllegalArgumentException("record digest changed");
        } catch (IOException error) {
            throw new IllegalStateException("cannot hash secondary record", error);
        }
    }

    private void validateDescriptorRequest(Descriptor descriptor) {
        if (descriptor == null) throw new IllegalArgumentException("descriptor is required");
        if (descriptor.length < 1 || descriptor.sha256 == null || !descriptor.sha256.matches("[0-9A-F]{64}")) {
            throw new IllegalArgumentException("invalid descriptor");
        }
    }

    private File quarantineFile(File file, String reason, boolean poison) {
        long bytes = file.length();
        String suffixText = poison ? POISON_SUFFIX : BAD_SUFFIX;
        File bad = new File(file.getPath() + suffixText);
        int suffix = 1;
        while (bad.exists()) bad = new File(file.getPath() + suffixText + "." + suffix++);
        try {
            failures.before("quarantine", file);
        } catch (IOException error) {
            notifyPersistenceFailure("quarantine", error);
            throw new IllegalStateException("cannot retain rejected secondary record", error);
        }
        if (!file.renameTo(bad)) {
            IOException error = new IOException("cannot quarantine " + file.getName());
            notifyPersistenceFailure("quarantine", error);
            throw new IllegalStateException("cannot retain corrupt secondary record", error);
        }
        notifyQuarantine(file.getName(), bytes, safeText(reason));
        return bad;
    }

    private void clearPoisonMarkers(List<File> markers) {
        for (File marker : markers) {
            String path = marker.getPath();
            int poisonIndex = path.indexOf(POISON_SUFFIX);
            if (poisonIndex < 0) continue;
            File retained = new File(
                path.substring(0, poisonIndex) + BAD_SUFFIX + path.substring(poisonIndex + POISON_SUFFIX.length())
            );
            if (!marker.renameTo(retained)) {
                IOException error = new IOException("cannot resolve poison marker " + marker.getName());
                notifyPersistenceFailure("resolve_poison", error);
                throw new IllegalStateException("cannot resolve secondary poison marker", error);
            }
        }
    }

    private LossSummary readLoss() {
        File file = new File(directory, LOSS_FILE);
        if (!file.isFile()) return null;
        try {
            return LossSummary.decode(new JSONObject(new String(readBytes(file), StandardCharsets.UTF_8)));
        } catch (Exception error) {
            notifyPersistenceFailure("read_loss", error);
            throw new IllegalStateException("cannot read secondary loss summary", error);
        }
    }

    private void persistLoss(LossSummary loss) {
        byte[] bytes = loss.encode().toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > lossReserveBytes) throw new IllegalStateException("secondary loss summary exceeds reserve");
        File temporary = new File(directory, LOSS_TMP);
        File target = new File(directory, LOSS_FILE);
        try {
            failures.before("loss", temporary);
            writeDurably(temporary, bytes);
            failures.before("loss_publish", temporary);
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            if (temporary.isFile()) temporary.delete();
            notifyPersistenceFailure("persist_loss", error);
            throw new IllegalStateException("cannot persist secondary loss summary", error);
        }
    }

    private long footprintBytes() {
        File[] files = directory.listFiles();
        if (files == null) throw new IllegalStateException("cannot list secondary spool: " + directory);
        long total = 0L;
        for (File file : files) {
            if (!file.isFile()) continue;
            total = Long.MAX_VALUE - total < file.length() ? Long.MAX_VALUE : total + file.length();
        }
        return total;
    }

    private static List<Value> changes(List<Value> before, List<Value> after) {
        List<Value> result = new ArrayList<>();
        for (int index = 0; index < after.size(); index++) {
            if (!after.get(index).sameObservation(before.get(index))) result.add(after.get(index));
        }
        return result;
    }

    private static void validateCycle(Cycle cycle, int expectedFieldCount) {
        if (cycle == null) throw new IllegalArgumentException("cycle is required");
        if (cycle.values.size() != expectedFieldCount) {
            throw new IllegalArgumentException("secondary frame cardinality mismatch");
        }
        for (int index = 0; index < cycle.values.size(); index++) {
            if (cycle.values.get(index).ordinal != index) {
                throw new IllegalArgumentException("secondary frame ordinal mismatch at " + index);
            }
        }
    }

    private static List<Value> copyValues(List<Value> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static long findNextOrder(File directory) {
        long max = -1L;
        File[] files = directory.listFiles();
        if (files == null) return 0L;
        for (File file : files) {
            String name = file.getName();
            if (!name.startsWith("s")) continue;
            int underscore = name.indexOf('_');
            if (underscore < 2) continue;
            try { max = Math.max(max, Long.parseLong(name.substring(1, underscore))); }
            catch (NumberFormatException ignored) { }
        }
        return max == Long.MAX_VALUE ? Long.MAX_VALUE : max + 1L;
    }

    private static String fileStem(long order, CycleIdentity identity) {
        return String.format(java.util.Locale.ROOT, "s%020d_%s", order, identityKey(identity));
    }

    private static String identityKey(CycleIdentity identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(identity.bootId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(identity.helperGeneration.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(identity.gapId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(identity.sequence).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static byte[] readBytes(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE, file.length()))) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static void writeDurably(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(bytes);
            output.flush();
            FileDescriptor descriptor = output.getFD();
            descriptor.sync();
        }
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02X", value));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("secondary spool is closed");
    }

    private void invalidateBaseline() {
        baseline = null;
        baselineIdentity = null;
        baselineCatalogVersion = null;
        forceFull = true;
    }

    private static String safeText(String value) {
        if (value == null) return null;
        return value.length() <= MAX_DIAGNOSTIC_TEXT ? value : value.substring(0, MAX_DIAGNOSTIC_TEXT);
    }

    private void notifyAppend(Record record, long bytes) {
        try { diagnostics.onAppend(record.identity, record.kind, bytes); } catch (Throwable ignored) { }
    }

    private void notifyLoss(LossSummary loss) {
        try { diagnostics.onLoss(loss); } catch (Throwable ignored) { }
    }

    private void notifyQuarantine(String file, long bytes, String reason) {
        try { diagnostics.onQuarantine(file, bytes, reason); } catch (Throwable ignored) { }
    }

    private void notifyPersistenceFailure(String operation, Throwable error) {
        try { diagnostics.onPersistenceFailure(operation, error); } catch (Throwable ignored) { }
    }

    interface FailureInjector {
        void before(String operation, File file) throws IOException;
    }

    public interface DiagnosticListener {
        default void onAppend(CycleIdentity identity, Kind kind, long bytes) { }
        default void onLoss(LossSummary loss) { }
        default void onQuarantine(String fileName, long bytes, String reason) { }
        default void onPersistenceFailure(String operation, Throwable error) { }
    }

    public enum AppendResult { FULL, DELTA, DUPLICATE, CAP_REACHED }
    public enum AckResult { RELEASED, NOT_FOUND, REJECTED }
    public enum Kind { FULL, DELTA }

    public static final class CycleIdentity {
        public final String bootId;
        public final String helperGeneration;
        public final String gapId;
        public final long sequence;

        public CycleIdentity(String bootId, String helperGeneration, String gapId, long sequence) {
            this.bootId = identifier(bootId, "bootId");
            this.helperGeneration = identifier(helperGeneration, "helperGeneration");
            this.gapId = identifier(gapId, "gapId");
            if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
            this.sequence = sequence;
        }

        public boolean sameEpoch(CycleIdentity other) {
            return other != null && bootId.equals(other.bootId) &&
                helperGeneration.equals(other.helperGeneration) && gapId.equals(other.gapId);
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof CycleIdentity)) return false;
            CycleIdentity that = (CycleIdentity) other;
            return sequence == that.sequence && sameEpoch(that);
        }

        @Override public int hashCode() {
            int result = bootId.hashCode();
            result = 31 * result + helperGeneration.hashCode();
            result = 31 * result + gapId.hashCode();
            return 31 * result + Long.hashCode(sequence);
        }

        @Override public String toString() {
            return bootId + ":" + helperGeneration + ":" + gapId + ":" + sequence;
        }
    }

    public static final class Value {
        public final int ordinal;
        public final int status;
        public final boolean rawPresent;
        public final Integer raw;
        public final String error;
        public final boolean cached;

        public Value(int ordinal, int status, boolean rawPresent, Integer raw, String error) {
            this(ordinal, status, rawPresent, raw, error, false);
        }

        public Value(int ordinal, int status, boolean rawPresent, Integer raw, String error,
                     boolean cached) {
            if (ordinal < 0) throw new IllegalArgumentException("ordinal must be non-negative");
            if (rawPresent != (raw != null)) throw new IllegalArgumentException("rawPresent/raw mismatch");
            this.ordinal = ordinal;
            this.status = status;
            this.rawPresent = rawPresent;
            this.raw = raw;
            this.error = error;
            if (cached && (status != CollectorHelperProtocol.STATUS_OK || raw == null)) {
                throw new IllegalArgumentException("cached value requires usable raw");
            }
            this.cached = cached;
        }

        boolean sameObservation(Value other) {
            return status == other.status && rawPresent == other.rawPresent &&
                (raw == null ? other.raw == null : raw.equals(other.raw)) &&
                (error == null ? other.error == null : error.equals(other.error)) && cached == other.cached;
        }
    }

    public static final class Cycle {
        public final CycleIdentity identity;
        public final String catalogVersion;
        public final long capturedWallMs;
        public final long capturedElapsedMs;
        public final long cycleElapsedMs;
        public final int batchStatus;
        public final int batchMode;
        public final boolean nativeAvailable;
        public final int nativeGroupCount;
        public final int fallbackGroupCount;
        public final int fallbackReadCount;
        public final int groupFailureCount;
        public final String error;
        public final List<Value> values;

        public Cycle(
            CycleIdentity identity, String catalogVersion, long capturedWallMs, long capturedElapsedMs,
            long cycleElapsedMs, int batchStatus, int batchMode, boolean nativeAvailable,
            int nativeGroupCount, int fallbackGroupCount, int fallbackReadCount, int groupFailureCount,
            String error, List<Value> values
        ) {
            if (identity == null) throw new IllegalArgumentException("identity is required");
            this.identity = identity;
            this.catalogVersion = identifier(catalogVersion, "catalogVersion");
            if (capturedWallMs < 0 || capturedElapsedMs < 0 || cycleElapsedMs < 0) {
                throw new IllegalArgumentException("capture clocks must be non-negative");
            }
            if (nativeGroupCount < 0 || fallbackGroupCount < 0 || fallbackReadCount < 0 || groupFailureCount < 0) {
                throw new IllegalArgumentException("cycle counts must be non-negative");
            }
            if (values == null) throw new IllegalArgumentException("values is required");
            this.capturedWallMs = capturedWallMs;
            this.capturedElapsedMs = capturedElapsedMs;
            this.cycleElapsedMs = cycleElapsedMs;
            this.batchStatus = batchStatus;
            this.batchMode = batchMode;
            this.nativeAvailable = nativeAvailable;
            this.nativeGroupCount = nativeGroupCount;
            this.fallbackGroupCount = fallbackGroupCount;
            this.fallbackReadCount = fallbackReadCount;
            this.groupFailureCount = groupFailureCount;
            this.error = error;
            this.values = copyValues(values);
        }
    }

    public static final class Record {
        public final long spoolOrder;
        public final Kind kind;
        public final CycleIdentity identity;
        public final String catalogVersion;
        public final long capturedWallMs;
        public final long capturedElapsedMs;
        public final long cycleElapsedMs;
        public final int batchStatus;
        public final int batchMode;
        public final boolean nativeAvailable;
        public final int nativeGroupCount;
        public final int fallbackGroupCount;
        public final int fallbackReadCount;
        public final int groupFailureCount;
        public final String error;
        public final int fieldCount;
        public final int okCount;
        public final int errorCount;
        public final List<Value> values;
        public final LossSummary lossBefore;
        public final CycleIdentity predecessorIdentity;

        private Record(
            long spoolOrder, Kind kind, CycleIdentity identity, String catalogVersion,
            long capturedWallMs, long capturedElapsedMs, long cycleElapsedMs, int batchStatus,
            int batchMode, boolean nativeAvailable, int nativeGroupCount, int fallbackGroupCount,
            int fallbackReadCount, int groupFailureCount, String error, int fieldCount,
            int okCount, int errorCount, List<Value> values, LossSummary lossBefore,
            CycleIdentity predecessorIdentity
        ) {
            if (spoolOrder < 0 || kind == null || identity == null) {
                throw new IllegalArgumentException("invalid record identity");
            }
            identifier(catalogVersion, "catalogVersion");
            if (capturedWallMs < 0 || capturedElapsedMs < 0 || cycleElapsedMs < 0 ||
                nativeGroupCount < 0 || fallbackGroupCount < 0 || fallbackReadCount < 0 ||
                groupFailureCount < 0 || fieldCount <= 0 || okCount < 0 || errorCount < 0 ||
                okCount + errorCount != fieldCount || values == null) {
                throw new IllegalArgumentException("invalid record metadata");
            }
            int previousOrdinal = -1;
            for (Value value : values) {
                if (value == null || value.ordinal <= previousOrdinal || value.ordinal >= fieldCount) {
                    throw new IllegalArgumentException("invalid record value order");
                }
                previousOrdinal = value.ordinal;
            }
            if (kind == Kind.FULL) {
                if (values.size() != fieldCount || predecessorIdentity != null) {
                    throw new IllegalArgumentException("invalid FULL record");
                }
                int actualOk = 0;
                for (Value value : values) if (value.status == 0 && value.rawPresent) actualOk++;
                if (actualOk != okCount) throw new IllegalArgumentException("FULL cycle counts mismatch");
            } else if (predecessorIdentity == null || !identity.sameEpoch(predecessorIdentity) ||
                predecessorIdentity.sequence == Long.MAX_VALUE || predecessorIdentity.sequence + 1L != identity.sequence) {
                throw new IllegalArgumentException("invalid DELTA predecessor");
            }
            this.spoolOrder = spoolOrder;
            this.kind = kind;
            this.identity = identity;
            this.catalogVersion = catalogVersion;
            this.capturedWallMs = capturedWallMs;
            this.capturedElapsedMs = capturedElapsedMs;
            this.cycleElapsedMs = cycleElapsedMs;
            this.batchStatus = batchStatus;
            this.batchMode = batchMode;
            this.nativeAvailable = nativeAvailable;
            this.nativeGroupCount = nativeGroupCount;
            this.fallbackGroupCount = fallbackGroupCount;
            this.fallbackReadCount = fallbackReadCount;
            this.groupFailureCount = groupFailureCount;
            this.error = error;
            this.fieldCount = fieldCount;
            this.okCount = okCount;
            this.errorCount = errorCount;
            this.values = copyValues(values);
            this.lossBefore = lossBefore;
            this.predecessorIdentity = predecessorIdentity;
        }

        public List<Value> materialize(CycleIdentity previousIdentity, List<Value> previous) {
            if (kind == Kind.FULL) {
                if (values.size() != fieldCount) throw new IllegalStateException("invalid FULL cardinality");
                return copyValues(values);
            }
            if (predecessorIdentity == null || !predecessorIdentity.equals(previousIdentity) ||
                previous == null || previous.size() != fieldCount) {
                throw new IllegalArgumentException("DELTA requires exact prior baseline");
            }
            List<Value> result = new ArrayList<>(previous);
            for (Value value : values) result.set(value.ordinal, value);
            int materializedOk = 0;
            for (Value value : result) if (value.status == 0 && value.rawPresent) materializedOk++;
            if (materializedOk != okCount || fieldCount - materializedOk != errorCount) {
                throw new IllegalStateException("DELTA materialized cycle counts mismatch");
            }
            return copyValues(result);
        }
    }

    public static final class Descriptor {
        public final long spoolOrder;
        public final CycleIdentity identity;
        public final Kind kind;
        public final long capturedWallMs;
        public final long capturedElapsedMs;
        public final String fileName;
        public final long length;
        public final String sha256;

        public Descriptor(
            long spoolOrder, CycleIdentity identity, Kind kind, long capturedWallMs,
            long capturedElapsedMs, String fileName, long length, String sha256
        ) {
            if (spoolOrder < 0 || identity == null || kind == null || capturedWallMs < 0 || capturedElapsedMs < 0) {
                throw new IllegalArgumentException("invalid descriptor metadata");
            }
            if (fileName == null || !fileName.equals(fileStem(spoolOrder, identity) + READY_SUFFIX)) {
                throw new IllegalArgumentException("invalid descriptor filename");
            }
            if (length < 1 || sha256 == null || !sha256.matches("[0-9A-F]{64}")) {
                throw new IllegalArgumentException("invalid descriptor content identity");
            }
            this.spoolOrder = spoolOrder;
            this.identity = identity;
            this.kind = kind;
            this.capturedWallMs = capturedWallMs;
            this.capturedElapsedMs = capturedElapsedMs;
            this.fileName = fileName;
            this.length = length;
            this.sha256 = sha256;
        }
    }

    public static final class LossSummary {
        public final long count;
        public final CycleIdentity firstIdentity;
        public final CycleIdentity lastIdentity;
        public final long firstWallMs;
        public final long lastWallMs;
        public final long firstElapsedMs;
        public final long lastElapsedMs;

        private LossSummary(
            long count, CycleIdentity firstIdentity, CycleIdentity lastIdentity, long firstWallMs,
            long lastWallMs, long firstElapsedMs, long lastElapsedMs
        ) {
            if (count < 1 || firstIdentity == null || lastIdentity == null ||
                firstWallMs < 0 || lastWallMs < 0 || firstElapsedMs < 0 || lastElapsedMs < 0) {
                throw new IllegalArgumentException("invalid loss summary");
            }
            this.count = count;
            this.firstIdentity = firstIdentity;
            this.lastIdentity = lastIdentity;
            this.firstWallMs = firstWallMs;
            this.lastWallMs = lastWallMs;
            this.firstElapsedMs = firstElapsedMs;
            this.lastElapsedMs = lastElapsedMs;
        }

        static LossSummary include(LossSummary prior, CycleIdentity identity, long wall, long elapsed) {
            if (prior == null) return new LossSummary(1L, identity, identity, wall, wall, elapsed, elapsed);
            long nextCount = prior.count == Long.MAX_VALUE ? Long.MAX_VALUE : prior.count + 1L;
            return new LossSummary(nextCount, prior.firstIdentity, identity,
                prior.firstWallMs, wall, prior.firstElapsedMs, elapsed);
        }

        JSONObject encode() {
            try {
                return new JSONObject()
                    .put("version", 1)
                    .put("count", count)
                    .put("first_identity", Codec.identity(firstIdentity))
                    .put("last_identity", Codec.identity(lastIdentity))
                    .put("first_wall_ms", firstWallMs)
                    .put("last_wall_ms", lastWallMs)
                    .put("first_elapsed_ms", firstElapsedMs)
                    .put("last_elapsed_ms", lastElapsedMs);
            } catch (JSONException error) {
                throw new IllegalStateException("cannot encode loss summary", error);
            }
        }

        static LossSummary decode(JSONObject json) {
            try {
                if (Codec.exactInt(json, "version") != 1) throw new IllegalArgumentException("unsupported loss version");
                return new LossSummary(
                    Codec.exactLong(json, "count"), Codec.identity(json.getJSONObject("first_identity")),
                    Codec.identity(json.getJSONObject("last_identity")), Codec.exactLong(json, "first_wall_ms"),
                    Codec.exactLong(json, "last_wall_ms"), Codec.exactLong(json, "first_elapsed_ms"),
                    Codec.exactLong(json, "last_elapsed_ms")
                );
            } catch (JSONException error) {
                throw new IllegalArgumentException("invalid loss summary", error);
            }
        }
    }

    public static final class Status {
        public final long bytes;
        public final int readyRecords;
        public final int quarantinedRecords;
        public final LossSummary loss;

        private Status(long bytes, int readyRecords, int quarantinedRecords, LossSummary loss) {
            this.bytes = bytes;
            this.readyRecords = readyRecords;
            this.quarantinedRecords = quarantinedRecords;
            this.loss = loss;
        }
    }

    /** Stable JSON codec shared with the later Kotlin APP replay layer. */
    public static final class Codec {
        private Codec() { }

        public static byte[] encode(Record record) {
            if (record == null) throw new IllegalArgumentException("record is required");
            try {
                JSONObject json = new JSONObject()
                .put("record_version", RECORD_VERSION)
                .put("spool_order", record.spoolOrder)
                .put("kind", record.kind.name())
                .put("identity", identity(record.identity))
                .put("catalog_version", record.catalogVersion)
                .put("captured_wall_ms", record.capturedWallMs)
                .put("captured_elapsed_ms", record.capturedElapsedMs)
                .put("cycle_elapsed_ms", record.cycleElapsedMs)
                .put("batch_status", record.batchStatus)
                .put("batch_mode", record.batchMode)
                .put("native_available", record.nativeAvailable)
                .put("native_group_count", record.nativeGroupCount)
                .put("fallback_group_count", record.fallbackGroupCount)
                .put("fallback_read_count", record.fallbackReadCount)
                .put("group_failure_count", record.groupFailureCount)
                .put("error", record.error == null ? JSONObject.NULL : record.error)
                .put("field_count", record.fieldCount)
                .put("attempted_count", record.fieldCount)
                .put("ok_count", record.okCount)
                .put("error_count", record.errorCount)
                .put("predecessor_identity", record.predecessorIdentity == null
                    ? JSONObject.NULL : identity(record.predecessorIdentity))
                .put("loss_before", record.lossBefore == null ? JSONObject.NULL : record.lossBefore.encode());
            JSONArray values = new JSONArray();
            for (Value value : record.values) {
                values.put(new JSONObject()
                    .put("ordinal", value.ordinal)
                    .put("status", value.status)
                    .put("raw_present", value.rawPresent)
                    .put("raw", value.raw == null ? JSONObject.NULL : value.raw)
                    .put("callback_cached", value.cached)
                    .put("error", value.error == null ? JSONObject.NULL : value.error));
            }
                json.put("values", values);
                return json.toString().getBytes(StandardCharsets.UTF_8);
            } catch (JSONException error) {
                throw new IllegalStateException("cannot encode secondary record", error);
            }
        }

        public static Record decode(byte[] bytes) {
            if (bytes == null) throw new IllegalArgumentException("bytes is required");
            try {
                JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            int recordVersion = exactInt(json, "record_version");
            if (recordVersion != RECORD_VERSION && recordVersion != LEGACY_RECORD_VERSION) {
                throw new IllegalArgumentException("unsupported secondary record version");
            }
            Kind kind = Kind.valueOf(exactString(json, "kind"));
            int fieldCount = exactInt(json, "field_count");
            if (fieldCount <= 0) throw new IllegalArgumentException("invalid field count");
            JSONArray encoded = json.getJSONArray("values");
            List<Value> values = new ArrayList<>(encoded.length());
            Set<Integer> ordinals = new HashSet<>();
            int previous = -1;
            for (int index = 0; index < encoded.length(); index++) {
                JSONObject value = encoded.getJSONObject(index);
                int ordinal = exactInt(value, "ordinal");
                if (ordinal < 0 || ordinal >= fieldCount || ordinal <= previous || !ordinals.add(ordinal)) {
                    throw new IllegalArgumentException("invalid value ordinal");
                }
                previous = ordinal;
                boolean present = exactBoolean(value, "raw_present");
                Integer raw = value.isNull("raw") ? null : exactInt(value, "raw");
                values.add(new Value(ordinal, exactInt(value, "status"), present, raw,
                    nullableString(value, "error"), recordVersion >= 2 && exactBoolean(value, "callback_cached")));
            }
            if (kind == Kind.FULL && values.size() != fieldCount) {
                throw new IllegalArgumentException("FULL field count mismatch");
            }
            LossSummary loss = json.isNull("loss_before") ? null : LossSummary.decode(json.getJSONObject("loss_before"));
            CycleIdentity predecessor = json.isNull("predecessor_identity")
                ? null : identity(json.getJSONObject("predecessor_identity"));
            if (kind == Kind.FULL && predecessor != null) {
                throw new IllegalArgumentException("FULL must not have predecessor");
            }
            CycleIdentity recordIdentity = identity(json.getJSONObject("identity"));
            if (kind == Kind.DELTA && (predecessor == null || !recordIdentity.sameEpoch(predecessor) ||
                predecessor.sequence == Long.MAX_VALUE || predecessor.sequence + 1L != recordIdentity.sequence)) {
                throw new IllegalArgumentException("DELTA predecessor mismatch");
            }
            int attemptedCount = exactInt(json, "attempted_count");
            int okCount = exactInt(json, "ok_count");
            int errorCount = exactInt(json, "error_count");
            if (attemptedCount != fieldCount || okCount < 0 || errorCount < 0 || okCount + errorCount != fieldCount) {
                throw new IllegalArgumentException("cycle count mismatch");
            }
                return new Record(
                exactLong(json, "spool_order"), kind, recordIdentity,
                exactString(json, "catalog_version"), exactLong(json, "captured_wall_ms"),
                exactLong(json, "captured_elapsed_ms"), exactLong(json, "cycle_elapsed_ms"),
                exactInt(json, "batch_status"), exactInt(json, "batch_mode"),
                exactBoolean(json, "native_available"), exactInt(json, "native_group_count"),
                exactInt(json, "fallback_group_count"), exactInt(json, "fallback_read_count"),
                exactInt(json, "group_failure_count"), nullableString(json, "error"),
                fieldCount, okCount, errorCount, values, loss, predecessor
                );
            } catch (JSONException error) {
                throw new IllegalArgumentException("invalid secondary record JSON", error);
            }
        }

        static JSONObject identity(CycleIdentity identity) {
            try {
                return new JSONObject()
                    .put("boot_id", identity.bootId)
                    .put("helper_generation", identity.helperGeneration)
                    .put("gap_id", identity.gapId)
                    .put("sequence", identity.sequence);
            } catch (JSONException error) {
                throw new IllegalStateException("cannot encode cycle identity", error);
            }
        }

        static CycleIdentity identity(JSONObject json) {
            return new CycleIdentity(
                exactString(json, "boot_id"), exactString(json, "helper_generation"),
                exactString(json, "gap_id"), exactLong(json, "sequence")
            );
        }

        private static int exactInt(JSONObject json, String key) {
            try {
                Object value = json.get(key);
                if (!(value instanceof Integer)) throw new IllegalArgumentException(key + " must be an exact int");
                return (Integer) value;
            } catch (JSONException error) {
                throw new IllegalArgumentException("missing " + key, error);
            }
        }

        private static long exactLong(JSONObject json, String key) {
            try {
                Object value = json.get(key);
                if (value instanceof Integer) return ((Integer) value).longValue();
                if (value instanceof Long) return (Long) value;
                throw new IllegalArgumentException(key + " must be an exact long");
            } catch (JSONException error) {
                throw new IllegalArgumentException("missing " + key, error);
            }
        }

        private static String exactString(JSONObject json, String key) {
            try {
                Object value = json.get(key);
                if (!(value instanceof String)) throw new IllegalArgumentException(key + " must be a string");
                return (String) value;
            } catch (JSONException error) {
                throw new IllegalArgumentException("missing " + key, error);
            }
        }

        private static boolean exactBoolean(JSONObject json, String key) {
            try {
                Object value = json.get(key);
                if (!(value instanceof Boolean)) throw new IllegalArgumentException(key + " must be a boolean");
                return (Boolean) value;
            } catch (JSONException error) {
                throw new IllegalArgumentException("missing " + key, error);
            }
        }

        private static String nullableString(JSONObject json, String key) {
            try {
                if (!json.has(key) || json.isNull(key)) return null;
                Object value = json.get(key);
                if (!(value instanceof String)) throw new IllegalArgumentException(key + " must be a string");
                return (String) value;
            } catch (JSONException error) {
                throw new IllegalArgumentException("invalid " + key, error);
            }
        }

    }

    private static String identifier(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        if (value.length() > MAX_IDENTIFIER_CHARS) throw new IllegalArgumentException(name + " is too long");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) {
                throw new IllegalArgumentException(name + " contains control characters");
            }
        }
        return value;
    }

    private static final class DecodedFile {
        final File file;
        final Record record;
        DecodedFile(File file, Record record) { this.file = file; this.record = record; }
    }
}
