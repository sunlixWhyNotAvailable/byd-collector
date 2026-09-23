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
import java.security.MessageDigest;
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

        final String helperBootId = readBootId();
        final String helperGeneration = UUID.randomUUID().toString();
        final HelperDiagnostics helperDiagnostics = HelperDiagnostics.open(
            helperBootId,
            Process.myPid(),
            helperGeneration,
            TelemetryWorkerSpool.MAX_SPOOL_BYTES,
            null
        );
        HelperBootstrapOutput.install(helperDiagnostics);

        HelperResources resources = new HelperResources(ownerLock, helperDiagnostics);
        int exitCode;
        try {
            exitCode = runMain(appUid, apkPath, spoolMode, helperBootId, helperGeneration, helperDiagnostics, resources);
        } catch (Exception error) {
            recordDiagnosticError(helperDiagnostics, "helper startup/runtime failed: " + describe(error));
            throw error;
        } catch (Error error) {
            recordDiagnosticError(helperDiagnostics, "helper startup/runtime failed: " + describe(error));
            throw error;
        } finally {
            try {
                resources.close();
            } finally {
                helperDiagnostics.close();
            }
        }
        System.exit(exitCode);
    }

    private static int runMain(
        int appUid,
        String apkPath,
        boolean spoolMode,
        String helperBootId,
        String helperGeneration,
        HelperDiagnostics helperDiagnostics,
        HelperResources resources
    ) throws Exception {
        prepareMainLooper();
        final List<Address> mainRows = loadMainRows();
        final List<Address> secondaryRows = loadSecondaryRows(apkPath);
        final Set<Address> whitelist = loadWhitelist(apkPath, mainRows);
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getMethod("getService", String.class);
        final IBinder autoservice = (IBinder) getService.invoke(null, "autoservice");
        if (autoservice == null) {
            System.err.println("ERR: autoservice not found");
            helperDiagnostics.error("autoservice not found");
            return 3;
        }
        String descriptor = autoservice.getInterfaceDescriptor();
        final String autoserviceDescriptor = descriptor == null ? "" : descriptor;
        final NativeArrayReader nativeReader = NativeArrayReader.create();
        final SecondarySpoolBinder secondarySpoolBinder = new SecondarySpoolBinder();
        resources.secondarySpoolBinder = secondarySpoolBinder;
        if (secondarySpoolBinder.openError() != null) {
            helperDiagnostics.error("secondary spool unavailable: " + secondarySpoolBinder.openError());
        }
        final CallbackSpoolBinder callbackSpoolBinder = new CallbackSpoolBinder();
        resources.callbackSpoolBinder = callbackSpoolBinder;
        TelemetryWorkerSpool openedSpool = null;
        String openedSpoolError = null;
        try {
            openedSpool = TelemetryWorkerSpool.open();
        } catch (Throwable error) {
            openedSpoolError = describe(error);
            helperDiagnostics.onPersistenceFailure("spool_open", error);
        }
        final TelemetryWorkerSpool workerSpool = openedSpool;
        resources.workerSpool = workerSpool;
        final String workerSpoolError = openedSpoolError;
        if (workerSpool != null) {
            try {
                TelemetryWorkerSpool.Footprint initialFootprint = workerSpool.observe();
                helperDiagnostics.onObservation(initialFootprint, false);
            } catch (Throwable error) {
                //An unavailable initial observation stays unknown; diagnostics never blocks telemetry startup.
                helperDiagnostics.onPersistenceFailure("initial_spool_observation", error);
            }
            workerSpool.setDiagnosticListener(helperDiagnostics);
        }
        helperDiagnostics.mode("app");
        final Object readLock = new Object();
        final Handler mainHandler = new Handler(Looper.myLooper());
        final String mainCatalogVersion = loadMainCatalogVersion();
        final String secondaryCatalogVersion = loadSecondaryCatalogVersion();
        final String legacyWorkerCatalogVersion = loadLegacyWorkerCatalogVersion();
        final List<Address> legacyWorkerRows = mainCatalogVersion.equals(legacyWorkerCatalogVersion)
            ? mainRows
            : loadWorkerReplayRows(legacyWorkerCatalogVersion);
        if (legacyWorkerRows == null) throw new IllegalStateException("legacy worker catalog is unavailable");
        final TelemetryWorkerSpool.SampleValidator replaySampleValidator = sample ->
            validateWorkerSampleForReplay(
                sample,
                mainCatalogVersion,
                mainRows,
                legacyWorkerCatalogVersion,
                legacyWorkerRows
            );
        final HelperDualStreamRuntime runtime = new HelperDualStreamRuntime(
            mainHandler,
            mainRows,
            secondaryRows,
            mainCatalogVersion,
            secondaryCatalogVersion,
            helperBootId,
            helperGeneration,
            workerSpool,
            secondarySpoolBinder.spool(),
            address -> scalarRead(autoservice, autoserviceDescriptor, address),
            nativeReader,
            callbackSpoolBinder,
            new HelperWakeLockController(
                SystemClock::elapsedRealtime,
                HelperWakeLockPlatform::acquireShellPartialWakeLock,
                message -> {
                    log(message);
                    if (message.startsWith("WARN:") || message.startsWith("ERR:")) {
                        helperDiagnostics.error("wake_lock: " + message);
                    } else {
                        helperDiagnostics.context("wake_lock", message);
                    }
                }
            ),
            helperDiagnostics
        );
        resources.runtime = runtime;
        Binder helperBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                //keeps the shell-launched helper private to the collector app that requested it
                if (Binder.getCallingUid() != appUid) {
                    return false;
                }
                data.enforceInterface(CollectorHelperProtocol.DESCRIPTOR);
                if (code == CollectorHelperProtocol.TX_STREAM_CONTROL) {
                    HelperStreamRuntimeState.ControlResult result;
                    try {
                        result = runtime.control(
                            data.readInt(), data.readString(), data.readLong(), data.readInt(),
                            data.readLong(), data.readInt());
                    } catch (Throwable error) {
                        HelperStreamRuntimeState.ControlResult snapshot = runtime.control(
                            -1, null, 0L, 0, 0L, 0);
                        result = new HelperStreamRuntimeState.ControlResult(
                            CollectorHelperProtocol.STATUS_INVALID_REQUEST,
                            snapshot.controllerToken, snapshot.mainEpoch, snapshot.secondaryEpoch,
                            0L, describe(error));
                    }
                    if (reply != null) writeControlReply(reply, result);
                    return true;
                }
                if (code == CollectorHelperProtocol.TX_SECONDARY_READ_BATCH) {
                    BatchResult result;
                    try {
                        result = runtime.readSecondary(data.readLong(), data.readLong(), data.readString());
                    } catch (Throwable error) {
                        result = BatchResult.rejected(secondaryRows.size(), describe(error));
                    }
                    if (reply != null) writeSecondaryBatchReply(reply, secondaryCatalogVersion, result);
                    return true;
                }
                if (code == CollectorHelperProtocol.TX_SECONDARY_STATUS) {
                    secondarySpoolBinder.writeStatus(
                        reply, runtime.barrierPending(CollectorHelperProtocol.STREAM_SECONDARY));
                    return true;
                }
                if (code == CollectorHelperProtocol.TX_CALLBACK_PENDING_PAGE ||
                    code == CollectorHelperProtocol.TX_CALLBACK_ACK ||
                    code == CollectorHelperProtocol.TX_CALLBACK_STATUS ||
                    code == CollectorHelperProtocol.TX_CALLBACK_QUARANTINE) {
                    long token;
                    int stream;
                    long epoch;
                    try {
                        token = data.readLong();
                        stream = data.readInt();
                        epoch = data.readLong();
                    } catch (Throwable error) {
                        CallbackSpoolBinder.writeUnavailable(code, reply,
                            CollectorHelperProtocol.STATUS_INVALID_REQUEST, describe(error));
                        return true;
                    }
                    int access = runtime.authorizeCallbackTransport(token, stream, epoch);
                    if (access != CollectorHelperProtocol.STATUS_OK) {
                        CallbackSpoolBinder.writeUnavailable(code, reply, access,
                            access == CollectorHelperProtocol.STATUS_REPLAY_PENDING
                                ? "callback persistence or archive fence pending"
                                : "callback stream credentials are stale or stopped");
                        return true;
                    }
                    return callbackSpoolBinder.onTransact(code, stream, data, reply);
                }
                if (
                    code == CollectorHelperProtocol.TX_SECONDARY_PENDING_PAGE ||
                    code == CollectorHelperProtocol.TX_SECONDARY_ACK ||
                    code == CollectorHelperProtocol.TX_SECONDARY_QUARANTINE
                ) {
                    if (!runtime.replayAllowed(CollectorHelperProtocol.STREAM_SECONDARY)) {
                        SecondarySpoolBinder.writeUnavailable(
                            code, reply, CollectorHelperProtocol.STATUS_LEASE_EXPIRED, "secondary stream stopped or lease expired");
                        return true;
                    }
                    if (runtime.barrierPending(CollectorHelperProtocol.STREAM_SECONDARY)) {
                        SecondarySpoolBinder.writeUnavailable(
                            code, reply, CollectorHelperProtocol.STATUS_REPLAY_PENDING, "secondary persistence pending");
                        return true;
                    }
                    return secondarySpoolBinder.onTransact(code, data, reply);
                }
                if (code == CollectorHelperProtocol.TX_PING) {
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
                        result = runtime.readDiagnostic(address);
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
                        long controllerToken = data.readLong();
                        long mainEpoch = data.readLong();
                        List<Address> rows = readBatchRequest(data);
                        String validationError = validateRows(rows, whitelist);
                        if (validationError != null) {
                            result = BatchResult.rejected(rows.size(), validationError);
                        } else {
                            result = runtime.readMain(controllerToken, mainEpoch, rows);
                        }
                    } catch (Throwable error) {
                        result = BatchResult.rejected(0, describe(error));
                    }
                    if (reply != null) writeBatchReply(reply, result);
                    return true;
                }
                if (code == CollectorHelperProtocol.TX_WORKER_PENDING) {
                    if (reply != null) {
                        if (!runtime.replayAllowed(CollectorHelperProtocol.STREAM_MAIN)) {
                            writeWorkerPendingReply(
                                reply, CollectorHelperProtocol.STATUS_LEASE_EXPIRED,
                                "main stream stopped or lease expired", Collections.<TelemetryWorkerSpool.Sample>emptyList());
                        } else if (runtime.barrierPending(CollectorHelperProtocol.STREAM_MAIN)) {
                            writeWorkerPendingReply(
                                reply, CollectorHelperProtocol.STATUS_REPLAY_PENDING,
                                "main persistence pending", Collections.<TelemetryWorkerSpool.Sample>emptyList());
                        } else if (workerSpool == null) {
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
                    return true;
                }
                if (code == CollectorHelperProtocol.TX_WORKER_ACK) {
                    if (reply != null) {
                        if (!runtime.replayAllowed(CollectorHelperProtocol.STREAM_MAIN)) {
                            writeWorkerAckReply(
                                reply, CollectorHelperProtocol.STATUS_LEASE_EXPIRED, 0,
                                "main stream stopped or lease expired");
                        } else if (runtime.barrierPending(CollectorHelperProtocol.STREAM_MAIN)) {
                            writeWorkerAckReply(
                                reply, CollectorHelperProtocol.STATUS_REPLAY_PENDING, 0, "main persistence pending");
                        } else if (workerSpool == null) {
                            recordFailedAck(helperDiagnostics, workerSpoolError);
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
                                TelemetryWorkerSpool.AckResult ack = workerSpool.acknowledge(identity, data.readLong());
                                writeWorkerAckReply(
                                    reply,
                                    ack.status == TelemetryWorkerSpool.AckStatus.RELEASED
                                        ? CollectorHelperProtocol.STATUS_OK
                                        : ack.status == TelemetryWorkerSpool.AckStatus.NOT_FOUND
                                            ? CollectorHelperProtocol.STATUS_SAMPLE_NOT_FOUND
                                            : CollectorHelperProtocol.STATUS_SPOOL_UNAVAILABLE,
                                    ack.releasedRecords,
                                    ack.error
                                );
                            } catch (IllegalArgumentException error) {
                                recordFailedAck(helperDiagnostics, describe(error));
                                writeWorkerAckReply(
                                    reply,
                                    CollectorHelperProtocol.STATUS_INVALID_REQUEST,
                                    0,
                                    describe(error)
                                );
                            } catch (Throwable error) {
                                recordFailedAck(helperDiagnostics, describe(error));
                                writeWorkerAckReply(
                                    reply,
                                    CollectorHelperProtocol.STATUS_SPOOL_UNAVAILABLE,
                                    0,
                                    describe(error)
                                );
                            }
                        }
                    }
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
                            mainHandler.getLooper().quitSafely();
                        }, 100L);
                    }
                    return true;
                }
                return false;
            }
        };
        helperBinder.attachInterface(null, CollectorHelperProtocol.DESCRIPTOR);
        runtime.start();
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
        helperDiagnostics.context("helper_ready", "spool_mode=" + spoolMode);
        Looper.loop();
        // ActivityThread/Binder threads may outlive main; process death is the final
        // fallback that releases a lock whose explicit teardown persistently failed.
        return 0;
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

    private static void recordFailedAck(HelperDiagnostics diagnostics, String error) {
        try {
            diagnostics.onAcknowledge(TelemetryWorkerSpool.AckResult.failed(error));
        } catch (Throwable ignored) {
            //Diagnostic accounting cannot change the ACK reply path.
        }
    }

    private static void recordDiagnosticError(HelperDiagnostics diagnostics, String error) {
        try {
            diagnostics.error(error);
        } catch (Throwable ignored) {
            //Cleanup and original failure propagation remain authoritative.
        }
    }

    static void log(String message) {
        System.err.println(message);
        System.err.flush();
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
            CallbackValueSource.writeNullable(reply, value.callbackSource);
        }
    }

    private static void writeControlReply(Parcel reply, HelperStreamRuntimeState.ControlResult result) {
        reply.writeInt(result.status);
        reply.writeLong(result.controllerToken);
        reply.writeLong(result.mainEpoch);
        reply.writeLong(result.secondaryEpoch);
        reply.writeLong(result.leaseExpiresElapsedMs);
        reply.writeString(boundError(result.error));
    }

    private static void writeSecondaryBatchReply(Parcel reply, String catalogVersion, BatchResult result) {
        int count = result.batchStatus == CollectorHelperProtocol.STATUS_OK ? result.values.length : 0;
        int[] statuses = new int[count];
        byte[] rawPresent = new byte[(count + 7) / 8];
        byte[] callbackCached = new byte[(count + 7) / 8];
        int[] raws = new int[count];
        for (int index = 0; index < count; index++) {
            ReadValue value = result.values[index];
            statuses[index] = value.status;
            if (value.raw != null) {
                rawPresent[index >>> 3] = (byte) (rawPresent[index >>> 3] | (1 << (index & 7)));
                raws[index] = value.raw;
            }
            if (value.callbackSource != null) callbackCached[index >>> 3] =
                (byte) (callbackCached[index >>> 3] | (1 << (index & 7)));
        }
        reply.writeInt(result.batchStatus);
        reply.writeInt(result.mode);
        reply.writeInt(result.nativeAvailable ? 1 : 0);
        reply.writeInt(result.nativeGroupCount);
        reply.writeInt(result.fallbackGroupCount);
        reply.writeInt(result.fallbackReadCount);
        reply.writeInt(result.groupFailureCount);
        reply.writeLong(result.elapsedMs);
        reply.writeInt(count);
        reply.writeString(boundError(result.error));
        reply.writeString(catalogVersion);
        reply.writeIntArray(statuses);
        reply.writeByteArray(rawPresent);
        reply.writeIntArray(raws);
        reply.writeByteArray(callbackCached);
    }

    private static String boundError(String error) {
        if (error == null || error.length() <= 512) return error;
        return error.substring(0, 512);
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
                CallbackValueSource.writeNullable(reply, value.callbackSource);
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
            if (TelemetryCatalogPolicy.isRuntimeSelected(dev, fid)) rows.add(new Address(tx, dev, fid));
        }
        if (rows.isEmpty()) throw new IllegalStateException("empty main telemetry catalog");
        return Collections.unmodifiableList(rows);
    }

    static List<Address> loadSecondaryRows(String apkPath) throws Exception {
        List<Address> rows = new ArrayList<Address>(SecondaryTelemetrySpool.EXPECTED_FIELD_COUNT);
        MessageDigest fingerprint = TelemetryCatalogPolicy.newFingerprint();
        try (ZipFile apk = new ZipFile(apkPath)) {
            String[] assetNames = {
                "assets/direct_debug_round_robin_parameters_1.csv",
                "assets/direct_debug_round_robin_parameters_2.csv",
                "assets/direct_debug_round_robin_parameters_3.csv"
            };
            int[] expectedSizes = { 7_692, 7_693, 7_698 };
            List<String> expectedHeader = java.util.Arrays.asList(
                "key", "feature_group", "dev", "fid", "tx",
                "feature_names", "feature_refs", "candidate_source");
            for (int shardIndex = 0; shardIndex < assetNames.length; shardIndex++) {
                String assetName = assetNames[shardIndex];
                ZipEntry asset = apk.getEntry(assetName);
                if (asset == null) throw new IllegalStateException("secondary catalog asset missing: " + assetName);
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(apk.getInputStream(asset), StandardCharsets.UTF_8)
                )) {
                    List<String> header = splitCsvLine(reader.readLine());
                    if (!expectedHeader.equals(header)) {
                        throw new IllegalArgumentException("unexpected secondary catalog header");
                    }
                    int devIndex = header.indexOf("dev");
                    int fidIndex = header.indexOf("fid");
                    int txIndex = header.indexOf("tx");
                    int definitionCount = 0;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        definitionCount++;
                        List<String> columns = splitCsvLine(line);
                        int tx = Integer.parseInt(columns.get(txIndex));
                        if (!isAllowedTx(tx)) throw new IllegalArgumentException("unsupported secondary catalog tx: " + tx);
                        int dev = Integer.parseInt(columns.get(devIndex));
                        int fid = Integer.parseInt(columns.get(fidIndex));
                        if (TelemetryCatalogPolicy.isRuntimeSelected(dev, fid)) {
                            rows.add(new Address(tx, dev, fid));
                            TelemetryCatalogPolicy.addToFingerprint(fingerprint, dev, fid, tx);
                        }
                    }
                    if (definitionCount != expectedSizes[shardIndex]) {
                        throw new IllegalStateException(
                            "unexpected secondary catalog shard size: " + assetName + "=" + definitionCount);
                    }
                }
            }
        }
        if (rows.size() != SecondaryTelemetrySpool.EXPECTED_FIELD_COUNT) {
            throw new IllegalStateException("unexpected secondary catalog size: " + rows.size());
        }
        String actualFingerprint = TelemetryCatalogPolicy.finishFingerprint(fingerprint);
        if (!TelemetryCatalogPolicy.SECONDARY_ACTIVE_FINGERPRINT.equals(actualFingerprint)) {
            throw new IllegalStateException("unexpected secondary catalog fingerprint: " + actualFingerprint);
        }
        return Collections.unmodifiableList(rows);
    }

    private static String loadSecondaryCatalogVersion() throws Exception {
        Class<?> assetClass = Class.forName("com.bydcollector.collector.data.debug.DirectDebugParameterAsset");
        return (String) assetClass.getField("SOURCE_VERSION").get(null);
    }

    private static String loadMainCatalogVersion() throws Exception {
        Class<?> registryClass = Class.forName("com.bydcollector.collector.data.direct.DirectFidRegistry");
        return (String) registryClass.getField("CATALOG_VERSION").get(null);
    }

    private static String loadLegacyWorkerCatalogVersion() throws Exception {
        Class<?> registryClass = Class.forName("com.bydcollector.collector.data.direct.DirectFidRegistry");
        return (String) registryClass.getField("LEGACY_WORKER_CATALOG_VERSION").get(null);
    }

    static List<Address> loadWorkerReplayRows(String catalogVersion) {
        try {
            Class<?> registryClass = Class.forName("com.bydcollector.collector.data.direct.DirectFidRegistry");
            Object registry = registryClass.getField("INSTANCE").get(null);
            List<?> entries = (List<?>) registryClass
                .getMethod("workerReplayEntriesForCatalog", String.class)
                .invoke(registry, catalogVersion);
            if (entries == null) return null;
            List<Address> rows = new ArrayList<Address>(entries.size());
            for (Object entry : entries) {
                Class<?> entryClass = entry.getClass();
                int tx = (Integer) entryClass.getMethod("getTx").invoke(entry);
                if (!isAllowedTx(tx)) throw new IllegalArgumentException("unsupported replay catalog tx: " + tx);
                rows.add(new Address(
                    tx,
                    (Integer) entryClass.getMethod("getDev").invoke(entry),
                    (Integer) entryClass.getMethod("getFid").invoke(entry)
                ));
            }
            return Collections.unmodifiableList(rows);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("cannot load telemetry worker replay catalog", error);
        }
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
                    if (TelemetryCatalogPolicy.isRuntimeSelected(dev, fid)) {
                        whitelist.add(new Address(tx, dev, fid));
                    }
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
                value.error,
                value.callbackSource
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
        String currentCatalogVersion,
        List<Address> currentRows,
        String legacyCatalogVersion,
        List<Address> legacyRows
    ) {
        if (currentCatalogVersion.equals(sample.catalogVersion)) {
            validateWorkerSampleForReplay(sample, currentCatalogVersion, currentRows);
        } else if (legacyCatalogVersion.equals(sample.catalogVersion)) {
            validateWorkerSampleForReplay(sample, legacyCatalogVersion, legacyRows);
        } else {
            throw new IllegalArgumentException("unsupported worker catalog: " + sample.catalogVersion);
        }
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
        final CallbackValueSource callbackSource;

        ReadValue(int status, Integer raw, String error) {
            this(status, raw, error, null);
        }

        ReadValue(int status, Integer raw, String error, CallbackValueSource callbackSource) {
            this.status = status;
            this.raw = raw;
            this.error = error;
            if (callbackSource != null && (status != CollectorHelperProtocol.STATUS_OK || raw == null)) {
                throw new IllegalArgumentException("callback source requires a usable raw value");
            }
            this.callbackSource = callbackSource;
        }

        static ReadValue ok(int raw) {
            return new ReadValue(CollectorHelperProtocol.STATUS_OK, raw, null);
        }

        static ReadValue error(int status, String error) {
            return new ReadValue(status, null, error);
        }

        static ReadValue cached(int raw, CallbackValueSource source) {
            if (source == null || source.rawBits != raw) throw new IllegalArgumentException("invalid cached callback value");
            return new ReadValue(CollectorHelperProtocol.STATUS_OK, raw, null, source);
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

        static BatchResult replayPending(int count, String error) {
            ReadValue[] values = new ReadValue[count];
            for (int index = 0; index < count; index++) {
                values[index] = ReadValue.error(CollectorHelperProtocol.STATUS_REPLAY_PENDING, null);
            }
            return new BatchResult(
                CollectorHelperProtocol.STATUS_REPLAY_PENDING,
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

        Object manager() { return manager; }
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

    private static final class HelperResources implements AutoCloseable {
        private final OwnerLock ownerLock;
        private final HelperDiagnostics diagnostics;
        private TelemetryWorkerSpool workerSpool;
        private SecondarySpoolBinder secondarySpoolBinder;
        private CallbackSpoolBinder callbackSpoolBinder;
        private HelperDualStreamRuntime runtime;
        private boolean closed;

        HelperResources(OwnerLock ownerLock, HelperDiagnostics diagnostics) {
            this.ownerLock = ownerLock;
            this.diagnostics = diagnostics;
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            boolean callbackTransportSafeToClose = true;
            try {
                if (runtime != null) {
                    runtime.close();
                    callbackTransportSafeToClose = runtime.callbackWorkersStopped();
                }
            } catch (Throwable error) {
                recordDiagnosticError(diagnostics, "helper runtime teardown failed: " + describe(error));
                callbackTransportSafeToClose = runtime == null || runtime.callbackWorkersStopped();
            }
            try {
                if (workerSpool != null) workerSpool.close();
            } catch (Throwable error) {
                recordDiagnosticError(diagnostics, "helper spool teardown failed: " + describe(error));
            }
            try {
                if (secondarySpoolBinder != null) secondarySpoolBinder.close();
            } catch (Throwable error) {
                recordDiagnosticError(diagnostics, "secondary spool teardown failed: " + describe(error));
            }
            if (callbackTransportSafeToClose) {
                try {
                    if (callbackSpoolBinder != null) callbackSpoolBinder.close();
                } catch (Throwable error) {
                    recordDiagnosticError(diagnostics, "callback spool teardown failed: " + describe(error));
                }
            } else {
                recordDiagnosticError(diagnostics,
                    "callback workers exceeded shutdown wait; callback spool and owner lock retained until helper process exit");
            }
            if (callbackTransportSafeToClose) {
                try {
                    ownerLock.close();
                } catch (Throwable error) {
                    recordDiagnosticError(diagnostics, "helper owner teardown failed: " + describe(error));
                }
            }
        }
    }
}
