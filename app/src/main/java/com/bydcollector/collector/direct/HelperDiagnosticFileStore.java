package com.bydcollector.collector.direct;

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

//All diagnostic writers and the future Share/Clear entrypoint use this same lock file.
final class HelperDiagnosticFileStore implements HelperDiagnostics.Sink {
    static final String DIRECTORY_PATH = "/data/local/tmp/bydcollector_helper_diagnostics";
    static final String ACTIVE_NAME = "helper-diagnostics.jsonl";
    static final String SNAPSHOT_NAME = "helper-diagnostics-current.json";
    static final String LOCK_NAME = "helper-diagnostics.lock";
    static final String BOOTSTRAP_NAME = "helper-bootstrap.log";
    static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;
    static final int ROTATION_COUNT = 3;

    private final File directory;
    private final long maxLogBytes;

    HelperDiagnosticFileStore(File directory) {
        this(directory, MAX_LOG_BYTES);
    }

    HelperDiagnosticFileStore(File directory, long maxLogBytes) {
        if (directory == null) throw new IllegalArgumentException("directory is required");
        if (maxLogBytes < 1L) throw new IllegalArgumentException("maxLogBytes must be positive");
        this.directory = directory;
        this.maxLogBytes = maxLogBytes;
    }

    @Override public void persist(String jsonLine, String snapshotJson) throws Exception {
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

    @Override public void persistBootstrap(byte[] chunk) throws Exception {
        if (chunk == null || chunk.length == 0) return;
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

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("cannot create helper diagnostic directory: " + directory);
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
}
