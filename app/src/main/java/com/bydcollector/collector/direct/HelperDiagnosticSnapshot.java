package com.bydcollector.collector.direct;

import org.json.JSONObject;

//Immutable helper/spool diagnostic state. Raw telemetry stays in its own spool.
final class HelperDiagnosticSnapshot {
    final String bootId;
    final int pid;
    final String helperGeneration;
    final long intervalStartedWallMs;
    final long intervalStartedElapsedMs;
    final Long currentBytes;
    final Integer pendingReadyRecords;
    final long capBytes;
    final Long observationPeakBytes;
    final Long historicalPeakBytes;
    final long successfulAppends;
    final long duplicateRefusals;
    final long capRefusals;
    final long capSkippedPollCycles;
    final long persistenceFailures;
    final long ackReleasedRecords;
    final long ackReleasedBytes;
    final long ackNotFound;
    final long ackFailures;
    final long quarantinedRecords;
    final long diagnosticQueueDrops;
    final long diagnosticDiskFailures;
    final String lastError;
    final long repeatedErrorCount;
    final String collectionMode;
    final boolean capReached;
    final int callbackAcceptedNativeKeys;
    final int callbackFailedNativeKeys;
    final int callbackPromotedKeys;
    final int callbackPollKeys;
    final int callbackFallbackKeys;
    final long callbacksReceived;
    final long callbackMainQueueBytes;
    final long callbackMainQueueOldestAgeMs;
    final long callbackMainQueueHighWaterBytes;
    final long callbackSecondaryQueueBytes;
    final long callbackSecondaryQueueOldestAgeMs;
    final long callbackSecondaryQueueHighWaterBytes;
    final long callbackQueueLossCount;
    final String callbackRetryReason;

    HelperDiagnosticSnapshot(
        String bootId,
        int pid,
        String helperGeneration,
        long intervalStartedWallMs,
        long intervalStartedElapsedMs,
        Long currentBytes,
        Integer pendingReadyRecords,
        long capBytes,
        Long observationPeakBytes,
        Long historicalPeakBytes,
        long successfulAppends,
        long duplicateRefusals,
        long capRefusals,
        long capSkippedPollCycles,
        long persistenceFailures,
        long ackReleasedRecords,
        long ackReleasedBytes,
        long ackNotFound,
        long ackFailures,
        long quarantinedRecords,
        long diagnosticQueueDrops,
        long diagnosticDiskFailures,
        String lastError,
        long repeatedErrorCount,
        String collectionMode,
        boolean capReached,
        int callbackAcceptedNativeKeys,
        int callbackFailedNativeKeys,
        int callbackPromotedKeys,
        int callbackPollKeys,
        int callbackFallbackKeys,
        long callbacksReceived,
        long callbackMainQueueBytes,
        long callbackMainQueueOldestAgeMs,
        long callbackMainQueueHighWaterBytes,
        long callbackSecondaryQueueBytes,
        long callbackSecondaryQueueOldestAgeMs,
        long callbackSecondaryQueueHighWaterBytes,
        long callbackQueueLossCount,
        String callbackRetryReason
    ) {
        this.bootId = bootId;
        this.pid = pid;
        this.helperGeneration = helperGeneration;
        this.intervalStartedWallMs = intervalStartedWallMs;
        this.intervalStartedElapsedMs = intervalStartedElapsedMs;
        this.currentBytes = currentBytes;
        this.pendingReadyRecords = pendingReadyRecords;
        this.capBytes = capBytes;
        this.observationPeakBytes = observationPeakBytes;
        this.historicalPeakBytes = historicalPeakBytes;
        this.successfulAppends = successfulAppends;
        this.duplicateRefusals = duplicateRefusals;
        this.capRefusals = capRefusals;
        this.capSkippedPollCycles = capSkippedPollCycles;
        this.persistenceFailures = persistenceFailures;
        this.ackReleasedRecords = ackReleasedRecords;
        this.ackReleasedBytes = ackReleasedBytes;
        this.ackNotFound = ackNotFound;
        this.ackFailures = ackFailures;
        this.quarantinedRecords = quarantinedRecords;
        this.diagnosticQueueDrops = diagnosticQueueDrops;
        this.diagnosticDiskFailures = diagnosticDiskFailures;
        this.lastError = lastError;
        this.repeatedErrorCount = repeatedErrorCount;
        this.collectionMode = collectionMode;
        this.capReached = capReached;
        this.callbackAcceptedNativeKeys = callbackAcceptedNativeKeys;
        this.callbackFailedNativeKeys = callbackFailedNativeKeys;
        this.callbackPromotedKeys = callbackPromotedKeys;
        this.callbackPollKeys = callbackPollKeys;
        this.callbackFallbackKeys = callbackFallbackKeys;
        this.callbacksReceived = callbacksReceived;
        this.callbackMainQueueBytes = callbackMainQueueBytes;
        this.callbackMainQueueOldestAgeMs = callbackMainQueueOldestAgeMs;
        this.callbackMainQueueHighWaterBytes = callbackMainQueueHighWaterBytes;
        this.callbackSecondaryQueueBytes = callbackSecondaryQueueBytes;
        this.callbackSecondaryQueueOldestAgeMs = callbackSecondaryQueueOldestAgeMs;
        this.callbackSecondaryQueueHighWaterBytes = callbackSecondaryQueueHighWaterBytes;
        this.callbackQueueLossCount = callbackQueueLossCount;
        this.callbackRetryReason = callbackRetryReason;
    }

    JSONObject toJson(long observedWallMs, long observedElapsedMs) throws Exception {
        JSONObject json = new JSONObject();
        json.put("schema_version", 1);
        json.put("boot_id", bootId);
        json.put("pid", pid);
        json.put("helper_generation", helperGeneration);
        json.put("interval_started_wall_ms", intervalStartedWallMs);
        json.put("interval_started_elapsed_ms", intervalStartedElapsedMs);
        json.put("observed_wall_ms", observedWallMs);
        json.put("observed_elapsed_ms", observedElapsedMs);
        json.put("current_bytes", currentBytes == null ? JSONObject.NULL : currentBytes);
        json.put("pending_ready_records", pendingReadyRecords == null ? JSONObject.NULL : pendingReadyRecords);
        json.put("cap_bytes", capBytes);
        json.put("observation_peak_bytes", observationPeakBytes == null ? JSONObject.NULL : observationPeakBytes);
        //A new helper interval cannot reconstruct a trustworthy peak from older runs.
        json.put("historical_peak_bytes", historicalPeakBytes == null ? JSONObject.NULL : historicalPeakBytes);
        json.put("successful_appends", successfulAppends);
        json.put("duplicate_refusals", duplicateRefusals);
        json.put("cap_refusals", capRefusals);
        json.put("cap_skipped_poll_cycles", capSkippedPollCycles);
        json.put("persistence_failures", persistenceFailures);
        json.put("ack_released_records", ackReleasedRecords);
        json.put("ack_released_bytes", ackReleasedBytes);
        json.put("ack_not_found", ackNotFound);
        json.put("ack_failures", ackFailures);
        json.put("quarantined_records", quarantinedRecords);
        json.put("diagnostic_queue_drops", diagnosticQueueDrops);
        json.put("diagnostic_disk_failures", diagnosticDiskFailures);
        json.put("last_error", lastError == null ? JSONObject.NULL : lastError);
        json.put("repeated_error_count", repeatedErrorCount);
        json.put("collection_mode", collectionMode);
        json.put("cap_reached", capReached);
        json.put("callback_accepted_native_keys", callbackAcceptedNativeKeys);
        json.put("callback_failed_native_keys", callbackFailedNativeKeys);
        json.put("callback_promoted_keys", callbackPromotedKeys);
        json.put("callback_poll_keys", callbackPollKeys);
        json.put("callback_fallback_keys", callbackFallbackKeys);
        json.put("callbacks_received", callbacksReceived);
        json.put("callback_main_queue_bytes", callbackMainQueueBytes);
        json.put("callback_main_queue_oldest_age_ms",
            callbackMainQueueOldestAgeMs < 0L ? JSONObject.NULL : callbackMainQueueOldestAgeMs);
        json.put("callback_main_queue_high_water_bytes", callbackMainQueueHighWaterBytes);
        json.put("callback_secondary_queue_bytes", callbackSecondaryQueueBytes);
        json.put("callback_secondary_queue_oldest_age_ms",
            callbackSecondaryQueueOldestAgeMs < 0L ? JSONObject.NULL : callbackSecondaryQueueOldestAgeMs);
        json.put("callback_secondary_queue_high_water_bytes", callbackSecondaryQueueHighWaterBytes);
        json.put("callback_queue_loss_count", callbackQueueLossCount);
        json.put("callback_retry_reason", callbackRetryReason == null ? JSONObject.NULL : callbackRetryReason);
        return json;
    }
}
