package com.bydcollector.collector.direct;

import android.os.Parcel;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Native Parcel check, optionally replaying a copied vehicle corpus. Never reads vehicle services. */
public final class WorkerReplayParcelGate {
    public static String run(File root, File fixture) throws Exception {
        if (!root.mkdirs() && !root.isDirectory()) throw new AssertionError("test root unavailable");
        int expected = 0;
        if (fixture != null) {
            File[] files = fixture.listFiles((dir, name) -> name.endsWith(".ready"));
            if (files == null || files.length == 0) throw new AssertionError("empty replay fixture");
            for (File file : files) Files.copy(file.toPath(), new File(root, file.getName()).toPath());
            expected = files.length;
        }
        try (TelemetryWorkerSpool spool = TelemetryWorkerSpool.openForTest(root, TelemetryWorkerSpool.MAX_SPOOL_BYTES)) {
            if (fixture == null) {
                for (int i = 0; i < 101; i++) {
                    List<TelemetryWorkerSpool.Value> values = new ArrayList<>();
                    for (int f = 0; f < 95; f++) {
                        CallbackValueSource source = new CallbackValueSource("parcel-boot", "parcel-helper", 1,
                            1, i * 95L + f, 1001, f, TelemetryCallbackBatch.TYPE_INT, i,
                            1000 + i, 1000 + i, null, "callback");
                        values.add(new TelemetryWorkerSpool.Value(f, 5, 1001, f, 0, i, null, source));
                    }
                    check(spool.append(new TelemetryWorkerSpool.Sample(
                        new TelemetryWorkerSampleIdentity("parcel-boot", "parcel-helper", i), "catalog",
                        1000 + i, 1000 + i, 1, 0, 1, true, 0, null, values)) == TelemetryWorkerSpool.AppendResult.SUCCESS,
                        "append sample");
                }
                expected = 101;
            }
            int drained = 0, pages = 0, maxBytes = 0;
            while (true) {
                List<TelemetryWorkerSpool.Sample> pending = spool.pending(100);
                if (pending.isEmpty()) break;
                Parcel reply = Parcel.obtain();
                try {
                    CollectorHelperDaemon.writeWorkerPendingReply(reply, 0, null, pending);
                    check(reply.dataSize() <= SecondarySpoolBinder.MAX_REPLY_BYTES, "oversized reply");
                    maxBytes = Math.max(maxBytes, reply.dataSize());
                    byte[] pageBytes = reply.marshall();
                    reply.setDataPosition(0);
                    check(reply.readInt() == 0 && reply.readString() == null, "page failed");
                    int count = reply.readInt(), offset = reply.dataPosition();
                    check(count > 0 && count <= pending.size(), "no page progress");
                    for (int i = 0; i < count; i++) {
                        Parcel single = Parcel.obtain();
                        try {
                            CollectorHelperDaemon.writeWorkerPendingReply(single, 0, null, Collections.singletonList(pending.get(i)));
                            byte[] singleBytes = single.marshall();
                            int size = singleBytes.length - 12;
                            check(Arrays.equals(Arrays.copyOfRange(singleBytes, 12, singleBytes.length),
                                Arrays.copyOfRange(pageBytes, offset, offset + size)), "sample content/order changed");
                            offset += size;
                        } finally { single.recycle(); }
                    }
                    check(offset == pageBytes.length, "trailing/truncated sample");
                    check(spool.observe().pendingReadyRecords == expected - drained, "read removed a record");
                    for (int i = 0; i < count; i++) {
                        check(spool.acknowledge(pending.get(i).identity, 5000).status == TelemetryWorkerSpool.AckStatus.RELEASED,
                            "ACK failed");
                        drained++;
                    }
                    pages++;
                    check(pages <= expected, "replay loop stuck");
                } finally { reply.recycle(); }
            }
            check(drained == expected && pages > 1, "incomplete multi-page replay");
            // An individually oversized record must fail explicitly, not be silently truncated/ACKed.
            String huge = String.join("", Collections.nCopies(150000, "x"));
            TelemetryWorkerSpool.Sample oversized = new TelemetryWorkerSpool.Sample(
                new TelemetryWorkerSampleIdentity("boot", "helper", 0), "catalog", 1, 1, 1, 0, 1, true, 0, huge,
                Collections.singletonList(new TelemetryWorkerSpool.Value(0, 5, 1001, 1, 0, 1, null)));
            check(spool.append(oversized) == TelemetryWorkerSpool.AppendResult.SUCCESS, "oversized fixture append");
            Parcel rejected = Parcel.obtain();
            try {
                CollectorHelperDaemon.writeWorkerPendingReply(rejected, 0, null, spool.pending(100));
                check(rejected.dataSize() <= SecondarySpoolBinder.MAX_REPLY_BYTES, "oversized error reply");
                rejected.setDataPosition(0);
                check(rejected.readInt() == CollectorHelperProtocol.STATUS_SPOOL_UNAVAILABLE, "oversized sample accepted");
                check(rejected.readString().contains("record retained") && rejected.readInt() == 0, "missing retained error");
                check(spool.observe().pendingReadyRecords == 1, "oversized record lost");
            } finally { rejected.recycle(); }
            return "WORKER_REPLAY_PARCEL_PASS records=" + drained + " pages=" + pages + " max_reply_bytes=" + maxBytes;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
