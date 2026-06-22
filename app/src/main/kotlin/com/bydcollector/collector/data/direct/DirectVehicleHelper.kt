package com.bydcollector.collector.data.direct

interface DirectVehicleHelper {
    fun isAlive(): Boolean
    fun read(entry: DirectFidEntry): DirectHelperReadResult
}

data class DirectHelperReadResult(
    val status: Int,
    val raw: Int?,
    val error: String? = null
) {
    val ok: Boolean = status == 0 && raw != null
}
