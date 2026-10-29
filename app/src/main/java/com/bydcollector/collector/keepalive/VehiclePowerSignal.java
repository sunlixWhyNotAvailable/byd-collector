package com.bydcollector.collector.keepalive;

import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/** Latest POWER transition from the existing telemetry callback; no extra vehicle subscription. */
public final class VehiclePowerSignal {
    static final String DIRECTORY = "/data/local/tmp";
    static final String NAME = "bydcollector_vehicle_power.json";
    private static String lastOwner;
    private static int lastPower = Integer.MIN_VALUE;

    private VehiclePowerSignal() { }

    public static synchronized void publish(String boot, String generation, int raw, long elapsed) throws Exception {
        if (Process.myUid() != 2000) throw new SecurityException("POWER signal requires shell UID");
        int power = raw < 0 || raw > 4 ? -1 : raw == 0 ? 0 : 1;
        String owner = boot + ":" + generation;
        if (owner.equals(lastOwner) && power == lastPower) return;
        JSONObject value = new JSONObject().put("boot", boot).put("generation", generation)
                .put("pid", Process.myPid()).put("elapsed", elapsed).put("power", power);
        Path temporary = Files.createTempFile(Paths.get(DIRECTORY), NAME, ".tmp");
        try {
            Os.chmod(temporary.toString(), 0600);
            Files.write(temporary, value.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, Paths.get(DIRECTORY, NAME),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            lastOwner = owner;
            lastPower = power;
        } catch (Exception error) {
            try { Files.deleteIfExists(temporary); }
            catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    static int read(String boot) throws Exception {
        Path path = Paths.get(DIRECTORY, NAME);
        if (Os.stat(path.toString()).st_uid != 2000 || Files.size(path) > 2048) {
            throw new IOException("Invalid POWER signal owner/size");
        }
        JSONObject value = new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        int power = value.getInt("power"), pid = value.getInt("pid");
        long elapsed = value.getLong("elapsed");
        if (!boot.equals(value.getString("boot")) || elapsed < 0 || elapsed > SystemClock.elapsedRealtime()
                || power < -1 || power > 1 || pid <= 0 || value.getString("generation").isEmpty()) {
            throw new IOException("Invalid/stale POWER signal");
        }
        Os.kill(pid, 0);
        return power;
    }
}
