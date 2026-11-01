package com.bydcollector.collector.keepalive;

import android.os.Process;
import android.os.SystemClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real shell observer. Host toggles emulator Wi-Fi; no fabricated Network or Binder results. */
public final class CellularNetworkObserverProbe {
    public static void main(String[] args) {
        AtomicInteger losses = new AtomicInteger();
        CountDownLatch lost = new CountDownLatch(1), registered = new CountDownLatch(1), closed = new CountDownLatch(1);
        try (CellularNetworkObserver observer = new CellularNetworkObserver((reason, detail) -> {
            if (reason == CellularApnSchedule.WIFI_LOST) {
                losses.incrementAndGet();
                System.out.println("REAL_WIFI_LOSS uid=" + Process.myUid() + " calling_uid=" + android.os.Binder.getCallingUid()
                        + " elapsed_ms=" + SystemClock.elapsedRealtime() + " " + detail);
                lost.countDown();
            }
        }, (event, detail) -> {
            System.out.println(event + " " + detail);
            if (event.equals("network_registered")) registered.countDown();
            if (event.equals("network_unregistered")) closed.countDown();
        })) {
            if (Process.myUid() != 2000) throw new AssertionError("Expected actual shell UID");
            observer.start();
            if (!registered.await(20, TimeUnit.SECONDS)) throw new AssertionError("Observer registration not confirmed");
            System.out.println("READY_FOR_WIFI_DISCONNECT");
            if (!lost.await(60, TimeUnit.SECONDS)) throw new AssertionError("No real Wi-Fi loss callback");
            observer.close();
            if (!closed.await(5, TimeUnit.SECONDS)) throw new AssertionError("Observer cleanup not confirmed");
            System.out.println("CLOSED_VERIFY_NO_MORE_CALLBACKS");
            Thread.sleep(20_000);
            if (losses.get() != 1) throw new AssertionError("Unexpected callbacks after close: " + losses.get());
            System.out.println("PASS real_shell_wifi_loss_and_unregistration");
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
        System.exit(0);
    }
}
