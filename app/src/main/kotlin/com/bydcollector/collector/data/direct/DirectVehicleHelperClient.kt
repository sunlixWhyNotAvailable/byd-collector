package com.bydcollector.collector.data.direct

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.bydcollector.collector.direct.CollectorHelperProtocol
import com.bydcollector.collector.direct.TelemetryWorkerSampleIdentity
import com.bydcollector.collector.direct.SecondarySpoolBinder
import com.bydcollector.collector.direct.SecondaryTelemetrySpool
import com.bydcollector.collector.direct.CallbackSpool
import com.bydcollector.collector.direct.CallbackSpoolBinder
import com.bydcollector.collector.direct.CallbackValueSource
import com.bydcollector.collector.direct.TelemetryCallbackBatch
import com.bydcollector.collector.data.debug.DirectDebugParameterAsset
import com.bydcollector.collector.data.callback.CallbackDelivery
import java.io.ByteArrayOutputStream

//wraps the helper binder so autoservice calls stay behind one read-only app-facing interface
class DirectVehicleHelperClient : DirectVehicleHelper {
    private val lock = Any()

    @Volatile
    private var cached: IBinder? = null

    override fun isAlive(): Boolean = ownerMode() != null

    override fun ownerMode(): DirectHelperOwnerMode? = readOwnerMode(stopOnly = false)

    override fun ownerModeForStop(): DirectHelperOwnerMode? = readOwnerMode(stopOnly = true)

    private fun readOwnerMode(stopOnly: Boolean): DirectHelperOwnerMode? = synchronized(lock) {
        val binder = ensureBinder() ?: return@synchronized null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(CollectorHelperProtocol.DESCRIPTOR)
            if (!binder.transact(CollectorHelperProtocol.TX_PING, data, reply, 0)) {
                cached = null
                return@synchronized null
            }
            if (reply.dataAvail() < 12) return@synchronized null
            val status = reply.readInt()
            val protocolVersion = reply.readInt()
            val ownerMode = reply.readInt()
            ownerFromPing(status, protocolVersion, ownerMode, stopOnly)
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

    override fun readBatch(entries: List<DirectFidEntry>): DirectHelperBatchResult = readBatchInternal(entries, false)

    fun readKpiBatch(entries: List<DirectFidEntry>): DirectHelperBatchResult = readBatchInternal(entries, true)

    private fun readBatchInternal(entries: List<DirectFidEntry>, kpi: Boolean): DirectHelperBatchResult {
        if (entries.isEmpty() || entries.size > CollectorHelperProtocol.MAX_BATCH_SIZE) {
            return batchFailure(entries.size, STATUS_CLIENT_ERROR, "invalid batch size: ${entries.size}")
        }
        return synchronized(lock) {
            val owner = if (kpi) null else DirectStreamController.credentials(CollectorHelperProtocol.STREAM_MAIN)
            if (!kpi && owner == null) return@synchronized batchFailure(entries.size,
                CollectorHelperProtocol.STATUS_STALE_TOKEN, "Main stream is not claimed")
            val binder = ensureBinder()
                ?: return@synchronized batchFailure(entries.size, STATUS_NO_BINDER, "helper binder unavailable")
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(CollectorHelperProtocol.DESCRIPTOR)
                if (!kpi) {
                    data.writeLong(requireNotNull(owner).controllerToken)
                    data.writeLong(owner.epoch)
                }
                data.writeInt(entries.size)
                entries.forEach { entry ->
                    data.writeInt(entry.tx)
                    data.writeInt(entry.dev)
                    data.writeInt(entry.fid)
                }
                if (!binder.transact(if (kpi) CollectorHelperProtocol.TX_KPI_READ_BATCH else CollectorHelperProtocol.TX_READ_BATCH,
                        data, reply, 0)) {
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
                    DirectHelperReadResult(status, raw, error, CallbackValueSource.readNullable(reply))
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
                        error = batchError ?: if (batchStatus == 0) null else "batch_status=$batchStatus",
                        status = batchStatus
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

    override fun readSecondaryBatch(entries: List<DirectFidEntry>): DirectHelperBatchResult {
        if (entries.size != DirectDebugParameterAsset.TOTAL_PARAMETER_COUNT) {
            return batchFailure(entries.size, STATUS_CLIENT_ERROR, "secondary requires complete ordered catalog")
        }
        val owner = DirectStreamController.credentials(CollectorHelperProtocol.STREAM_SECONDARY)
            ?: return batchFailure(entries.size, CollectorHelperProtocol.STATUS_STALE_TOKEN, "Secondary stream is not claimed")
        return synchronized(lock) {
            val binder = ensureBinder() ?: return@synchronized batchFailure(entries.size, STATUS_NO_BINDER, "helper binder unavailable")
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(CollectorHelperProtocol.DESCRIPTOR)
                data.writeLong(owner.controllerToken)
                data.writeLong(owner.epoch)
                data.writeString(DirectDebugParameterAsset.SOURCE_VERSION)
                if (!binder.transact(CollectorHelperProtocol.TX_SECONDARY_READ_BATCH, data, reply, 0)) {
                    cached = null
                    return@synchronized batchFailure(entries.size, STATUS_TRANSACT_FALSE, "secondary live transact returned false")
                }
                require(reply.dataSize() <= SecondarySpoolBinder.MAX_REPLY_BYTES) { "oversize secondary live reply" }
                val status = reply.readInt()
                val mode = modeName(reply.readInt())
                val native = reply.readInt() == 1
                val nativeGroups = reply.readInt()
                val fallbackGroups = reply.readInt()
                val fallbackReads = reply.readInt()
                val groupFailures = reply.readInt()
                val elapsed = reply.readLong()
                val count = reply.readInt()
                val batchError = reply.readString()
                val catalog = reply.readString()
                val statuses = reply.createIntArray() ?: error("missing secondary statuses")
                val presence = reply.createByteArray() ?: error("missing secondary presence")
                val raws = reply.createIntArray() ?: error("missing secondary raw values")
                val callbackCached = reply.createByteArray() ?: error("missing secondary callback presence")
                require(count in 0..entries.size && statuses.size == count && raws.size == count &&
                    presence.size == (count + 7) / 8 && callbackCached.size == (count + 7) / 8
                ) { "invalid secondary packed payload" }
                require(reply.dataAvail() == 0) { "unexpected secondary callback payload" }
                require(catalog == DirectDebugParameterAsset.SOURCE_VERSION) { "secondary catalog mismatch" }
                require(elapsed >= 0 && listOf(nativeGroups, fallbackGroups, fallbackReads, groupFailures).all { it >= 0 }) {
                    "invalid secondary diagnostics"
                }
                if (count == 0 && status != CollectorHelperProtocol.STATUS_OK) {
                    return@synchronized batchFailure(entries.size, status, batchError ?: "secondary live unavailable")
                }
                require(count == entries.size) { "incomplete secondary cycle" }
                DirectHelperBatchResult(
                    List(count) { i ->
                        val raw = if ((presence[i / 8].toInt() and (1 shl (i % 8))) != 0) raws[i] else null
                        val cachedValue = (callbackCached[i / 8].toInt() and (1 shl (i % 8))) != 0
                        require(!cachedValue || raw != null) { "secondary callback cache marker without raw" }
                        DirectHelperReadResult(statuses[i], raw, callbackCached = cachedValue)
                    },
                    DirectBatchDiagnostics(mode, native, nativeGroups, fallbackGroups, fallbackReads,
                        groupFailures, elapsed, count, batchError, status)
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

    fun streamControl(
        action: Int, nonce: String, controllerToken: Long, stream: Int, epoch: Long, value: Int
    ): DirectStreamControlResult = synchronized(lock) {
        val binder = ensureBinder() ?: return@synchronized DirectStreamControlResult(STATUS_NO_BINDER, error = "helper binder unavailable")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            require(nonce.isNotEmpty() && nonce.length <= 64) { "invalid controller nonce" }
            data.writeInterfaceToken(CollectorHelperProtocol.DESCRIPTOR)
            data.writeInt(action)
            data.writeString(nonce)
            data.writeLong(controllerToken)
            data.writeInt(stream)
            data.writeLong(epoch)
            data.writeInt(value)
            if (!binder.transact(CollectorHelperProtocol.TX_STREAM_CONTROL, data, reply, 0)) {
                cached = null
                return@synchronized DirectStreamControlResult(STATUS_TRANSACT_FALSE, error = "stream control transact returned false")
            }
            require(reply.dataSize() <= 4096) { "oversize stream control reply" }
            val result = DirectStreamControlResult(reply.readInt(), reply.readLong(), reply.readLong(), reply.readLong(), reply.readLong(), reply.readString())
            require(reply.dataAvail() == 0 && result.controllerToken >= 0 && result.mainEpoch >= 0 && result.secondaryEpoch >= 0 &&
                result.leaseExpiresElapsedMs >= 0 && (result.error?.length ?: 0) <= 512
            ) { "invalid stream control reply" }
            result
        } catch (error: DeadObjectException) {
            cached = null
            DirectStreamControlResult(STATUS_DEAD_OBJECT, error = error.message ?: "dead binder")
        } catch (error: Exception) {
            DirectStreamControlResult(STATUS_CLIENT_ERROR, error = "${error::class.java.simpleName}: ${error.message ?: "no message"}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun secondarySpoolStatus(): SecondarySpoolStatus = synchronized(lock) {
        val binder = ensureBinder() ?: return@synchronized SecondarySpoolStatus(STATUS_NO_BINDER, error = "helper binder unavailable")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(CollectorHelperProtocol.DESCRIPTOR)
            if (!binder.transact(CollectorHelperProtocol.TX_SECONDARY_STATUS, data, reply, 0)) {
                cached = null
                return@synchronized SecondarySpoolStatus(STATUS_TRANSACT_FALSE, error = "secondary status transact returned false")
            }
            require(reply.dataSize() <= 4096) { "oversize secondary status" }
            val status = reply.readInt()
            val ready = reply.readInt()
            val busy = reply.readInt()
            val error = reply.readString()
            require(ready >= 0 && busy in 0..1 && reply.dataAvail() == 0 && (error?.length ?: 0) <= 512) {
                "invalid secondary status"
            }
            SecondarySpoolStatus(status, ready, busy == 1, error)
        } catch (error: DeadObjectException) {
            cached = null
            SecondarySpoolStatus(STATUS_DEAD_OBJECT, error = error.message ?: "dead binder")
        } catch (error: Exception) {
            SecondarySpoolStatus(STATUS_CLIENT_ERROR, error = "${error::class.java.simpleName}: ${error.message ?: "no message"}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun callbackSpoolStatus(stream: Int): CallbackSpoolStatus = synchronized(lock) {
        val owner = DirectStreamController.credentials(stream)
            ?: return@synchronized CallbackSpoolStatus(CollectorHelperProtocol.STATUS_STALE_TOKEN, error = "Callback stream is not claimed")
        val binder = ensureBinder()
            ?: return@synchronized CallbackSpoolStatus(STATUS_NO_BINDER, error = "helper binder unavailable")
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            writeCallbackCredentials(data, owner.controllerToken, stream, owner.epoch)
            if (!binder.transact(CollectorHelperProtocol.TX_CALLBACK_STATUS, data, reply, 0)) {
                cached = null
                return@synchronized CallbackSpoolStatus(STATUS_TRANSACT_FALSE, error = "callback status transact returned false")
            }
            require(reply.dataSize() <= 4096) { "oversize callback status" }
            val status = reply.readInt(); val footprint = reply.readLong(); val ready = reply.readInt(); val bad = reply.readInt()
            val lossMarker = reply.readInt()
            require(lossMarker in 0..1) { "invalid callback loss marker" }
            val loss = if (lossMarker == 1) CallbackLoss(reply.readLong(), reply.readLong(), reply.readLong(), reply.readString() ?: error("missing loss reason")) else null
            val error = reply.readString()
            val liveRetainedBytes = reply.readLong()
            require(footprint >= 0 && ready >= 0 && bad >= 0 && reply.dataAvail() == 0 &&
                liveRetainedBytes >= 0 &&
                (loss == null || (loss.count > 0 && loss.firstWallMs >= 0 && loss.lastWallMs >= loss.firstWallMs && loss.reason.length <= 128))
            ) { "invalid callback status reply" }
            CallbackSpoolStatus(status, footprint, ready, bad, loss, error, liveRetainedBytes)
        } catch (error: DeadObjectException) {
            cached = null; CallbackSpoolStatus(STATUS_DEAD_OBJECT, error = error.message ?: "dead binder")
        } catch (error: Exception) {
            CallbackSpoolStatus(STATUS_CLIENT_ERROR, error = "${error::class.java.simpleName}: ${error.message ?: "no message"}")
        } finally { data.recycle(); reply.recycle() }
    }

    fun callbackSpoolPage(
        stream: Int,
        descriptor: CallbackSpool.Descriptor? = null,
        offset: Long = 0,
        limit: Int = CallbackSpool.MAX_SLICE_BYTES
    ): CallbackSpoolPage {
        fun failure(status: Int, error: String) = CallbackSpoolPage(status, error = error)
        if (stream !in CollectorHelperProtocol.STREAM_MAIN..CollectorHelperProtocol.STREAM_SECONDARY ||
            offset < 0 || limit !in 1..CallbackSpool.MAX_SLICE_BYTES || (descriptor == null && offset != 0L) ||
            (descriptor != null && descriptor.stream != stream)) return failure(STATUS_CLIENT_ERROR, "invalid callback page request")
        return synchronized(lock) {
            val owner = DirectStreamController.credentials(stream)
                ?: return@synchronized failure(CollectorHelperProtocol.STATUS_STALE_TOKEN, "Callback stream is not claimed")
            val binder = ensureBinder() ?: return@synchronized failure(STATUS_NO_BINDER, "helper binder unavailable")
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                writeCallbackCredentials(data, owner.controllerToken, stream, owner.epoch)
                CallbackSpoolBinder.writeDescriptor(data, descriptor); data.writeLong(offset); data.writeInt(limit)
                if (!binder.transact(CollectorHelperProtocol.TX_CALLBACK_PENDING_PAGE, data, reply, 0)) {
                    cached = null; return@synchronized failure(STATUS_TRANSACT_FALSE, "callback page transact returned false")
                }
                require(reply.dataSize() <= CallbackSpoolBinder.MAX_REPLY_BYTES) { "oversize callback page reply" }
                val status = reply.readInt(); val responseError = reply.readString()
                val selected = CallbackSpoolBinder.readDescriptor(reply); val returnedOffset = reply.readLong()
                val bytes = reply.createByteArray() ?: error("missing callback page bytes")
                require(bytes.size <= limit && reply.dataAvail() == 0) { "invalid callback page payload" }
                if (status == CollectorHelperProtocol.STATUS_OK) {
                    require(returnedOffset == offset) { "callback page offset mismatch" }
                    if (selected == null) require(descriptor == null && bytes.isEmpty()) { "callback batch disappeared" }
                    else {
                        require(selected.stream == stream && returnedOffset <= selected.length && bytes.size.toLong() <= selected.length - returnedOffset) {
                            "callback page exceeds batch"
                        }
                        if (descriptor != null) require(selected == descriptor) { "callback descriptor changed during paging" }
                    }
                }
                CallbackSpoolPage(status, selected, returnedOffset, bytes, responseError)
            } catch (error: DeadObjectException) {
                cached = null; failure(STATUS_DEAD_OBJECT, error.message ?: "dead binder")
            } catch (error: Exception) {
                failure(STATUS_CLIENT_ERROR, "${error::class.java.simpleName}: ${error.message ?: "no message"}")
            } finally { data.recycle(); reply.recycle() }
        }
    }

    /** Downloads and validates one immutable batch. The caller ACKs only after its SQLite commit. */
    fun drainCallbackBatch(stream: Int): CallbackBatchDownload {
        var page = callbackSpoolPage(stream)
        if (!page.ok) return CallbackBatchDownload(page.status, error = page.error)
        val descriptor = page.descriptor ?: return CallbackBatchDownload(CollectorHelperProtocol.STATUS_OK)
        val payload = try {
            require(descriptor.length in 1..TelemetryCallbackBatch.MAX_BYTES.toLong()) { "invalid callback batch length" }
            val bytes = ByteArrayOutputStream(descriptor.length.toInt())
            var offset = 0L
            while (offset < descriptor.length) {
                if (offset != 0L) page = callbackSpoolPage(stream, descriptor, offset)
                require(page.ok && page.descriptor == descriptor && page.offset == offset && page.bytes.isNotEmpty()) {
                    page.error ?: "callback paging failed"
                }
                bytes.write(page.bytes)
                offset += page.bytes.size
            }
            bytes.toByteArray()
        } catch (error: Exception) {
            return CallbackBatchDownload(STATUS_CLIENT_ERROR, descriptor = descriptor,
                error = "${error::class.java.simpleName}: ${error.message ?: "no message"}")
        }
        return try {
            require(payload.size.toLong() == descriptor.length && TelemetryCallbackBatch.digest(payload) == descriptor.sha256) {
                "callback batch digest mismatch"
            }
            val batch = TelemetryCallbackBatch.decode(payload)
            require(batch.identity() == descriptor.identity && batch.stream == stream &&
                batch.bootId == descriptor.bootId && batch.helperGeneration == descriptor.helperGeneration &&
                batch.epoch == descriptor.epoch && batch.batchSequence == descriptor.batchSequence
            ) { "callback batch identity mismatch" }
            CallbackBatchDownload(CollectorHelperProtocol.STATUS_OK, descriptor, batch,
                if (descriptor.spoolOrder < 0) CallbackDelivery.LIVE else CallbackDelivery.REPLAY)
        } catch (error: Exception) {
            CallbackBatchDownload(STATUS_CLIENT_ERROR, descriptor = descriptor,
                error = "${error::class.java.simpleName}: ${error.message ?: "no message"}",
                permanentFormatError = true)
        }
    }

    /** Call only after the exact callback batch transaction has committed durably. */
    fun acknowledgeCallbackSpool(stream: Int, descriptor: CallbackSpool.Descriptor): CallbackSpoolActionResult = synchronized(lock) {
        callbackSpoolAction(CollectorHelperProtocol.TX_CALLBACK_ACK, stream, descriptor, null)
    }

    fun quarantineCallbackSpool(stream: Int, descriptor: CallbackSpool.Descriptor, reason: String): CallbackSpoolActionResult = synchronized(lock) {
        callbackSpoolAction(CollectorHelperProtocol.TX_CALLBACK_QUARANTINE, stream, descriptor, reason.take(512))
    }

    private fun callbackSpoolAction(
        transaction: Int, stream: Int, descriptor: CallbackSpool.Descriptor, reason: String?
    ): CallbackSpoolActionResult = synchronized(lock) {
        if (descriptor.stream != stream) return@synchronized CallbackSpoolActionResult(STATUS_CLIENT_ERROR, error = "callback stream mismatch")
        val owner = DirectStreamController.credentials(stream)
            ?: return@synchronized CallbackSpoolActionResult(CollectorHelperProtocol.STATUS_STALE_TOKEN, error = "Callback stream is not claimed")
        val binder = ensureBinder()
            ?: return@synchronized CallbackSpoolActionResult(STATUS_NO_BINDER, error = "helper binder unavailable")
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            writeCallbackCredentials(data, owner.controllerToken, stream, owner.epoch)
            CallbackSpoolBinder.writeDescriptor(data, descriptor)
            if (transaction == CollectorHelperProtocol.TX_CALLBACK_QUARANTINE) data.writeString(reason)
            if (!binder.transact(transaction, data, reply, 0)) {
                cached = null
                return@synchronized CallbackSpoolActionResult(STATUS_TRANSACT_FALSE, error = "callback action transact returned false")
            }
            val status = reply.readInt(); val affected = reply.readInt(); val error = reply.readString()
            require(affected in 0..1 && reply.dataAvail() == 0) { "invalid callback ACK reply" }
            CallbackSpoolActionResult(status, affected, error)
        } catch (error: DeadObjectException) {
            cached = null; CallbackSpoolActionResult(STATUS_DEAD_OBJECT, error = error.message ?: "dead binder")
        } catch (error: Exception) {
            CallbackSpoolActionResult(STATUS_CLIENT_ERROR, error = "${error::class.java.simpleName}: ${error.message ?: "no message"}")
        } finally { data.recycle(); reply.recycle() }
    }

    private fun writeCallbackCredentials(data: Parcel, token: Long, stream: Int, epoch: Long) {
        data.writeInterfaceToken(CollectorHelperProtocol.DESCRIPTOR)
        data.writeLong(token); data.writeInt(stream); data.writeLong(epoch)
    }

    fun secondarySpoolPage(
        descriptor: SecondaryTelemetrySpool.Descriptor? = null,
        offset: Long = 0,
        limit: Int = SecondaryTelemetrySpool.MAX_SLICE_BYTES
    ): SecondarySpoolPage {
        fun failure(status: Int, error: String) = SecondarySpoolPage(status, null, 0, byteArrayOf(), error)
        if (offset < 0 || limit !in 1..SecondaryTelemetrySpool.MAX_SLICE_BYTES ||
            (descriptor == null && offset != 0L)
        ) return failure(STATUS_CLIENT_ERROR, "invalid secondary page request")
        return synchronized(lock) {
            val binder = ensureBinder() ?: return@synchronized failure(STATUS_NO_BINDER, "helper binder unavailable")
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(CollectorHelperProtocol.DESCRIPTOR)
                SecondarySpoolBinder.writeDescriptor(data, descriptor)
                data.writeLong(offset)
                data.writeInt(limit)
                if (!binder.transact(CollectorHelperProtocol.TX_SECONDARY_PENDING_PAGE, data, reply, 0)) {
                    cached = null
                    return@synchronized failure(STATUS_TRANSACT_FALSE, "secondary page transact returned false")
                }
                require(reply.dataSize() <= SecondarySpoolBinder.MAX_REPLY_BYTES) { "oversize secondary reply" }
                val status = reply.readInt()
                val responseError = reply.readString()
                val selected = SecondarySpoolBinder.readDescriptor(reply)
                val returnedOffset = reply.readLong()
                val bytes = reply.createByteArray() ?: error("missing secondary page bytes")
                require(bytes.size <= limit) { "oversize secondary page" }
                require(reply.dataAvail() == 0) { "unexpected secondary reply data" }
                if (status == CollectorHelperProtocol.STATUS_OK) {
                    require(returnedOffset == offset) { "secondary page offset mismatch" }
                    if (selected == null) {
                        require(descriptor == null && bytes.isEmpty()) { "secondary record disappeared" }
                    } else {
                        require(returnedOffset <= selected.length && bytes.size.toLong() <= selected.length - returnedOffset) {
                            "secondary page exceeds record"
                        }
                        if (descriptor != null) require(
                            selected.identity == descriptor.identity && selected.sha256 == descriptor.sha256 &&
                                selected.length == descriptor.length && selected.spoolOrder == descriptor.spoolOrder &&
                                selected.kind == descriptor.kind && selected.fileName == descriptor.fileName &&
                                selected.capturedWallMs == descriptor.capturedWallMs &&
                                selected.capturedElapsedMs == descriptor.capturedElapsedMs
                        ) { "secondary descriptor changed during paging" }
                    }
                }
                SecondarySpoolPage(status, selected, returnedOffset, bytes, responseError)
            } catch (error: DeadObjectException) {
                cached = null
                failure(STATUS_DEAD_OBJECT, error.message ?: "dead binder")
            } catch (error: Exception) {
                failure(STATUS_CLIENT_ERROR, "${error::class.java.simpleName}: ${error.message ?: "no message"}")
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }

    fun acknowledgeSecondarySpool(descriptor: SecondaryTelemetrySpool.Descriptor): SecondarySpoolActionResult =
        secondarySpoolAction(CollectorHelperProtocol.TX_SECONDARY_ACK, descriptor, null)

    fun quarantineSecondarySpool(
        descriptor: SecondaryTelemetrySpool.Descriptor,
        reason: String
    ): SecondarySpoolActionResult = secondarySpoolAction(
        CollectorHelperProtocol.TX_SECONDARY_QUARANTINE, descriptor, reason.take(512)
    )

    private fun secondarySpoolAction(
        transaction: Int,
        descriptor: SecondaryTelemetrySpool.Descriptor,
        reason: String?
    ): SecondarySpoolActionResult = synchronized(lock) {
        val binder = ensureBinder() ?: return@synchronized SecondarySpoolActionResult(
            STATUS_NO_BINDER, error = "helper binder unavailable"
        )
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(CollectorHelperProtocol.DESCRIPTOR)
            SecondarySpoolBinder.writeDescriptor(data, descriptor)
            if (transaction == CollectorHelperProtocol.TX_SECONDARY_QUARANTINE) data.writeString(reason)
            if (!binder.transact(transaction, data, reply, 0)) {
                cached = null
                return@synchronized SecondarySpoolActionResult(STATUS_TRANSACT_FALSE, error = "secondary action transact returned false")
            }
            val status = reply.readInt()
            val affected = reply.readInt()
            require(affected >= 0) { "negative secondary action count" }
            if (transaction == CollectorHelperProtocol.TX_SECONDARY_ACK) require(affected <= 1) { "invalid ACK count" }
            val error = reply.readString()
            require(reply.dataAvail() == 0) { "unexpected secondary action reply" }
            SecondarySpoolActionResult(status, affected, error)
        } catch (error: DeadObjectException) {
            cached = null
            SecondarySpoolActionResult(STATUS_DEAD_OBJECT, error = error.message ?: "dead binder")
        } catch (error: Exception) {
            SecondarySpoolActionResult(STATUS_CLIENT_ERROR, error = "${error::class.java.simpleName}: ${error.message ?: "no message"}")
        } finally {
            data.recycle()
            reply.recycle()
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

    override fun requestStop(ownerMode: DirectHelperOwnerMode): DirectHelperStopResult {
        return synchronized(lock) {
            val binder = ensureBinder()
                ?: return@synchronized stopFailure(STATUS_NO_BINDER, "helper binder unavailable")
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(CollectorHelperProtocol.DESCRIPTOR)
                data.writeInt(ownerMode.protocolValue)
                if (!binder.transact(CollectorHelperProtocol.TX_STOP_OWNER, data, reply, 0)) {
                    cached = null
                    return@synchronized stopFailure(STATUS_TRANSACT_FALSE, "stop transact returned false")
                }
                val status = reply.readInt()
                val acceptedMarker = reply.readInt()
                require(acceptedMarker == 0 || acceptedMarker == 1) {
                    "invalid stop accepted marker: $acceptedMarker"
                }
                val accepted = acceptedMarker == 1
                require((status == CollectorHelperProtocol.STATUS_OK) == accepted) {
                    "inconsistent stop reply: status=$status accepted=$acceptedMarker"
                }
                val result = DirectHelperStopResult(status, accepted, reply.readString())
                if (result.ok) cached = null
                result
            } catch (error: DeadObjectException) {
                cached = null
                stopFailure(STATUS_DEAD_OBJECT, error.message ?: "dead binder")
            } catch (error: Exception) {
                stopFailure(STATUS_CLIENT_ERROR, "${error::class.java.simpleName}: ${error.message ?: "no message"}")
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
                error = reply.readString(),
                callbackSource = CallbackValueSource.readNullable(reply)
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
                error = error,
                status = status
            )
        )
    }

    private fun pendingFailure(status: Int, error: String): PendingTelemetryWorkerSamples =
        PendingTelemetryWorkerSamples(status, emptyList(), error)

    private fun ackFailure(status: Int, error: String): TelemetryWorkerAckResult =
        TelemetryWorkerAckResult(status, updated = false, error)

    private fun stopFailure(status: Int, error: String): DirectHelperStopResult =
        DirectHelperStopResult(status, accepted = false, error)

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
        internal fun ownerFromPing(status: Int, protocol: Int, mode: Int, stopOnly: Boolean): DirectHelperOwnerMode? {
            val supported = if (stopOnly) {
                protocol in CollectorHelperProtocol.MIN_STOP_PROTOCOL_VERSION..CollectorHelperProtocol.PROTOCOL_VERSION
            } else protocol == CollectorHelperProtocol.PROTOCOL_VERSION
            return if (status == CollectorHelperProtocol.STATUS_OK && supported) DirectHelperOwnerMode.fromProtocolValue(mode) else null
        }

        private const val TAG = "BYDCollectorHelper"
        private const val STATUS_NO_BINDER = -900
        private const val STATUS_TRANSACT_FALSE = -901
        private const val STATUS_EMPTY_REPLY = -902
        private const val STATUS_DEAD_OBJECT = -903
        private const val STATUS_CLIENT_ERROR = -904
    }
}
