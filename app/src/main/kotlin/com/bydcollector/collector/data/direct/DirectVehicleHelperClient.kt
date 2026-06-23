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
        return transact(CollectorHelperProtocol.TX_PING) { }?.status == 0
    }

    override fun read(entry: DirectFidEntry): DirectHelperReadResult {
        return transact(CollectorHelperProtocol.TX_READ) { data ->
            data.writeInt(entry.tx)
            data.writeInt(entry.dev)
            data.writeInt(entry.fid)
        } ?: DirectHelperReadResult(status = STATUS_NO_BINDER, raw = null, error = "helper binder unavailable")
    }

    private fun transact(code: Int, writeArgs: (Parcel) -> Unit): DirectHelperReadResult? {
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
