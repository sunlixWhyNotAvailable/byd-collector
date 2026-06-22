package com.bydcollector.collector.keepalive;

public final class KeepAliveProtocol {
    public static final String PROCESS_NAME = "bydcollector_keepalive";
    public static final String DAEMON_CLASS = "com.bydcollector.collector.keepalive.KeepAliveDaemon";
    public static final String LOG_PATH = "/data/local/tmp/bydcollector_keepalive.log";
    public static final String LOCK_PATH = "/data/local/tmp/bydcollector_keepalive.lock";
    public static final String KEY_KEEP_WIFI = "bydcollector_keep_wifi";
    public static final String KEY_KEEP_MOBILE_DATA = "bydcollector_keep_mobile_data";
    public static final String KEY_KEEP_BLUETOOTH = "bydcollector_keep_bluetooth";
    public static final String KEY_RECOVER_COLLECTOR_SERVICE = "bydcollector_recover_collector_service";
    public static final long LOOP_INTERVAL_MS = 30_000L;

    private KeepAliveProtocol() {
    }
}
