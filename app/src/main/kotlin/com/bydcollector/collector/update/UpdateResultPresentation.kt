package com.bydcollector.collector.update

/** Eligibility is not delivery: only an actually presented result is consumed. */
class UpdateResultPresentation {
    private var lastPresentedResultId: Long? = null

    @Synchronized
    fun canPresentHint(
        snapshot: UpdateCheckSession.Snapshot,
        ownUiVisible: Boolean,
        hintEnabled: Boolean
    ): Boolean {
        val resultId = snapshot.availableResultId ?: return false
        if (lastPresentedResultId?.let { resultId <= it } == true) return false
        return snapshot.uiState is UpdateUiState.Available && !ownUiVisible && hintEnabled
    }

    @Synchronized
    fun markPresented(snapshot: UpdateCheckSession.Snapshot, resultId: Long): Boolean {
        if (snapshot.availableResultId != resultId || snapshot.uiState !is UpdateUiState.Available) return false
        if (lastPresentedResultId?.let { resultId <= it } == true) return false
        lastPresentedResultId = resultId
        return true
    }

    @Synchronized
    fun reset() {
        lastPresentedResultId = null
    }
}
