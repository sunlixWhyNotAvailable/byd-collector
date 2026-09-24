package com.bydcollector.collector.direct;

public final class CollectorHelperProtocol {
    public static final String SERVICE_NAME = "bydcollector_helper";
    public static final String PROCESS_NAME = "bydcollector_helper";
    public static final String DESCRIPTOR = "com.bydcollector.collector.direct.ICollectorHelper";
    public static final String HELPER_CLASS = "com.bydcollector.collector.direct.CollectorHelperDaemon";
    public static final String SPOOL_MODE_ARG = "spool";
    public static final String LOG_PATH = "/data/local/tmp/bydcollector_helper.log";
    public static final String LOCK_PATH = "/data/local/tmp/bydcollector_helper.lock";
    public static final int PROTOCOL_VERSION = 14;
    public static final int TX_PING = 1;
    public static final int TX_READ = 2;
    public static final int TX_READ_BATCH = 3;
    public static final int TX_WORKER_PENDING = 4;
    public static final int TX_WORKER_ACK = 5;
    public static final int TX_STOP_OWNER = 6;
    public static final int TX_SECONDARY_PENDING_PAGE = 7;
    public static final int TX_SECONDARY_ACK = 8;
    public static final int TX_SECONDARY_QUARANTINE = 9;
    public static final int TX_STREAM_CONTROL = 10;
    public static final int TX_SECONDARY_READ_BATCH = 11;
    public static final int TX_SECONDARY_STATUS = 12;
    public static final int TX_CALLBACK_PENDING_PAGE = 13;
    public static final int TX_CALLBACK_ACK = 14;
    public static final int TX_CALLBACK_STATUS = 15;
    public static final int TX_CALLBACK_QUARANTINE = 16;
    public static final int TX_KPI_READ_BATCH = 17;
    public static final int STREAM_MAIN = 1;
    public static final int STREAM_SECONDARY = 2;
    public static final int CONTROL_CLAIM = 1;
    public static final int CONTROL_SET_DESIRED = 2;
    public static final int CONTROL_RENEW = 3;
    public static final int CONTROL_PAUSE_FENCE = 4;
    public static final int CONTROL_RESUME = 5;
    public static final int CONTROL_SET_AUTONOMY = 6;
    public static final int SECONDARY_CHUNK_SIZE = 128;
    public static final int AUTO_TX_INT = 5;
    public static final int AUTO_TX_FLOAT = 7;
    public static final int MAX_BATCH_SIZE = 23_096;
    public static final int MAX_PENDING_WORKER_SAMPLES = 100;
    public static final int MAX_WORKER_FIELD_COUNT = 128;
    public static final int OWNER_MODE_APP = 0;
    public static final int OWNER_MODE_APP_GAP_SPOOL = 1;

    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_REQUEST = -910;
    public static final int STATUS_NOT_WHITELISTED = -911;
    public static final int STATUS_READ_ERROR = -912;
    public static final int STATUS_SPOOL_UNAVAILABLE = -913;
    public static final int STATUS_SAMPLE_NOT_FOUND = -914;
    public static final int STATUS_REPLAY_PENDING = -915;
    public static final int STATUS_STALE_TOKEN = -916;
    public static final int STATUS_LEASE_EXPIRED = -917;

    public static final int MODE_REJECTED = 0;
    public static final int MODE_NATIVE = 1;
    public static final int MODE_NATIVE_WITH_FALLBACK = 2;
    public static final int MODE_SCALAR_FALLBACK = 3;

    private CollectorHelperProtocol() {
    }
}
