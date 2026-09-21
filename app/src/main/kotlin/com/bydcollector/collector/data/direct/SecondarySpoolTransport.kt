package com.bydcollector.collector.data.direct

import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.SecondaryTelemetrySpool

data class SecondarySpoolStatus(
    val status: Int,
    val readyRecords: Int = 0,
    val inFlight: Boolean = false,
    val error: String? = null
) {
    val ok: Boolean get() = status == 0
    val pending: Boolean get() = readyRecords > 0 || inFlight
}

data class SecondarySpoolPage(
    val status: Int,
    val descriptor: SecondaryTelemetrySpool.Descriptor?,
    val offset: Long,
    val bytes: ByteArray,
    val error: String? = null
) {
    val ok: Boolean get() = status == CollectorHelperProtocol.STATUS_OK
}

data class SecondarySpoolActionResult(val status: Int, val affected: Int = 0, val error: String? = null) {
    val ok: Boolean get() = status == CollectorHelperProtocol.STATUS_OK
}
