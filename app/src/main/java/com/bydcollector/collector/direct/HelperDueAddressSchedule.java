package com.bydcollector.collector.direct;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded due index over a stable catalog; replacing a deadline removes its old bucket entry. */
final class HelperDueAddressSchedule {
    private final long[] dueByOrdinal;
    private final TreeMap<Long, LinkedHashSet<Integer>> dueOrdinals = new TreeMap<>();

    HelperDueAddressSchedule(int fieldCount, long initialDueElapsedMs) {
        if (fieldCount < 0 || initialDueElapsedMs < 0L) {
            throw new IllegalArgumentException("invalid due schedule dimensions");
        }
        dueByOrdinal = new long[fieldCount];
        for (int ordinal = 0; ordinal < fieldCount; ordinal++) {
            dueByOrdinal[ordinal] = Long.MAX_VALUE;
            schedule(ordinal, initialDueElapsedMs);
        }
    }

    synchronized void schedule(int ordinal, long dueElapsedMs) {
        if (ordinal < 0 || ordinal >= dueByOrdinal.length || dueElapsedMs < 0L) {
            throw new IllegalArgumentException("invalid due address");
        }
        long previous = dueByOrdinal[ordinal];
        if (previous == dueElapsedMs) return;
        if (previous != Long.MAX_VALUE) {
            LinkedHashSet<Integer> bucket = dueOrdinals.get(previous);
            if (bucket != null) {
                bucket.remove(ordinal);
                if (bucket.isEmpty()) dueOrdinals.remove(previous);
            }
        }
        dueByOrdinal[ordinal] = dueElapsedMs;
        if (dueElapsedMs != Long.MAX_VALUE) {
            dueOrdinals.computeIfAbsent(dueElapsedMs, ignored -> new LinkedHashSet<>()).add(ordinal);
        }
    }

    synchronized List<Integer> takeDue(long nowElapsedMs, int limit) {
        if (nowElapsedMs < 0L || limit <= 0) throw new IllegalArgumentException("invalid due selection");
        List<Integer> result = new ArrayList<>(Math.min(limit, 128));
        while (result.size() < limit && !dueOrdinals.isEmpty()) {
            Map.Entry<Long, LinkedHashSet<Integer>> first = dueOrdinals.firstEntry();
            if (first.getKey() > nowElapsedMs) break;
            LinkedHashSet<Integer> bucket = first.getValue();
            Integer ordinal = bucket.iterator().next();
            bucket.remove(ordinal);
            if (bucket.isEmpty()) dueOrdinals.pollFirstEntry();
            dueByOrdinal[ordinal] = Long.MAX_VALUE;
            result.add(ordinal);
        }
        result.sort(Integer::compareTo);
        return result;
    }

    synchronized long nextDelayMs(long nowElapsedMs, long idleCapMs) {
        if (nowElapsedMs < 0L || idleCapMs <= 0L) throw new IllegalArgumentException("invalid due delay request");
        if (dueOrdinals.isEmpty()) return idleCapMs;
        long deadline = dueOrdinals.firstKey();
        long delay = deadline <= nowElapsedMs ? 1L : deadline - nowElapsedMs;
        return Math.min(delay, idleCapMs);
    }

    synchronized int size() { return dueOrdinals.values().stream().mapToInt(java.util.Set::size).sum(); }

    int fieldCount() { return dueByOrdinal.length; }
}
