package com.bydcollector.collector.update

data class UpdateInfo(
    val version: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val downloadContentType: String? = null
) : java.io.Serializable

sealed interface UpdateCheckResult : java.io.Serializable {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

enum class UpdateFailureStage { CHECK, DOWNLOAD, VERIFICATION, INSTALLATION, UNKNOWN }

sealed interface UpdateUiState : java.io.Serializable {
    data object Hidden : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val info: UpdateInfo) : UpdateUiState
    data class Downloading(val info: UpdateInfo, val progress: Int) : UpdateUiState
    data class Cancelling(val info: UpdateInfo) : UpdateUiState
    data class Cancelled(val info: UpdateInfo) : UpdateUiState
    data class CancelError(val info: UpdateInfo) : UpdateUiState
    data class Error(val message: String, val stage: UpdateFailureStage = UpdateFailureStage.UNKNOWN) : UpdateUiState
}
