package com.bydcollector.collector.keepalive;

import android.os.FileObserver;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Independent from APN2: one analysis per firmware/request, never one per recovery attempt. */
final class OemWakeDiscovery implements AutoCloseable {
    private final Object lock = new Object();
    private final OwnedShellProcess child = new OwnedShellProcess();
    private final Thread worker = new Thread(this::loop, "byd-wake-discovery");
    private volatile boolean stopped;
    private volatile WakeDiscoveryState.Selection selected = WakeDiscoveryState.DEFAULT;
    private volatile String observedRequest = "";
    private String lastPublished = "", lastError = "";
    private JSONObject failedInMemory;
    private final FileObserver observer = new FileObserver("/data/local/tmp", FileObserver.MOVED_TO | FileObserver.DELETE) {
        @Override public void onEvent(int event, String path) {
            if (!"bydcollector_wake_discovery_request".equals(path)) return;
            synchronized (lock) {
                try {
                    if (!request().equals(observedRequest)) child.cancel();
                } catch (Exception e) { child.cancel(); logError(e, "request_change"); }
                lock.notifyAll();
            }
        }
    };

    void start() { observer.startWatching(); worker.setDaemon(true); worker.start(); }
    WakeDiscoveryState.Selection selection() { return selected; }

    private void loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        while (!stopped) {
            try { reconcile(); }
            catch (OwnedShellProcess.StaleGenerationException replaced) { logError(replaced, "apk_replaced"); }
            catch (Exception error) {
                if (stopped) return;
                selected = WakeDiscoveryState.DEFAULT;
                logError(error, "discovery_reconcile");
                try { publish("failed"); } catch (Exception e) { logError(e, "status_publish"); }
            }
            synchronized (lock) {
                if (stopped) return;
                try {
                    // Metadata only; no DEX parsing/hashing on this timer.
                    if (request().equals(observedRequest)) lock.wait(30_000L);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                catch (Exception e) { logError(e, "request_read"); try { lock.wait(30_000L); } catch (InterruptedException stop) { return; } }
            }
        }
    }

    private void reconcile() throws Exception {
        long token;
        String request;
        synchronized (lock) {
            if (stopped) return;
            request = request(); observedRequest = request; token = child.token();
        }
        if (request.isEmpty()) {
            selected = WakeDiscoveryState.DEFAULT;
            publish("pending");
            return;
        }
        String[] parts = request.split(":");
        long generation = Long.parseLong(parts[0]);
        if (!parts[1].equals("1") || !parts[2].equals("0")) return;
        List<File> sources = WakeDiscoveryState.sources();
        String identity = WakeDiscoveryState.identity(sources);
        JSONObject saved = null;
        try { saved = WakeDiscoveryState.read(); }
        catch (Exception e) { logError(e, "cache_read"); }
        if ((saved == null || saved.optString("status").equals("pending")) && failedInMemory != null) saved = failedInMemory;
        if (saved != null && saved.getString("identity").equals(identity)
                && (saved.getString("status").equals("found")
                    || (saved.getString("status").equals("failed") && saved.getLong("request") == generation))) {
            selected = saved.getString("status").equals("found")
                    ? new WakeDiscoveryState.Selection(saved.getInt("reason"), "detected") : WakeDiscoveryState.DEFAULT;
            publish(saved.getString("status"));
            return;
        }
        selected = WakeDiscoveryState.DEFAULT;
        JSONObject record = WakeDiscoveryState.record(identity, generation, "pending", 13);
        WakeDiscoveryState.write(record);
        publish("pending");
        KeepAliveDaemon.log("wake_discovery_start request=" + generation + " identity=" + identity + " uid=" + android.os.Process.myUid());
        JSONObject completed;
        JSONObject sourceHashes = null;
        try {
            OwnedShellProcess.Result result = child.run(token, OemWakeDiscoveryMain.class.getName(),
                    "bydcollector_wake_discovery", SystemClock.elapsedRealtime() + 30_000L, identity);
            for (String line : result.output.split("\\r?\\n")) if (line.startsWith("WAKE_SOURCES "))
                sourceHashes = new JSONObject(line.substring(13));
            if (result.exitCode != 0) throw new IOException("Analyzer exit=" + result.exitCode + " output=" + result.output);
            String payload = null;
            for (String line : result.output.split("\\r?\\n")) if (line.startsWith("WAKE_RESULT ")) {
                if (payload != null) throw new IOException("Multiple analyzer results");
                payload = line.substring(12);
            }
            if (payload == null) throw new IOException("Analyzer result absent: " + result.output);
            JSONObject found = new JSONObject(payload);
            if (found.getInt("reason") < 0 || found.getString("evidence").isEmpty()) throw new IOException("Invalid analyzer result");
            completed = WakeDiscoveryState.record(identity, generation, "found", found.getInt("reason"))
                    .put("evidence", found.getString("evidence")).put("hashes", found.getJSONObject("hashes"));
        } catch (OwnedShellProcess.StaleGenerationException replaced) {
            throw replaced;
        } catch (Exception e) {
            logError(e, "analyzer");
            completed = WakeDiscoveryState.record(identity, generation, "failed", 13)
                    .put("error", KeepAliveDaemon.diagnosticDetail(e, "stage=wake_analyzer"));
            if (sourceHashes != null) completed.put("hashes", sourceHashes);
        }
        synchronized (lock) {
            if (stopped || !request.equals(request()) || token != child.token()) return;
            // An update cancellation is not a firmware-analysis failure to cache for the next daemon.
            OwnedShellProcess.verifyOwnerGeneration();
            if (!identity.equals(WakeDiscoveryState.identity(sources))) { observedRequest = ""; return; }
            completed.put("started_wall_ms", record.getLong("wall_ms"));
            if (completed.getString("status").equals("failed")) failedInMemory = completed;
            try { WakeDiscoveryState.write(completed); }
            catch (Exception error) {
                failedInMemory = WakeDiscoveryState.record(identity, generation, "failed", 13)
                        .put("error", KeepAliveDaemon.diagnosticDetail(error, "stage=discovery_persist"));
                throw error;
            }
            selected = completed.getString("status").equals("found")
                    ? new WakeDiscoveryState.Selection(completed.getInt("reason"), "detected") : WakeDiscoveryState.DEFAULT;
        }
        publish(completed.getString("status"));
        KeepAliveDaemon.log("wake_discovery_finished request=" + generation + " status=" + completed.getString("status")
                + " reason=" + selected.reason + " source=" + selected.source);
    }

    private static String request() throws Exception {
        Path path = Paths.get(WakeDiscoveryState.REQUEST_PATH);
        // First-run initialization may not have published a request yet.
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return "";
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 128)
            throw new IOException("Wake discovery request missing/invalid");
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        if (!text.matches("[0-9]{1,18}:[01]:[01]")) throw new IOException("Invalid wake request: " + text);
        return text;
    }

    private void publish(String status) {
        try { publishStatus(status); }
        catch (Exception error) { if (!stopped) logError(error, "status_publish"); }
    }

    private void publishStatus(String status) throws Exception {
        if (stopped) return;
        String generation = observedRequest.matches("[0-9]{1,18}:[01]:[01]") ? observedRequest.split(":")[0] : "0";
        String firmware = WakeDiscoveryState.hex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(android.os.Build.FINGERPRINT.getBytes(StandardCharsets.UTF_8)));
        String value = generation + ":" + status + ":" + firmware + ":" + OemWakeAnalyzer.VERSION;
        if (value.equals(lastPublished)) return;
        Process process = new ProcessBuilder("/system/bin/settings", "put", "global", WakeDiscoveryState.STATUS_KEY, value)
                .redirectErrorStream(true).start();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) throw new IOException("Discovery status publication timeout");
            byte[] bytes = new byte[4096]; int n = process.getInputStream().read(bytes);
            String output = n > 0 ? new String(bytes, 0, n, StandardCharsets.UTF_8) : "";
            if (process.exitValue() != 0 || !output.trim().isEmpty())
                throw new IOException("Discovery status publication exit=" + process.exitValue() + " output=" + output);
            lastPublished = value;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private void logError(Exception error, String stage) {
        String detail = KeepAliveDaemon.diagnosticDetail(error, "stage=" + stage);
        if (!detail.equals(lastError)) { KeepAliveDaemon.log("wake_discovery_error " + detail); lastError = detail; }
    }

    @Override public void close() {
        observer.stopWatching();
        synchronized (lock) { stopped = true; child.cancel(); lock.notifyAll(); }
        worker.interrupt();
    }
}
