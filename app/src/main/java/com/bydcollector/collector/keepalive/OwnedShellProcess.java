package com.bydcollector.collector.keepalive;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import com.bydcollector.collector.BuildConfig;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** One shell child group with a deadline, parent-pipe ownership and bounded output. */
final class OwnedShellProcess implements AutoCloseable {
    private static final Path CODE_DIR = Paths.get("/data/local/tmp/bydcollector_keepalive_code");
    private static final Path CODE_APK = CODE_DIR.resolve("runtime.apk");
    private static final String SOURCE_ENV = "BYDCOLLECTOR_CHILD_SOURCE_APK";
    private static final String REVISION_ENV = "BYDCOLLECTOR_CHILD_REVISION";
    private static boolean codePrepared;
    private Process active;
    private int pid;
    private long epoch;
    synchronized long token() { return epoch; }

    static final class Result {
        final int exitCode; final String output;
        Result(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
    }

    static final class Failure extends IOException {
        final String output;
        Failure(String name, String output, Exception cause) {
            super("Shell child failed: " + name + " output=" + output, cause);
            this.output = output;
        }
    }

    static final class StaleGenerationException extends IOException {
        static final int EXIT_CODE = 75;
        StaleGenerationException(String message) { super(message); }
    }

    Result run(long token, String className, String niceName, long deadline, String... args) throws Exception {
        prepareCode(deadline);
        Process child;
        synchronized (this) {
            if (epoch != token) throw new InterruptedIOException("Shell child cancelled before start");
            if (active != null && active.isAlive()) throw new IOException("Previous shell child still alive; refusing overlap");
            if (!niceName.matches("bydcollector_[a-z0-9_]+")) throw new IOException("Invalid child name");
            if (SystemClock.elapsedRealtime() >= deadline) throw new InterruptedIOException("Shell child deadline expired before start");
            List<String> command = new ArrayList<>(Arrays.asList("/system/bin/setsid", "/system/bin/sh", "-c",
                    "echo CHILD_PID=$$; exec app_process /system/bin --nice-name=" + niceName + " \"$@\"",
                    "child", className, Long.toString(deadline)));
            command.addAll(Arrays.asList(args));
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("CLASSPATH", CODE_APK.toString());
            builder.environment().put(SOURCE_ENV, sourceApk());
            builder.environment().put(REVISION_ENV, BuildConfig.RUNTIME_REVISION);
            active = child = builder.start();
            pid = 0;
        }
        FutureTask<String> output = new FutureTask<>(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                if (header == null || !header.matches("CHILD_PID=[1-9][0-9]*"))
                    throw new IOException("Shell child identity missing: " + header);
                int childPid = Integer.parseInt(header.substring(10));
                synchronized (this) {
                    if (!CellularApnCheck.ownsGroup(childPid)) throw new IOException("Shell child group mismatch: " + childPid);
                    if (active != child) throw new IOException("Shell child ownership changed");
                    pid = childPid;
                    if (token != epoch) stop();
                    else {
                        child.getOutputStream().write("start\n".getBytes(StandardCharsets.UTF_8));
                        child.getOutputStream().flush();
                    }
                }
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[1024];
                int n; boolean truncated = false;
                while ((n = reader.read(buffer)) >= 0) {
                    int keep = Math.min(n, Math.max(0, 65_536 - text.length()));
                    text.append(buffer, 0, keep); truncated |= keep != n;
                }
                if (truncated) text.append("\n[output truncated]");
                return text.toString();
            }
        });
        Thread drain = new Thread(output, niceName + "-output");
        drain.setDaemon(true); drain.start();
        Exception failure = null;
        try {
            if (!child.waitFor(Math.max(1L, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS))
                failure = new IOException("Shell child deadline exceeded: " + niceName + "; remote completion unknown");
        } catch (Exception e) { failure = e; }
        synchronized (this) { if (active == child) stop(); }
        String captured = "";
        try {
            if (!child.waitFor(1, TimeUnit.SECONDS)) throw new IOException("Shell child did not exit: " + niceName);
            captured = output.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (failure == null) failure = e; else failure.addSuppressed(e);
        } finally {
            output.cancel(true);
            try { child.getInputStream().close(); }
            catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            synchronized (this) { if (active == child && !child.isAlive()) { active = null; pid = 0; } }
        }
        synchronized (this) {
            if (token != epoch && failure == null) failure = new InterruptedIOException("Shell child cancelled: " + niceName);
        }
        if (failure != null) throw new Failure(niceName, captured, failure);
        if (child.exitValue() == StaleGenerationException.EXIT_CODE)
            throw new StaleGenerationException("Shell child rejected APK generation: " + niceName + " output=" + captured);
        return new Result(child.exitValue(), captured);
    }

    synchronized void cancel() { epoch++; stop(); }

    private static String sourceApk() throws IOException {
        String source = System.getenv("CLASSPATH");
        if (source == null || !new File(source).isAbsolute() || source.indexOf(':') >= 0)
            throw new IOException("Expected one absolute daemon APK in CLASSPATH: " + source);
        return source;
    }

    private static void verifyInstalledSource(String expected) throws Exception {
        android.os.IBinder binder = (android.os.IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "package");
        if (binder == null) throw new IOException("Package service unavailable during shell generation check");
        Class<?> api = Class.forName("android.content.pm.IPackageManager");
        Object manager = Class.forName(api.getName() + "$Stub").getMethod("asInterface", android.os.IBinder.class)
                .invoke(null, binder);
        android.content.pm.ApplicationInfo info;
        try {
            info = (android.content.pm.ApplicationInfo) api.getMethod("getApplicationInfo", String.class, int.class, int.class)
                    .invoke(manager, "com.bydcollector.collector", 0, 0);
        } catch (NoSuchMethodException newerFlags) {
            info = (android.content.pm.ApplicationInfo) api.getMethod("getApplicationInfo", String.class, long.class, int.class)
                    .invoke(manager, "com.bydcollector.collector", 0L, 0);
        }
        String installed = info == null ? null : info.sourceDir;
        if (expected == null || !expected.equals(installed))
            throw new StaleGenerationException("Stale shell APK generation: source=" + expected + " installed=" + installed
                    + "; awaiting keep-alive supervisor replacement");
    }

    static void verifyOwnerGeneration() throws Exception {
        String source = sourceApk();
        try {
            if (!OsConstants.S_ISREG(Os.stat(source).st_mode)) throw new IOException("Daemon APK is not a regular file: " + source);
        } catch (android.system.ErrnoException error) {
            if (error.errno != OsConstants.ENOENT) throw error;
            StaleGenerationException replaced = new StaleGenerationException("Daemon APK removed: " + source
                    + "; awaiting keep-alive supervisor replacement");
            replaced.initCause(error);
            throw replaced;
        }
    }

    /** A stable pathname avoids ART's pre-main abort when PackageManager removes the old APK. */
    private static synchronized void prepareCode(long deadline) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("Shell code cache requires UID 2000");
        String source = sourceApk();
        verifyOwnerGeneration();
        if (codePrepared) return;
        try { Files.createDirectory(CODE_DIR, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))); }
        catch (FileAlreadyExistsException exists) { /* Validate the existing private directory below. */ }
        android.system.StructStat directory = Os.lstat(CODE_DIR.toString());
        if (!OsConstants.S_ISDIR(directory.st_mode) || directory.st_uid != 2000 || (directory.st_mode & 077) != 0)
            throw new IOException("Unsafe shell code cache directory: " + CODE_DIR);
        Path staging = CODE_DIR.resolve("runtime.apk.tmp");
        // Single daemon ownership permits removing only this unfinished staging file after a crash.
        Files.deleteIfExists(staging);
        try {
            try (InputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(Files.createFile(staging).toFile())) {
                byte[] buffer = new byte[262_144];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= deadline)
                        throw new InterruptedIOException("Shell code snapshot cancelled or deadline expired");
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            verifyOwnerGeneration();
            Os.chmod(staging.toString(), 0400);
            Files.move(staging, CODE_APK, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            codePrepared = true;
            KeepAliveDaemon.log("shell_child_code_ready source=" + source + " revision=" + BuildConfig.RUNTIME_REVISION);
        } catch (Exception error) {
            try { Files.deleteIfExists(staging); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    static void verifyChildGeneration() throws Exception {
        String expectedRevision = System.getenv(REVISION_ENV);
        if (!BuildConfig.RUNTIME_REVISION.equals(expectedRevision))
            throw new StaleGenerationException("Shell child code revision changed: expected=" + expectedRevision
                    + " actual=" + BuildConfig.RUNTIME_REVISION + "; awaiting keep-alive supervisor replacement");
        verifyInstalledSource(System.getenv(SOURCE_ENV));
    }

    private void stop() {
        if (active == null) return;
        try { active.getOutputStream().close(); }
        catch (Exception e) { log(e, "child_pipe_close"); }
        try {
            if (pid > 0) Os.kill(-pid, OsConstants.SIGKILL);
            else if (active.isAlive()) active.destroyForcibly();
        } catch (android.system.ErrnoException e) {
            if (e.errno != OsConstants.ESRCH) log(e, "child_group_stop");
        } catch (Exception e) { log(e, "child_stop"); }
    }

    private static void log(Exception e, String stage) {
        KeepAliveDaemon.log("shell_child_error " + KeepAliveDaemon.diagnosticDetail(e, "stage=" + stage));
    }
    @Override public void close() { cancel(); }
}
