package com.bydcollector.collector.direct;

import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

public final class CollectorHelperDaemon {
    private CollectorHelperDaemon() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("ERR: usage: CollectorHelperDaemon <appUid>");
            System.exit(2);
            return;
        }
        final int appUid = Integer.parseInt(args[0]);
        OwnerLock ownerLock = acquireSingleOwnerLock();
        if (ownerLock == null) {
            System.out.println("ALREADY_RUNNING");
            System.exit(0);
            return;
        }

        prepareMainLooper();
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
        Binder helperBinder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                // The helper is launched by the shell ADB session, but Binder reports the app process
                // as the caller here. Keep the helper private to this collector package UID.
                if (Binder.getCallingUid() != appUid) {
                    return false;
                }
                data.enforceInterface(CollectorHelperProtocol.DESCRIPTOR);
                if (code == CollectorHelperProtocol.TX_PING) {
                    if (reply != null) reply.writeInt(0);
                    return true;
                }
                if (code == CollectorHelperProtocol.TX_READ) {
                    int tx = data.readInt();
                    int dev = data.readInt();
                    int fid = data.readInt();
                    int[] result = autoserviceTransact(autoservice, autoserviceDescriptor, tx, dev, fid);
                    if (reply != null) {
                        reply.writeInt(result[0]);
                        reply.writeInt(result[1]);
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
            System.out.println("READY pid=" + Process.myPid());
            System.out.flush();
            Looper.loop();
        } finally {
            ownerLock.close();
        }
    }

    private static int[] autoserviceTransact(
            IBinder autoservice,
            String descriptor,
            int tx,
            int dev,
            int fid
    ) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeInt(dev);
            data.writeInt(fid);
            autoservice.transact(tx, data, reply, 0);
            int available = reply.dataAvail();
            int status = available >= 4 ? reply.readInt() : -999;
            int raw = available >= 8 ? reply.readInt() : 0;
            return new int[]{status, raw};
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void prepareMainLooper() {
        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignored) {
            // app_process may already have the main looper prepared.
        }
    }

    private static OwnerLock acquireSingleOwnerLock() {
        try {
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
