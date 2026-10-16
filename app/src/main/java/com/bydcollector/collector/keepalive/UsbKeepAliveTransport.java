package com.bydcollector.collector.keepalive;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UsbKeepAliveTransport {
    public static final int INVALID_VALUE = -1;
    public static final String READ_ACC_COMMAND =
            "service call autoservice 5 i32 1005 i32 -1728053193";
    public static final String READ_BODYWORK_POWER_LEVEL_COMMAND =
            "service call autoservice 5 i32 1001 i32 315621418";
    public static final String USB_DATA_PORT_ON_COMMAND =
            "service call DevOperatorService 1 s16 /sys/devices/platform/soc/a600000.ssusb/sentry_mode i32 0 i32 0 i32 $$";

    private static final Pattern PARCEL_REPLY = Pattern.compile(
            "\\A\\s*Result:\\s*Parcel\\(\\s*(?:0x00000000:\\s*)?([0-9a-fA-F]{8})\\s+([0-9a-fA-F]{8})\\s+'[^'\\r\\n]*'\\s*\\)\\s*\\z"
    );

    private UsbKeepAliveTransport() {
    }

    public static Result parseAccReply(String rawReply) {
        return parse(rawReply, 1, "value_out_of_range");
    }

    public static Result parseBodyworkPowerLevelReply(String rawReply) {
        return parse(rawReply, 4, "value_out_of_range");
    }

    // A successful Parcel confirms the service reply only; it cannot confirm physical VBUS.
    public static Result parseUsbOnReply(String rawReply) {
        return parse(rawReply, 0, "nonzero_service_result");
    }

    private static Result parse(String rawReply, int maximumValue, String invalidResultError) {
        String raw = rawReply == null ? "" : rawReply;
        Matcher parcel = PARCEL_REPLY.matcher(raw);
        if (!parcel.matches()) {
            return new Result(false, INVALID_VALUE, INVALID_VALUE, INVALID_VALUE, "malformed_parcel", raw);
        }

        long exceptionCode = Long.parseLong(parcel.group(1), 16);
        long returnedInt = Long.parseLong(parcel.group(2), 16);
        if (exceptionCode != 0) {
            return new Result(false, INVALID_VALUE, exceptionCode, returnedInt, "binder_exception", raw);
        }
        if (returnedInt > maximumValue) {
            return new Result(false, INVALID_VALUE, exceptionCode, returnedInt, invalidResultError, raw);
        }
        return new Result(true, (int) returnedInt, exceptionCode, returnedInt, "", raw);
    }

    public static final class Result {
        public final boolean valid;
        // Invalid states use -1 so they can never be mistaken for OFF (0).
        public final int value;
        public final long exceptionCode;
        public final long returnedInt;
        public final String error;
        public final String rawReply;

        private Result(boolean valid, int value, long exceptionCode, long returnedInt,
                       String error, String rawReply) {
            this.valid = valid;
            this.value = value;
            this.exceptionCode = exceptionCode;
            this.returnedInt = returnedInt;
            this.error = error;
            this.rawReply = rawReply;
        }
    }
}
