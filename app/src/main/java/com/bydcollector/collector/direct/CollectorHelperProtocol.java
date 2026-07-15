package com.bydcollector.collector.direct;

public final class CollectorHelperProtocol {
    public static final String SERVICE_NAME = "bydcollector_helper";
    public static final String PROCESS_NAME = "bydcollector_helper";
    public static final String DESCRIPTOR = "com.bydcollector.collector.direct.ICollectorHelper";
    public static final String HELPER_CLASS = "com.bydcollector.collector.direct.CollectorHelperDaemon";
    public static final String LOG_PATH = "/data/local/tmp/bydcollector_helper.log";
    public static final String LOCK_PATH = "/data/local/tmp/bydcollector_helper.lock";
    public static final int PROTOCOL_VERSION = 2;
    public static final int TX_PING = 1;
    public static final int TX_READ = 2;
    public static final int TX_READ_BATCH = 3;

    public static final int AUTO_TX_INT = 5;
    public static final int AUTO_TX_FLOAT = 7;
    public static final int MAX_BATCH_SIZE = 6100;

    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_REQUEST = -910;
    public static final int STATUS_NOT_WHITELISTED = -911;
    public static final int STATUS_READ_ERROR = -912;

    public static final int MODE_REJECTED = 0;
    public static final int MODE_NATIVE = 1;
    public static final int MODE_NATIVE_WITH_FALLBACK = 2;
    public static final int MODE_SCALAR_FALLBACK = 3;

    private CollectorHelperProtocol() {
    }
}
