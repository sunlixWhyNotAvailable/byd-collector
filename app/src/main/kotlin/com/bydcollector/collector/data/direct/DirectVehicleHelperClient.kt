package com.bydcollector.collector.data.direct

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.bydcollector.collector.direct.CollectorHelperProtocol

//wraps the helper binder so autoservice calls stay behind one read-only app-facing interface
class DirectVehicleHelperClient : DirectVehicleHelper {
    private val lock = Any()

    @Volatile
    private var cached: IBinder? = null

    override fun isAlive(): Boolean {
        val ping = transactScalar(CollectorHelperProtocol.TX_PING) { }
        return ping?.status == 0 && ping.raw == CollectorHelperProtocol.PROTOCOL_VERSION
    }

    override fun read(entry: DirectFidEntry): DirectHelperReadResult {
        return transactScalar(CollectorHelperProtocol.TX_READ) { data ->
            data.writeInt(entry.tx)
            data.writeInt(entry.dev)
            data.writeInt(entry.fid)
        } ?: DirectHelperReadResult(status = STATUS_NO_BINDER, raw = null, error = "helper binder unavailable")
    }

    override fun readBatch(entries: List<DirectFidEntry>): DirectHelperBatchResult {
        if (entries.isEmpty() || entries.size > CollectorHelperProtocol.MAX_BATCH_SIZE) {
            return batchFailure(entries.size, STATUS_CLIENT_ERROR, "invalid batch size: ${entries.size}")
        }
        return synchronized(lock) {
            val binder = ensureBinder()
                ?: return@synchronized batchFailure(entries.size, STATUS_NO_BINDER, "helper binder unavailable")
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(CollectorHelperProtocol.DESCRIPTOR)
                data.writeInt(entries.size)
                entries.forEach { entry ->
                    data.writeInt(entry.tx)
                    data.writeInt(entry.dev)
                    data.writeInt(entry.fid)
                }
                if (!binder.transact(CollectorHelperProtocol.TX_READ_BATCH, data, reply, 0)) {
                    cached = null
                    return@synchronized batchFailure(entries.size, STATUS_TRANSACT_FALSE, "batch transact returned false")
                }
                val batchStatus = reply.readInt()
                val mode = modeName(reply.readInt())
                val nativeAvailable = reply.readInt() == 1
                val nativeGroupCount = reply.readInt()
                val fallbackGroupCount = reply.readInt()
                val fallbackReadCount = reply.readInt()
                val groupFailureCount = reply.readInt()
                val helperElapsedMs = reply.readLong()
                val returnedCount = reply.readInt()
                val batchError = reply.readString()
                require(returnedCount == entries.size) {
                    "batch reply count mismatch: expected=${entries.size} actual=$returnedCount"
                }
                val results = List(returnedCount) {
                    val status = reply.readInt()
                    val hasRaw = reply.readInt()
                    require(hasRaw == 0 || hasRaw == 1) { "invalid raw marker: $hasRaw" }
                    val raw = if (hasRaw == 1) reply.readInt() else null
                    val error = batchError.takeIf { status != 0 && mode == "rejected" }
                    DirectHelperReadResult(status, raw, error)
                }
                DirectHelperBatchResult(
                    results = results,
                    diagnostics = DirectBatchDiagnostics(
                        mode = mode,
                        nativeAvailable = nativeAvailable,
                        nativeGroupCount = nativeGroupCount,
                        fallbackGroupCount = fallbackGroupCount,
                        fallbackReadCount = fallbackReadCount,
                        groupFailureCount = groupFailureCount,
                        helperElapsedMs = helperElapsedMs,
                        returnedCount = returnedCount,
                        error = batchError ?: if (batchStatus == 0) null else "batch_status=$batchStatus"
                    )
                )
            } catch (error: DeadObjectException) {
                cached = null
                batchFailure(entries.size, STATUS_DEAD_OBJECT, error.message ?: "dead binder")
            } catch (error: Exception) {
                batchFailure(entries.size, STATUS_CLIENT_ERROR, "${error::class.java.simpleName}: ${error.message ?: "no message"}")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    private fun transactScalar(code: Int, writeArgs: (Parcel) -> Unit): DirectHelperReadResult? {
        return synchronized(lock) {
            //synchronizes binder parcels because one cached binder is shared by polling and status checks
            val binder = ensureBinder() ?: return@synchronized null
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(CollectorHelperProtocol.DESCRIPTOR)
                writeArgs(data)
                if (!binder.transact(code, data, reply, 0)) {
                    cached = null
                    return@synchronized DirectHelperReadResult(status = STATUS_TRANSACT_FALSE, raw = null, error = "binder transact returned false")
                }
                val status = if (reply.dataAvail() >= 4) reply.readInt() else STATUS_EMPTY_REPLY
                val raw = if (reply.dataAvail() >= 4) reply.readInt() else null
                DirectHelperReadResult(status = status, raw = raw)
            } catch (error: DeadObjectException) {
                cached = null
                DirectHelperReadResult(status = STATUS_DEAD_OBJECT, raw = null, error = error.message ?: "dead binder")
            } catch (error: Exception) {
                DirectHelperReadResult(status = STATUS_CLIENT_ERROR, raw = null, error = "${error::class.java.simpleName}: ${error.message ?: "no message"}")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    private fun batchFailure(count: Int, status: Int, error: String): DirectHelperBatchResult {
        return DirectHelperBatchResult(
            results = List(count) { DirectHelperReadResult(status, null, error) },
            diagnostics = DirectBatchDiagnostics(
                mode = "client_error",
                nativeAvailable = false,
                nativeGroupCount = 0,
                fallbackGroupCount = 0,
                fallbackReadCount = 0,
                groupFailureCount = 0,
                helperElapsedMs = 0,
                returnedCount = 0,
                error = error
            )
        )
    }

    private fun modeName(mode: Int): String = when (mode) {
        CollectorHelperProtocol.MODE_NATIVE -> "native"
        CollectorHelperProtocol.MODE_NATIVE_WITH_FALLBACK -> "native_with_fallback"
        CollectorHelperProtocol.MODE_SCALAR_FALLBACK -> "scalar_fallback"
        else -> "rejected"
    }

    private fun ensureBinder(): IBinder? {
        //reuses the service-manager lookup until android tells us the binder died
        cached?.takeIf { it.isBinderAlive }?.let { return it }
        return resolveBinder()?.also { cached = it }
    }

    private fun resolveBinder(): IBinder? {
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            serviceManager.getMethod("getService", String::class.java)
                .invoke(null, CollectorHelperProtocol.SERVICE_NAME) as? IBinder
        } catch (error: Exception) {
            Log.w(TAG, "getService failed: ${error.message}")
            null
        }
    }

    companion object {
        private const val TAG = "BYDCollectorHelper"
        private const val STATUS_NO_BINDER = -900
        private const val STATUS_TRANSACT_FALSE = -901
        private const val STATUS_EMPTY_REPLY = -902
        private const val STATUS_DEAD_OBJECT = -903
        private const val STATUS_CLIENT_ERROR = -904
    }
}
