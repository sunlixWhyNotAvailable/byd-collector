package com.bydcollector.collector.update

import android.app.Service
import android.content.Intent
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.bydcollector.collector.BydCollectorApplication
import java.lang.ref.WeakReference

internal data class UpdateHintSubscriptionIdentity(val owner: String, val sessionId: String) {
    fun matches(record: UpdateHintRecord): Boolean =
        owner == record.ownerPackage && sessionId == record.processSessionId
}

class UpdateHintCoordinationService : Service() {
    private enum class SendResult { SENT, TRANSIENT_FAILURE, DEAD }

    private data class Subscription(
        val identity: UpdateHintSubscriptionIdentity,
        val reply: Messenger,
        val death: IBinder.DeathRecipient
    )

    private val subscriptions = mutableMapOf<IBinder, Subscription>()
    private lateinit var endpoint: Messenger

    override fun onCreate() {
        super.onCreate()
        endpoint = Messenger(Handler(Looper.getMainLooper(), ::handleMessage))
        current = WeakReference(this)
    }

    override fun onBind(intent: Intent?): IBinder? =
        endpoint.binder.takeIf { intent?.action == UpdateHintProtocol.ACTION }

    override fun onDestroy() {
        subscriptions.forEach { (binder, subscription) ->
            try { binder.unlinkToDeath(subscription.death, 0) } catch (_: NoSuchElementException) { }
        }
        subscriptions.clear()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun handleMessage(message: Message): Boolean {
        if (message.what !in UpdateHintProtocol.SUBSCRIBE..UpdateHintProtocol.UNSUBSCRIBE) return false
        val record = UpdateHintWire.record(message.data) ?: return true
        if (!UpdateHintWire.authenticated(this, message.sendingUid, record.ownerPackage) ||
            record.ownerPackage == UpdateHintProtocol.OWNER_COLLECTOR) {
            journal("hint_coordination_rejected", "owner=${record.ownerPackage} uid=${message.sendingUid}")
            return true
        }
        when (message.what) {
            UpdateHintProtocol.SUBSCRIBE -> subscribe(record, message.replyTo)
            UpdateHintProtocol.STATE -> if (subscriptions.values.any { it.identity.matches(record) }) {
                UpdateHintCoordinator.acceptFromService(record.ownerPackage, record)
            }
            UpdateHintProtocol.UNSUBSCRIBE -> unsubscribe(record, message.replyTo)
        }
        return true
    }

    private fun subscribe(record: UpdateHintRecord, reply: Messenger?) {
        if (reply == null) return
        val binder = reply.binder
        val death = IBinder.DeathRecipient {
            Handler(Looper.getMainLooper()).post {
                val removed = subscriptions.remove(binder) ?: return@post
                UpdateHintCoordinator.confirmedDeath(removed.identity.owner, removed.identity.sessionId)
            }
        }
        try {
            binder.linkToDeath(death, 0)
            if (!UpdateHintCoordinator.acceptFromService(record.ownerPackage, record)) {
                binder.unlinkToDeath(death, 0)
                return
            }
            subscriptions.entries.filter { it.value.identity.owner == record.ownerPackage }.toList()
                .forEach { (oldBinder, old) ->
                    subscriptions.remove(oldBinder)
                    try { oldBinder.unlinkToDeath(old.death, 0) } catch (_: NoSuchElementException) { }
            }
            subscriptions[binder] = Subscription(
                UpdateHintSubscriptionIdentity(record.ownerPackage, record.processSessionId), reply, death)
            if (send(reply, UpdateHintCoordinator.ownSnapshot()) == SendResult.DEAD) {
                subscriptions.remove(binder)
                try { binder.unlinkToDeath(death, 0) } catch (_: NoSuchElementException) { }
                UpdateHintCoordinator.confirmedDeath(record.ownerPackage, record.processSessionId)
            }
        } catch (_: DeadObjectException) {
            UpdateHintCoordinator.confirmedDeath(record.ownerPackage, record.processSessionId)
        } catch (_: RemoteException) {
            try { binder.unlinkToDeath(death, 0) } catch (_: NoSuchElementException) { }
        }
    }

    private fun unsubscribe(record: UpdateHintRecord, reply: Messenger?) {
        val match = reply?.binder?.let { binder ->
            subscriptions[binder]?.takeIf { it.identity.matches(record) }?.let { binder to it }
        } ?: subscriptions.entries.firstOrNull { it.value.identity.matches(record) }?.toPair()
        match?.let { (binder, subscription) ->
            subscriptions.remove(binder)
            try { binder.unlinkToDeath(subscription.death, 0) } catch (_: NoSuchElementException) { }
        }
    }

    private fun broadcast(record: UpdateHintRecord) {
        subscriptions.entries.toList().forEach { (binder, subscription) ->
            if (send(subscription.reply, record) == SendResult.DEAD) {
                subscriptions.remove(binder)
                try { binder.unlinkToDeath(subscription.death, 0) } catch (_: NoSuchElementException) { }
                UpdateHintCoordinator.confirmedDeath(
                    subscription.identity.owner, subscription.identity.sessionId)
            }
        }
    }

    private fun send(reply: Messenger, record: UpdateHintRecord): SendResult = try {
        reply.send(Message.obtain(null, UpdateHintProtocol.STATE).apply {
            data = UpdateHintWire.bundle(record)
        })
        SendResult.SENT
    } catch (_: DeadObjectException) {
        SendResult.DEAD
    } catch (_: RemoteException) {
        SendResult.TRANSIENT_FAILURE
    }

    private fun journal(message: String, detail: String) {
        (applicationContext as? BydCollectorApplication)?.recordUpdateEvent(message, detail)
    }

    companion object {
        private var current: WeakReference<UpdateHintCoordinationService>? = null

        internal fun broadcastOwn(record: UpdateHintRecord) {
            val service = current?.get() ?: return
            if (Looper.myLooper() == Looper.getMainLooper()) service.broadcast(record)
            else Handler(Looper.getMainLooper()).post { service.broadcast(record) }
        }
    }
}
