package com.bydcollector.collector.direct;

import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shell-owned scalar connection. Recovery is serialized by the existing vendor scheduler. */
final class AutoserviceConnection implements CollectorHelperDaemon.ScalarReader, AutoCloseable {
    private final Method getService;
    private final HelperDiagnostics diagnostics;
    private volatile Binding binding;
    private volatile boolean closed;
    private Runnable lossListener;
    private long episode;
    private long lostAt;
    private long attempts;
    private long bindingSerial;
    private Throwable originalFailure;
    private String failureStage;
    private String lastReconnectError;
    private long dataEpisode;

    AutoserviceConnection(HelperDiagnostics diagnostics) throws Exception {
        this.diagnostics = diagnostics;
        getService = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
    }

    synchronized long episode() { return episode; }
    boolean available() { return !closed && binding != null; }
    long bindingIdentity() { Binding current = binding; return current == null ? -1L : current.serial; }

    void setLossListener(Runnable listener) {
        boolean pending;
        synchronized (this) {
            lossListener = listener;
            pending = !closed && binding == null && originalFailure != null;
        }
        if (pending) listener.run();
    }

    // Called only from startup or the vendor owner thread, never under a stream lock.
    void connect() throws Exception {
        long expectedEpisode;
        long attempt;
        synchronized (this) {
            if (closed) throw new IllegalStateException("autoservice connection closed");
            if (binding != null) return;
            expectedEpisode = episode;
            attempt = ++attempts;
        }
        diagnostics.context("autoservice_connect_attempt", "episode=" + expectedEpisode + " attempt=" + attempt);
        Binding candidate = null;
        try {
            IBinder binder;
            try { binder = (IBinder) getService.invoke(null, "autoservice"); }
            catch (InvocationTargetException error) {
                Throwable cause = error.getTargetException();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw error;
            }
            if (binder == null) throw new IllegalStateException("ServiceManager returned no autoservice Binder");
            if (!binder.isBinderAlive()) throw new DeadObjectException("ServiceManager returned a dead autoservice Binder");
            String descriptor = binder.getInterfaceDescriptor();
            if (descriptor == null || descriptor.isEmpty()) throw new IllegalStateException("autoservice descriptor is empty");
            candidate = new Binding(binder, descriptor, ++bindingSerial);
            binder.linkToDeath(candidate, 0);
            candidate.linked.set(true);
            synchronized (this) {
                if (closed || episode != expectedEpisode) {
                    throw new IllegalStateException("autoservice reconnect superseded or closed");
                }
                binding = candidate;
            }
            if (!binder.isBinderAlive()) {
                DeadObjectException error = new DeadObjectException("autoservice died while installing its Binder");
                lost(candidate, error, "connect");
                throw error;
            }
            diagnostics.context("autoservice_binder_ready", "episode=" + expectedEpisode + " attempt=" + attempt +
                " binding=" + candidate.serial + " elapsed_ms=" + (lostAt == 0L ? 0L : SystemClock.elapsedRealtime() - lostAt));
            lastReconnectError = null;
        } catch (Exception error) {
            if (candidate != null && binding != candidate) detach(candidate);
            String detail = error.getClass().getName() + ": " + String.valueOf(error.getMessage());
            diagnostics.context("autoservice_connect_failed", bounded("episode=" + expectedEpisode +
                " attempt=" + attempt + " error=" + detail));
            if (!detail.equals(lastReconnectError)) {
                lastReconnectError = detail;
                System.err.println("autoservice reconnect failure episode=" + expectedEpisode + " attempt=" + attempt);
                error.printStackTrace(System.err);
            }
            throw error;
        }
    }

    boolean listenerDied(long identity, String message) {
        Binding current = binding;
        return current != null && current.serial == identity && lost(current,
            new DeadObjectException("OEM onError(-1): " + String.valueOf(message)), "native_listener");
    }

    @Override public void nativeFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 32; depth++, current = current.getCause()) {
            if (current instanceof DeadObjectException) {
                Binding failed = binding;
                if (failed != null) lost(failed, failure, "native_call");
                return;
            }
        }
    }

    @Override public boolean transportAvailable() { return available(); }

    @Override public String transportError() { return failureDetail(); }

    synchronized String failureDetail() {
        String detail = originalFailure == null ? "autoservice is not connected" :
            originalFailure.getClass().getName() + ": " + String.valueOf(originalFailure.getMessage());
        return bounded("episode=" + episode + " stage=" + failureStage + " " + detail);
    }

    void noteData(String source) {
        long recovered;
        synchronized (this) {
            if (!available() || episode == 0L || dataEpisode == episode) return;
            recovered = dataEpisode = episode;
        }
        diagnostics.context("autoservice_data_observed", "episode=" + recovered + " source=" + source);
    }

    @Override public CollectorHelperDaemon.ReadValue read(CollectorHelperDaemon.Address address) {
        Binding current = binding;
        if (closed || current == null) return CollectorHelperDaemon.ReadValue.notAttempted(failureDetail());
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(current.descriptor);
            data.writeInt(address.dev);
            data.writeInt(address.fid);
            if (!current.binder.transact(address.tx, data, reply, 0)) {
                return CollectorHelperDaemon.ReadValue.error(CollectorHelperProtocol.STATUS_READ_ERROR,
                    "autoservice transact returned false address=" + address);
            }
            int available = reply.dataAvail();
            int status = available >= 4 ? reply.readInt() : -999;
            Integer raw = available >= 8 ? reply.readInt() : null;
            return new CollectorHelperDaemon.ReadValue(status, raw,
                available >= 8 ? null : "autoservice reply missing raw value");
        } catch (Throwable error) {
            if (error instanceof DeadObjectException) lost(current, error, "scalar_get " + address);
            else {
                System.err.println("autoservice scalar read failed address=" + address);
                error.printStackTrace(System.err);
            }
            return CollectorHelperDaemon.ReadValue.error(CollectorHelperProtocol.STATUS_READ_ERROR,
                bounded(error.getClass().getName() + ": " + String.valueOf(error.getMessage()) +
                    " address=" + address + " binding=" + current.serial));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean lost(Binding expected, Throwable cause, String stage) {
        Runnable listener;
        synchronized (this) {
            if (closed || binding != expected) return false;
            binding = null;
            episode++;
            lostAt = SystemClock.elapsedRealtime();
            attempts = 0L;
            originalFailure = cause;
            failureStage = stage;
            listener = lossListener;
        }
        detach(expected);
        diagnostics.context("autoservice_lost", failureDetail());
        System.err.println("autoservice lost " + failureDetail());
        cause.printStackTrace(System.err);
        if (listener != null) listener.run();
        return true;
    }

    @Override public void close() {
        Binding previous;
        synchronized (this) {
            closed = true;
            previous = binding;
            binding = null;
            lossListener = null;
        }
        if (previous != null) detach(previous);
    }

    private void detach(Binding previous) {
        if (!previous.linked.compareAndSet(true, false)) return;
        try { previous.binder.unlinkToDeath(previous, 0); }
        catch (RuntimeException error) {
            diagnostics.context("autoservice_unlink_failed", bounded("binding=" + previous.serial + " " + error));
            error.printStackTrace(System.err);
        }
    }

    private static String bounded(String text) {
        return text.length() <= 480 ? text : text.substring(0, 460) + " [truncated]";
    }

    private final class Binding implements IBinder.DeathRecipient {
        final IBinder binder;
        final String descriptor;
        final long serial;
        final AtomicBoolean linked = new AtomicBoolean();
        Binding(IBinder binder, String descriptor, long serial) {
            this.binder = binder;
            this.descriptor = descriptor;
            this.serial = serial;
        }
        @Override public void binderDied() {
            lost(this, new DeadObjectException("autoservice Binder death notification"), "death_recipient");
        }
    }
}
