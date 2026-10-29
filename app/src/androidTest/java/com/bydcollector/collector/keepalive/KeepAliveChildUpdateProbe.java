package com.bydcollector.collector.keepalive;

import android.os.SystemClock;
import android.system.Os;
import com.bydcollector.collector.BuildConfig;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Emulator shell check using a real installed APK and an actual host-driven package replacement. */
public final class KeepAliveChildUpdateProbe {
    public static void main(String[] args) {
        int result = 1;
        try {
            if (android.os.Process.myUid() != 2000 || args.length != 2)
                throw new IllegalArgumentException("Expected shell UID, installed APK path and before/after mode");
            // The test DEX is needed only to load this observer; children use the real installed APK alone.
            Os.setenv("CLASSPATH", args[0], true);
            try (RandomAccessFile owner = new RandomAccessFile(KeepAliveProtocol.LOCK_PATH, "rw");
                 FileLock lock = owner.getChannel().tryLock();
                 OwnedShellProcess runner = new OwnedShellProcess()) {
                if (lock == null) throw new IOException("Keep-alive daemon already owns this emulator");
                OwnedShellProcess.Result check = runner.run(runner.token(), CellularApnCheck.class.getName(),
                        "bydcollector_apn2_check", SystemClock.elapsedRealtime() + 10_000L, "13", "default");
                // The emulator really lacks BYD autoservice; reaching that read proves startup/generation passed.
                if (check.exitCode != 1 || !check.output.contains("stage=apn2_vehicle_power")
                        || !check.output.contains("Service unavailable: autoservice"))
                    throw new AssertionError("Unexpected real APN2 result: " + check.exitCode + " " + check.output);
                System.out.println("CURRENT_GENERATION_STARTED " + check.output);
                if (args[1].equals("before")) replacement(runner, args[0]);
                else if (!args[1].equals("after")) throw new IllegalArgumentException("Expected before or after");
                System.out.println("CHILD_UPDATE_PROBE_PASS " + args[1]);
                result = 0;
            }
        } catch (Throwable error) { error.printStackTrace(System.out); }
        System.out.flush();
        System.exit(result);
    }

    private static void replacement(OwnedShellProcess runner, String source) throws Exception {
        List<Process> children = new ArrayList<>();
        List<BufferedReader> readers = new ArrayList<>();
        Path signal = Paths.get("/data/local/tmp/collector-child-update-continue");
        Files.deleteIfExists(signal);
        try {
            long deadline = SystemClock.elapsedRealtime() + 60_000L;
            String[] classes = {CellularApnCheck.class.getName(), OemWakeDiscoveryMain.class.getName()};
            String[] names = {"bydcollector_apn2_check", "bydcollector_wake_discovery"};
            for (int i = 0; i < classes.length; i++) {
                List<String> command = new ArrayList<>(Arrays.asList("/system/bin/setsid", "/system/bin/sh", "-c",
                        "echo CHILD_PID=$$; exec app_process /system/bin --nice-name=" + names[i] + " \"$@\"",
                        "child", classes[i], Long.toString(deadline)));
                if (i == 0) command.addAll(Arrays.asList("13", "default"));
                else command.add(WakeDiscoveryState.identity(WakeDiscoveryState.sources()));
                ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
                builder.environment().put("CLASSPATH", "/data/local/tmp/bydcollector_keepalive_code/runtime.apk");
                builder.environment().put("BYDCOLLECTOR_CHILD_SOURCE_APK", source);
                builder.environment().put("BYDCOLLECTOR_CHILD_REVISION", BuildConfig.RUNTIME_REVISION);
                Process child = builder.start(); children.add(child);
                BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
                readers.add(reader);
                String header = reader.readLine();
                if (header == null || !header.matches("CHILD_PID=[1-9][0-9]*")
                        || !CellularApnCheck.ownsGroup(Integer.parseInt(header.substring(10))))
                    throw new AssertionError("Missing actual child group: " + header);
                System.out.println(header);
            }
            System.out.println("READY_FOR_REAL_APK_REPLACEMENT"); System.out.flush();
            while (!Files.exists(signal) && SystemClock.elapsedRealtime() < deadline - 5_000L) Thread.sleep(100);
            if (!Files.exists(signal)) throw new IOException("Host did not complete real package replacement");
            for (int i = 0; i < children.size(); i++) {
                Process child = children.get(i);
                child.getOutputStream().write("start\n".getBytes(StandardCharsets.UTF_8));
                child.getOutputStream().flush();
                if (!child.waitFor(10, TimeUnit.SECONDS)) throw new IOException("Child did not reject replaced APK");
                StringBuilder text = new StringBuilder(); String line;
                while ((line = readers.get(i).readLine()) != null) text.append(line).append('\n');
                System.out.println("REPLACED_CHILD exit=" + child.exitValue() + " " + text);
                if (child.exitValue() != OwnedShellProcess.StaleGenerationException.EXIT_CODE)
                    throw new AssertionError("Replaced child did not reject its generation");
            }
            try {
                runner.run(runner.token(), CellularApnCheck.class.getName(), "bydcollector_apn2_check",
                        SystemClock.elapsedRealtime() + 5_000L, "13", "default");
                throw new AssertionError("Old owner launched after its APK was removed");
            } catch (OwnedShellProcess.StaleGenerationException expected) {
                System.out.println("OLD_OWNER_REJECTED " + expected);
            }
        } finally {
            for (Process child : children) {
                child.getOutputStream().close();
                if (child.isAlive()) { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS); }
            }
            for (BufferedReader reader : readers) reader.close();
            Files.deleteIfExists(signal);
        }
    }
}
