package com.bydcollector.collector.direct;

import android.os.Binder;
import android.content.Context;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

//runs as shell app_process so the app can read autoservice through a narrow binder bridge
public final class CollectorHelperDaemon {
    private CollectorHelperDaemon() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("ERR: usage: CollectorHelperDaemon <appUid> <apkPath>");
            System.exit(2);
            return;
        }
        final int appUid = Integer.parseInt(args[0]);
        final String apkPath = args[1];
        OwnerLock ownerLock = acquireSingleOwnerLock();
        if (ownerLock == null) {
            System.out.println("ALREADY_RUNNING");
            System.exit(0);
            return;
        }

        final HelperIdentity helperIdentity = HelperIdentity.create();
        prepareMainLooper();
        final MainCatalog mainCatalog = loadMainCatalog();
        final Set<Address> whitelist = loadWhitelist(apkPath, mainCatalog);
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getMethod("getService", String.class);
        final IBinder autoservice = (IBinder) getService.invoke(null, "autoservice");
        if (autoservice == null) {
            System.err.println("ERR: autoservice not found");
            System.exit(3);
            return;
        }
        String descriptor = autoservice.getInterfaceDescriptor();
        final String autoserviceDescriptor = descriptor == null ? "" : descriptor;
        final NativeArrayReader nativeReader = NativeArrayReader.create();
        final Object readLock = new Object();
        final EvidenceStore evidence = new EvidenceStore(
            new File(CollectorHelperProtocol.OFFCAR_ROOT),
            mainCatalog,
            helperIdentity,
            128L * 1024L * 1024L,
            1L * 1024L * 1024L,
            127L * 1024L * 1024L
        );
        final OffcarController offcar = new OffcarController(
            mainCatalog,
            autoservice,
            autoserviceDescriptor,
            nativeReader,
            readLock,
            evidence
        );
        Binder helperBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                //keeps the shell-launched helper private to the collector app that requested it
                if (Binder.getCallingUid() != appUid) {
                    return false;
                }
                data.enforceInterface(CollectorHelperProtocol.DESCRIPTOR);
                if (code == CollectorHelperProtocol.TX_PING) {
                    if (reply != null) {
                        reply.writeInt(CollectorHelperProtocol.STATUS_OK);
                        reply.writeInt(CollectorHelperProtocol.PROTOCOL_VERSION);
                        reply.writeInt(nativeReader.isAvailable() ? 1 : 0);
                        reply.writeString(nativeReader.unavailableReason());
                    }
                    return true;
                }
                if (code == CollectorHelperProtocol.TX_MAIN_HEARTBEAT) {
                    offcar.mainHeartbeat();
                    if (reply != null) reply.writeInt(CollectorHelperProtocol.STATUS_OK);
                    return true;
                }
                if (code == CollectorHelperProtocol.TX_OFFCAR_DISARM) {
                    offcar.disarm();
                    if (reply != null) reply.writeInt(CollectorHelperProtocol.STATUS_OK);
                    return true;
                }
                if (code == CollectorHelperProtocol.TX_READ) {
                    int tx = data.readInt();
                    int dev = data.readInt();
                    int fid = data.readInt();
                    Address address = new Address(tx, dev, fid);
                    ReadValue result;
                    if (!isAllowedTx(tx)) {
                        result = ReadValue.error(CollectorHelperProtocol.STATUS_INVALID_REQUEST, "unsupported read transaction");
                    } else if (!whitelist.contains(address)) {
                        result = ReadValue.error(CollectorHelperProtocol.STATUS_NOT_WHITELISTED, "address is not whitelisted");
                    } else {
                        synchronized (readLock) {
                            result = scalarRead(autoservice, autoserviceDescriptor, address);
                        }
                    }
                    if (reply != null) {
                        int status = result.status == CollectorHelperProtocol.STATUS_OK && result.raw == null
                            ? CollectorHelperProtocol.STATUS_READ_ERROR
                            : result.status;
                        reply.writeInt(status);
                        reply.writeInt(result.raw == null ? 0 : result.raw);
                    }
                    return true;
                }
                if (code == CollectorHelperProtocol.TX_READ_BATCH) {
                    BatchResult result;
                    try {
                        List<Address> rows = readBatchRequest(data);
                        String validationError = validateRows(rows, whitelist);
                        if (validationError != null) {
                            result = BatchResult.rejected(rows.size(), validationError);
                        } else {
                            synchronized (readLock) {
                                result = BatchEngine.run(
                                    rows,
                                    address -> scalarRead(autoservice, autoserviceDescriptor, address),
                                    nativeReader
                                );
                            }
                        }
                    } catch (Throwable error) {
                        result = BatchResult.rejected(0, describe(error));
                    }
                    if (reply != null) writeBatchReply(reply, result);
                    return true;
                }
                return false;
            }
        };
        helperBinder.attachInterface(null, CollectorHelperProtocol.DESCRIPTOR);
        try {
            Method addService = serviceManager.getMethod("addService", String.class, IBinder.class);
            addService.invoke(null, CollectorHelperProtocol.SERVICE_NAME, helperBinder);
            System.out.println(
                "READY pid=" + Process.myPid() +
                    " protocol=" + CollectorHelperProtocol.PROTOCOL_VERSION +
                    " whitelist=" + whitelist.size() +
                    " native=" + nativeReader.isAvailable() +
                    (nativeReader.isAvailable() ? "" : " native_error=" + nativeReader.unavailableReason())
            );
            System.out.flush();
            evidence.appendLifecycle("helper_started", 0L, null);
            offcar.start();
            Looper.loop();
        } finally {
            offcar.stop();
            ownerLock.close();
        }
    }

    private static ReadValue scalarRead(
            IBinder autoservice,
            String descriptor,
            Address address
    ) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeInt(address.dev);
            data.writeInt(address.fid);
            if (!autoservice.transact(address.tx, data, reply, 0)) {
                return ReadValue.error(CollectorHelperProtocol.STATUS_READ_ERROR, "autoservice transact returned false");
            }
            int available = reply.dataAvail();
            int status = available >= 4 ? reply.readInt() : -999;
            int raw = available >= 8 ? reply.readInt() : 0;
            return new ReadValue(status, available >= 8 ? raw : null, available >= 8 ? null : "autoservice reply missing raw value");
        } catch (Throwable error) {
            return ReadValue.error(CollectorHelperProtocol.STATUS_READ_ERROR, describe(error));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static List<Address> readBatchRequest(Parcel data) {
        int count = data.readInt();
        if (count < 1 || count > CollectorHelperProtocol.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("invalid batch size: " + count);
        }
        List<Address> rows = new ArrayList<Address>(count);
        for (int index = 0; index < count; index++) {
            rows.add(new Address(data.readInt(), data.readInt(), data.readInt()));
        }
        return rows;
    }

    static String validateRows(List<Address> rows, Set<Address> whitelist) {
        for (Address row : rows) {
            if (!isAllowedTx(row.tx)) return "unsupported read transaction: " + row.tx;
            if (!whitelist.contains(row)) return "address is not whitelisted: " + row;
        }
        return null;
    }

    private static boolean isAllowedTx(int tx) {
        return tx == CollectorHelperProtocol.AUTO_TX_INT || tx == CollectorHelperProtocol.AUTO_TX_FLOAT;
    }

    private static void writeBatchReply(Parcel reply, BatchResult result) {
        reply.writeInt(result.batchStatus);
        reply.writeInt(result.mode);
        reply.writeInt(result.nativeAvailable ? 1 : 0);
        reply.writeInt(result.nativeGroupCount);
        reply.writeInt(result.fallbackGroupCount);
        reply.writeInt(result.fallbackReadCount);
        reply.writeInt(result.groupFailureCount);
        reply.writeLong(result.elapsedMs);
        reply.writeInt(result.values.length);
        reply.writeString(result.error);
        for (ReadValue value : result.values) {
            reply.writeInt(value.status);
            reply.writeInt(value.raw == null ? 0 : 1);
            if (value.raw != null) reply.writeInt(value.raw);
        }
    }

    static Set<Address> loadWhitelist(String apkPath) throws Exception {
        return loadWhitelist(apkPath, loadMainCatalog());
    }

    private static Set<Address> loadWhitelist(String apkPath, MainCatalog mainCatalog) throws Exception {
        Set<Address> whitelist = new HashSet<Address>();
        whitelist.addAll(mainCatalog.rows);
        loadDebugWhitelist(apkPath, whitelist);
        if (whitelist.isEmpty()) throw new IllegalStateException("empty telemetry whitelist");
        return whitelist;
    }

    static MainCatalog loadMainCatalog() throws Exception {
        Class<?> registryClass = Class.forName("com.bydcollector.collector.data.direct.DirectFidRegistry");
        Object registry = registryClass.getField("INSTANCE").get(null);
        List<?> entries = (List<?>) registryClass.getMethod("getEntries").invoke(registry);
        String version = (String) registryClass.getField("CATALOG_VERSION").get(null);
        List<Address> rows = new ArrayList<Address>(entries.size());
        StringBuilder tsv = new StringBuilder(
            "position\tkey\ttx\tdev\tfid\tdecoder\tscale\tgroup_name\tfeature_names\tclassification\tprod_category\tsource\tsource_id\tnote\n"
        );
        for (int position = 0; position < entries.size(); position++) {
            Object entry = entries.get(position);
            Class<?> entryClass = entry.getClass();
            int tx = (Integer) entryClass.getMethod("getTx").invoke(entry);
            int dev = (Integer) entryClass.getMethod("getDev").invoke(entry);
            int fid = (Integer) entryClass.getMethod("getFid").invoke(entry);
            if (!isAllowedTx(tx)) throw new IllegalArgumentException("unsupported main whitelist tx: " + tx);
            rows.add(new Address(tx, dev, fid));
            appendTsv(tsv, position);
            appendTsv(tsv, entryClass.getMethod("getKey").invoke(entry));
            appendTsv(tsv, tx);
            appendTsv(tsv, dev);
            appendTsv(tsv, fid);
            appendTsv(tsv, entryClass.getMethod("getDecoder").invoke(entry));
            appendTsv(tsv, entryClass.getMethod("getScale").invoke(entry));
            appendTsv(tsv, entryClass.getMethod("getGroupName").invoke(entry));
            appendTsv(tsv, entryClass.getMethod("getFeatureNames").invoke(entry));
            appendTsv(tsv, entryClass.getMethod("getClassification").invoke(entry));
            appendTsv(tsv, entryClass.getMethod("getProdCategory").invoke(entry));
            appendTsv(tsv, entryClass.getMethod("getSource").invoke(entry));
            appendTsv(tsv, entryClass.getMethod("getSourceId").invoke(entry));
            appendTsv(tsv, entryClass.getMethod("getNote").invoke(entry));
            tsv.setLength(tsv.length() - 1);
            tsv.append('\n');
        }
        byte[] bytes = tsv.toString().getBytes(StandardCharsets.UTF_8);
        return new MainCatalog(version, rows, bytes, sha256(bytes));
    }

    private static void loadDebugWhitelist(String apkPath, Set<Address> whitelist) throws Exception {
        try (ZipFile apk = new ZipFile(apkPath)) {
            String[] assetNames = {
                "assets/direct_debug_round_robin_parameters_1.csv",
                "assets/direct_debug_round_robin_parameters_2.csv",
                "assets/direct_debug_round_robin_parameters_3.csv"
            };
            for (String assetName : assetNames) {
                ZipEntry asset = apk.getEntry(assetName);
                if (asset == null) throw new IllegalStateException("debug whitelist asset missing: " + assetName);
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(apk.getInputStream(asset), StandardCharsets.UTF_8)
                )) {
                List<String> header = splitCsvLine(reader.readLine());
                int devIndex = header.indexOf("dev");
                int fidIndex = header.indexOf("fid");
                int txIndex = header.indexOf("tx");
                if (devIndex < 0 || fidIndex < 0 || txIndex < 0) {
                    throw new IllegalArgumentException("unexpected debug whitelist header");
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    List<String> columns = splitCsvLine(line);
                    int tx = Integer.parseInt(columns.get(txIndex));
                    int dev = Integer.parseInt(columns.get(devIndex));
                    int fid = Integer.parseInt(columns.get(fidIndex));
                    if (!isAllowedTx(tx)) throw new IllegalArgumentException("unsupported debug whitelist tx: " + tx);
                    whitelist.add(new Address(tx, dev, fid));
                }
                }
            }
        }
    }

    private static void appendTsv(StringBuilder target, Object value) {
        String text = String.valueOf(value)
            .replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
        target.append(text).append('\t');
    }

    static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest(bytes));
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format("%02x", value & 0xff));
        return text.toString();
    }

    private static List<String> splitCsvLine(String line) {
        if (line == null) throw new IllegalArgumentException("missing csv header");
        List<String> values = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (inQuotes && ch == '"' && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                current.append('"');
                index++;
            } else if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (ch == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static String describe(Throwable error) {
        Throwable current = error;
        while (current instanceof InvocationTargetException && ((InvocationTargetException) current).getTargetException() != null) {
            current = ((InvocationTargetException) current).getTargetException();
        }
        return current.getClass().getSimpleName() + ": " + (current.getMessage() == null ? "no message" : current.getMessage());
    }

    private static void prepareMainLooper() {
        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignored) {
            // app_process may already have the main looper prepared.
        }
    }

    enum OffcarEvent {
        NONE,
        ARMED,
        RECOVERED,
        DISARMED
    }

    static final class PollPermit {
        final long generation;
        final boolean firstFallback;

        PollPermit(long generation, boolean firstFallback) {
            this.generation = generation;
            this.firstFallback = firstFallback;
        }
    }

    static final class SampleGate {
        final long generation;
        final long heartbeatAgeMs;

        SampleGate(long generation, long heartbeatAgeMs) {
            this.generation = generation;
            this.heartbeatAgeMs = heartbeatAgeMs;
        }
    }

    static final class HelperIdentity {
        private static final String UNKNOWN_BOOT_ID = "unknown";
        final String bootId;
        final String helperRunId;
        final int pid;
        final long startEpochMs;
        final long startElapsedMs;

        HelperIdentity(String bootId, String helperRunId, int pid, long startEpochMs, long startElapsedMs) {
            this.bootId = sanitizeBootId(bootId);
            this.helperRunId = helperRunId;
            this.pid = pid;
            this.startEpochMs = startEpochMs;
            this.startElapsedMs = startElapsedMs;
        }

        static HelperIdentity create() {
            int pid = Process.myPid();
            long startEpochMs = System.currentTimeMillis();
            long startElapsedMs = SystemClock.elapsedRealtime();
            return new HelperIdentity(
                readBootId(),
                "pid-" + pid + "-epoch-" + startEpochMs + "-elapsed-" + startElapsedMs,
                pid,
                startEpochMs,
                startElapsedMs
            );
        }

        private static String readBootId() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream("/proc/sys/kernel/random/boot_id"),
                StandardCharsets.UTF_8
            ))) {
                return sanitizeBootId(reader.readLine());
            } catch (Throwable ignored) {
                return UNKNOWN_BOOT_ID;
            }
        }

        static String sanitizeBootId(String value) {
            if (value == null) return UNKNOWN_BOOT_ID;
            String trimmed = value.trim();
            if (trimmed.isEmpty() || trimmed.length() > 128) return UNKNOWN_BOOT_ID;
            for (int index = 0; index < trimmed.length(); index++) {
                char ch = trimmed.charAt(index);
                if (!(ch >= 'a' && ch <= 'z') && !(ch >= 'A' && ch <= 'Z') &&
                    !(ch >= '0' && ch <= '9') && ch != '-' && ch != '_' && ch != '.') {
                    return UNKNOWN_BOOT_ID;
                }
            }
            return trimmed;
        }
    }

    interface SampleAppender {
        boolean append();
    }

    static final class OffcarState {
        private boolean armed;
        private boolean fallbackActive;
        private long generation;
        private long lastHeartbeatElapsedMs;
        private long nextPollElapsedMs;

        synchronized OffcarEvent heartbeat(long nowElapsedMs) {
            OffcarEvent event = !armed
                ? OffcarEvent.ARMED
                : fallbackActive ? OffcarEvent.RECOVERED : OffcarEvent.NONE;
            generation++;
            armed = true;
            fallbackActive = false;
            lastHeartbeatElapsedMs = nowElapsedMs;
            nextPollElapsedMs = nowElapsedMs + CollectorHelperProtocol.OFFCAR_STALE_MS;
            notifyAll();
            return event;
        }

        synchronized OffcarEvent disarm() {
            boolean wasArmed = armed;
            generation++;
            armed = false;
            fallbackActive = false;
            notifyAll();
            return wasArmed ? OffcarEvent.DISARMED : OffcarEvent.NONE;
        }

        synchronized PollPermit beginPoll(long nowElapsedMs) {
            long heartbeatAgeMs = Math.max(0L, nowElapsedMs - lastHeartbeatElapsedMs);
            if (!armed || heartbeatAgeMs < CollectorHelperProtocol.OFFCAR_STALE_MS || nowElapsedMs < nextPollElapsedMs) {
                return null;
            }
            boolean firstFallback = !fallbackActive;
            fallbackActive = true;
            nextPollElapsedMs = nowElapsedMs + CollectorHelperProtocol.OFFCAR_POLL_MS;
            return new PollPermit(generation, firstFallback);
        }

        synchronized SampleGate completePoll(PollPermit permit, long nowElapsedMs) {
            long heartbeatAgeMs = Math.max(0L, nowElapsedMs - lastHeartbeatElapsedMs);
            if (!armed || generation != permit.generation || heartbeatAgeMs < CollectorHelperProtocol.OFFCAR_STALE_MS) {
                return null;
            }
            return new SampleGate(generation, heartbeatAgeMs);
        }

        synchronized boolean commitPoll(PollPermit permit, long nowElapsedMs, SampleAppender appender) {
            if (completePoll(permit, nowElapsedMs) == null) return false;
            return appender.append();
        }

        synchronized long nextDelayMs(long nowElapsedMs) {
            if (!armed) return Long.MAX_VALUE;
            long staleAt = lastHeartbeatElapsedMs + CollectorHelperProtocol.OFFCAR_STALE_MS;
            if (nowElapsedMs < staleAt) return staleAt - nowElapsedMs;
            if (nowElapsedMs < nextPollElapsedMs) return nextPollElapsedMs - nowElapsedMs;
            return 0L;
        }
    }

    static final class MainCatalog {
        final String version;
        final List<Address> rows;
        final byte[] tsvBytes;
        final String sha256;

        MainCatalog(String version, List<Address> rows, byte[] tsvBytes, String sha256) {
            this.version = version;
            this.rows = rows;
            this.tsvBytes = tsvBytes;
            this.sha256 = sha256;
        }
    }

    static final class EvidenceStore {
        private static final long SYNC_INTERVAL_MS = 30_000L;
        private final File root;
        private final MainCatalog catalog;
        private final String identityJson;
        private final long totalCapBytes;
        private final long reserveCapBytes;
        private final long telemetryCapBytes;
        private final File catalogFile;
        private final File telemetryFile;
        private final File lifecycleFile;
        private long lastTelemetrySyncElapsedMs = -SYNC_INTERVAL_MS;
        private boolean stopped;
        private String stopReason;

        EvidenceStore(
            File root,
            MainCatalog catalog,
            HelperIdentity helperIdentity,
            long totalCapBytes,
            long reserveCapBytes,
            long telemetryCapBytes
        ) {
            this.root = root;
            this.catalog = catalog;
            this.identityJson = new StringBuilder()
                .append("\"boot_id\":\"").append(jsonEscape(helperIdentity.bootId)).append("\"")
                .append(",\"helper_run_id\":\"").append(jsonEscape(helperIdentity.helperRunId)).append("\"")
                .append(",\"helper_pid\":").append(helperIdentity.pid)
                .append(",\"helper_start_epoch_ms\":").append(helperIdentity.startEpochMs)
                .append(",\"helper_start_elapsed_ms\":").append(helperIdentity.startElapsedMs)
                .toString();
            this.totalCapBytes = totalCapBytes;
            this.reserveCapBytes = reserveCapBytes;
            this.telemetryCapBytes = telemetryCapBytes;
            this.catalogFile = new File(root, "main_catalog_" + catalog.sha256 + ".tsv");
            this.telemetryFile = new File(root, "telemetry_samples.jsonl");
            this.lifecycleFile = new File(root, "helper_lifecycle.jsonl");
            initialize();
        }

        private void initialize() {
            try {
                if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("cannot create evidence root");
                if (!catalogFile.exists()) {
                    if (reservedBytes() + catalog.tsvBytes.length > reserveCapBytes || rootBytes() + catalog.tsvBytes.length > totalCapBytes) {
                        stop("cap");
                        return;
                    }
                    try (RandomAccessFile file = new RandomAccessFile(catalogFile, "rwd")) {
                        if (file.length() == 0L) file.write(catalog.tsvBytes);
                    }
                }
                if (catalogFile.length() != catalog.tsvBytes.length || !catalog.sha256.equals(sha256(catalogFile))) {
                    stop("catalog_mismatch");
                    return;
                }
                if (telemetryFile.length() >= telemetryCapBytes || rootBytes() >= totalCapBytes) stop("cap");
            } catch (Throwable error) {
                stop("io:" + describe(error));
            }
        }

        synchronized boolean canCollect() {
            return !stopped;
        }

        synchronized String stopReason() {
            return stopReason;
        }

        synchronized boolean appendSample(String json, long elapsedMs) {
            if (stopped) return false;
            try {
                if (json.length() < 2 || json.charAt(0) != '{' || json.charAt(json.length() - 1) != '}') {
                    throw new IllegalArgumentException("sample must be a JSON object");
                }
                String evidenceJson = "{" + identityJson + (json.length() == 2 ? "" : ",") + json.substring(1);
                byte[] line = (evidenceJson + "\n").getBytes(StandardCharsets.UTF_8);
                long currentLength = telemetryFile.length();
                boolean needsSeparator = needsSeparator(telemetryFile, currentLength);
                long writeLength = line.length + (needsSeparator ? 1L : 0L);
                if (currentLength + writeLength > telemetryCapBytes || rootBytes() + writeLength > totalCapBytes) {
                    stop("cap");
                    return false;
                }
                try (RandomAccessFile file = new RandomAccessFile(telemetryFile, "rw")) {
                    file.seek(currentLength);
                    if (needsSeparator) file.write('\n');
                    file.write(line);
                    if (elapsedMs - lastTelemetrySyncElapsedMs >= SYNC_INTERVAL_MS) {
                        file.getFD().sync();
                        lastTelemetrySyncElapsedMs = elapsedMs;
                    }
                }
                return true;
            } catch (Throwable error) {
                stop("io:" + describe(error));
                return false;
            }
        }

        synchronized void appendLifecycle(String event, long generation, String detail) {
            appendLifecycle(event, generation, detail, System.currentTimeMillis(), SystemClock.elapsedRealtime());
        }

        synchronized void appendLifecycle(
            String event,
            long generation,
            String detail,
            long epochMs,
            long elapsedMs
        ) {
            try {
                StringBuilder json = new StringBuilder()
                    .append("{\"event\":\"").append(jsonEscape(event)).append("\"")
                    .append(',').append(identityJson)
                    .append(",\"protocol\":").append(CollectorHelperProtocol.PROTOCOL_VERSION)
                    .append(",\"generation\":").append(generation)
                    .append(",\"catalog_sha256\":\"").append(catalog.sha256).append("\"")
                    .append(",\"catalog_version\":\"").append(jsonEscape(catalog.version)).append("\"")
                    .append(",\"epoch_ms\":").append(epochMs)
                    .append(",\"elapsed_ms\":").append(elapsedMs);
                if (detail != null) json.append(",\"detail\":\"").append(jsonEscape(detail)).append("\"");
                byte[] line = json.append("}\n").toString().getBytes(StandardCharsets.UTF_8);
                long currentLength = lifecycleFile.length();
                boolean needsSeparator = needsSeparator(lifecycleFile, currentLength);
                long writeLength = line.length + (needsSeparator ? 1L : 0L);
                if (reservedBytes() + writeLength > reserveCapBytes || rootBytes() + writeLength > totalCapBytes) return;
                try (RandomAccessFile file = new RandomAccessFile(lifecycleFile, "rw")) {
                    file.seek(currentLength);
                    if (needsSeparator) file.write('\n');
                    file.write(line);
                    file.getFD().sync();
                }
            } catch (Throwable ignored) {
                // Evidence failure must never take down the Binder helper.
            }
        }

        private boolean needsSeparator(File file, long length) throws Exception {
            if (length == 0L) return false;
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                input.seek(length - 1L);
                return input.read() != '\n';
            }
        }

        private long reservedBytes() {
            long total = lifecycleFile.length();
            File[] files = root.listFiles();
            if (files == null) return total;
            for (File file : files) {
                if (file.isFile() && file.getName().startsWith("main_catalog_") && file.getName().endsWith(".tsv")) {
                    total += file.length();
                }
            }
            return total;
        }

        private long rootBytes() {
            long total = 0L;
            File[] files = root.listFiles();
            if (files == null) return total;
            for (File file : files) if (file.isFile()) total += file.length();
            return total;
        }

        private void stop(String reason) {
            stopped = true;
            stopReason = reason;
        }
    }

    static final class OffcarController implements Runnable {
        private final MainCatalog catalog;
        private final IBinder autoservice;
        private final String autoserviceDescriptor;
        private final NativeReader nativeReader;
        private final Object readLock;
        private final EvidenceStore evidence;
        private final OffcarState state = new OffcarState();
        private volatile boolean running;
        private Thread thread;

        OffcarController(
            MainCatalog catalog,
            IBinder autoservice,
            String autoserviceDescriptor,
            NativeReader nativeReader,
            Object readLock,
            EvidenceStore evidence
        ) {
            this.catalog = catalog;
            this.autoservice = autoservice;
            this.autoserviceDescriptor = autoserviceDescriptor;
            this.nativeReader = nativeReader;
            this.readLock = readLock;
            this.evidence = evidence;
        }

        void start() {
            if (!evidence.canCollect()) {
                evidence.appendLifecycle("evidence_stopped", 0L, evidence.stopReason());
                return;
            }
            running = true;
            thread = new Thread(this, "BYDCollectorOffcar");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            synchronized (state) {
                state.notifyAll();
            }
        }

        void mainHeartbeat() {
            OffcarEvent event = state.heartbeat(SystemClock.elapsedRealtime());
            if (event == OffcarEvent.ARMED) evidence.appendLifecycle("offcar_armed", currentGeneration(), null);
            if (event == OffcarEvent.RECOVERED) evidence.appendLifecycle("main_heartbeat_recovered", currentGeneration(), null);
        }

        void disarm() {
            OffcarEvent event = state.disarm();
            if (event == OffcarEvent.DISARMED) evidence.appendLifecycle("offcar_disarmed", currentGeneration(), null);
        }

        private long currentGeneration() {
            synchronized (state) {
                return state.generation;
            }
        }

        @Override public void run() {
            while (running && evidence.canCollect()) {
                PollPermit permit = awaitPermit();
                if (permit == null) continue;
                if (permit.firstFallback) evidence.appendLifecycle("offcar_fallback_started", permit.generation, null);
                BatchResult result;
                try {
                    synchronized (readLock) {
                        result = BatchEngine.run(
                            catalog.rows,
                            address -> scalarRead(autoservice, autoserviceDescriptor, address),
                            nativeReader
                        );
                    }
                } catch (Throwable error) {
                    evidence.appendLifecycle("offcar_poll_error", permit.generation, describe(error));
                    continue;
                }
                long elapsedMs = SystemClock.elapsedRealtime();
                SampleGate gate = state.completePoll(permit, elapsedMs);
                if (gate == null) continue;
                String sample = sampleJson(catalog, gate, result, System.currentTimeMillis(), elapsedMs);
                boolean appended = state.commitPoll(permit, elapsedMs, () -> evidence.appendSample(sample, elapsedMs));
                if (!appended && !evidence.canCollect()) {
                    evidence.appendLifecycle("evidence_stopped", gate.generation, evidence.stopReason());
                    return;
                }
            }
        }

        private PollPermit awaitPermit() {
            synchronized (state) {
                while (running) {
                    long nowElapsedMs = SystemClock.elapsedRealtime();
                    PollPermit permit = state.beginPoll(nowElapsedMs);
                    if (permit != null) return permit;
                    long delayMs = state.nextDelayMs(nowElapsedMs);
                    try {
                        if (delayMs == Long.MAX_VALUE) state.wait();
                        else state.wait(Math.max(1L, delayMs));
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
            return null;
        }
    }

    static String sampleJson(
        MainCatalog catalog,
        SampleGate gate,
        BatchResult result,
        long epochMs,
        long elapsedMs
    ) {
        int statusOk = 0;
        int rawPresent = 0;
        for (ReadValue value : result.values) {
            if (value != null && value.status == CollectorHelperProtocol.STATUS_OK) statusOk++;
            if (value != null && value.raw != null) rawPresent++;
        }
        StringBuilder json = new StringBuilder(4096)
            .append("{\"schema\":1")
            .append(",\"generation\":").append(gate.generation)
            .append(",\"catalog_sha256\":\"").append(catalog.sha256).append("\"")
            .append(",\"catalog_version\":\"").append(jsonEscape(catalog.version)).append("\"")
            .append(",\"epoch_ms\":").append(epochMs)
            .append(",\"elapsed_ms\":").append(elapsedMs)
            .append(",\"heartbeat_age_ms\":").append(gate.heartbeatAgeMs)
            .append(",\"position_count\":").append(result.values.length)
            .append(",\"poll\":{")
            .append("\"batch_status\":").append(result.batchStatus)
            .append(",\"mode\":\"").append(modeName(result.mode)).append("\"")
            .append(",\"native_available\":").append(result.nativeAvailable)
            .append(",\"native_group_count\":").append(result.nativeGroupCount)
            .append(",\"fallback_group_count\":").append(result.fallbackGroupCount)
            .append(",\"fallback_read_count\":").append(result.fallbackReadCount)
            .append(",\"group_failure_count\":").append(result.groupFailureCount)
            .append(",\"elapsed_ms\":").append(result.elapsedMs);
        if (result.error != null) json.append(",\"error\":\"").append(jsonEscape(result.error)).append("\"");
        json.append("},\"quality\":{")
            .append("\"status_ok\":").append(statusOk)
            .append(",\"status_error\":").append(result.values.length - statusOk)
            .append(",\"raw_present\":").append(rawPresent)
            .append(",\"raw_missing\":").append(result.values.length - rawPresent)
            .append("},\"raw_values\":[");
        for (int index = 0; index < result.values.length; index++) {
            if (index > 0) json.append(',');
            ReadValue value = result.values[index];
            if (value == null || value.raw == null) json.append("null");
            else json.append(value.raw);
        }
        json.append("],\"status_values\":[");
        for (int index = 0; index < result.values.length; index++) {
            if (index > 0) json.append(',');
            ReadValue value = result.values[index];
            json.append(value == null ? CollectorHelperProtocol.STATUS_READ_ERROR : value.status);
        }
        return json.append("]}").toString();
    }

    private static String modeName(int mode) {
        if (mode == CollectorHelperProtocol.MODE_NATIVE) return "native";
        if (mode == CollectorHelperProtocol.MODE_NATIVE_WITH_FALLBACK) return "native_with_fallback";
        if (mode == CollectorHelperProtocol.MODE_SCALAR_FALLBACK) return "scalar_fallback";
        return "rejected";
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '"' || ch == '\\') escaped.append('\\').append(ch);
            else if (ch == '\b') escaped.append("\\b");
            else if (ch == '\f') escaped.append("\\f");
            else if (ch == '\n') escaped.append("\\n");
            else if (ch == '\r') escaped.append("\\r");
            else if (ch == '\t') escaped.append("\\t");
            else if (ch < 0x20) escaped.append(String.format("\\u%04x", (int) ch));
            else escaped.append(ch);
        }
        return escaped.toString();
    }

    interface ScalarReader {
        ReadValue read(Address address);
    }

    interface NativeReader {
        boolean isAvailable();
        String unavailableReason();
        int[] readInts(int dev, int[] fids) throws Throwable;
        float[] readFloats(int dev, int[] fids) throws Throwable;
    }

    static final class BatchEngine {
        static BatchResult run(List<Address> rows, ScalarReader scalarReader, NativeReader nativeReader) {
            long startedAt = System.nanoTime();
            ReadValue[] values = new ReadValue[rows.size()];
            LinkedHashMap<GroupKey, List<IndexedAddress>> groups = new LinkedHashMap<GroupKey, List<IndexedAddress>>();
            for (int index = 0; index < rows.size(); index++) {
                Address row = rows.get(index);
                GroupKey key = new GroupKey(row.tx, row.dev);
                List<IndexedAddress> group = groups.get(key);
                if (group == null) {
                    group = new ArrayList<IndexedAddress>();
                    groups.put(key, group);
                }
                group.add(new IndexedAddress(index, row));
            }

            int nativeGroups = 0;
            int fallbackGroups = 0;
            int fallbackReads = 0;
            int groupFailures = 0;
            String firstGroupError = null;
            for (Map.Entry<GroupKey, List<IndexedAddress>> entry : groups.entrySet()) {
                List<IndexedAddress> group = entry.getValue();
                boolean useFallback = !nativeReader.isAvailable();
                if (!useFallback) {
                    try {
                        readNativeGroup(entry.getKey(), group, values, nativeReader);
                        nativeGroups++;
                    } catch (Throwable error) {
                        useFallback = true;
                        groupFailures++;
                        if (firstGroupError == null) firstGroupError = describe(error);
                    }
                }
                if (useFallback) {
                    fallbackGroups++;
                    fallbackReads += group.size();
                    for (IndexedAddress indexed : group) {
                        ReadValue value = scalarReader.read(indexed.address);
                        values[indexed.index] = value;
                        if (firstGroupError == null && value.error != null) firstGroupError = value.error;
                    }
                }
            }

            int mode = !nativeReader.isAvailable()
                ? CollectorHelperProtocol.MODE_SCALAR_FALLBACK
                : fallbackGroups == 0
                    ? CollectorHelperProtocol.MODE_NATIVE
                    : CollectorHelperProtocol.MODE_NATIVE_WITH_FALLBACK;
            return new BatchResult(
                CollectorHelperProtocol.STATUS_OK,
                mode,
                nativeReader.isAvailable(),
                nativeGroups,
                fallbackGroups,
                fallbackReads,
                groupFailures,
                (System.nanoTime() - startedAt) / 1_000_000L,
                values,
                nativeReader.isAvailable() ? firstGroupError : nativeReader.unavailableReason()
            );
        }

        private static void readNativeGroup(
            GroupKey key,
            List<IndexedAddress> group,
            ReadValue[] values,
            NativeReader nativeReader
        ) throws Throwable {
            int[] fids = new int[group.size()];
            for (int index = 0; index < group.size(); index++) fids[index] = group.get(index).address.fid;
            if (key.tx == CollectorHelperProtocol.AUTO_TX_INT) {
                int[] raws = nativeReader.readInts(key.dev, fids);
                if (raws == null || raws.length != fids.length) throw new IllegalStateException("native int array length mismatch");
                for (int index = 0; index < raws.length; index++) {
                    values[group.get(index).index] = ReadValue.ok(raws[index]);
                }
            } else {
                float[] raws = nativeReader.readFloats(key.dev, fids);
                if (raws == null || raws.length != fids.length) throw new IllegalStateException("native float array length mismatch");
                for (int index = 0; index < raws.length; index++) {
                    values[group.get(index).index] = ReadValue.ok(Float.floatToRawIntBits(raws[index]));
                }
            }
        }
    }

    static final class Address {
        final int tx;
        final int dev;
        final int fid;

        Address(int tx, int dev, int fid) {
            this.tx = tx;
            this.dev = dev;
            this.fid = fid;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Address)) return false;
            Address that = (Address) other;
            return tx == that.tx && dev == that.dev && fid == that.fid;
        }

        @Override public int hashCode() {
            int result = tx;
            result = 31 * result + dev;
            return 31 * result + fid;
        }

        @Override public String toString() {
            return tx + ":" + dev + ":" + fid;
        }
    }

    static final class GroupKey {
        final int tx;
        final int dev;

        GroupKey(int tx, int dev) {
            this.tx = tx;
            this.dev = dev;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof GroupKey)) return false;
            GroupKey that = (GroupKey) other;
            return tx == that.tx && dev == that.dev;
        }

        @Override public int hashCode() {
            return 31 * tx + dev;
        }
    }

    static final class IndexedAddress {
        final int index;
        final Address address;

        IndexedAddress(int index, Address address) {
            this.index = index;
            this.address = address;
        }
    }

    static final class ReadValue {
        final int status;
        final Integer raw;
        final String error;

        ReadValue(int status, Integer raw, String error) {
            this.status = status;
            this.raw = raw;
            this.error = error;
        }

        static ReadValue ok(int raw) {
            return new ReadValue(CollectorHelperProtocol.STATUS_OK, raw, null);
        }

        static ReadValue error(int status, String error) {
            return new ReadValue(status, null, error);
        }
    }

    static final class BatchResult {
        final int batchStatus;
        final int mode;
        final boolean nativeAvailable;
        final int nativeGroupCount;
        final int fallbackGroupCount;
        final int fallbackReadCount;
        final int groupFailureCount;
        final long elapsedMs;
        final ReadValue[] values;
        final String error;

        BatchResult(
            int batchStatus,
            int mode,
            boolean nativeAvailable,
            int nativeGroupCount,
            int fallbackGroupCount,
            int fallbackReadCount,
            int groupFailureCount,
            long elapsedMs,
            ReadValue[] values,
            String error
        ) {
            this.batchStatus = batchStatus;
            this.mode = mode;
            this.nativeAvailable = nativeAvailable;
            this.nativeGroupCount = nativeGroupCount;
            this.fallbackGroupCount = fallbackGroupCount;
            this.fallbackReadCount = fallbackReadCount;
            this.groupFailureCount = groupFailureCount;
            this.elapsedMs = elapsedMs;
            this.values = values;
            this.error = error;
        }

        static BatchResult rejected(int count, String error) {
            ReadValue[] values = new ReadValue[count];
            for (int index = 0; index < count; index++) {
                values[index] = ReadValue.error(CollectorHelperProtocol.STATUS_NOT_WHITELISTED, null);
            }
            return new BatchResult(
                CollectorHelperProtocol.STATUS_INVALID_REQUEST,
                CollectorHelperProtocol.MODE_REJECTED,
                false,
                0,
                0,
                0,
                0,
                0,
                values,
                error
            );
        }
    }

    static final class NativeArrayReader implements NativeReader {
        private final boolean available;
        private final String error;
        private final Object manager;
        private final Method getIntArray;
        private final Method getDoubleArray;

        static NativeArrayReader create() {
            try {
                Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
                Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
                if (activityThread == null) activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
                Context context = (Context) activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
                Object manager = context.getSystemService("auto");
                Class<?> managerClass = Class.forName("android.hardware.BYDAutoManager");
                if (manager == null) {
                    Constructor<?> constructor = managerClass.getConstructor(Context.class);
                    manager = constructor.newInstance(context);
                }
                return new NativeArrayReader(
                    true,
                    null,
                    manager,
                    managerClass.getMethod("getIntArray", int.class, int[].class),
                    managerClass.getMethod("getDoubleArray", int.class, int[].class)
                );
            } catch (Throwable error) {
                return new NativeArrayReader(false, describe(error), null, null, null);
            }
        }

        NativeArrayReader(boolean available, String error, Object manager, Method getIntArray, Method getDoubleArray) {
            this.available = available;
            this.error = error;
            this.manager = manager;
            this.getIntArray = getIntArray;
            this.getDoubleArray = getDoubleArray;
        }

        @Override public boolean isAvailable() {
            return available;
        }

        @Override public String unavailableReason() {
            return error;
        }

        @Override public int[] readInts(int dev, int[] fids) throws Throwable {
            try {
                return (int[]) getIntArray.invoke(manager, dev, fids);
            } catch (InvocationTargetException error) {
                throw error.getTargetException();
            }
        }

        @Override public float[] readFloats(int dev, int[] fids) throws Throwable {
            try {
                return (float[]) getDoubleArray.invoke(manager, dev, fids);
            } catch (InvocationTargetException error) {
                throw error.getTargetException();
            }
        }
    }

    private static OwnerLock acquireSingleOwnerLock() {
        try {
            //prevents multiple shell helper processes from registering competing binder services
            RandomAccessFile file = new RandomAccessFile(CollectorHelperProtocol.LOCK_PATH, "rw");
            FileChannel channel = file.getChannel();
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                file.close();
                return null;
            }
            return new OwnerLock(file, channel, lock);
        } catch (Exception error) {
            System.err.println("WARN: helper lock unavailable: " + error.getMessage());
            return new OwnerLock(null, null, null);
        }
    }

    private static final class OwnerLock {
        private final RandomAccessFile file;
        private final FileChannel channel;
        private final FileLock lock;

        private OwnerLock(RandomAccessFile file, FileChannel channel, FileLock lock) {
            this.file = file;
            this.channel = channel;
            this.lock = lock;
        }

        private void close() {
            try {
                if (lock != null) lock.release();
            } catch (Exception ignored) {
            }
            try {
                if (channel != null) channel.close();
            } catch (Exception ignored) {
            }
            try {
                if (file != null) file.close();
            } catch (Exception ignored) {
            }
        }
    }
}
