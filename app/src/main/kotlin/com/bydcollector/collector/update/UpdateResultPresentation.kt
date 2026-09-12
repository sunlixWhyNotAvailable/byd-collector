package com.bydcollector.collector.update

/** Gives each completed available check at most one background hint opportunity. */
class UpdateResultPresentation {
    private var lastSeenAvailableResultId: Long? = null

    @Synchronized
    fun accept(
        snapshot: UpdateCheckSession.Snapshot,
        ownUiVisible: Boolean,
        hintEnabled: Boolean
    ): Boolean {
        val resultId = snapshot.availableResultId ?: return false
        if (lastSeenAvailableResultId?.let { resultId <= it } == true) return false
        lastSeenAvailableResultId = resultId
        return !ownUiVisible && hintEnabled
    }

    @Synchronized
    fun reset() {
        lastSeenAvailableResultId = null
    }
}
