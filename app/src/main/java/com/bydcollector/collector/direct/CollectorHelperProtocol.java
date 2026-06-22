package com.bydcollector.collector.direct;

public final class CollectorHelperProtocol {
    public static final String SERVICE_NAME = "bydcollector_helper";
    public static final String PROCESS_NAME = "bydcollector_helper";
    public static final String DESCRIPTOR = "com.bydcollector.collector.direct.ICollectorHelper";
    public static final String HELPER_CLASS = "com.bydcollector.collector.direct.CollectorHelperDaemon";
    public static final String LOG_PATH = "/data/local/tmp/bydcollector_helper.log";
    public static final String LOCK_PATH = "/data/local/tmp/bydcollector_helper.lock";
    public static final int TX_PING = 1;
    public static final int TX_READ = 2;

    private CollectorHelperProtocol() {
    }
}
