package com.bydcollector.collector;

import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import com.bydcollector.collector.direct.CollectorHelperProtocol;

import java.lang.reflect.Method;

/** Test-APK-only Binder peer for the production helper replacement path. It performs no vehicle reads. */
public final class HelperReplacementProcessFixture {
    private HelperReplacementProcessFixture() {}

    public static void main(String[] args) throws Exception {
        if (!android.os.Build.MODEL.startsWith("sdk_gphone") &&
            !android.os.Build.FINGERPRINT.contains("generic")) {
            throw new SecurityException("Helper fixture requires an emulator");
        }
        if (args.length != 3 || !CollectorHelperProtocol.SPOOL_MODE_ARG.equals(args[2])) {
            throw new IllegalArgumentException("usage: HelperReplacementProcessFixture <appUid> <apkPath> spool");
        }
        final int appUid = Integer.parseInt(args[0]);
        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignored) {
            // app_process may already have prepared the main looper.
        }
        Binder helper = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (Binder.getCallingUid() != appUid) return false;
                data.enforceInterface(CollectorHelperProtocol.DESCRIPTOR);
                if (code == CollectorHelperProtocol.TX_PING) {
                    reply.writeInt(CollectorHelperProtocol.STATUS_OK);
                    reply.writeInt(CollectorHelperProtocol.PROTOCOL_VERSION);
                    reply.writeInt(CollectorHelperProtocol.OWNER_MODE_APP_GAP_SPOOL);
                    return true;
                }
                if (code == CollectorHelperProtocol.TX_STOP_OWNER) {
                    int requestedMode = data.readInt();
                    if (requestedMode != CollectorHelperProtocol.OWNER_MODE_APP_GAP_SPOOL) {
                        reply.writeInt(CollectorHelperProtocol.STATUS_INVALID_REQUEST);
                        reply.writeInt(0);
                        reply.writeString("owner mode mismatch");
                        return true;
                    }
                    reply.writeInt(CollectorHelperProtocol.STATUS_OK);
                    reply.writeInt(1);
                    reply.writeString(null);
                    Thread killer = new Thread(() -> {
                        try {
                            Thread.sleep(100L);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        Process.killProcess(Process.myPid());
                    }, "byd-helper-fixture-stop");
                    killer.setDaemon(true);
                    killer.start();
                    return true;
                }
                return false;
            }
        };
        helper.attachInterface(null, CollectorHelperProtocol.DESCRIPTOR);
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method addService = serviceManager.getMethod("addService", String.class, IBinder.class);
        addService.invoke(null, CollectorHelperProtocol.SERVICE_NAME, helper);
        System.out.println("HELPER_FIXTURE_READY pid=" + Process.myPid());
        System.out.flush();
        Looper.loop();
    }
}
