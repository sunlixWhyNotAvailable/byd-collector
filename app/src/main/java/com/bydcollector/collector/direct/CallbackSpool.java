package com.bydcollector.collector.direct;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/** Callback-only files inside an existing poll spool root; the quota is shared with poll files. */
public final class CallbackSpool implements AutoCloseable {
    public static final int MAX_SLICE_BYTES = 224 * 1024;
    public static final int LOSS_RESERVE_BYTES = 8 * 1024;
    private static final String READY = ".cbready";
    private static final String TMP = ".cbtmp";
    private static final String BAD = ".cbbad";
    private static final String LOSS_READY = "callback-loss.cbready";
    private static final String LOSS_TMP = "callback-loss.cbtmp";
    private static final ConcurrentHashMap<String, RootState> ROOT_STATES = new ConcurrentHashMap<>();

    private final File directory;
    private final long maxBytes;
    private final long ordinaryReserveBytes;
    private final RootState rootState;
    private final Object persistenceLock;
    private boolean closed;

    public static CallbackSpool openMain() {
        return open(new File(TelemetryWorkerSpool.SPOOL_DIRECTORY_PATH), TelemetryWorkerSpool.MAX_SPOOL_BYTES, LOSS_RESERVE_BYTES);
    }

    public static CallbackSpool openSecondary() {
        return open(new File(SecondaryTelemetrySpool.SPOOL_DIRECTORY_PATH), SecondaryTelemetrySpool.MAX_SPOOL_BYTES, 2L * LOSS_RESERVE_BYTES);
    }

    static CallbackSpool openForTest(File directory, long maxBytes) { return open(directory, maxBytes, LOSS_RESERVE_BYTES); }

    private static CallbackSpool open(File directory, long maxBytes, long ordinaryReserveBytes) {
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IllegalStateException("cannot create callback spool directory: " + directory);
        }
        if (maxBytes <= LOSS_RESERVE_BYTES) throw new IllegalArgumentException("callback spool quota too small");
        CallbackSpool result = new CallbackSpool(directory, maxBytes, ordinaryReserveBytes);
        synchronized (result.persistenceLock) {
            result.rootState.ensureFresh();
            result.recover();
        }
        return result;
    }

    private CallbackSpool(File directory, long maxBytes, long ordinaryReserveBytes) {
        this.directory = directory;
        this.maxBytes = maxBytes;
        this.ordinaryReserveBytes = ordinaryReserveBytes;
        this.rootState = rootState(directory);
        this.persistenceLock = rootState.lock;
    }

    static RootState rootState(File directory) {
        try {
            String canonicalPath = directory.getCanonicalPath();
            return ROOT_STATES.computeIfAbsent(canonicalPath, ignored -> new RootState(new File(canonicalPath)));
        } catch (IOException error) {
            throw new IllegalStateException("cannot resolve spool root", error);
        }
    }

    static Object persistenceLock(File directory) {
        return rootState(directory).lock;
    }

    public AppendResult append(TelemetryCallbackBatch batch) {
        return append(batch, true);
    }

    AppendResult appendRetained(TelemetryCallbackBatch batch) {
        return append(batch, false);
    }

    private AppendResult append(TelemetryCallbackBatch batch, boolean recordCapacityLoss) {
        if (batch == null) throw new IllegalArgumentException("callback batch is required");
        byte[] bytes;
        try { bytes = batch.encode(); }
        catch (IOException error) { recordLoss(batch.events.size(), eventFirstWall(batch), eventLastWall(batch), "encode"); return AppendResult.REJECTED; }
        synchronized (persistenceLock) {
            ensureOpen();
            rootState.ensureFresh();
            String digest = TelemetryCallbackBatch.digest(bytes);
            File existing = findIdentity(identityDigest(batch.identity()));
            if (existing != null) {
                try {
                    Descriptor current = descriptor(existing);
                    if (current.length == bytes.length && current.sha256.equals(digest)) return AppendResult.DUPLICATE;
                    preserveBad(existing);
                    mergeLoss(batch.events.size(), eventFirstWall(batch), eventLastWall(batch), "identity_conflict");
                } catch (Exception error) {
                    preserveBad(existing);
                    mergeLoss(batch.events.size(), eventFirstWall(batch), eventLastWall(batch), "corruption");
                }
            }
            long order = rootState.nextCallbackOrder();
            String stem = fileStem(order, batch, digest);
            File ready = new File(directory, stem + READY);
            File temporary = new File(directory, stem + TMP);
            long footprint = rootState.footprintBytes();
            long ordinaryLimit = maxBytes - ordinaryReserveBytes;
            if (bytes.length > ordinaryLimit || footprint > ordinaryLimit - bytes.length) {
                if (recordCapacityLoss) mergeLoss(batch.events.size(), eventFirstWall(batch), eventLastWall(batch),
                    bytes.length > ordinaryLimit ? "oversize" : "capacity");
                return AppendResult.CAP_REACHED;
            }
            try {
                writeDurably(temporary, bytes);
                rootState.noteFile(temporary);
                Files.move(temporary.toPath(), ready.toPath(), StandardCopyOption.ATOMIC_MOVE);
                rootState.noteRename(temporary, ready);
                rootState.advanceCallbackOrder(order);
                return AppendResult.SUCCESS;
            } catch (IOException error) {
                try { preserveBad(temporary); }
                catch (RuntimeException preserveError) { error.addSuppressed(preserveError); rootState.invalidate(); }
                rootState.invalidate();
                mergeLoss(batch.events.size(), eventFirstWall(batch), eventLastWall(batch), "io");
                return AppendResult.REJECTED;
            }
        }
    }

    public void recordLoss(long count, long firstWallMs, long lastWallMs, String reason) {
        synchronized (persistenceLock) {
            ensureOpen();
            rootState.ensureFresh();
            mergeLoss(count, firstWallMs, lastWallMs, reason);
        }
    }

    public Descriptor oldest() {
        synchronized (persistenceLock) {
            ensureOpen();
            File previouslyIndexedHead = rootState.cachedOldestCallbackReady();
            rootState.ensureFresh();
            if (previouslyIndexedHead != null && !rootState.tracksCallbackReady(previouslyIndexedHead) &&
                !previouslyIndexedHead.isFile()) {
                // A refresh may have already dropped an externally deleted head from the index.
                // Record that loss even when the directory timestamp exposed the mutation first.
                long now = System.currentTimeMillis();
                mergeLoss(1L, now, now, "corruption");
            }
            File file;
            while ((file = rootState.oldestCallbackReady()) != null) {
                try { return descriptor(file); }
                catch (Exception error) {
                    long now = System.currentTimeMillis();
                    if (!file.isFile()) {
                        // An external delete can leave the cached head stale without changing
                        // the file we are trying to quarantine. Reconcile once, then continue.
                        rootState.invalidate();
                        rootState.ensureFresh();
                    } else {
                        preserveBad(file);
                    }
                    mergeLoss(1L, now, now, "corruption");
                }
            }
            return null;
        }
    }

    public byte[] readSlice(Descriptor descriptor, long offset, int limit) {
        if (descriptor == null || offset < 0 || limit < 1 || limit > MAX_SLICE_BYTES) {
            throw new IllegalArgumentException("invalid callback page request");
        }
        synchronized (persistenceLock) {
            ensureOpen();
            File file = exact(descriptor);
            validateMetadata(file, descriptor);
            if (offset > descriptor.length) throw new IllegalArgumentException("callback offset exceeds record");
            int count = (int) Math.min((long) limit, descriptor.length - offset);
            byte[] bytes = new byte[count];
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                input.seek(offset);
                input.readFully(bytes);
                return bytes;
            } catch (IOException error) { throw new IllegalStateException("cannot read callback page", error); }
        }
    }

    public AckResult acknowledge(Descriptor descriptor) {
        synchronized (persistenceLock) {
            ensureOpen();
            rootState.ensureFresh();
            File file = exact(descriptor);
            if (!file.isFile()) return AckResult.NOT_FOUND;
            validate(file, descriptor);
            if (!file.delete()) return AckResult.REJECTED;
            rootState.noteDelete(file);
            return AckResult.RELEASED;
        }
    }

    public int quarantine(Descriptor descriptor, String reason) {
        if (reason == null || reason.isEmpty() || reason.length() > 512) throw new IllegalArgumentException("invalid callback quarantine reason");
        synchronized (persistenceLock) {
            ensureOpen();
            rootState.ensureFresh();
            File file = exact(descriptor);
            if (!file.isFile()) return 0;
            validate(file, descriptor);
            int count = 1; long first = System.currentTimeMillis(); long last = first;
            try {
                TelemetryCallbackBatch batch = TelemetryCallbackBatch.decode(readBytes(file, TelemetryCallbackBatch.MAX_BYTES));
                count = batch.events.size(); first = eventFirstWall(batch); last = eventLastWall(batch);
            } catch (Exception ignored) { }
            preserveBad(file);
            mergeLoss(count, first, last, "consumer_rejected");
            return 1;
        }
    }

    void quarantineMemory(byte[] bytes, TelemetryCallbackBatch batch) {
        synchronized (persistenceLock) {
            ensureOpen();
            rootState.ensureFresh();
            File evidence = new File(directory,
                fileStem(rootState.nextCallbackOrder(), batch, TelemetryCallbackBatch.digest(bytes)) + BAD);
            try {
                if (!evidence.exists() && rootState.footprintBytes() <= maxBytes - ordinaryReserveBytes - bytes.length) {
                    writeDurably(evidence, bytes);
                    rootState.noteFile(evidence);
                }
            }
            catch (IOException error) {
                rootState.invalidate();
                throw new IllegalStateException("cannot preserve rejected callback memory batch", error);
            }
            mergeLoss(batch.events.size(), eventFirstWall(batch), eventLastWall(batch), "consumer_rejected");
        }
    }

    public Status status() {
        synchronized (persistenceLock) {
            ensureOpen();
            rootState.ensureFresh();
            Loss loss = readLoss();
            return new Status(rootState.footprintBytes(), rootState.callbackReadyCount(),
                rootState.callbackBadCount(), loss);
        }
    }

    private void recover() {
        synchronized (persistenceLock) {
            File lossTemp = new File(directory, LOSS_TMP);
            if (lossTemp.isFile()) {
                try {
                    Loss.decode(readBytes(lossTemp, LOSS_RESERVE_BYTES));
                    Files.move(lossTemp.toPath(), new File(directory, LOSS_READY).toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    rootState.noteRename(lossTemp, new File(directory, LOSS_READY));
                } catch (Exception error) {
                    preserveBad(lossTemp);
                    long now = System.currentTimeMillis();
                    mergeLoss(1L, now, now, "crash_loss_tmp_corruption");
                }
            }
            File[] files = batchFiles(TMP);
            for (File temporary : files) {
                try {
                    byte[] bytes = readBytes(temporary, TelemetryCallbackBatch.MAX_BYTES);
                    TelemetryCallbackBatch batch = TelemetryCallbackBatch.decode(bytes);
                    long order = parseOrder(temporary.getName());
                    String stem = fileStem(order, batch, TelemetryCallbackBatch.digest(bytes));
                    if (!temporary.getName().equals(stem + TMP)) throw new IOException("callback temporary identity mismatch");
                    File ready = new File(directory, stem + READY);
                    if (!ready.exists()) {
                        Files.move(temporary.toPath(), ready.toPath(), StandardCopyOption.ATOMIC_MOVE);
                        rootState.noteRename(temporary, ready);
                    }
                    else if (Arrays.equals(readBytes(ready, TelemetryCallbackBatch.MAX_BYTES), bytes)) {
                        Files.delete(temporary.toPath());
                        rootState.noteDelete(temporary);
                    }
                    else throw new IOException("callback temporary conflicts with ready");
                } catch (Exception error) {
                    long now = System.currentTimeMillis();
                    preserveBad(temporary);
                    mergeLoss(1L, now, now, "crash_tmp_corruption");
                }
            }
        }
    }

    private Descriptor descriptor(File file) throws IOException {
        byte[] bytes = readBytes(file, TelemetryCallbackBatch.MAX_BYTES);
        return descriptor(file, bytes);
    }

    private Descriptor descriptor(File file, byte[] bytes) throws IOException {
        TelemetryCallbackBatch batch = TelemetryCallbackBatch.decode(bytes);
        String digest = TelemetryCallbackBatch.digest(bytes);
        long order = parseOrder(file.getName());
        if (!file.getName().equals(fileStem(order, batch, digest) + READY)) throw new IOException("callback filename mismatch");
        return new Descriptor(order, batch.stream, batch.identity(), file.getName(), bytes.length, digest,
            batch.bootId, batch.helperGeneration, batch.epoch, batch.batchSequence);
    }

    private void validate(File file, Descriptor descriptor) {
        try {
            if (!file.isFile() || file.length() != descriptor.length) {
                throw new IllegalArgumentException("callback descriptor mismatch");
            }
            byte[] bytes = readBytes(file, TelemetryCallbackBatch.MAX_BYTES);
            if (!TelemetryCallbackBatch.digest(bytes).equals(descriptor.sha256)) {
                throw new IllegalArgumentException("callback descriptor digest mismatch");
            }
            Descriptor actual = descriptor(file, bytes);
            if (!actual.equals(descriptor)) throw new IllegalArgumentException("callback identity mismatch");
        } catch (IOException error) { throw new IllegalStateException("cannot validate callback record", error); }
    }

    private void validateMetadata(File file, Descriptor descriptor) {
        if (!file.isFile() || file.length() != descriptor.length || !file.getName().equals(descriptor.fileName) ||
            !file.getName().contains("_" + identityDigest(descriptor.identity) + "_" + descriptor.sha256 + READY)) {
            throw new IllegalArgumentException("callback descriptor metadata mismatch");
        }
    }

    private File exact(Descriptor descriptor) {
        File file = new File(directory, descriptor.fileName);
        try {
            if (!file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()) ||
                !file.getName().endsWith(READY) || file.getName().equals(LOSS_READY)) {
                throw new IllegalArgumentException("invalid callback filename");
            }
        } catch (IOException error) { throw new IllegalArgumentException("invalid callback path", error); }
        return file;
    }

    private void mergeLoss(long count, long first, long last, String reason) {
        Loss old = readLoss();
        Loss merged = old == null ? new Loss(count, first, last, reason)
            : new Loss(old.count + count, Math.min(old.firstWallMs, first), Math.max(old.lastWallMs, last),
                old.reason.equals(reason) ? reason : "multiple");
        byte[] bytes = merged.encode();
        if (bytes.length > LOSS_RESERVE_BYTES) throw new IllegalStateException("callback loss exceeds reserve");
        File temporary = new File(directory, LOSS_TMP);
        File ready = new File(directory, LOSS_READY);
        try {
            writeDurably(temporary, bytes);
            rootState.noteFile(temporary);
            Files.move(temporary.toPath(), ready.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            rootState.noteRename(temporary, ready);
        } catch (IOException error) {
            try { preserveBad(temporary); }
            catch (RuntimeException preserveError) { error.addSuppressed(preserveError); }
            rootState.invalidate();
            throw new IllegalStateException("cannot persist callback loss", error);
        }
    }

    private Loss readLoss() {
        File file = new File(directory, LOSS_READY);
        if (!file.isFile()) return null;
        try { return Loss.decode(readBytes(file, LOSS_RESERVE_BYTES)); }
        catch (Exception error) {
            preserveBad(file);
            rootState.invalidate();
            long now = System.currentTimeMillis();
            return new Loss(1L, now, now, "loss_summary_corruption");
        }
    }

    private File[] batchFiles(String suffix) {
        File[] files = directory.listFiles((dir, name) -> name.startsWith("cb_") && name.endsWith(suffix));
        if (files == null) throw new IllegalStateException("cannot list callback spool");
        return files;
    }

    private File findIdentity(String digest) {
        File indexed = rootState.callbackIdentityFile(digest);
        if (indexed != null && indexed.isFile()) return indexed;
        if (indexed != null) {
            rootState.invalidate();
            rootState.ensureFresh();
            indexed = rootState.callbackIdentityFile(digest);
            if (indexed != null && indexed.isFile()) return indexed;
        }
        if (!rootState.callbackIndexIncomplete()) return null;
        // Preserve the old recovery behavior for malformed externally-created filenames. Normal
        // records always use the indexed path and do not enumerate the callback directory.
        String marker = "_" + digest + "_";
        for (File file : batchFiles(READY)) if (file.getName().contains(marker)) return file;
        return null;
    }

    private void preserveBad(File file) {
        if (file == null || !file.isFile()) return;
        File target = new File(file.getPath() + BAD);
        int suffix = 1;
        while (target.exists()) target = new File(file.getPath() + BAD + "." + suffix++);
        if (!file.renameTo(target)) {
            rootState.invalidate();
            throw new IllegalStateException("cannot quarantine callback evidence");
        }
        rootState.noteRename(file, target);
    }

    private void ensureOpen() { if (closed) throw new IllegalStateException("callback spool closed"); }
    @Override public void close() { synchronized (persistenceLock) { closed = true; } }

    private static String fileStem(long order, TelemetryCallbackBatch batch, String digest) {
        return String.format(java.util.Locale.US, "cb_%020d_%d_%020d_%s_%s", order, batch.stream,
            batch.batchSequence, identityDigest(batch.identity()), digest);
    }
    private static String identityDigest(String identity) {
        return TelemetryCallbackBatch.digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static long parseOrder(String name) {
        String[] parts = name.split("_", 5);
        if (parts.length < 5 || !"cb".equals(parts[0])) throw new IllegalArgumentException("invalid callback order filename");
        return Long.parseLong(parts[1]);
    }
    private static long eventFirstWall(TelemetryCallbackBatch batch) { return batch.events.get(0).receivedWallMs; }
    private static long eventLastWall(TelemetryCallbackBatch batch) { return batch.events.get(batch.events.size() - 1).receivedWallMs; }

    private static byte[] readBytes(File file, long maxBytes) throws IOException {
        if (!file.isFile() || file.length() < 0 || file.length() > maxBytes) throw new IOException("callback file exceeds read bound");
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            long total = 0L;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) throw new IOException("callback file grew beyond read bound");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
    private static void writeDurably(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(bytes); output.flush(); FileDescriptor fd = output.getFD(); fd.sync();
        }
    }

    /** Shared, process-local accounting for every regular file in one canonical spool root. */
    static final class RootState {
        final File directory;
        final Object lock = new Object();

        private final Map<String, Long> regularFiles = new HashMap<>();
        private final NavigableSet<String> callbackReadyNames = new TreeSet<>();
        private final Map<String, NavigableSet<String>> callbackNamesByIdentity = new HashMap<>();
        private long totalBytes;
        private int readyRecords;
        private int callbackReadyRecords;
        private int callbackBadFiles;
        private int malformedCallbackReadyNames;
        private long nextCallbackOrder;
        private boolean callbackIndexIncomplete;
        private boolean initialized;
        private boolean dirty = true;
        private java.nio.file.attribute.FileTime directoryStamp;

        RootState(File directory) { this.directory = directory; }

        void ensureFresh() {
            if (!directory.isDirectory()) {
                dirty = true;
                throw new IllegalStateException("cannot list spool root: " + directory);
            }
            java.nio.file.attribute.FileTime currentStamp = directoryStamp();
            if (!initialized || dirty || !currentStamp.equals(directoryStamp)) rebuild(currentStamp);
        }

        long footprintBytes() {
            ensureFresh();
            return totalBytes;
        }

        int readyRecordCount() {
            ensureFresh();
            return readyRecords;
        }

        int callbackReadyCount() {
            ensureFresh();
            return callbackReadyRecords;
        }

        int callbackBadCount() {
            ensureFresh();
            return callbackBadFiles;
        }

        long nextCallbackOrder() {
            ensureFresh();
            return nextCallbackOrder;
        }

        void advanceCallbackOrder(long usedOrder) {
            if (usedOrder == Long.MAX_VALUE) nextCallbackOrder = 0L;
            else if (usedOrder >= nextCallbackOrder) nextCallbackOrder = usedOrder + 1L;
        }

        File oldestCallbackReady() {
            ensureFresh();
            return cachedOldestCallbackReady();
        }

        File cachedOldestCallbackReady() {
            String name = callbackReadyNames.isEmpty() ? null : callbackReadyNames.first();
            return name == null ? null : new File(directory, name);
        }

        boolean tracksCallbackReady(File file) {
            return file != null && regularFiles.containsKey(file.getName()) && isCallbackReady(file.getName());
        }

        File callbackIdentityFile(String identityDigest) {
            ensureFresh();
            NavigableSet<String> names = callbackNamesByIdentity.get(identityDigest);
            return names == null || names.isEmpty() ? null : new File(directory, names.first());
        }

        boolean callbackIndexIncomplete() {
            ensureFresh();
            return callbackIndexIncomplete;
        }

        /** Call after a successful create/truncate/write while holding {@link #lock}. */
        void noteFile(File file) {
            if (file == null) return;
            removeTracked(file.getName());
            if (file.isFile()) addTracked(file.getName(), file.length());
            updateStamp();
        }

        /** Call after a successful rename/move while holding {@link #lock}. */
        void noteRename(File from, File to) {
            if (from != null) removeTracked(from.getName());
            if (to != null) {
                removeTracked(to.getName());
                if (to.isFile()) addTracked(to.getName(), to.length());
            }
            updateStamp();
        }

        /** Call after a successful delete while holding {@link #lock}. */
        void noteDelete(File file) {
            if (file != null) {
                removeTracked(file.getName());
                if (file.isFile()) dirty = true;
            }
            updateStamp();
        }

        /** Call when an operation may have changed disk state but its outcome is uncertain. */
        void invalidate() { dirty = true; }

        private void rebuild(java.nio.file.attribute.FileTime currentStamp) {
            File[] files = directory.listFiles();
            if (files == null) {
                dirty = true;
                throw new IllegalStateException("cannot list spool root: " + directory);
            }
            boolean hadState = initialized;
            long previousNextCallbackOrder = nextCallbackOrder;
            regularFiles.clear();
            callbackReadyNames.clear();
            callbackNamesByIdentity.clear();
            totalBytes = 0L;
            readyRecords = 0;
            callbackReadyRecords = 0;
            callbackBadFiles = 0;
            malformedCallbackReadyNames = 0;
            callbackIndexIncomplete = false;
            nextCallbackOrder = 0L;

            long largestCallbackOrder = -1L;
            for (File file : files) {
                if (!file.isFile()) continue;
                String name = file.getName();
                addTracked(name, file.length());
                if (isCallbackOrderEvidence(name)) {
                    try { largestCallbackOrder = Math.max(largestCallbackOrder, parseOrder(name)); }
                    catch (RuntimeException ignored) { }
                }
            }
            long scannedNext = largestCallbackOrder == Long.MAX_VALUE ? 0L : largestCallbackOrder + 1L;
            if (hadState && previousNextCallbackOrder > scannedNext) nextCallbackOrder = previousNextCallbackOrder;
            else nextCallbackOrder = scannedNext;
            directoryStamp = currentStamp;
            initialized = true;
            dirty = false;
        }

        private void addTracked(String name, long length) {
            Long replaced = regularFiles.put(name, length);
            if (replaced != null) {
                subtractBytes(replaced);
                adjustCounts(name, -1);
                removeCallbackIndex(name);
            }
            if (Long.MAX_VALUE - totalBytes < length) totalBytes = Long.MAX_VALUE;
            else totalBytes += length;
            adjustCounts(name, 1);
            if (isCallbackReady(name)) addCallbackIndex(name);
            if (isCallbackOrderEvidence(name)) {
                try { advanceCallbackOrder(parseOrder(name)); }
                catch (RuntimeException ignored) { }
            }
        }

        private void removeTracked(String name) {
            Long length = regularFiles.remove(name);
            if (length == null) return;
            subtractBytes(length);
            adjustCounts(name, -1);
            removeCallbackIndex(name);
        }

        private void subtractBytes(long length) {
            if (totalBytes == Long.MAX_VALUE) {
                // Saturation is only possible for an impossible-to-fit spool; rebuild before a
                // later capacity decision rather than making an inexact subtraction.
                dirty = true;
            } else {
                totalBytes = Math.max(0L, totalBytes - length);
            }
        }

        private void adjustCounts(String name, int delta) {
            if (name.endsWith(".ready")) readyRecords += delta;
            if (isCallbackReady(name)) callbackReadyRecords += delta;
            if (name.contains(".cbbad")) callbackBadFiles += delta;
        }

        private void addCallbackIndex(String name) {
            callbackReadyNames.add(name);
            String identityDigest = callbackIdentityDigest(name);
            if (identityDigest == null) {
                malformedCallbackReadyNames++;
                callbackIndexIncomplete = malformedCallbackReadyNames > 0;
                return;
            }
            callbackNamesByIdentity.computeIfAbsent(identityDigest, ignored -> new TreeSet<>()).add(name);
        }

        private void removeCallbackIndex(String name) {
            if (!isCallbackReady(name)) return;
            callbackReadyNames.remove(name);
            String identityDigest = callbackIdentityDigest(name);
            if (identityDigest == null) {
                malformedCallbackReadyNames = Math.max(0, malformedCallbackReadyNames - 1);
                callbackIndexIncomplete = malformedCallbackReadyNames > 0;
                return;
            }
            NavigableSet<String> names = callbackNamesByIdentity.get(identityDigest);
            if (names == null) return;
            names.remove(name);
            if (names.isEmpty()) callbackNamesByIdentity.remove(identityDigest);
        }

        private void updateStamp() {
            try { directoryStamp = directoryStamp(); }
            catch (RuntimeException error) { dirty = true; }
        }

        private java.nio.file.attribute.FileTime directoryStamp() {
            try { return Files.getLastModifiedTime(directory.toPath()); }
            catch (IOException error) { throw new IllegalStateException("cannot stat spool root: " + directory, error); }
        }

        private static boolean isCallbackReady(String name) {
            return name.startsWith("cb_") && name.endsWith(READY);
        }

        private static boolean isCallbackOrderEvidence(String name) {
            return name.startsWith("cb_") &&
                (name.contains(READY) || name.contains(TMP) || name.contains(BAD));
        }

        private static String callbackIdentityDigest(String name) {
            String[] parts = name.split("_", 6);
            if (parts.length != 6 || !"cb".equals(parts[0]) ||
                !parts[4].matches("[0-9a-f]{64}") || !parts[5].endsWith(READY)) return null;
            return parts[4];
        }
    }

    public enum AppendResult { SUCCESS, DUPLICATE, CAP_REACHED, REJECTED }
    public enum AckResult { RELEASED, NOT_FOUND, REJECTED }

    public static final class Descriptor {
        public final long spoolOrder; public final int stream; public final String identity; public final String fileName;
        public final long length; public final String sha256; public final String bootId;
        public final String helperGeneration; public final long epoch; public final long batchSequence;
        public Descriptor(long spoolOrder, int stream, String identity, String fileName, long length, String sha256,
                          String bootId, String helperGeneration, long epoch, long batchSequence) {
            this.spoolOrder = spoolOrder; this.stream = stream; this.identity = identity; this.fileName = fileName; this.length = length;
            this.sha256 = sha256; this.bootId = bootId; this.helperGeneration = helperGeneration;
            this.epoch = epoch; this.batchSequence = batchSequence;
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Descriptor)) return false; Descriptor d = (Descriptor) value;
            return spoolOrder == d.spoolOrder && stream == d.stream && length == d.length && epoch == d.epoch && batchSequence == d.batchSequence &&
                identity.equals(d.identity) && fileName.equals(d.fileName) && sha256.equals(d.sha256) &&
                bootId.equals(d.bootId) && helperGeneration.equals(d.helperGeneration);
        }
        @Override public int hashCode() { return identity.hashCode(); }
    }

    public static final class Status {
        public final long footprintBytes; public final int readyBatches; public final int quarantinedFiles; public final Loss loss;
        Status(long footprintBytes, int readyBatches, int quarantinedFiles, Loss loss) {
            this.footprintBytes = footprintBytes; this.readyBatches = readyBatches;
            this.quarantinedFiles = quarantinedFiles; this.loss = loss;
        }
    }

    public static final class Loss {
        public final long count; public final long firstWallMs; public final long lastWallMs; public final String reason;
        Loss(long count, long firstWallMs, long lastWallMs, String reason) {
            if (count < 1 || firstWallMs < 0 || lastWallMs < firstWallMs || reason == null || reason.isEmpty() || reason.length() > 128) {
                throw new IllegalArgumentException("invalid callback loss");
            }
            this.count = count; this.firstWallMs = firstWallMs; this.lastWallMs = lastWallMs; this.reason = reason;
        }
        byte[] encode() {
            try { ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream o = new DataOutputStream(b);
                o.writeInt(1); o.writeLong(count); o.writeLong(firstWallMs); o.writeLong(lastWallMs); o.writeUTF(reason); o.close(); return b.toByteArray();
            } catch (IOException error) { throw new IllegalStateException(error); }
        }
        static Loss decode(byte[] bytes) throws IOException {
            DataInputStream i = new DataInputStream(new ByteArrayInputStream(bytes));
            if (i.readInt() != 1) throw new IOException("unsupported callback loss");
            Loss result = new Loss(i.readLong(), i.readLong(), i.readLong(), i.readUTF());
            if (i.available() != 0) throw new IOException("trailing callback loss"); return result;
        }
    }
}
