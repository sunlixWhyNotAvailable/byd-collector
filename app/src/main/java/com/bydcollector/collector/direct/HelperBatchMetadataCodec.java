package com.bydcollector.collector.direct;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compact, validated metadata shared by the helper's v18 batch replies and APP reader. */
public final class HelperBatchMetadataCodec {
    public static final int MAX_ERROR_DETAIL_BYTES = 32 * 1024;
    private static final int MAX_ERROR_TEXT_CHARS = 512;

    private HelperBatchMetadataCodec() { }

    public static byte[] encodeIndices(int[] indices) {
        if (indices == null) throw new IllegalArgumentException("indices are required");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        for (int index : indices) {
            if (index <= previous) throw new IllegalArgumentException("indices must be strictly increasing");
            writeVarInt(output, index - previous);
            previous = index;
        }
        return output.toByteArray();
    }

    public static int[] decodeIndices(byte[] encoded, int count, int requestSize) {
        if (encoded == null || count < 0 || requestSize < 0 || count > requestSize) {
            throw new IllegalArgumentException("invalid index metadata");
        }
        int[] result = new int[count];
        int offset = 0;
        int previous = -1;
        for (int i = 0; i < count; i++) {
            VarInt decoded = readVarInt(encoded, offset);
            offset = decoded.nextOffset;
            if (decoded.value <= 0 || (long) decoded.value > (long) Integer.MAX_VALUE - previous) {
                throw new IllegalArgumentException("invalid index delta");
            }
            int index = previous + decoded.value;
            if (index < 0 || index >= requestSize) throw new IllegalArgumentException("result index out of range");
            result[i] = index;
            previous = index;
        }
        if (offset != encoded.length) throw new IllegalArgumentException("trailing index metadata");
        return result;
    }

    public static EncodedErrors encodeErrors(String[] errors) {
        if (errors == null) throw new IllegalArgumentException("errors are required");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        boolean truncated = false;
        for (int index = 0; index < errors.length; index++) {
            String error = errors[index];
            if (error == null) continue;
            if (error.length() > MAX_ERROR_TEXT_CHARS) truncated = true;
            String bounded = error.length() <= MAX_ERROR_TEXT_CHARS
                ? error : error.substring(0, MAX_ERROR_TEXT_CHARS - 14) + "...[truncated]";
            byte[] text = bounded.getBytes(StandardCharsets.UTF_8);
            int needed = varIntSize(index - previous) + varIntSize(text.length) + text.length;
            if (needed > MAX_ERROR_DETAIL_BYTES - output.size()) {
                truncated = true;
                break;
            }
            writeVarInt(output, index - previous);
            writeVarInt(output, text.length);
            output.write(text, 0, text.length);
            previous = index;
        }
        return new EncodedErrors(output.toByteArray(), truncated);
    }

    public static Map<Integer, String> decodeErrors(byte[] encoded, int resultCount) {
        if (encoded == null || resultCount < 0 || encoded.length > MAX_ERROR_DETAIL_BYTES) {
            throw new IllegalArgumentException("invalid error metadata");
        }
        Map<Integer, String> result = new LinkedHashMap<>();
        int offset = 0;
        int previous = -1;
        while (offset < encoded.length) {
            VarInt indexDelta = readVarInt(encoded, offset);
            offset = indexDelta.nextOffset;
            if (indexDelta.value <= 0 || (long) indexDelta.value > (long) Integer.MAX_VALUE - previous) {
                throw new IllegalArgumentException("invalid error index delta");
            }
            int index = previous + indexDelta.value;
            if (index < 0 || index >= resultCount) throw new IllegalArgumentException("error index out of range");
            VarInt byteLength = readVarInt(encoded, offset);
            offset = byteLength.nextOffset;
            if (byteLength.value < 0 || byteLength.value > encoded.length - offset) {
                throw new IllegalArgumentException("invalid error text length");
            }
            String text = decodeUtf8(encoded, offset, byteLength.value);
            offset += byteLength.value;
            if (result.put(index, text) != null) throw new IllegalArgumentException("duplicate error index");
            previous = index;
        }
        return result;
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("invalid UTF-8 error metadata", error);
        }
    }

    private static int varIntSize(int value) {
        if (value < 0) throw new IllegalArgumentException("negative varint");
        int size = 1;
        while ((value & ~0x7f) != 0) { value >>>= 7; size++; }
        return size;
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        if (value < 0) throw new IllegalArgumentException("negative varint");
        while ((value & ~0x7f) != 0) {
            output.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    private static VarInt readVarInt(byte[] bytes, int offset) {
        long value = 0L;
        int shift = 0;
        int current = offset;
        while (current < bytes.length && shift <= 28) {
            int next = bytes[current++] & 0xff;
            value |= (long) (next & 0x7f) << shift;
            if ((next & 0x80) == 0) {
                if (current - offset > 1 && (next & 0x7f) == 0) {
                    throw new IllegalArgumentException("non-canonical varint");
                }
                if (value > Integer.MAX_VALUE) throw new IllegalArgumentException("varint overflow");
                return new VarInt((int) value, current);
            }
            shift += 7;
        }
        throw new IllegalArgumentException("truncated or oversized varint");
    }

    public static final class EncodedErrors {
        public final byte[] bytes;
        public final boolean truncated;

        private EncodedErrors(byte[] bytes, boolean truncated) {
            this.bytes = bytes;
            this.truncated = truncated;
        }
    }

    private static final class VarInt {
        final int value;
        final int nextOffset;
        VarInt(int value, int nextOffset) { this.value = value; this.nextOffset = nextOffset; }
    }
}
