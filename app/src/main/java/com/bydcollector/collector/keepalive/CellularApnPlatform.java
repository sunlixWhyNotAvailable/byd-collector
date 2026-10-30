package com.bydcollector.collector.keepalive;

import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.net.NetworkInterface;

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

    boolean screenOffEnabled() throws Exception {
        return flag(KeepAliveProtocol.KEY_KEEP_WIFI) && flag(KeepAliveProtocol.KEY_SCREEN_OFF_AFTER_RECOVERY);
    }

    long lastWakeUptime() throws Exception {
        checkActive();
        long value = KeepAliveDaemon.readLastWakeUptime(deadline - SystemClock.elapsedRealtime());
        checkActive();
        return value;
    }

    int backlightStatus() throws Exception {
        int value = (Integer) displayApi.getMethod("getPowerScreenStatus", int.class).invoke(service("display", displayApi), 0);
        checkActive();
        if (value != 0 && value != 1) throw new IOException("Unknown main backlight status=" + value);
        return value;
    }

    void backlightOff() throws Exception {
        Object display = service("display", displayApi);
        Method off = displayApi.getMethod("turnBacklightOff", int.class, long.class);
        checkActive();
        off.invoke(display, 0, SystemClock.uptimeMillis());
        checkActive();
    }

    void backlightOn() throws Exception {
        Object display = service("display", displayApi);
        Method on = displayApi.getMethod("turnBacklightOn", int.class, long.class);
        checkActive();
        on.invoke(display, 0, SystemClock.uptimeMillis());
        checkActive();
    }

    BacklightRecoveryState backlightOwner() throws Exception {
        java.nio.file.Path path = BacklightRecoveryState.PATH;
        if (java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                android.system.Os.lstat(path.toString()).st_uid != 2000)
            throw new IOException("Invalid backlight ownership UID");
        return new BacklightRecoveryState(path);
    }

    String bootId() throws Exception {
        String boot = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(KeepAliveProtocol.KERNEL_BOOT_ID_PATH)),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        if (boot.isEmpty()) throw new IOException("Kernel boot ID unavailable");
        return boot;
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

    @SuppressWarnings("deprecation")
    boolean wifiConnected() throws Exception {
        Object connectivity = service("connectivity", networkApi);
        // Read the Wi-Fi network itself, even when cellular or a VPN is the default.
        NetworkInfo info = (NetworkInfo) networkApi.getMethod("getNetworkInfo", int.class)
                .invoke(connectivity, ConnectivityManager.TYPE_WIFI);
        checkActive();
        return info != null && info.isConnected();
    }

    ApnState apn2() throws Exception {
        String apnType = property("persist.sys.byd.apn_type");
        if (!"double_apn".equals(apnType)) throw new IOException("APN2 configuration unsupported or unknown: " + apnType);
        String iface = property("net.lte.apn2.ifname");
        ApnState result = readApnInterface(iface);
        checkActive();
        if (!iface.equals(property("net.lte.apn2.ifname"))) throw new IOException("APN2 changed during observation");
        return result;
    }

    static ApnState readApnInterface(String iface) throws IOException {
        if (iface == null) throw new IOException("APN2 observation incomplete");
        if (iface.isEmpty()) return new ApnState(false, iface, "interface_name_missing");
        if (!iface.matches("[a-zA-Z0-9_.:-]{1,15}")) throw new IOException("Invalid APN2 interface name: " + iface);
        // OEM identifies APN2; kernel presence does not require coverage, an IP or Internet validation.
        boolean present = NetworkInterface.getByName(iface) != null;
        return new ApnState(present, iface, present ? "interface_present" : "interface_missing");
    }

    long wake(int reason) throws Exception {
        Object power = service("power", powerApi);
        Method wake = powerApi.getMethod("wakeUp", long.class, int.class, String.class, String.class);
        checkActive();
        long uptime = SystemClock.uptimeMillis();
        wake.invoke(power, uptime, reason, "BYDCollector:APN2Recovery", "com.android.shell");
        checkActive();
        return uptime;
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
        final String iface, reason;

        ApnState(boolean ready, String iface, String reason) {
            this.ready = ready;
            this.iface = iface;
            this.reason = reason;
        }

        @Override public String toString() { return "apn2_ready=" + ready + " interface=" + iface + " reason=" + reason; }
    }
}
