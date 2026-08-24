package com.bydcollector.collector.direct;

import android.annotation.SuppressLint;
import android.os.Binder;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

//runs as shell app_process so the app can read autoservice through a narrow binder bridge
public final class CollectorHelperDaemon {
    private CollectorHelperDaemon() {
    }

    public static void main(String[] args) throws Exception {
        if (
            args.length < 2 ||
            args.length > 3 ||
            (args.length == 3 && !CollectorHelperProtocol.SPOOL_MODE_ARG.equals(args[2]))
        ) {
            System.err.println("ERR: usage: CollectorHelperDaemon <appUid> <apkPath> [spool]");
            System.exit(2);
            return;
        }
        final int appUid = Integer.parseInt(args[0]);
        final String apkPath = args[1];
        final boolean spoolMode = args.length == 3;
        OwnerLock ownerLock = acquireSingleOwnerLock();
        if (ownerLock == null) {
            System.out.println("ALREADY_RUNNING");
            System.exit(0);
            return;
        }

        prepareMainLooper();
        final List<Address> mainRows = loadMainRows();
        final Set<Address> whitelist = loadWhitelist(apkPath, mainRows);
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
        TelemetryWorkerSpool openedSpool = null;
        String openedSpoolError = null;
        try {
            openedSpool = TelemetryWorkerSpool.open();
        } catch (Throwable error) {
            openedSpoolError = describe(error);
        }
        final TelemetryWorkerSpool workerSpool = openedSpool;
        final String workerSpoolError = openedSpoolError;
        if (spoolMode && workerSpool == null) {
            System.err.println("ERR: app-gap spool unavailable: " + workerSpoolError);
            ownerLock.close();
            System.exit(4);
            return;
        }
        final Object readLock = new Object();
        final Handler mainHandler = new Handler(Looper.myLooper());
        final String mainCatalogVersion = loadMainCatalogVersion();
        final TelemetryWorkerSpool.SampleValidator replaySampleValidator = sample ->
            validateWorkerSampleForReplay(sample, mainCatalogVersion, mainRows);
        final WorkerPollLoop workerPollLoop = spoolMode
            ? new WorkerPollLoop(
                mainHandler,
                mainRows,
                mainCatalogVersion,
                readBootId(),
                UUID.randomUUID().toString(),
                workerSpool,
                readLock,
                address -> scalarRead(autoservice, autoserviceDescriptor, address),
                nativeReader
            )
            : null;
        Binder helperBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                //keeps the shell-launched helper private to the collector app that requested it
                if (Binder.getCallingUid() != appUid) {
                    return false;
                }
                data.enforceInterface(CollectorHelperProtocol.DESCRIPTOR);
                if (code == CollectorHelperProtocol.TX_PING) {
                    if (workerPollLoop != null) workerPollLoop.markConsumerHeartbeat();
                    if (reply != null) {
                        reply.writeInt(CollectorHelperProtocol.STATUS_OK);
                        reply.writeInt(CollectorHelperProtocol.PROTOCOL_VERSION);
                        reply.writeInt(
                            spoolMode
                                ? CollectorHelperProtocol.OWNER_MODE_APP_GAP_SPOOL
                                : CollectorHelperProtocol.OWNER_MODE_APP
                        );
                        reply.writeInt(nativeReader.isAvailable() ? 1 : 0);
                        reply.writeString(nativeReader.unavailableReason());
                    }
                    return true;
                }
                if (code == CollectorHelperProtocol.TX_READ) {
                    if (workerPollLoop != null) workerPollLoop.markConsumerHeartbeat();
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
                    if (workerPollLoop != null) workerPollLoop.markConsumerHeartbeat();
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
                                if (workerPollLoop != null) workerPollLoop.markConsumerHeartbeat();
                                if (
                                    workerSpool != null &&
                                    workerPollLoop != null &&
                                    !workerSpool.pending(1, replaySampleValidator).isEmpty()
                                ) {
                                    result = BatchResult.rejected(
                                        rows.size(),
                                        "app-gap spool pending; replay before live read"
                                    );
                                } else {
                                    result = BatchEngine.run(
                                        rows,
                                        address -> scalarRead(autoservice, autoserviceDescriptor, address),
                                        nativeReader
                                    );
                                }
                                if (workerPollLoop != null) workerPollLoop.markConsumerHeartbeat();
                            }
                        }
                    } catch (Throwable error) {
                        result = BatchResult.rejected(0, describe(error));
                    }
                    if (reply != null) writeBatchReply(reply, result);
                    return true;
                }
                if (code == CollectorHelperProtocol.TX_WORKER_PENDING) {
                    if (workerPollLoop != null) workerPollLoop.markConsumerHeartbeat();
                    if (reply != null) {
                        if (workerSpool == null) {
                            writeWorkerPendingReply(
                                reply,
                                CollectorHelperProtocol.STATUS_SPOOL_UNAVAILABLE,
                                workerSpoolError,
                                Collections.<TelemetryWorkerSpool.Sample>emptyList()
                            );
                        } else {
                            try {
                                List<TelemetryWorkerSpool.Sample> samples;
                                synchronized (readLock) {
                                    samples = workerSpool.pending(data.readInt(), replaySampleValidator);
                                }
                                writeWorkerPendingReply(
                                    reply,
                                    CollectorHelperProtocol.STATUS_OK,
                                    null,
                                    samples
                                );
                            } catch (IllegalArgumentException error) {
                                writeWorkerPendingReply(
                                    reply,
                                    CollectorHelperProtocol.STATUS_INVALID_REQUEST,
                                    describe(error),
                                    Collections.<TelemetryWorkerSpool.Sample>emptyList()
                                );
                            } catch (Throwable error) {
                                writeWorkerPendingReply(
                                    reply,
                                    CollectorHelperProtocol.STATUS_SPOOL_UNAVAILABLE,
                                    describe(error),
                                    Collections.<TelemetryWorkerSpool.Sample>emptyList()
                                );
                            }
                        }
                    }
                    if (workerPollLoop != null) workerPollLoop.markConsumerHeartbeat();
                    return true;
                }
                if (code == CollectorHelperProtocol.TX_WORKER_ACK) {
                    if (workerPollLoop != null) workerPollLoop.markConsumerHeartbeat();
                    if (reply != null) {
                        if (workerSpool == null) {
                            writeWorkerAckReply(
                                reply,
                                CollectorHelperProtocol.STATUS_SPOOL_UNAVAILABLE,
                                0,
                                workerSpoolError
                            );
                        } else {
                            try {
                                TelemetryWorkerSampleIdentity identity = new TelemetryWorkerSampleIdentity(
                                    data.readString(),
                                    data.readString(),
                                    data.readLong()
                                );
                                int updated = workerSpool.acknowledge(identity, data.readLong());
                                writeWorkerAckReply(
                                    reply,
                                    updated == 1
                                        ? CollectorHelperProtocol.STATUS_OK
                                        : CollectorHelperProtocol.STATUS_SAMPLE_NOT_FOUND,
                                    updated,
                                    updated == 1 ? null : "worker sample not found"
                                );
                            } catch (IllegalArgumentException error) {
                                writeWorkerAckReply(
                                    reply,
                                    CollectorHelperProtocol.STATUS_INVALID_REQUEST,
                                    0,
                                    describe(error)
                                );
                            } catch (Throwable error) {
                                writeWorkerAckReply(
                                    reply,
                                    CollectorHelperProtocol.STATUS_SPOOL_UNAVAILABLE,
                                    0,
                                    describe(error)
                                );
                            }
                        }
                    }
                    if (workerPollLoop != null) workerPollLoop.markConsumerHeartbeat();
                    return true;
                }
                if (code == CollectorHelperProtocol.TX_STOP_OWNER) {
                    int expectedOwnerMode = data.readInt();
                    int actualOwnerMode = spoolMode
                        ? CollectorHelperProtocol.OWNER_MODE_APP_GAP_SPOOL
                        : CollectorHelperProtocol.OWNER_MODE_APP;
                    boolean accepted = expectedOwnerMode == actualOwnerMode;
                    if (reply != null) {
                        reply.writeInt(
                            accepted
                                ? CollectorHelperProtocol.STATUS_OK
                                : CollectorHelperProtocol.STATUS_INVALID_REQUEST
                        );
                        reply.writeInt(accepted ? 1 : 0);
                        reply.writeString(accepted ? null : "owner mode mismatch");
                    }
                    if (accepted) {
                        //allows the synchronous Binder reply to leave the shell process before its main looper exits
                        mainHandler.postDelayed(() -> {
                            if (workerPollLoop != null) workerPollLoop.stop();
                            mainHandler.getLooper().quitSafely();
                        }, 100L);
                    }
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
                    (nativeReader.isAvailable() ? "" : " native_error=" + nativeReader.unavailableReason()) +
                    " spool=" + (workerSpool != null) +
                    " spool_mode=" + spoolMode +
                    (workerSpoolError == null ? "" : " spool_error=" + workerSpoolError)
            );
            System.out.flush();
            if (workerPollLoop != null) workerPollLoop.start();
            Looper.loop();
        } finally {
            try {
                if (workerPollLoop != null) workerPollLoop.stop();
                if (workerSpool != null) workerSpool.close();
            } finally {
                ownerLock.close();
            }
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

    private static void writeWorkerPendingReply(
            Parcel reply,
            int status,
            String error,
            List<TelemetryWorkerSpool.Sample> samples
    ) {
        reply.writeInt(status);
        reply.writeString(error);
        reply.writeInt(samples.size());
        for (TelemetryWorkerSpool.Sample sample : samples) {
            reply.writeString(sample.identity.bootId);
            reply.writeString(sample.identity.helperGeneration);
            reply.writeLong(sample.identity.pollSequence);
            reply.writeString(sample.catalogVersion);
            reply.writeLong(sample.capturedWallMs);
            reply.writeLong(sample.capturedElapsedMs);
            reply.writeLong(sample.pollElapsedMs);
            reply.writeInt(sample.batchStatus);
            reply.writeInt(sample.batchMode);
            reply.writeInt(sample.nativeAvailable ? 1 : 0);
            reply.writeInt(sample.groupFailureCount);
            reply.writeString(sample.error);
            reply.writeInt(sample.values.size());
            for (TelemetryWorkerSpool.Value value : sample.values) {
                reply.writeInt(value.fieldIndex);
                reply.writeInt(value.tx);
                reply.writeInt(value.dev);
                reply.writeInt(value.fid);
                reply.writeInt(value.status);
                reply.writeInt(value.raw == null ? 0 : 1);
                if (value.raw != null) reply.writeInt(value.raw);
                reply.writeString(value.error);
            }
        }
    }

    private static void writeWorkerAckReply(Parcel reply, int status, int updated, String error) {
        reply.writeInt(status);
        reply.writeInt(updated);
        reply.writeString(error);
    }

    static Set<Address> loadWhitelist(String apkPath) throws Exception {
        return loadWhitelist(apkPath, loadMainRows());
    }

    private static Set<Address> loadWhitelist(String apkPath, List<Address> mainRows) throws Exception {
        Set<Address> whitelist = new HashSet<Address>();
        whitelist.addAll(mainRows);
        loadDebugWhitelist(apkPath, whitelist);
        if (whitelist.isEmpty()) throw new IllegalStateException("empty telemetry whitelist");
        return whitelist;
    }

    static List<Address> loadMainRows() throws Exception {
        Class<?> registryClass = Class.forName("com.bydcollector.collector.data.direct.DirectFidRegistry");
        Object registry = registryClass.getField("INSTANCE").get(null);
        List<?> entries = (List<?>) registryClass.getMethod("getEntries").invoke(registry);
        List<Address> rows = new ArrayList<Address>(entries.size());
        for (Object entry : entries) {
            Class<?> entryClass = entry.getClass();
            int tx = (Integer) entryClass.getMethod("getTx").invoke(entry);
            int dev = (Integer) entryClass.getMethod("getDev").invoke(entry);
            int fid = (Integer) entryClass.getMethod("getFid").invoke(entry);
            if (!isAllowedTx(tx)) throw new IllegalArgumentException("unsupported main whitelist tx: " + tx);
            rows.add(new Address(tx, dev, fid));
        }
        if (rows.isEmpty()) throw new IllegalStateException("empty main telemetry catalog");
        return Collections.unmodifiableList(rows);
    }

    private static String loadMainCatalogVersion() throws Exception {
        Class<?> registryClass = Class.forName("com.bydcollector.collector.data.direct.DirectFidRegistry");
        return (String) registryClass.getField("CATALOG_VERSION").get(null);
    }

    private static String readBootId() throws Exception {
        return new String(
            Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")),
            StandardCharsets.UTF_8
        ).trim();
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

    interface ScalarReader {
        ReadValue read(Address address);
    }

    interface NativeReader {
        boolean isAvailable();
        String unavailableReason();
        int[] readInts(int dev, int[] fids) throws Throwable;
        float[] readFloats(int dev, int[] fids) throws Throwable;
    }

    static TelemetryWorkerSpool.Sample workerSample(
        TelemetryWorkerSampleIdentity identity,
        String catalogVersion,
        long capturedWallMs,
        long capturedElapsedMs,
        List<Address> rows,
        BatchResult result
    ) {
        if (rows.size() != result.values.length) {
            throw new IllegalArgumentException(
                "worker batch size mismatch: rows=" + rows.size() + " values=" + result.values.length
            );
        }
        List<TelemetryWorkerSpool.Value> values = new ArrayList<TelemetryWorkerSpool.Value>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            Address row = rows.get(index);
            ReadValue value = result.values[index];
            values.add(new TelemetryWorkerSpool.Value(
                index,
                row.tx,
                row.dev,
                row.fid,
                value.status,
                value.raw,
                value.error
            ));
        }
        return new TelemetryWorkerSpool.Sample(
            identity,
            catalogVersion,
            capturedWallMs,
            capturedElapsedMs,
            result.elapsedMs,
            result.batchStatus,
            result.mode,
            result.nativeAvailable,
            result.groupFailureCount,
            result.error,
            values
        );
    }

    static void validateWorkerSampleForReplay(
        TelemetryWorkerSpool.Sample sample,
        String catalogVersion,
        List<Address> rows
    ) {
        if (!catalogVersion.equals(sample.catalogVersion)) {
            throw new IllegalArgumentException(
                "worker catalog mismatch: expected=" + catalogVersion + " actual=" + sample.catalogVersion
            );
        }
        if (sample.values.size() != rows.size()) {
            throw new IllegalArgumentException(
                "worker field count mismatch: expected=" + rows.size() + " actual=" + sample.values.size()
            );
        }
        for (int index = 0; index < rows.size(); index++) {
            Address row = rows.get(index);
            TelemetryWorkerSpool.Value value = sample.values.get(index);
            if (
                value.fieldIndex != index ||
                value.tx != row.tx ||
                value.dev != row.dev ||
                value.fid != row.fid
            ) {
                throw new IllegalArgumentException("worker field mismatch at index=" + index);
            }
        }
    }

    static final class WorkerPollLoop implements Runnable {
        static final long FALLBACK_INTERVAL_MS = 500L;
        static final long CONSUMER_LEASE_MS = 2_000L;

        private final Handler handler;
        private final List<Address> rows;
        private final String catalogVersion;
        private final String bootId;
        private final String helperGeneration;
        private final TelemetryWorkerSpool spool;
        private final Object readLock;
        private final ScalarReader scalarReader;
        private final NativeReader nativeReader;
        private final ConsumerLease consumerLease;
        private long sequence;
        private boolean stopped;
        private String lastError;

        WorkerPollLoop(
            Handler handler,
            List<Address> rows,
            String catalogVersion,
            String bootId,
            String helperGeneration,
            TelemetryWorkerSpool spool,
            Object readLock,
            ScalarReader scalarReader,
            NativeReader nativeReader
        ) {
            this.handler = handler;
            this.rows = rows;
            this.catalogVersion = catalogVersion;
            this.bootId = bootId;
            this.helperGeneration = helperGeneration;
            this.spool = spool;
            this.readLock = readLock;
            this.scalarReader = scalarReader;
            this.nativeReader = nativeReader;
            this.consumerLease = new ConsumerLease(
                SystemClock.elapsedRealtime(),
                CONSUMER_LEASE_MS
            );
        }

        void start() {
            handler.post(this);
        }

        void stop() {
            stopped = true;
            handler.removeCallbacks(this);
        }

        void markConsumerHeartbeat() {
            long now = SystemClock.elapsedRealtime();
            boolean wakeFallback;
            synchronized (readLock) {
                wakeFallback = !consumerLease.isActive(now);
                if (consumerLease.renew(now)) {
                    log("INFO: app consumer lease restored");
                    lastError = null;
                }
            }
            if (wakeFallback) {
                //wake a fallback loop immediately; repeated active heartbeats do not repost work
                handler.removeCallbacks(this);
                if (!stopped) handler.post(this);
            }
        }

        @Override public void run() {
            if (stopped) return;
            long cycleStartedAt = SystemClock.elapsedRealtime();
            try {
                synchronized (readLock) {
                    long now = SystemClock.elapsedRealtime();
                    if (!consumerLease.isActive(now)) {
                        if (consumerLease.beginFallback(now)) {
                            log("INFO: app consumer lease expired; fallback spool started");
                        }
                        if (!spool.canAppend()) {
                            recordError("app-gap spool cap reached");
                        } else {
                            long capturedWallMs = System.currentTimeMillis();
                            long capturedElapsedMs = SystemClock.elapsedRealtime();
                            long pollSequence = sequence++;
                            BatchResult result = BatchEngine.run(rows, scalarReader, nativeReader);
                            //the read lock also serializes lease renewal, so a restored APP cannot
                            //race this append and leave a sample after the lease becomes active
                            if (!consumerLease.isActive(SystemClock.elapsedRealtime())) {
                                boolean appended = spool.append(workerSample(
                                    new TelemetryWorkerSampleIdentity(bootId, helperGeneration, pollSequence),
                                    catalogVersion,
                                    capturedWallMs,
                                    capturedElapsedMs,
                                    rows,
                                    result
                                ));
                                if (!appended) {
                                    recordError("app-gap spool append rejected (cap or duplicate)");
                                } else {
                                    lastError = null;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable error) {
                recordError("app-gap spool poll failed: " + describe(error));
            }
            scheduleNext(cycleStartedAt);
        }

        private void scheduleNext(long cycleStartedAt) {
            if (!stopped) {
                long delayMs = Math.max(0L, FALLBACK_INTERVAL_MS - (SystemClock.elapsedRealtime() - cycleStartedAt));
                handler.postDelayed(this, delayMs);
            }
        }

        private void recordError(String message) {
            if (!message.equals(lastError)) {
                log("WARN: " + message);
                lastError = message;
            }
        }

        private static void log(String message) {
            System.err.println(message);
            System.err.flush();
        }
    }

    static final class ConsumerLease {
        private final long durationMs;
        private long expiresAtMs;
        private boolean fallbackActive;

        ConsumerLease(long startedAtMs, long durationMs) {
            if (durationMs < 1L) throw new IllegalArgumentException("lease duration must be positive");
            this.durationMs = durationMs;
            this.expiresAtMs = startedAtMs + durationMs;
        }

        synchronized boolean isActive(long nowMs) {
            return nowMs < expiresAtMs;
        }

        synchronized boolean renew(long nowMs) {
            boolean restored = fallbackActive;
            fallbackActive = false;
            expiresAtMs = nowMs + durationMs;
            return restored;
        }

        synchronized boolean beginFallback(long nowMs) {
            if (isActive(nowMs) || fallbackActive) return false;
            fallbackActive = true;
            return true;
        }
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

        @SuppressLint("WrongConstant")
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
