package com.bydcollector.collector.keepalive;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Passive shell-owned network observation. Only hints the existing guarded APN2 checker. */
final class CellularNetworkObserver implements AutoCloseable {
    private final BiConsumer<Integer, String> lost;
    private final BiConsumer<String, String> log;
    private final HandlerThread thread = new HandlerThread("byd-apn2-networks");
    private final Map<Network, Link> links = new HashMap<>();
    private volatile boolean closed;
    private Handler handler;
    private ConnectivityManager manager;
    private ConnectivityManager.NetworkCallback callback;
    private boolean registered;

    CellularNetworkObserver(BiConsumer<Integer, String> lost, BiConsumer<String, String> log) {
        this.lost = lost;
        this.log = log;
    }

    synchronized void start() {
        if (closed || handler != null) return;
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::register);
    }

    private void register() {
        if (closed || registered) return;
        try {
            Context context = KeepAliveDaemon.shellContext();
            if (Process.myUid() != 2000 || context.getApplicationInfo().uid != 2000
                    || !"com.android.shell".equals(context.getOpPackageName()))
                throw new SecurityException("Network observer requires shell attribution");
            manager = context.getSystemService(ConnectivityManager.class);
            if (manager == null) throw new IOException("ConnectivityManager unavailable");
            callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (!closed) links.computeIfAbsent(network, key -> new Link());
                }

                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    if (!closed) links.computeIfAbsent(network, key -> new Link()).wifi =
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                }

                @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                    if (closed) return;
                    Link link = links.computeIfAbsent(network, key -> new Link());
                    try {
                        String name = properties.getInterfaceName();
                        String apn = apnInterface();
                        boolean isApn = !apn.isEmpty() && apn.equals(name);
                        // Retain APN identity if OEM clears its property before delivering onLost.
                        if (link.apn && !java.util.Objects.equals(link.iface, name))
                            lost.accept(CellularApnSchedule.APN_LOST, "network=" + network + " interface=" + link.iface
                                    + " source=link_properties");
                        link.apn = isApn || (link.apn && java.util.Objects.equals(link.iface, name));
                        link.iface = name;
                        log.accept("network_link", "network=" + network + " interface=" + name
                                + " wifi=" + link.wifi + " apn2=" + link.apn);
                    } catch (Exception error) {
                        log.accept("network_error", KeepAliveDaemon.diagnosticDetail(error, "stage=network_link network=" + network));
                    }
                }

                @Override public void onLost(Network network) {
                    if (closed) return;
                    Link link = links.remove(network);
                    if (link == null) return;
                    String detail = "network=" + network + " interface=" + link.iface + " source=onLost";
                    if (link.apn) lost.accept(CellularApnSchedule.APN_LOST, detail);
                    if (link.wifi) lost.accept(CellularApnSchedule.WIFI_LOST, detail);
                }
            };
            // No default-network, Internet, VPN or unrestricted-network requirement.
            manager.registerNetworkCallback(new NetworkRequest.Builder().clearCapabilities().build(), callback, handler);
            registered = true;
            log.accept("network_registered", "uid=" + Process.myUid() + " package=" + context.getOpPackageName());
        } catch (Exception error) {
            log.accept("network_registration_error", KeepAliveDaemon.diagnosticDetail(error, "stage=register_network_callback"));
            if (!closed) handler.postDelayed(this::register, CellularApnSchedule.NORMAL_INTERVAL_MS);
        } finally {
            if (closed) unregister();
        }
    }

    private static String apnInterface() throws Exception {
        String name = (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class).invoke(null, "net.lte.apn2.ifname");
        if (name == null || (!name.isEmpty() && !name.matches("[a-zA-Z0-9_.:-]{1,15}")))
            throw new IOException("Invalid OEM APN2 interface name");
        return name;
    }

    private void unregister() {
        try {
            if (registered) {
                manager.unregisterNetworkCallback(callback);
                log.accept("network_unregistered", "uid=" + Process.myUid());
            }
        } catch (Exception error) {
            log.accept("network_unregister_error", KeepAliveDaemon.diagnosticDetail(error, "stage=unregister_network_callback"));
        } finally {
            registered = false;
            links.clear();
            thread.quitSafely();
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler.post(this::unregister);
        }
    }

    private static final class Link {
        String iface;
        boolean wifi, apn;
    }
}
