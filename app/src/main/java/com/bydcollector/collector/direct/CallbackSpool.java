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
import java.util.Comparator;
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
    private static final ConcurrentHashMap<String, Object> ROOT_LOCKS = new ConcurrentHashMap<>();

    private final File directory;
    private final long maxBytes;
    private final long ordinaryReserveBytes;
    private final Object persistenceLock;
    private long nextOrder;
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
        result.recover();
        result.nextOrder = result.findNextOrder();
        return result;
    }

    private CallbackSpool(File directory, long maxBytes, long ordinaryReserveBytes) {
        this.directory = directory;
        this.maxBytes = maxBytes;
        this.ordinaryReserveBytes = ordinaryReserveBytes;
        this.persistenceLock = persistenceLock(directory);
    }

    static Object persistenceLock(File directory) {
        try {
            return ROOT_LOCKS.computeIfAbsent(directory.getCanonicalPath(), ignored -> new Object());
        } catch (IOException error) {
            throw new IllegalStateException("cannot resolve spool root", error);
        }
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
            String digest = TelemetryCallbackBatch.digest(bytes);
            File existing = findIdentity(batch.identity());
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
            long order = nextOrder;
            String stem = fileStem(order, batch, digest);
            File ready = new File(directory, stem + READY);
            File temporary = new File(directory, stem + TMP);
            long footprint = footprintBytes();
            long ordinaryLimit = maxBytes - ordinaryReserveBytes;
            if (bytes.length > ordinaryLimit || footprint > ordinaryLimit - bytes.length) {
                if (recordCapacityLoss) mergeLoss(batch.events.size(), eventFirstWall(batch), eventLastWall(batch),
                    bytes.length > ordinaryLimit ? "oversize" : "capacity");
                return AppendResult.CAP_REACHED;
            }
            try {
                writeDurably(temporary, bytes);
                Files.move(temporary.toPath(), ready.toPath(), StandardCopyOption.ATOMIC_MOVE);
                nextOrder = order == Long.MAX_VALUE ? 0L : order + 1L;
                return AppendResult.SUCCESS;
            } catch (IOException error) {
                preserveBad(temporary);
                mergeLoss(batch.events.size(), eventFirstWall(batch), eventLastWall(batch), "io");
                return AppendResult.REJECTED;
            }
        }
    }

    public void recordLoss(long count, long firstWallMs, long lastWallMs, String reason) {
        synchronized (persistenceLock) {
            ensureOpen();
            mergeLoss(count, firstWallMs, lastWallMs, reason);
        }
    }

    public Descriptor oldest() {
        synchronized (persistenceLock) {
            ensureOpen();
            File[] files = batchFiles(READY);
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) {
                try { return descriptor(file); }
                catch (Exception error) {
                    long now = System.currentTimeMillis();
                    preserveBad(file);
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
            File file = exact(descriptor);
            if (!file.isFile()) return AckResult.NOT_FOUND;
            validate(file, descriptor);
            return file.delete() ? AckResult.RELEASED : AckResult.REJECTED;
        }
    }

    public int quarantine(Descriptor descriptor, String reason) {
        if (reason == null || reason.isEmpty() || reason.length() > 512) throw new IllegalArgumentException("invalid callback quarantine reason");
        synchronized (persistenceLock) {
            ensureOpen();
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
            File evidence = new File(directory, fileStem(nextOrder, batch, TelemetryCallbackBatch.digest(bytes)) + BAD);
            try {
                if (!evidence.exists() && footprintBytes() <= maxBytes - ordinaryReserveBytes - bytes.length) {
                    writeDurably(evidence, bytes);
                }
            }
            catch (IOException error) { throw new IllegalStateException("cannot preserve rejected callback memory batch", error); }
            mergeLoss(batch.events.size(), eventFirstWall(batch), eventLastWall(batch), "consumer_rejected");
        }
    }

    public Status status() {
        synchronized (persistenceLock) {
            ensureOpen();
            return new Status(footprintBytes(), batchFiles(READY).length, badFiles().length, readLoss());
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
                    if (!ready.exists()) Files.move(temporary.toPath(), ready.toPath(), StandardCopyOption.ATOMIC_MOVE);
                    else if (Arrays.equals(readBytes(ready, TelemetryCallbackBatch.MAX_BYTES), bytes)) Files.delete(temporary.toPath());
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
        try {
            writeDurably(temporary, bytes);
            Files.move(temporary.toPath(), new File(directory, LOSS_READY).toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            preserveBad(temporary);
            throw new IllegalStateException("cannot persist callback loss", error);
        }
    }

    private Loss readLoss() {
        File file = new File(directory, LOSS_READY);
        if (!file.isFile()) return null;
        try { return Loss.decode(readBytes(file, LOSS_RESERVE_BYTES)); }
        catch (Exception error) {
            preserveBad(file);
            long now = System.currentTimeMillis();
            return new Loss(1L, now, now, "loss_summary_corruption");
        }
    }

    private long footprintBytes() {
        File[] files = directory.listFiles();
        if (files == null) throw new IllegalStateException("cannot list callback spool root");
        long bytes = 0L;
        for (File file : files) if (file.isFile()) bytes += file.length();
        return bytes;
    }

    private File[] batchFiles(String suffix) {
        File[] files = directory.listFiles((dir, name) -> name.startsWith("cb_") && name.endsWith(suffix));
        if (files == null) throw new IllegalStateException("cannot list callback spool");
        return files;
    }

    private File[] badFiles() {
        File[] files = directory.listFiles((dir, name) -> name.contains(BAD));
        return files == null ? new File[0] : files;
    }

    private File findIdentity(String identity) {
        String marker = "_" + identityDigest(identity) + "_";
        for (File file : batchFiles(READY)) {
            if (file.getName().contains(marker)) return file;
        }
        return null;
    }

    private long findNextOrder() {
        File[] files = directory.listFiles((dir, name) -> name.startsWith("cb_") &&
            (name.contains(READY) || name.contains(TMP) || name.contains(BAD)));
        if (files == null) throw new IllegalStateException("cannot list callback spool order");
        long next = 0L;
        for (File file : files) {
            try {
                long order = parseOrder(file.getName());
                if (order >= next) next = order == Long.MAX_VALUE ? 0L : order + 1L;
            } catch (Exception ignored) { }
        }
        return next;
    }

    private void preserveBad(File file) {
        if (file == null || !file.isFile()) return;
        File target = new File(file.getPath() + BAD);
        int suffix = 1;
        while (target.exists()) target = new File(file.getPath() + BAD + "." + suffix++);
        if (!file.renameTo(target)) throw new IllegalStateException("cannot quarantine callback evidence");
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
