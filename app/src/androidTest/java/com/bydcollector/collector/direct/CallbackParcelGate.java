package com.bydcollector.collector.direct;

import android.os.Binder;
import android.os.Parcel;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Android Parcel dispatch test. Deliberately does not claim cross-process or vehicle UID coverage. */
public final class CallbackParcelGate {
    public static void run(File root) throws Exception {
        CallbackSpool main = CallbackSpool.openForTest(new File(root, "main"), 8L * 1024 * 1024);
        CallbackSpool secondary = CallbackSpool.openForTest(new File(root, "secondary"), 8L * 1024 * 1024);
        try (CallbackSpoolBinder transport = new CallbackSpoolBinder(main, secondary)) {
            byte[] payload = new byte[1024 * 1024 + 37];
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
            TelemetryCallbackBatch batch = new TelemetryCallbackBatch("parcel-boot", "parcel-generation", 1, 7, 0,
                Collections.singletonList(new TelemetryCallbackBatch.Event(1, 1001, 42,
                    TelemetryCallbackBatch.TYPE_BYTES, 0, payload, 10000, 2000, null, "ok")));
            require(transport.deliver(batch, true) == CallbackSpool.AppendResult.SUCCESS, "live delivery");
            require(main.status().readyBatches == 0, "healthy live delivery must not force disk spool");
            Binder binder = new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    return transport.onTransact(code, data.readInt(), data, reply);
                }
            };
            CallbackSpool.Descriptor descriptor = null;
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            int pages = 0;
            do {
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    data.writeInt(1);
                    CallbackSpoolBinder.writeDescriptor(data, descriptor);
                    data.writeLong(received.size()); data.writeInt(CallbackSpool.MAX_SLICE_BYTES);
                    require(binder.transact(CollectorHelperProtocol.TX_CALLBACK_PENDING_PAGE, data, reply, 0), "page dispatch");
                    require(reply.dataSize() <= CallbackSpoolBinder.MAX_REPLY_BYTES, "bounded reply");
                    require(reply.readInt() == CollectorHelperProtocol.STATUS_OK, "page status");
                    require(reply.readString() == null, "page error");
                    CallbackSpool.Descriptor current = CallbackSpoolBinder.readDescriptor(reply);
                    require(current != null && (descriptor == null || descriptor.equals(current)), "stable descriptor");
                    descriptor = current;
                    require(reply.readLong() == received.size(), "page offset");
                    byte[] part = reply.createByteArray();
                    require(part != null && part.length > 0 && part.length <= CallbackSpool.MAX_SLICE_BYTES, "page bytes");
                    received.write(part); pages++;
                    require(reply.dataAvail() == 0, "no trailing page data");
                } finally { data.recycle(); reply.recycle(); }
            } while (received.size() < descriptor.length);
            require(pages > 1 && Arrays.equals(received.toByteArray(), batch.encode()), "exact multi-page round trip");
            require(Arrays.equals(TelemetryCallbackBatch.decode(received.toByteArray()).events.get(0).rawBytes(), payload), "exact blob bits");
            CallbackSpool.Descriptor wrong = new CallbackSpool.Descriptor(descriptor.spoolOrder, descriptor.stream,
                descriptor.identity, descriptor.fileName, descriptor.length, "00", descriptor.bootId,
                descriptor.helperGeneration, descriptor.epoch, descriptor.batchSequence);
            // A forged descriptor must not release the live record, even if the on-disk lookup reports absent.
            action(binder, wrong);
            require(pending(binder), "wrong ACK cannot discard live data");
            require(action(binder, descriptor) == 1, "exact ACK releases one batch");
            require(!pending(binder), "no pending record after exact ACK");
            require(action(binder, descriptor) == 0, "duplicate ACK is idempotent");
        }
        blockedSpoolDoesNotBlockOtherStream(new File(root, "isolation-main"), 1);
        blockedSpoolDoesNotBlockOtherStream(new File(root, "isolation-secondary"), 2);
    }

    private static void blockedSpoolDoesNotBlockOtherStream(File root, int blockedStream) throws Exception {
        File mainRoot = new File(root, "main"), secondaryRoot = new File(root, "secondary");
        CallbackSpool main = CallbackSpool.openForTest(mainRoot, 8L * 1024 * 1024);
        CallbackSpool secondary = CallbackSpool.openForTest(secondaryRoot, 8L * 1024 * 1024);
        int freeStream = blockedStream == 1 ? 2 : 1;
        AtomicReference<Throwable> publisherError = new AtomicReference<>();
        Thread publisher = null;
        try (CallbackSpoolBinder transport = new CallbackSpoolBinder(main, secondary)) {
            require(transport.deliver(smallBatch(freeStream), true) == CallbackSpool.AppendResult.SUCCESS, "seed free stream");
            Binder binder = new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                    return transport.onTransact(code, data.readInt(), data, reply);
                }
            };
            publisher = new Thread(() -> {
                try {
                    require(transport.deliver(smallBatch(blockedStream), false) == CallbackSpool.AppendResult.SUCCESS,
                        "blocked publication resumes intact");
                } catch (Throwable error) { publisherError.set(error); }
            }, "test-blocked-spool");
            // Hold the actual per-root persistence barrier to simulate a stalled disk write.
            // No reflection or assertions about transport's chosen locking implementation.
            synchronized (CallbackSpool.persistenceLock(blockedStream == 1 ? mainRoot : secondaryRoot)) {
                publisher.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (publisher.getState() != Thread.State.BLOCKED && publisher.isAlive() && System.nanoTime() < deadline) {
                    Thread.sleep(1);
                }
                require(publisher.getState() == Thread.State.BLOCKED, "publication reached blocked storage");
                CompletableFuture.runAsync(() -> {
                    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                    try {
                        data.writeInt(freeStream); CallbackSpoolBinder.writeDescriptor(data, null);
                        data.writeLong(0); data.writeInt(CallbackSpool.MAX_SLICE_BYTES);
                        require(binder.transact(CollectorHelperProtocol.TX_CALLBACK_PENDING_PAGE, data, reply, 0), "parallel page dispatch");
                        require(reply.readInt() == CollectorHelperProtocol.STATUS_OK, "parallel page status");
                        reply.readString();
                        CallbackSpool.Descriptor descriptor = CallbackSpoolBinder.readDescriptor(reply);
                        require(descriptor != null && descriptor.stream == freeStream, "parallel stream identity");
                        reply.readLong();
                        byte[] bytes = reply.createByteArray();
                        require(Arrays.equals(smallBatch(freeStream).encode(), bytes), "parallel exact payload");
                        require(action(binder, descriptor) == 1, "parallel ACK progresses during other write");
                    } catch (Exception error) { throw new RuntimeException(error); }
                    finally { data.recycle(); reply.recycle(); }
                }).get(2, TimeUnit.SECONDS);
            }
            publisher.join(2_000);
            require(!publisher.isAlive(), "publisher drained after storage released");
            if (publisherError.get() != null) throw new AssertionError("publisher failed", publisherError.get());
        } finally {
            if (publisher != null) publisher.join(2_000);
        }
    }

    private static TelemetryCallbackBatch smallBatch(int stream) {
        return new TelemetryCallbackBatch("parallel-boot", "parallel-helper", stream, 1, 0,
            Collections.singletonList(new TelemetryCallbackBatch.Event(1, 1001, 42,
                TelemetryCallbackBatch.TYPE_INT, stream * 11, null, 10000, 2000, null, "ok")));
    }

    private static int action(Binder binder, CallbackSpool.Descriptor descriptor) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInt(descriptor.stream); CallbackSpoolBinder.writeDescriptor(data, descriptor);
            require(binder.transact(CollectorHelperProtocol.TX_CALLBACK_ACK, data, reply, 0), "ACK dispatch");
            int status = reply.readInt(), affected = reply.readInt(); reply.readString();
            require(reply.dataAvail() == 0, "no trailing ACK data");
            return status == CollectorHelperProtocol.STATUS_OK ? affected : -1;
        } finally { data.recycle(); reply.recycle(); }
    }

    private static boolean pending(Binder binder) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInt(1); CallbackSpoolBinder.writeDescriptor(data, null);
            data.writeLong(0); data.writeInt(1);
            require(binder.transact(CollectorHelperProtocol.TX_CALLBACK_PENDING_PAGE, data, reply, 0), "pending dispatch");
            require(reply.readInt() == CollectorHelperProtocol.STATUS_OK, "pending status"); reply.readString();
            return CallbackSpoolBinder.readDescriptor(reply) != null;
        } finally { data.recycle(); reply.recycle(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
