package com.bydcollector.collector.data.direct

/** Maps actual attempted GET results back to the caller's ordered request list. */
internal fun <T, R> selectRequestedBatchResults(
    requested: List<T>,
    requestedIndices: List<Int>,
    results: List<R>
): List<Pair<T, R>> {
    require(requestedIndices.size == results.size) { "batch result/index count mismatch" }
    require(requestedIndices.all { it in requested.indices }) { "batch contains out-of-range requested index" }
    require(requestedIndices.zipWithNext().all { (previous, next) -> previous < next }) {
        "batch requested indices are duplicate or out of source order"
    }
    return requestedIndices.mapIndexed { resultIndex, requestedIndex ->
        requested[requestedIndex] to results[resultIndex]
    }
}
