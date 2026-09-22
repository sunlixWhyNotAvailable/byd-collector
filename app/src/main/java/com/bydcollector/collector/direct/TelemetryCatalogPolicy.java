package com.bydcollector.collector.direct;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Shared read-only runtime selection policy for APP and app_process helper catalogs. */
public final class TelemetryCatalogPolicy {
    public static final int SECONDARY_DEFINITION_COUNT = 23_083;
    public static final int SECONDARY_ACTIVE_COUNT = 23_069;
    public static final int SECONDARY_ACTIVE_KEY_COUNT = 11_588;
    public static final String SECONDARY_ACTIVE_FINGERPRINT =
        "ED379DFBCE0C07CE972D07D6A68EB28F0CAAC2692A2053938997DF4AA9EC694F";

    private static final int[][] EXCLUDED_DEVICE_FIDS = {
        { 1061, -1728053216 }, // BIGDATA_DYNAMIC_DATA_CALLBACK
        { 1039, -1728053217 }, // GB_DYNAMIC_DATA_CALLBACK
        { 1034, -1728053215 }, // YUN_DYNAMIC_DATA_CALLBACK
        { 1033, -1728052891 }, // MQTT_DYNAMIC_DATA_CALLBACK
        { 1043, -1728052722 }, // SENSOR_AX_AY_OFFSET
        { 1023, -1728052840 }, // SETTING_11F_CONTENT_TIMESTAMP
        { 1001, -1728052203 }  // BODYWORK_MCU_CAN_NETWORK_STATUS
    };

    private TelemetryCatalogPolicy() { }

    public static boolean isRuntimeSelected(int device, int fid) {
        for (int[] excluded : EXCLUDED_DEVICE_FIDS) {
            if (excluded[0] == device && excluded[1] == fid) return false;
        }
        return true;
    }

    public static MessageDigest newFingerprint() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public static void addToFingerprint(MessageDigest digest, int device, int fid, int tx) {
        digest.update((device + ":" + fid + ":" + tx + "\n").getBytes(StandardCharsets.UTF_8));
    }

    public static String finishFingerprint(MessageDigest digest) {
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02X", value & 0xff));
        return result.toString();
    }
}
