package com.bydcollector.collector.keepalive;

import org.json.JSONObject;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/** Only a confirmed OFF grants ownership; retained across APP/helper replacement. */
final class BacklightRecoveryState {
    static final Path PATH = Paths.get("/data/local/tmp/bydcollector_backlight_owner.json");
    private final Path path;

    BacklightRecoveryState(Path path) { this.path = path; }

    boolean owned(String boot) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1024)
            throw new IOException("Invalid backlight ownership file");
        JSONObject record = new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        if (record.getInt("schema") != 1 || record.getLong("wake_uptime") < 0 ||
                record.getString("boot").isEmpty()) throw new IOException("Invalid backlight ownership record");
        if (!boot.equals(record.getString("boot"))) { clear(); return false; }
        return true;
    }

    void confirmOff(String boot, long wakeUptime) throws Exception {
        if (boot.isEmpty() || wakeUptime < 0) throw new IllegalArgumentException("Invalid backlight owner");
        byte[] bytes = new JSONObject().put("schema", 1).put("boot", boot)
                .put("wake_uptime", wakeUptime).toString().getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(path.getParent(), "backlight-owner-", ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary.toFile())) {
                out.write(bytes);
                out.getFD().sync();
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) {
            try { Files.deleteIfExists(temporary); }
            catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    void clear() throws IOException { Files.deleteIfExists(path); }
}
