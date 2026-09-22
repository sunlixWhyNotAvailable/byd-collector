package com.bydcollector.collector.data.direct

import com.bydcollector.collector.direct.CallbackSpool
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.TelemetryCallbackBatch
import com.bydcollector.collector.data.callback.CallbackDelivery

data class CallbackLoss(val count: Long, val firstWallMs: Long, val lastWallMs: Long, val reason: String)

data class CallbackSpoolStatus(
    val status: Int,
    val footprintBytes: Long = 0,
    val readyBatches: Int = 0,
    val quarantinedFiles: Int = 0,
    val loss: CallbackLoss? = null,
    val error: String? = null
) { val ok: Boolean get() = status == CollectorHelperProtocol.STATUS_OK }

data class CallbackSpoolPage(
    val status: Int,
    val descriptor: CallbackSpool.Descriptor? = null,
    val offset: Long = 0,
    val bytes: ByteArray = byteArrayOf(),
    val error: String? = null
) { val ok: Boolean get() = status == CollectorHelperProtocol.STATUS_OK }

data class CallbackSpoolActionResult(val status: Int, val affected: Int = 0, val error: String? = null) {
    val ok: Boolean get() = status == CollectorHelperProtocol.STATUS_OK
}

data class CallbackBatchDownload(
    val status: Int,
    val descriptor: CallbackSpool.Descriptor? = null,
    val batch: TelemetryCallbackBatch? = null,
    val delivery: CallbackDelivery? = null,
    val error: String? = null,
    val permanentFormatError: Boolean = false
) { val ok: Boolean get() = status == CollectorHelperProtocol.STATUS_OK }
