package com.bydcollector.collector.direct;

import java.util.Objects;

//stable identity used to make helper-spool replay idempotent in the app database
public final class TelemetryWorkerSampleIdentity {
    public final String bootId;
    public final String helperGeneration;
    public final long pollSequence;

    public TelemetryWorkerSampleIdentity(String bootId, String helperGeneration, long pollSequence) {
        this.bootId = requireIdentifier(bootId, "bootId");
        this.helperGeneration = requireIdentifier(helperGeneration, "helperGeneration");
        if (pollSequence < 0) throw new IllegalArgumentException("pollSequence must be non-negative");
        this.pollSequence = pollSequence;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TelemetryWorkerSampleIdentity)) return false;
        TelemetryWorkerSampleIdentity that = (TelemetryWorkerSampleIdentity) other;
        return pollSequence == that.pollSequence &&
            bootId.equals(that.bootId) &&
            helperGeneration.equals(that.helperGeneration);
    }

    @Override public int hashCode() {
        return Objects.hash(bootId, helperGeneration, pollSequence);
    }

    @Override public String toString() {
        return bootId + ":" + helperGeneration + ":" + pollSequence;
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
