package com.bydcollector.collector.update

/** Process-owned lifetime for one update hint, starting at actual appearance. */
class UpdateHintLifetime(
    private val lifetimeMs: Long = 10_000L
) {
    var activeResultId: Long? = null
        private set
    var expiresAtElapsedMs: Long = 0L
        private set

    fun begin(resultId: Long) {
        if (activeResultId == resultId) return
        activeResultId = resultId
        expiresAtElapsedMs = 0L
    }

    fun shown(nowElapsedMs: Long) {
        if (activeResultId == null || expiresAtElapsedMs != 0L) return
        expiresAtElapsedMs = nowElapsedMs + lifetimeMs
    }

    fun clear() {
        activeResultId = null
        expiresAtElapsedMs = 0L
    }

    fun isExpired(nowElapsedMs: Long): Boolean =
        activeResultId != null && expiresAtElapsedMs != 0L && nowElapsedMs >= expiresAtElapsedMs
}
