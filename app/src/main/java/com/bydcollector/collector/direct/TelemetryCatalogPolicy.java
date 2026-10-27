package com.bydcollector.collector.direct;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Shared read-only runtime selection policy for APP and app_process helper catalogs. */
public final class TelemetryCatalogPolicy {
    public static final int SECONDARY_DEFINITION_COUNT = 23_083;
    public static final int SECONDARY_ACTIVE_COUNT = 23_020;
    public static final int SECONDARY_ACTIVE_KEY_COUNT = 11_569;
    public static final String SECONDARY_ACTIVE_FINGERPRINT =
        "1E774AB0A7F8228663C3B63397FE21A5B5891AA513FAB3967D3C472C85B103F7";
    public static final int SECONDARY_PREVIOUS_ACTIVE_COUNT = 23_069;
    public static final int SECONDARY_PREVIOUS_ACTIVE_KEY_COUNT = 11_588;
    public static final String SECONDARY_PREVIOUS_ACTIVE_FINGERPRINT =
        "ED379DFBCE0C07CE972D07D6A68EB28F0CAAC2692A2053938997DF4AA9EC694F";
    public static final int CANFD_SECONDARY_WINDOW_DEVICE = 1001;
    public static final int CANFD_SECONDARY_WINDOW_FID = 1267728400;
    public static final int CANFD_SECONDARY_WINDOW_TX = 5;

    private static final int[][] EXCLUDED_DEVICE_FIDS = {
        { 1061, -1728053216 }, // BIGDATA_DYNAMIC_DATA_CALLBACK
        { 1039, -1728053217 }, // GB_DYNAMIC_DATA_CALLBACK
        { 1034, -1728053215 }, // YUN_DYNAMIC_DATA_CALLBACK
        { 1033, -1728052891 }, // MQTT_DYNAMIC_DATA_CALLBACK
        { 1043, -1728052722 }, // SENSOR_AX_AY_OFFSET
        { 1023, -1728052840 }, // SETTING_11F_CONTENT_TIMESTAMP
        { 1001, -1728052203 }  // BODYWORK_MCU_CAN_NETWORK_STATUS
    };

    // Exact SL07 CAN-FD GET-address to callback-owner/type mappings; never alias by FID alone.
    private static final int[][] CANFD_SECONDARY_CALLBACKS = {
        { 1001, 1038, 305135616, 5 },
        { 1001, 1023, 562036736, 5 },
        { 1001, 1016, 1020264448, 5 },
        { 1001, 1000, 1077936128, 5 },
        { 1001, 1038, 1098907648, 5 },
        { 1001, 1031, 1329598464, 5 },
        { 1001, 1008, 1331691520, 5 },
        { 1007, 1014, 1032863794, 7 },
        { 1009, 1007, 842006536, 5 },
        { 1009, 1007, 842006544, 5 },
        { 1009, 1007, 842006552, 7 },
        { 1009, 1007, 842006564, 5 },
        { 1009, 1007, 842006568, 5 },
        { 1009, 1007, 842006576, 5 },
        { 1009, 1007, 842006584, 5 },
        { 1009, 1001, 1105199104, 5 },
        { 1023, 1001, 1267728400, 5 },
        { 1032, 1007, 89128968, 5 },
        { 1038, 1023, 1072693263, 5 }
    };

    private TelemetryCatalogPolicy() { }

    public static boolean isRuntimeSelected(int device, int fid) {
        for (int[] excluded : EXCLUDED_DEVICE_FIDS) {
            if (excluded[0] == device && excluded[1] == fid) return false;
        }
        return true;
    }

    public static boolean isPreviousRuntimeSelected(int device, int fid) {
        return isRuntimeSelected(device, fid);
    }

    public static boolean isSecondaryRuntimeSelected(int device, int fid, int tx) {
        if (!isRuntimeSelected(device, fid)) return false;
        for (int[] mapping : CANFD_SECONDARY_CALLBACKS) {
            if (mapping[2] == fid && (mapping[0] == device || mapping[1] == device)) {
                return mapping[1] == device && mapping[3] == tx;
            }
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
