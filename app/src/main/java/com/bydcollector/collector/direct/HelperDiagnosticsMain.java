package com.bydcollector.collector.direct;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

//Short-lived diagnostic maintenance only; it never creates Binder services or starts collection.
public final class HelperDiagnosticsMain {
    private HelperDiagnosticsMain() { }

    public static void main(String[] args) {
        int exitCode;
        try {
            if (args.length != 1) {
                System.err.println("usage: HelperDiagnosticsMain <snapshot|clear>");
                exitCode = 2;
            } else if ("snapshot".equals(args[0])) {
                exitCode = streamSnapshot(System.out);
            } else if ("clear".equals(args[0])) {
                exitCode = clear(System.out);
            } else {
                System.err.println("unsupported helper diagnostic operation");
                exitCode = 2;
            }
        } catch (Throwable error) {
            System.err.println("helper diagnostic operation failed: " + error.getClass().getSimpleName());
            exitCode = 1;
        }
        System.out.flush();
        System.err.flush();
        System.exit(exitCode);
    }

    static int streamSnapshot(OutputStream output) throws Exception {
        return streamSnapshot(defaultStore(), output);
    }

    static int streamSnapshot(HelperDiagnosticFileStore store, OutputStream output) throws Exception {
        HelperDiagnosticFileStore.SnapshotArchive archive;
        try {
            archive = store.createSnapshotArchive();
        } catch (Throwable error) {
            writeFailureArchive(output, error);
            return 0;
        }
        try {
            FileInputStream input = new FileInputStream(archive.file);
            try {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            } finally {
                input.close();
            }
            return 0;
        } finally {
            archive.close();
        }
    }

    static int clear(OutputStream output) throws Exception {
        return clear(defaultStore(), output);
    }

    static int clear(HelperDiagnosticFileStore store, OutputStream output) throws Exception {
        try {
            HelperDiagnosticFileStore.ClearResult result = store.clearDiagnostics();
            output.write((result.toJson().toString() + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            return result.isComplete() ? 0 : 1;
        } catch (Throwable error) {
            JSONObject result = new JSONObject();
            result.put("schema_version", 1);
            result.put("operation", "clear");
            result.put("completed_wall_ms", System.currentTimeMillis());
            result.put("overall_status", "partial");
            result.put("legacy_pre_jvm_consistency", "best_effort_unlocked_writer");
            result.put("operation_error", error.getClass().getSimpleName());
            result.put("files", new JSONArray());
            result.put("preserved", new JSONArray()
                .put(HelperDiagnosticFileStore.SNAPSHOT_NAME)
                .put(HelperDiagnosticFileStore.LOCK_NAME)
                .put("raw_telemetry_spool"));
            output.write((result.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            return 1;
        }
    }

    static void writeFailureArchive(OutputStream output, Throwable error) throws Exception {
        ZipOutputStream zip = new ZipOutputStream(output);
        JSONObject manifest = new JSONObject();
        manifest.put("schema_version", 1);
        manifest.put("operation", "snapshot");
        manifest.put("created_wall_ms", System.currentTimeMillis());
        manifest.put("overall_status", "partial");
        manifest.put("coherent_scope", "unavailable");
        manifest.put("legacy_pre_jvm_consistency", "best_effort_unlocked_writer");
        manifest.put("operation_error", error == null ? "unknown" : error.getClass().getSimpleName());
        manifest.put("files", new JSONArray());
        ZipEntry entry = new ZipEntry(HelperDiagnosticFileStore.MANIFEST_ENTRY);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.finish();
        zip.flush();
    }

    private static HelperDiagnosticFileStore defaultStore() {
        return new HelperDiagnosticFileStore(new File(HelperDiagnosticFileStore.DIRECTORY_PATH));
    }
}
