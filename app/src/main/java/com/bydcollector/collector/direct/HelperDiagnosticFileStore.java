package com.bydcollector.collector.direct;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

//All diagnostic writers and the future Share/Clear entrypoint use this same lock file.
final class HelperDiagnosticFileStore implements HelperDiagnostics.Sink {
    static final String DIRECTORY_PATH = "/data/local/tmp/bydcollector_helper_diagnostics";
    static final String ACTIVE_NAME = "helper-diagnostics.jsonl";
    static final String SNAPSHOT_NAME = "helper-diagnostics-current.json";
    static final String LOCK_NAME = "helper-diagnostics.lock";
    static final String BOOTSTRAP_NAME = "helper-bootstrap.log";
    static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;
    static final int ROTATION_COUNT = 3;
    static final String LEGACY_DIRECTORY_PATH = "/data/local/tmp";
    static final String LEGACY_BOOTSTRAP_NAME = "bydcollector_helper.log";
    static final String ARCHIVE_PREFIX = "helper-diagnostics-export-";
    static final String ARCHIVE_SUFFIX = ".zip";
    static final String ZIP_PREFIX = "helper-diagnostics/";
    static final String MANIFEST_ENTRY = ZIP_PREFIX + "manifest.json";
    static final long MAX_CURRENT_SNAPSHOT_BYTES = 256L * 1024L;
    static final long STALE_ARCHIVE_AGE_MS = 24L * 60L * 60L * 1000L;
    static final int MAX_STALE_ARCHIVE_DELETES = 32;
    private static final Object PROCESS_LOCK = new Object();

    private final File directory;
    private final File legacyDirectory;
    private final long maxLogBytes;

    HelperDiagnosticFileStore(File directory) {
        this(directory, new File(LEGACY_DIRECTORY_PATH), MAX_LOG_BYTES);
    }

    HelperDiagnosticFileStore(File directory, long maxLogBytes) {
        this(directory, new File(LEGACY_DIRECTORY_PATH), maxLogBytes);
    }

    HelperDiagnosticFileStore(File directory, File legacyDirectory, long maxLogBytes) {
        if (directory == null) throw new IllegalArgumentException("directory is required");
        if (legacyDirectory == null) throw new IllegalArgumentException("legacyDirectory is required");
        if (maxLogBytes < 1L || maxLogBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxLogBytes must fit a bounded byte array");
        }
        this.directory = directory;
        this.legacyDirectory = legacyDirectory;
        this.maxLogBytes = maxLogBytes;
    }

    SnapshotArchive createSnapshotArchive() throws Exception {
        ensureDirectory();
        cleanupStaleArchives();
        final File archive = File.createTempFile(ARCHIVE_PREFIX, ARCHIVE_SUFFIX, directory);
        try {
            withSharedLock(new LockedAction() {
                @Override public void run() throws Exception {
                    writeSnapshotArchive(archive);
                }
            });
            return new SnapshotArchive(archive);
        } catch (Throwable error) {
            archive.delete();
            if (error instanceof Exception) throw (Exception) error;
            throw (Error) error;
        }
    }

    ClearResult clearDiagnostics() throws Exception {
        ensureDirectory();
        cleanupStaleArchives();
        final ClearResult result = new ClearResult();
        withSharedLock(new LockedAction() {
            @Override public void run() throws Exception {
                clearActive(new File(directory, ACTIVE_NAME), ZIP_PREFIX + ACTIVE_NAME, result);
                clearRotations(directory, ACTIVE_NAME, ZIP_PREFIX + ACTIVE_NAME, result);
                clearActive(new File(directory, BOOTSTRAP_NAME), ZIP_PREFIX + BOOTSTRAP_NAME, result);
                clearRotations(directory, BOOTSTRAP_NAME, ZIP_PREFIX + BOOTSTRAP_NAME, result);
                clearActive(
                    new File(legacyDirectory, LEGACY_BOOTSTRAP_NAME),
                    ZIP_PREFIX + LEGACY_BOOTSTRAP_NAME,
                    result
                );
                clearRotations(
                    legacyDirectory,
                    LEGACY_BOOTSTRAP_NAME,
                    ZIP_PREFIX + LEGACY_BOOTSTRAP_NAME,
                    result
                );
            }
        });
        return result;
    }

    @Override public void persist(String jsonLine, String snapshotJson) throws Exception {
        synchronized (PROCESS_LOCK) {
            ensureDirectory();
            RandomAccessFile lockFile = new RandomAccessFile(new File(directory, LOCK_NAME), "rw");
            try {
                FileChannel channel = lockFile.getChannel();
                FileLock lock = channel.lock();
                try {
                    appendRotated(jsonLine);
                    replaceSnapshot(snapshotJson);
                } finally {
                    lock.release();
                    channel.close();
                }
            } finally {
                lockFile.close();
            }
        }
    }

    @Override public void persistBootstrap(byte[] chunk) throws Exception {
        if (chunk == null || chunk.length == 0) return;
        synchronized (PROCESS_LOCK) {
            ensureDirectory();
            RandomAccessFile lockFile = new RandomAccessFile(new File(directory, LOCK_NAME), "rw");
            try {
                FileChannel channel = lockFile.getChannel();
                FileLock lock = channel.lock();
                try {
                    appendRotatedBytes(BOOTSTRAP_NAME, chunk);
                } finally {
                    lock.release();
                    channel.close();
                }
            } finally {
                lockFile.close();
            }
        }
    }

    private void withSharedLock(LockedAction action) throws Exception {
        synchronized (PROCESS_LOCK) {
            File lockPath = new File(directory, LOCK_NAME);
            if (Files.isSymbolicLink(lockPath.toPath())) {
                throw new IOException("diagnostic lock is a symbolic link");
            }
            RandomAccessFile lockFile = new RandomAccessFile(lockPath, "rw");
            try {
                FileChannel channel = lockFile.getChannel();
                FileLock lock = channel.lock();
                try {
                    action.run();
                } finally {
                    lock.release();
                    channel.close();
                }
            } finally {
                lockFile.close();
            }
        }
    }

    private void writeSnapshotArchive(File archive) throws Exception {
        JSONArray files = new JSONArray();
        boolean[] partial = { false };
        FileOutputStream fileOutput = new FileOutputStream(archive, false);
        try {
            ZipOutputStream zip = new ZipOutputStream(fileOutput);
            try {
                addArchiveFamily(zip, directory, ACTIVE_NAME, "diagnostic_jsonl", true, files, partial);
                addArchiveFamily(zip, directory, BOOTSTRAP_NAME, "bootstrap_in_jvm", false, files, partial);
                addArchiveFile(
                    zip,
                    new File(directory, SNAPSHOT_NAME),
                    ZIP_PREFIX + SNAPSHOT_NAME,
                    "current_snapshot",
                    MAX_CURRENT_SNAPSHOT_BYTES,
                    false,
                    true,
                    files,
                    partial
                );
                addArchiveFamily(
                    zip,
                    legacyDirectory,
                    LEGACY_BOOTSTRAP_NAME,
                    "bootstrap_pre_jvm",
                    false,
                    files,
                    partial
                );
                JSONObject manifest = new JSONObject();
                manifest.put("schema_version", 1);
                manifest.put("operation", "snapshot");
                manifest.put("created_wall_ms", System.currentTimeMillis());
                manifest.put("overall_status", partial[0] ? "partial" : "complete");
                manifest.put("coherent_scope", "in_jvm_diagnostics_locked");
                manifest.put("legacy_pre_jvm_consistency", "best_effort_unlocked_writer");
                manifest.put("files", files);
                putZipEntry(zip, MANIFEST_ENTRY, manifest.toString().getBytes(StandardCharsets.UTF_8));
                zip.finish();
                zip.flush();
                fileOutput.getFD().sync();
            } finally {
                zip.close();
            }
        } finally {
            fileOutput.close();
        }
    }

    private void addArchiveFamily(
        ZipOutputStream zip,
        File sourceDirectory,
        String baseName,
        String sourceKind,
        boolean preserveJsonLines,
        JSONArray files,
        boolean[] partial
    ) throws Exception {
        addArchiveFile(
            zip,
            new File(sourceDirectory, baseName),
            ZIP_PREFIX + baseName,
            sourceKind,
            maxLogBytes,
            preserveJsonLines,
            false,
            files,
            partial
        );
        for (int index = 1; index <= ROTATION_COUNT; index++) {
            addArchiveFile(
                zip,
                new File(sourceDirectory, baseName + "." + index),
                ZIP_PREFIX + baseName + "." + index,
                sourceKind,
                maxLogBytes,
                preserveJsonLines,
                false,
                files,
                partial
            );
        }
    }

    private void addArchiveFile(
        ZipOutputStream zip,
        File source,
        String entryName,
        String sourceKind,
        long maxBytes,
        boolean preserveJsonLines,
        boolean required,
        JSONArray files,
        boolean[] partial
    ) throws Exception {
        JSONObject status = new JSONObject();
        status.put("entry_name", entryName);
        status.put("source_kind", sourceKind);
        status.put("required", required);
        status.put("bytes", 0L);
        status.put("truncated", false);
        status.put("error", JSONObject.NULL);
        if (Files.isSymbolicLink(source.toPath())) {
            status.put("status", "error");
            status.put("error", "symbolic_link_rejected");
            partial[0] = true;
            files.put(status);
            return;
        }
        if (!source.exists()) {
            status.put("status", "missing");
            if (required) partial[0] = true;
            files.put(status);
            return;
        }
        if (!source.isFile()) {
            status.put("status", "error");
            status.put("error", "not_regular_file");
            partial[0] = true;
            files.put(status);
            return;
        }
        // The current snapshot is one atomic JSON document, so tail truncation would make it
        // misleading or unparsable. Treat an impossible oversized snapshot as an explicit
        // partial capture instead; bounded rolling logs can safely retain their recent tail.
        if (required && source.length() > maxBytes) {
            status.put("status", "error");
            status.put("error", "source_exceeds_cap");
            partial[0] = true;
            files.put(status);
            return;
        }
        try {
            BoundedFile bounded = readBounded(source, maxBytes, preserveJsonLines);
            putZipEntry(zip, entryName, bounded.bytes);
            status.put("status", "included");
            status.put("bytes", bounded.bytes.length);
            status.put("truncated", bounded.truncated);
        } catch (Throwable error) {
            status.put("status", "error");
            status.put("error", errorType(error));
            partial[0] = true;
        }
        files.put(status);
    }

    private static BoundedFile readBounded(
        File source,
        long maxBytes,
        boolean preserveJsonLines
    ) throws IOException {
        long length = source.length();
        boolean truncated = length > maxBytes;
        long start = truncated ? length - maxBytes : 0L;
        int requested = (int) Math.min(maxBytes, Math.max(0L, length - start));
        byte[] bytes = new byte[requested];
        boolean startsAtLineBoundary = false;
        RandomAccessFile input = new RandomAccessFile(source, "r");
        try {
            if (truncated && preserveJsonLines && start > 0L) {
                input.seek(start - 1L);
                startsAtLineBoundary = input.read() == '\n';
            }
            input.seek(start);
            input.readFully(bytes);
        } finally {
            input.close();
        }
        int offset = 0;
        if (truncated && preserveJsonLines && !startsAtLineBoundary) {
            while (offset < bytes.length && bytes[offset] != '\n') offset++;
            if (offset < bytes.length) offset++;
        }
        if (offset == 0) return new BoundedFile(bytes, truncated);
        byte[] aligned = new byte[bytes.length - offset];
        System.arraycopy(bytes, offset, aligned, 0, aligned.length);
        return new BoundedFile(aligned, true);
    }

    private static void putZipEntry(ZipOutputStream zip, String entryName, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private void clearRotations(
        File sourceDirectory,
        String baseName,
        String resultBaseName,
        ClearResult result
    ) throws Exception {
        for (int index = 1; index <= ROTATION_COUNT; index++) {
            clearRotation(
                new File(sourceDirectory, baseName + "." + index),
                resultBaseName + "." + index,
                result
            );
        }
    }

    private static void clearActive(File file, String name, ClearResult result) throws Exception {
        if (Files.isSymbolicLink(file.toPath())) {
            result.add(name, "truncate", "error", "symbolic_link_rejected");
            return;
        }
        if (!file.exists()) {
            result.add(name, "truncate", "missing", null);
            return;
        }
        if (!file.isFile()) {
            result.add(name, "truncate", "error", "not_regular_file");
            return;
        }
        try {
            RandomAccessFile output = new RandomAccessFile(file, "rw");
            try {
                output.setLength(0L);
                output.getFD().sync();
            } finally {
                output.close();
            }
            result.add(name, "truncate", "cleared", null);
        } catch (Throwable error) {
            result.add(name, "truncate", "error", errorType(error));
        }
    }

    private static void clearRotation(File file, String name, ClearResult result) throws Exception {
        if (Files.isSymbolicLink(file.toPath())) {
            result.add(name, "delete", "error", "symbolic_link_rejected");
        } else if (!file.exists()) {
            result.add(name, "delete", "missing", null);
        } else if (!file.isFile()) {
            result.add(name, "delete", "error", "not_regular_file");
        } else if (file.delete()) {
            result.add(name, "delete", "cleared", null);
        } else {
            result.add(name, "delete", "error", "delete_failed");
        }
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("cannot create helper diagnostic directory: " + directory);
        }
    }

    private void cleanupStaleArchives() {
        File[] candidates = directory.listFiles();
        if (candidates == null) return;
        long cutoff = System.currentTimeMillis() - STALE_ARCHIVE_AGE_MS;
        int deleted = 0;
        for (File candidate : candidates) {
            if (deleted >= MAX_STALE_ARCHIVE_DELETES) return;
            String name = candidate.getName();
            if (!name.startsWith(ARCHIVE_PREFIX) || !name.endsWith(ARCHIVE_SUFFIX)) continue;
            if (Files.isSymbolicLink(candidate.toPath()) || !candidate.isFile()) continue;
            if (candidate.lastModified() > 0L && candidate.lastModified() < cutoff && candidate.delete()) {
                deleted++;
            }
        }
    }

    private void appendRotated(String jsonLine) throws IOException {
        byte[] payload = (jsonLine + "\n").getBytes(StandardCharsets.UTF_8);
        appendRotatedBytes(ACTIVE_NAME, payload);
    }

    private void appendRotatedBytes(String activeName, byte[] payload) throws IOException {
        if (payload.length > maxLogBytes) throw new IOException("diagnostic event exceeds log cap");
        normalizeExistingFiles(activeName);
        File active = new File(directory, activeName);
        if (active.isFile() && active.length() + payload.length > maxLogBytes) rotate(activeName, active);
        FileOutputStream output = new FileOutputStream(active, true);
        try {
            output.write(payload);
            output.flush();
            FileDescriptor descriptor = output.getFD();
            descriptor.sync();
        } finally {
            output.close();
        }
    }

    private void normalizeExistingFiles(String activeName) throws IOException {
        boolean preserveJsonLines = ACTIVE_NAME.equals(activeName);
        trimToRecentTail(new File(directory, activeName), preserveJsonLines);
        for (int index = 1; index <= ROTATION_COUNT; index++) {
            trimToRecentTail(rotation(activeName, index), preserveJsonLines);
        }
    }

    private void trimToRecentTail(File file, boolean preserveJsonLines) throws IOException {
        if (!file.isFile() || file.length() <= maxLogBytes) return;
        byte[] tail = new byte[(int) maxLogBytes];
        RandomAccessFile random = new RandomAccessFile(file, "rw");
        try {
            long start = random.length() - maxLogBytes;
            boolean startsAtLineBoundary = false;
            if (preserveJsonLines && start > 0L) {
                random.seek(start - 1L);
                startsAtLineBoundary = random.read() == '\n';
            }
            random.seek(start);
            random.readFully(tail);
            int offset = 0;
            if (preserveJsonLines && !startsAtLineBoundary) {
                while (offset < tail.length && tail[offset] != '\n') offset++;
                if (offset < tail.length) offset++;
            }
            random.seek(0L);
            random.write(tail, offset, tail.length - offset);
            random.setLength(tail.length - offset);
            random.getFD().sync();
        } finally {
            random.close();
        }
    }

    private void rotate(String activeName, File active) throws IOException {
        File oldest = rotation(activeName, ROTATION_COUNT);
        if (oldest.exists() && !oldest.delete()) throw new IOException("cannot delete oldest diagnostic rotation");
        for (int index = ROTATION_COUNT - 1; index >= 1; index--) {
            File source = rotation(activeName, index);
            if (source.exists()) move(source, rotation(activeName, index + 1));
        }
        move(active, rotation(activeName, 1));
    }

    private File rotation(String activeName, int index) {
        return new File(directory, activeName + "." + index);
    }

    private static void move(File source, File target) throws IOException {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private void replaceSnapshot(String snapshotJson) throws IOException {
        File target = new File(directory, SNAPSHOT_NAME);
        File temporary = new File(directory, SNAPSHOT_NAME + ".tmp");
        byte[] payload = (snapshotJson + "\n").getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = new FileOutputStream(temporary, false);
        try {
            output.write(payload);
            output.flush();
            output.getFD().sync();
        } finally {
            output.close();
        }
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        );
    }

    private interface LockedAction {
        void run() throws Exception;
    }

    static final class SnapshotArchive implements AutoCloseable {
        final File file;

        SnapshotArchive(File file) {
            this.file = file;
        }

        @Override public void close() {
            if (file.isFile()) file.delete();
        }
    }

    static final class ClearResult {
        private final JSONArray files = new JSONArray();
        private boolean partial;

        void add(String name, String action, String status, String error) throws Exception {
            JSONObject item = new JSONObject();
            item.put("name", name);
            item.put("action", action);
            item.put("status", status);
            item.put("error", error == null ? JSONObject.NULL : error);
            files.put(item);
            if ("error".equals(status)) partial = true;
        }

        boolean isComplete() {
            return !partial;
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("schema_version", 1);
            json.put("operation", "clear");
            json.put("completed_wall_ms", System.currentTimeMillis());
            json.put("overall_status", partial ? "partial" : "complete");
            json.put("legacy_pre_jvm_consistency", "best_effort_unlocked_writer");
            json.put("files", files);
            json.put("preserved", new JSONArray()
                .put(SNAPSHOT_NAME)
                .put(LOCK_NAME)
                .put("raw_telemetry_spool"));
            return json;
        }
    }

    private static final class BoundedFile {
        final byte[] bytes;
        final boolean truncated;

        BoundedFile(byte[] bytes, boolean truncated) {
            this.bytes = bytes;
            this.truncated = truncated;
        }
    }
}
