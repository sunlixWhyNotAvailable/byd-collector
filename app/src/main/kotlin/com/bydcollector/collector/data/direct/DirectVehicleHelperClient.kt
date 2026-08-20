package com.bydcollector.collector.data.direct

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.TelemetryWorkerSampleIdentity

//wraps the helper binder so autoservice calls stay behind one read-only app-facing interface
class DirectVehicleHelperClient : DirectVehicleHelper {
    private val lock = Any()

    @Volatile
    private var cached: IBinder? = null

    override fun isAlive(): Boolean = ownerMode() != null

    override fun ownerMode(): DirectHelperOwnerMode? = synchronized(lock) {
        val binder = ensureBinder() ?: return@synchronized null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(CollectorHelperProtocol.DESCRIPTOR)
            if (!binder.transact(CollectorHelperProtocol.TX_PING, data, reply, 0)) {
                cached = null
                return@synchronized null
            }
            val status = reply.readInt()
            val protocolVersion = reply.readInt()
            val ownerMode = reply.readInt()
            if (status != CollectorHelperProtocol.STATUS_OK || protocolVersion != CollectorHelperProtocol.PROTOCOL_VERSION) {
                return@synchronized null
            }
            DirectHelperOwnerMode.fromProtocolValue(ownerMode)
        } catch (_: Exception) {
            cached = null
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
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

    fun pendingWorkerSamples(limit: Int): PendingTelemetryWorkerSamples {
        if (limit !in 1..CollectorHelperProtocol.MAX_PENDING_WORKER_SAMPLES) {
            return pendingFailure(STATUS_CLIENT_ERROR, "invalid pending sample limit: $limit")
        }
        return synchronized(lock) {
            val binder = ensureBinder()
                ?: return@synchronized pendingFailure(STATUS_NO_BINDER, "helper binder unavailable")
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(CollectorHelperProtocol.DESCRIPTOR)
                data.writeInt(limit)
                if (!binder.transact(CollectorHelperProtocol.TX_WORKER_PENDING, data, reply, 0)) {
                    cached = null
                    return@synchronized pendingFailure(STATUS_TRANSACT_FALSE, "pending transact returned false")
                }
                val status = reply.readInt()
                val error = reply.readString()
                val count = reply.readInt()
                require(count in 0..limit) { "invalid pending sample count: $count" }
                PendingTelemetryWorkerSamples(
                    status = status,
                    samples = List(count) { readWorkerSample(reply) },
                    error = error
                )
            } catch (error: DeadObjectException) {
                cached = null
                pendingFailure(STATUS_DEAD_OBJECT, error.message ?: "dead binder")
            } catch (error: Exception) {
                pendingFailure(STATUS_CLIENT_ERROR, "${error::class.java.simpleName}: ${error.message ?: "no message"}")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    fun acknowledgeWorkerSample(
        identity: TelemetryWorkerSampleIdentity,
        acknowledgedAtMs: Long
    ): TelemetryWorkerAckResult {
        if (acknowledgedAtMs < 0) return ackFailure(STATUS_CLIENT_ERROR, "acknowledgedAtMs must be non-negative")
        return synchronized(lock) {
            val binder = ensureBinder()
                ?: return@synchronized ackFailure(STATUS_NO_BINDER, "helper binder unavailable")
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(CollectorHelperProtocol.DESCRIPTOR)
                data.writeString(identity.bootId)
                data.writeString(identity.helperGeneration)
                data.writeLong(identity.pollSequence)
                data.writeLong(acknowledgedAtMs)
                if (!binder.transact(CollectorHelperProtocol.TX_WORKER_ACK, data, reply, 0)) {
                    cached = null
                    return@synchronized ackFailure(STATUS_TRANSACT_FALSE, "ack transact returned false")
                }
                val status = reply.readInt()
                val updated = reply.readInt()
                require(updated == 0 || updated == 1) { "invalid ack update marker: $updated" }
                TelemetryWorkerAckResult(status, updated == 1, reply.readString())
            } catch (error: DeadObjectException) {
                cached = null
                ackFailure(STATUS_DEAD_OBJECT, error.message ?: "dead binder")
            } catch (error: Exception) {
                ackFailure(STATUS_CLIENT_ERROR, "${error::class.java.simpleName}: ${error.message ?: "no message"}")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    private fun readWorkerSample(reply: Parcel): TelemetryWorkerSample {
        val identity = TelemetryWorkerSampleIdentity(
            requireNotNull(reply.readString()) { "worker sample boot id missing" },
            requireNotNull(reply.readString()) { "worker sample generation missing" },
            reply.readLong()
        )
        val catalogVersion = requireNotNull(reply.readString()) { "worker sample catalog version missing" }
        val capturedWallMs = reply.readLong()
        val capturedElapsedMs = reply.readLong()
        val pollElapsedMs = reply.readLong()
        require(capturedElapsedMs >= 0 && pollElapsedMs >= 0) { "worker sample elapsed time is negative" }
        val batchStatus = reply.readInt()
        val batchMode = reply.readInt()
        val nativeAvailable = reply.readInt()
        require(nativeAvailable == 0 || nativeAvailable == 1) { "invalid native availability marker" }
        val groupFailureCount = reply.readInt()
        require(groupFailureCount >= 0) { "negative group failure count" }
        val error = reply.readString()
        val fieldCount = reply.readInt()
        require(fieldCount in 1..CollectorHelperProtocol.MAX_WORKER_FIELD_COUNT) {
            "invalid worker field count: $fieldCount"
        }
        val values = List(fieldCount) { expectedIndex ->
            val fieldIndex = reply.readInt()
            require(fieldIndex == expectedIndex) {
                "worker field order mismatch: expected=$expectedIndex actual=$fieldIndex"
            }
            val tx = reply.readInt()
            require(tx == CollectorHelperProtocol.AUTO_TX_INT || tx == CollectorHelperProtocol.AUTO_TX_FLOAT) {
                "unsupported worker read transaction: $tx"
            }
            val dev = reply.readInt()
            val fid = reply.readInt()
            val status = reply.readInt()
            val hasRaw = reply.readInt()
            require(hasRaw == 0 || hasRaw == 1) { "invalid worker raw marker: $hasRaw" }
            TelemetryWorkerFieldValue(
                fieldIndex = fieldIndex,
                tx = tx,
                dev = dev,
                fid = fid,
                status = status,
                raw = if (hasRaw == 1) reply.readInt() else null,
                error = reply.readString()
            )
        }
        return TelemetryWorkerSample(
            identity = identity,
            catalogVersion = catalogVersion,
            capturedWallMs = capturedWallMs,
            capturedElapsedMs = capturedElapsedMs,
            pollElapsedMs = pollElapsedMs,
            batchStatus = batchStatus,
            batchMode = batchMode,
            nativeAvailable = nativeAvailable == 1,
            groupFailureCount = groupFailureCount,
            error = error,
            values = values
        )
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

    private fun pendingFailure(status: Int, error: String): PendingTelemetryWorkerSamples =
        PendingTelemetryWorkerSamples(status, emptyList(), error)

    private fun ackFailure(status: Int, error: String): TelemetryWorkerAckResult =
        TelemetryWorkerAckResult(status, updated = false, error)

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
