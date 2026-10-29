package com.bydcollector.collector.keepalive;

import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Shell-only adapter for the SL07 logical-display/APN2 path. Never puts the display to sleep. */
final class CellularApnPlatform {
    private final long deadline;
    private final Class<?> powerApi = Class.forName("android.os.IPowerManager");
    private final Class<?> displayApi = Class.forName("android.hardware.display.IDisplayManager");
    private final Class<?> networkApi = Class.forName("android.net.IConnectivityManager");
    private final Method propertyGet = Class.forName("android.os.SystemProperties").getMethod("get", String.class);

    CellularApnPlatform(long deadline) throws Exception {
        if (Process.myUid() != 2000) {
            throw new SecurityException("APN2 recovery requires shell UID 2000");
        }
        this.deadline = deadline;
    }

    void checkActive() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() >= deadline) {
            throw new InterruptedIOException("APN2 recovery check cancelled or deadline expired");
        }
    }

    private IBinder binder(String name) throws Exception {
        checkActive();
        IBinder result = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
        checkActive();
        if (result == null) throw new IOException("Service unavailable: " + name);
        return result;
    }

    private Object service(String name, Class<?> api) throws Exception {
        return Class.forName(api.getName() + "$Stub").getMethod("asInterface", IBinder.class)
                .invoke(null, binder(name));
    }

    private String property(String key) throws Exception {
        checkActive();
        String result = (String) propertyGet.invoke(null, key);
        checkActive();
        return result;
    }

    private boolean flag(String key) throws Exception {
        checkActive();
        boolean value = KeepAliveDaemon.readGlobalFlag(key, deadline - SystemClock.elapsedRealtime());
        checkActive();
        return value;
    }

    String disabledReason() throws Exception {
        if (!flag(KeepAliveProtocol.KEY_KEEP_MOBILE_DATA)) return "feature_disabled";
        if (flag(KeepAliveProtocol.KEY_USER_SHUTDOWN)) return "user_shutdown";
        return null;
    }

    int vehiclePower() throws Exception {
        IBinder service = binder("autoservice");
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(service.getInterfaceDescriptor());
            request.writeInt(1001);
            request.writeInt(315621418);
            checkActive();
            if (!service.transact(5, request, reply, 0)) throw new IOException("Vehicle power transaction unavailable");
            reply.readException();
            if (reply.dataAvail() != 4) throw new IOException("Unexpected vehicle power reply bytes=" + reply.dataAvail());
            int value = reply.readInt();
            if (value < 0 || value > 4) throw new IOException("Unknown vehicle power value=" + value);
            checkActive();
            return value;
        } finally {
            reply.recycle();
            request.recycle();
        }
    }

    DisplayState display() throws Exception {
        checkActive();
        boolean interactive = (Boolean) powerApi.getMethod("isInteractive").invoke(service("power", powerApi));
        checkActive();
        Object info = displayApi.getMethod("getDisplayInfo", int.class).invoke(service("display", displayApi), 0);
        checkActive();
        if (info == null) throw new IOException("Main display information unavailable");
        return new DisplayState(interactive, info.getClass().getField("state").getInt(info),
                property("sys.accanim.status"));
    }

    ApnState apn2() throws Exception {
        String apnType = property("persist.sys.byd.apn_type");
        if (!"double_apn".equals(apnType)) throw new IOException("APN2 configuration unsupported or unknown: " + apnType);
        String iface = property("net.lte.apn2.ifname"), ip = property("net.lte.apn2.ip");
        List<LinkProperties> links = new ArrayList<>();
        if (!iface.isEmpty() && !ip.isEmpty()) {
            Object connectivity = service("connectivity", networkApi);
            Network[] networks = (Network[]) networkApi.getMethod("getAllNetworks").invoke(connectivity);
            checkActive();
            if (networks == null) throw new IOException("Connectivity service returned null networks");
            for (Network network : networks) {
                checkActive();
                links.add((LinkProperties) networkApi.getMethod("getLinkProperties", Network.class)
                        .invoke(connectivity, network));
            }
        }
        checkActive();
        return assessApn2(iface, ip, links, property("net.lte.apn2.ifname"), property("net.lte.apn2.ip"));
    }

    static ApnState assessApn2(String iface, String ip, List<LinkProperties> links,
                              String afterIface, String afterIp) throws IOException {
        if (iface == null || ip == null || afterIface == null || afterIp == null || links == null) {
            throw new IOException("APN2 observation incomplete");
        }
        if (!iface.equals(afterIface) || !ip.equals(afterIp)) throw new IOException("APN2 changed during observation");
        if (iface.isEmpty() || ip.isEmpty()) return new ApnState(false, iface, ip, "interface_or_ip_missing");
        if (!iface.matches("rmnet_data\\d+") || !android.util.Patterns.IP_ADDRESS.matcher(ip).matches()) {
            throw new IOException("Unsupported APN2 interface/IP value: " + iface + "/" + ip);
        }
        for (LinkProperties link : links) {
            if (link == null || !iface.equals(link.getInterfaceName())) continue;
            for (LinkAddress address : link.getLinkAddresses()) {
                if (ip.equals(address.getAddress().getHostAddress())) return new ApnState(true, iface, ip, "network_matched");
            }
        }
        return new ApnState(false, iface, ip, "no_matching_current_network");
    }

    void wake(int reason) throws Exception {
        Object power = service("power", powerApi);
        Method wake = powerApi.getMethod("wakeUp", long.class, int.class, String.class, String.class);
        checkActive();
        wake.invoke(power, SystemClock.uptimeMillis(), reason, "BYDCollector:APN2Recovery", "com.android.shell");
        checkActive();
    }

    static final class DisplayState {
        final boolean interactive;
        final int state;
        final String blackCover;

        DisplayState(boolean interactive, int state, String blackCover) {
            this.interactive = interactive;
            this.state = state;
            this.blackCover = blackCover;
        }

        boolean blackAsleep() { return !interactive && state == 1 && "1".equals(blackCover); }
        boolean blackAwake() { return interactive && state == 2 && "1".equals(blackCover); }

        @Override public String toString() {
            return "interactive=" + interactive + " display=" + state + " black_cover=" + blackCover;
        }
    }

    static final class ApnState {
        final boolean ready;
        final String iface, ip, reason;

        ApnState(boolean ready, String iface, String ip, String reason) {
            this.ready = ready;
            this.iface = iface;
            this.ip = ip;
            this.reason = reason;
        }

        @Override public String toString() { return "apn2_ready=" + ready + " interface=" + iface + " reason=" + reason; }
    }
}
