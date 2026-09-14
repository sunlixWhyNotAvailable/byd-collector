package com.bydcollector.collector.direct;

import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

/** Keeps helper output bounded without making telemetry wait for diagnostic disk writes. */
final class HelperBootstrapOutput {
    static final int MAX_CHUNK_BYTES = 4096;

    private HelperBootstrapOutput() { }

    static void install(HelperDiagnostics diagnostics) {
        try {
            PrintStream output = new PrintStream(new QueuedOutput(diagnostics::enqueueBootstrap), true, "UTF-8");
            System.setOut(output);
            System.setErr(output);
        } catch (Throwable error) {
            noteFailure(diagnostics, "java_output", error);
        }

        // Native code can write directly to fd 1/2, bypassing System.out/err. Drain
        // the pipe even when the bounded writer drops output or cannot write disk.
        FileDescriptor[] pipe = null;
        try {
            pipe = Os.pipe();
            Os.dup2(pipe[1], 1);
            Os.dup2(pipe[1], 2);
            closeQuietly(pipe[1]);
            pipe[1] = null;
            final FileDescriptor input = pipe[0];
            Thread reader = new Thread(() -> drain(input, diagnostics), "helper-bootstrap-drain");
            reader.setDaemon(true);
            reader.start();
        } catch (Throwable error) {
            muteNativeOutput();
            if (pipe != null) {
                closeQuietly(pipe[0]);
                closeQuietly(pipe[1]);
            }
            noteFailure(diagnostics, "native_output", error);
        }
    }

    private static void drain(FileDescriptor input, HelperDiagnostics diagnostics) {
        FileInputStream stream = null;
        try {
            stream = new FileInputStream(input);
            byte[] buffer = new byte[MAX_CHUNK_BYTES];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (count > 0) diagnostics.enqueueBootstrap(Arrays.copyOf(buffer, count));
            }
        } catch (Throwable error) {
            // A dead reader must not leave native writes blocked on a full pipe.
            muteNativeOutput();
            noteFailure(diagnostics, "native_drain", error);
        } finally {
            // Redirect native writers before closing a failed pipe's read end.
            if (stream != null) {
                try { stream.close(); } catch (Throwable ignored) { }
            } else {
                closeQuietly(input);
            }
        }
    }

    private static void muteNativeOutput() {
        FileDescriptor sink = null;
        try {
            sink = Os.open("/dev/null", OsConstants.O_WRONLY, 0);
            Os.dup2(sink, 1);
            Os.dup2(sink, 2);
        } catch (Throwable ignored) {
            // Diagnostic setup failure must not prevent helper startup.
        } finally {
            closeQuietly(sink);
        }
    }

    private static void closeQuietly(FileDescriptor descriptor) {
        if (descriptor == null) return;
        try { Os.close(descriptor); } catch (Throwable ignored) { }
    }

    private static void noteFailure(HelperDiagnostics diagnostics, String phase, Throwable error) {
        try {
            // No raw exception message: bootstrap setup needs only the phase/type.
            diagnostics.enqueueBootstrap(("bootstrap_capture_unavailable phase=" + phase +
                " error=" + error.getClass().getSimpleName() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
    }

    static final class QueuedOutput extends OutputStream {
        private final Consumer<byte[]> sink;

        QueuedOutput(Consumer<byte[]> sink) {
            this.sink = sink;
        }

        @Override public void write(int value) {
            offer(new byte[] { (byte) value });
        }

        @Override public void write(byte[] bytes, int offset, int count) {
            if (bytes == null) throw new NullPointerException("bytes");
            if (offset < 0 || count < 0 || offset > bytes.length - count) {
                throw new IndexOutOfBoundsException();
            }
            int end = offset + count;
            while (offset < end) {
                int next = Math.min(end, offset + MAX_CHUNK_BYTES);
                offer(Arrays.copyOfRange(bytes, offset, next));
                offset = next;
            }
        }

        private void offer(byte[] bytes) {
            try { sink.accept(bytes); } catch (Throwable ignored) { }
        }
    }
}
