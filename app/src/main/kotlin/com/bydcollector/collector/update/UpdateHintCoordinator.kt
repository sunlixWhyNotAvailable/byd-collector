package com.bydcollector.collector.update

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import com.bydcollector.collector.BydCollectorApplication
import java.util.UUID
import kotlin.math.roundToInt

data class UpdateHintRequest(
    val eventId: String,
    val requestedAtElapsedNanos: Long,
    val preferredSizePercent: Int,
    val width: Int,
    val height: Int
)

data class UpdateHintPlacement(
    val xPx: Int,
    val yPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val effectiveSizePercent: Float
)

object UpdateHintCoordinator {
    private val main = Handler(Looper.getMainLooper())
    private val sessionId = UUID.randomUUID().toString()
    private val engine = UpdateHintStateEngine()
    private var revision = 0L
    private var appContext: Context? = null
    private var own = noneRecord()
    private var callback: ((UpdateHintPlacement) -> Unit)? = null
    private var failureCallback: ((String) -> Unit)? = null
    private var admitted = false
    private var admissionGeneration = 0L
    private var lastPlacement: UpdateHintPlacement? = null
    private val waitingForInitial = mutableSetOf<String>()
    private val peers = mutableMapOf<String, PeerConnection>()
    private var packageReceiverRegistered = false
    private var displayListenerRegistered = false
    private var expiryRunnable: Runnable? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val owner = intent.data?.schemeSpecificPart ?: return
            if (owner !in UpdateHintProtocol.OWNERS || owner == UpdateHintProtocol.OWNER_COLLECTOR) return
            when (intent.action) {
                Intent.ACTION_PACKAGE_REMOVED -> if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    engine.current(owner)?.let { engine.confirmedDeath(owner, it.processSessionId) }
                    disconnect(owner, sendUnsubscribe = false)
                    publishLayout()
                }
                Intent.ACTION_PACKAGE_REPLACED -> {
                    engine.current(owner)?.let { engine.confirmedDeath(owner, it.processSessionId) }
                    disconnect(owner, sendUnsubscribe = false)
                    discoverPeer(owner, initial = false)
                    publishLayout()
                }
                Intent.ACTION_PACKAGE_ADDED -> {
                    disconnect(owner, sendUnsubscribe = false)
                    discoverPeer(owner, initial = false)
                }
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) publishLayout()
        }
    }

    fun request(
        context: Context,
        request: UpdateHintRequest,
        onPlacement: (UpdateHintPlacement) -> Unit,
        onUnavailable: (String) -> Unit = {}
    ) = onMain {
        require(request.eventId.isNotBlank() && request.eventId.length <= UpdateHintProtocol.MAX_ID_LENGTH)
        require(request.requestedAtElapsedNanos in 1..SystemClock.elapsedRealtimeNanos())
        require(request.preferredSizePercent in 1..UpdateHintProtocol.MAX_SIZE_PERCENT)
        require(request.width in 1..UpdateHintProtocol.MAX_DIMENSION_PX &&
            request.height in 1..UpdateHintProtocol.MAX_DIMENSION_PX)
        if (own.phase != UpdateHintPhase.NONE) releaseMain(own.eventId, "replaced")
        appContext = context.applicationContext
        callback = onPlacement
        failureCallback = onUnavailable
        admitted = false
        val admission = ++admissionGeneration
        lastPlacement = null
        own = UpdateHintRecord(
            ownerPackage = UpdateHintProtocol.OWNER_COLLECTOR,
            processSessionId = sessionId,
            revision = ++revision,
            eventId = request.eventId,
            phase = UpdateHintPhase.PENDING,
            requestedAtElapsedNanos = request.requestedAtElapsedNanos,
            expiresAtElapsedMs = 0L,
            displayId = Display.DEFAULT_DISPLAY,
            preferredSizePercent = request.preferredSizePercent,
            preferredWidthPx = request.width,
            preferredHeightPx = request.height
        )
        engine.accept(UpdateHintProtocol.OWNER_COLLECTOR, own)
        registerObservers()
        waitingForInitial.clear()
        UpdateHintProtocol.OWNERS.filter { it != UpdateHintProtocol.OWNER_COLLECTOR }
            .forEach { discoverPeer(it, initial = true) }
        sendOwn(UpdateHintProtocol.STATE)
        main.postDelayed({
            if (admissionGeneration == admission && own.eventId == request.eventId && !admitted) admit()
        }, UpdateHintProtocol.initialExchangeDelayMs(
            request.requestedAtElapsedNanos, SystemClock.elapsedRealtimeNanos()))
        maybeAdmit()
    }

    fun markVisible(eventId: String, expiresAtElapsedMs: Long) = onMain {
        if (own.eventId != eventId || own.phase != UpdateHintPhase.PENDING ||
            expiresAtElapsedMs <= SystemClock.elapsedRealtime()) return@onMain
        own = own.copy(revision = ++revision, phase = UpdateHintPhase.VISIBLE,
            expiresAtElapsedMs = expiresAtElapsedMs)
        engine.accept(UpdateHintProtocol.OWNER_COLLECTOR, own)
        sendOwn(UpdateHintProtocol.STATE)
        publishLayout()
    }

    fun updateGeometry(
        eventId: String,
        preferredSizePercent: Int,
        width: Int,
        height: Int
    ) = onMain {
        if (own.eventId != eventId || own.phase == UpdateHintPhase.NONE ||
            preferredSizePercent !in 1..UpdateHintProtocol.MAX_SIZE_PERCENT ||
            width !in 1..UpdateHintProtocol.MAX_DIMENSION_PX ||
            height !in 1..UpdateHintProtocol.MAX_DIMENSION_PX) return@onMain
        if (own.preferredSizePercent == preferredSizePercent && own.preferredWidthPx == width &&
            own.preferredHeightPx == height) return@onMain
        own = own.copy(revision = ++revision, preferredSizePercent = preferredSizePercent,
            preferredWidthPx = width, preferredHeightPx = height)
        engine.accept(UpdateHintProtocol.OWNER_COLLECTOR, own)
        sendOwn(UpdateHintProtocol.STATE)
        publishLayout()
    }

    fun release(eventId: String, reason: String) = onMain { releaseMain(eventId, reason) }

    internal fun ownSnapshot(): UpdateHintRecord = own

    internal fun acceptFromService(authenticatedOwner: String, record: UpdateHintRecord): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!record.hasSaneRequestTime()) return false
        val changed = engine.accept(authenticatedOwner, record)
        if (engine.current(authenticatedOwner)?.processSessionId != record.processSessionId) return false
        waitingForInitial.remove(authenticatedOwner)
        if (changed) publishLayout()
        maybeAdmit()
        return true
    }

    internal fun confirmedDeath(owner: String, processSessionId: String) = onMain {
        if (engine.confirmedDeath(owner, processSessionId)) publishLayout()
    }

    private fun receive(connection: PeerConnection, message: Message): Boolean {
        if (message.what != UpdateHintProtocol.STATE || peers[connection.owner] !== connection) return false
        val context = appContext ?: return true
        val record = UpdateHintWire.record(message.data) ?: return true
        if (record.ownerPackage != connection.owner ||
            !UpdateHintWire.authenticated(context, message.sendingUid, record.ownerPackage) ||
            !record.hasSaneRequestTime()) {
            journal("hint_coordination_rejected", "owner=${record.ownerPackage} uid=${message.sendingUid}")
            return true
        }
        val changed = engine.accept(record.ownerPackage, record)
        if (engine.current(record.ownerPackage)?.processSessionId != record.processSessionId) return true
        connection.acceptedSessionId = record.processSessionId
        waitingForInitial.remove(record.ownerPackage)
        if (changed) publishLayout()
        maybeAdmit()
        return true
    }

    private fun releaseMain(eventId: String, reason: String) {
        if (own.eventId != eventId || own.phase == UpdateHintPhase.NONE) return
        own = noneRecord(++revision)
        engine.accept(UpdateHintProtocol.OWNER_COLLECTOR, own)
        journal("hint_coordination_released", "event=$eventId reason=$reason")
        sendOwn(UpdateHintProtocol.STATE)
        peers.keys.toList().forEach { disconnect(it, sendUnsubscribe = true) }
        unregisterObservers()
        callback = null
        failureCallback = null
        admitted = false
        ++admissionGeneration
        lastPlacement = null
        waitingForInitial.clear()
        expiryRunnable?.let(main::removeCallbacks)
        expiryRunnable = null
    }

    private fun fail(reason: String) {
        val eventId = own.eventId
        val failure = failureCallback
        journal("hint_coordination_failed", "event=$eventId reason=$reason")
        releaseMain(eventId, reason)
        failure?.invoke(reason)
    }

    private fun maybeAdmit() {
        if (!admitted && waitingForInitial.isEmpty()) admit()
    }

    private fun admit() {
        if (admitted || own.phase == UpdateHintPhase.NONE) return
        admitted = true
        publishLayout(includeOwnExpiredPending = true)
    }

    private fun publishLayout(includeOwnExpiredPending: Boolean = false) {
        val context = appContext ?: return
        val bounds = usableBounds(context)
        if (bounds == null) {
            if (own.phase == UpdateHintPhase.PENDING) fail("no_usable_bounds")
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val active = engine.active(nowMs, nowNanos).toMutableList()
        peers.keys.toList().forEach { owner ->
            val snapshot = engine.current(owner)
            if (snapshot != null && !snapshot.isActive(nowMs, nowNanos)) disconnect(owner, true)
        }
        if ((includeOwnExpiredPending || admitted) && active.none { it.ownerPackage == own.ownerPackage } &&
            own.phase == UpdateHintPhase.PENDING) active += own
        val sorted = active.sortedWith(compareBy<UpdateHintRecord> { it.requestedAtElapsedNanos }
            .thenBy { UpdateHintProtocol.OWNERS.indexOf(it.ownerPackage) })
        val density = context.resources.displayMetrics.density
        val result = UpdateHintLayoutEngine.layout(
            sorted.map { UpdateHintLayoutItem(it.ownerPackage, it.preferredSizePercent,
                it.preferredWidthPx, it.preferredHeightPx) },
            bounds, (18f * density).roundToInt(), (8f * density).roundToInt()
        )[UpdateHintProtocol.OWNER_COLLECTOR]
        if (admitted && result == null && own.phase == UpdateHintPhase.PENDING) {
            fail("layout_unavailable")
            return
        }
        if (admitted && result != null) {
            val placement = UpdateHintPlacement(result.xPx, result.yPx, result.widthPx,
                result.heightPx, result.effectiveSizePercent)
            if (placement != lastPlacement) {
                lastPlacement = placement
                callback?.invoke(placement)
                journal("hint_coordination_layout", "event=${own.eventId} x=${placement.xPx} " +
                    "y=${placement.yPx} w=${placement.widthPx} h=${placement.heightPx} " +
                    "scale=${placement.effectiveSizePercent}")
            }
        }
        if (own.phase != UpdateHintPhase.NONE) {
            scheduleExpiry(active.filter { it.isActive(nowMs, nowNanos) }, nowMs, nowNanos)
        }
    }

    private fun scheduleExpiry(active: List<UpdateHintRecord>, nowMs: Long, nowNanos: Long) {
        expiryRunnable?.let(main::removeCallbacks)
        val delay = active.minOfOrNull {
            when (it.phase) {
                UpdateHintPhase.PENDING -> ((it.requestedAtElapsedNanos + 500_000_000L - nowNanos) /
                    1_000_000L).coerceAtLeast(0L)
                UpdateHintPhase.VISIBLE -> (it.expiresAtElapsedMs - nowMs).coerceAtLeast(0L)
                UpdateHintPhase.NONE -> Long.MAX_VALUE
            }
        } ?: return
        expiryRunnable = Runnable { publishLayout() }.also { main.postDelayed(it, delay + 1L) }
    }

    private fun discoverPeer(owner: String, initial: Boolean) {
        val context = appContext ?: return
        if (peers.containsKey(owner)) return
        val service = context.packageManager.queryIntentServices(
            Intent(UpdateHintProtocol.ACTION).setPackage(owner), PackageManager.GET_META_DATA)
            .firstOrNull { it.serviceInfo?.packageName == owner &&
                it.serviceInfo?.metaData?.getInt(UpdateHintProtocol.METADATA_PROTOCOL_VERSION, -1) ==
                UpdateHintProtocol.VERSION }?.serviceInfo ?: return
        if (initial) waitingForInitial += owner
        val connection = PeerConnection(owner)
        peers[owner] = connection
        val bound = try {
            context.bindService(Intent(UpdateHintProtocol.ACTION)
                .setComponent(ComponentName(service.packageName, service.name)), connection,
                Context.BIND_AUTO_CREATE)
        } catch (error: RuntimeException) {
            journal("hint_coordination_bind_failed", "owner=$owner error=${error::class.java.simpleName}")
            false
        }
        if (!bound) {
            peers.remove(owner)
            if (initial) {
                val eventId = own.eventId
                main.postDelayed({
                    if (own.eventId == eventId && own.phase != UpdateHintPhase.NONE &&
                        !peers.containsKey(owner)) discoverPeer(owner, initial = false)
                }, UpdateHintProtocol.initialExchangeDelayMs(
                    own.requestedAtElapsedNanos, SystemClock.elapsedRealtimeNanos()))
            } else {
                waitingForInitial.remove(owner)
            }
        }
    }

    private fun sendOwn(what: Int) {
        peers.values.forEach { it.send(what, own) }
        UpdateHintCoordinationService.broadcastOwn(own)
    }

    private fun disconnect(owner: String, sendUnsubscribe: Boolean) {
        val context = appContext ?: return
        val peer = peers.remove(owner) ?: return
        if (sendUnsubscribe) peer.send(UpdateHintProtocol.UNSUBSCRIBE, own)
        peer.deathRecipient?.let { death ->
            try { peer.binder?.unlinkToDeath(death, 0) } catch (_: NoSuchElementException) { }
        }
        try { context.unbindService(peer) } catch (_: IllegalArgumentException) { }
        waitingForInitial.remove(owner)
    }

    private fun registerObservers() {
        val context = appContext ?: return
        if (!packageReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(packageReceiver, filter)
            }
            packageReceiverRegistered = true
        }
        if (!displayListenerRegistered) {
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .registerDisplayListener(displayListener, main)
            displayListenerRegistered = true
        }
    }

    private fun unregisterObservers() {
        val context = appContext ?: return
        if (packageReceiverRegistered) {
            try { context.unregisterReceiver(packageReceiver) } catch (_: IllegalArgumentException) { }
            packageReceiverRegistered = false
        }
        if (displayListenerRegistered) {
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
    }

    @Suppress("DEPRECATION")
    private fun usableBounds(context: Context): UpdateHintBounds? {
        val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windows.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            UpdateHintBounds(0, 0, metrics.bounds.width() - insets.left - insets.right,
                metrics.bounds.height() - insets.top - insets.bottom)
        } else {
            val size = Point()
            windows.defaultDisplay.getSize(size)
            UpdateHintBounds(0, 0, size.x, size.y)
        }
        return bounds.takeIf { it.width > 0 && it.height > 0 }
    }

    private class PeerConnection(val owner: String) : ServiceConnection {
        var remote: Messenger? = null
        var binder: IBinder? = null
        var deathRecipient: IBinder.DeathRecipient? = null
        var acceptedSessionId: String? = null
        private val callbackMessenger = Messenger(Handler(Looper.getMainLooper()) { receive(this, it) })

        private fun binderDied(deadBinder: IBinder) {
            main.post {
                if (peers[owner] !== this@PeerConnection || binder !== deadBinder) return@post
                acceptedSessionId?.let { engine.confirmedDeath(owner, it) }
                disconnect(owner, sendUnsubscribe = false)
                publishLayout()
            }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (peers[owner] !== this) return
            binder = service
            remote = Messenger(service)
            acceptedSessionId = null
            val death = IBinder.DeathRecipient { binderDied(service) }
            deathRecipient = death
            try { service.linkToDeath(death, 0) } catch (_: RemoteException) {
                binderDied(service)
                return
            }
            send(UpdateHintProtocol.SUBSCRIBE, own)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (peers[owner] === this) remote = null
        }

        override fun onBindingDied(name: ComponentName) {
            if (peers[owner] !== this) return
            disconnect(owner, sendUnsubscribe = false)
            discoverPeer(owner, initial = !admitted)
            publishLayout()
        }

        override fun onNullBinding(name: ComponentName) {
            if (peers[owner] !== this) return
            disconnect(owner, sendUnsubscribe = false)
            maybeAdmit()
        }

        fun send(what: Int, record: UpdateHintRecord) {
            val endpoint = remote ?: return
            val message = Message.obtain(null, what).apply {
                data = UpdateHintWire.bundle(record)
                if (what == UpdateHintProtocol.SUBSCRIBE || what == UpdateHintProtocol.UNSUBSCRIBE) {
                    replyTo = callbackMessenger
                }
            }
            try {
                endpoint.send(message)
            } catch (_: DeadObjectException) {
                binder?.let(::binderDied)
            } catch (_: RemoteException) {
                journal("hint_coordination_send_failed", "owner=$owner")
            }
        }
    }

    private fun journal(message: String, detail: String) {
        (appContext as? BydCollectorApplication)?.recordUpdateEvent(message, detail)
    }

    private fun UpdateHintRecord.hasSaneRequestTime(): Boolean =
        phase == UpdateHintPhase.NONE || requestedAtElapsedNanos <= SystemClock.elapsedRealtimeNanos()

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun noneRecord(nextRevision: Long = 0L) = UpdateHintRecord(
        ownerPackage = UpdateHintProtocol.OWNER_COLLECTOR,
        processSessionId = sessionId,
        revision = nextRevision,
        eventId = "",
        phase = UpdateHintPhase.NONE,
        requestedAtElapsedNanos = 0L,
        expiresAtElapsedMs = 0L,
        displayId = Display.DEFAULT_DISPLAY,
        preferredSizePercent = 0,
        preferredWidthPx = 0,
        preferredHeightPx = 0
    )
}

internal object UpdateHintWire {
    fun bundle(record: UpdateHintRecord) = Bundle().apply {
        values(record).forEach { (key, value) ->
            when (value) {
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is String -> putString(key, value)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun record(data: Bundle): UpdateHintRecord? = try {
        recordValues(data.keySet().associateWith { data[it] })
    } catch (_: RuntimeException) { null }

    internal fun values(record: UpdateHintRecord): Map<String, Any> = linkedMapOf(
        UpdateHintProtocol.KEY_PROTOCOL_VERSION to record.protocolVersion,
        UpdateHintProtocol.KEY_OWNER_PACKAGE to record.ownerPackage,
        UpdateHintProtocol.KEY_PROCESS_SESSION_ID to record.processSessionId,
        UpdateHintProtocol.KEY_REVISION to record.revision,
        UpdateHintProtocol.KEY_EVENT_ID to record.eventId,
        UpdateHintProtocol.KEY_PHASE to record.phase.name,
        UpdateHintProtocol.KEY_REQUESTED_AT_NANOS to record.requestedAtElapsedNanos,
        UpdateHintProtocol.KEY_EXPIRES_AT_MS to record.expiresAtElapsedMs,
        UpdateHintProtocol.KEY_DISPLAY_ID to record.displayId,
        UpdateHintProtocol.KEY_PREFERRED_SIZE_PERCENT to record.preferredSizePercent,
        UpdateHintProtocol.KEY_PREFERRED_WIDTH_PX to record.preferredWidthPx,
        UpdateHintProtocol.KEY_PREFERRED_HEIGHT_PX to record.preferredHeightPx
    )

    internal fun recordValues(data: Map<String, Any?>): UpdateHintRecord? = try {
        val typesValid = data.keys == KEYS && data[UpdateHintProtocol.KEY_PROTOCOL_VERSION] is Int &&
            data[UpdateHintProtocol.KEY_OWNER_PACKAGE] is String &&
            data[UpdateHintProtocol.KEY_PROCESS_SESSION_ID] is String && data[UpdateHintProtocol.KEY_REVISION] is Long &&
            data[UpdateHintProtocol.KEY_EVENT_ID] is String && data[UpdateHintProtocol.KEY_PHASE] is String &&
            data[UpdateHintProtocol.KEY_REQUESTED_AT_NANOS] is Long && data[UpdateHintProtocol.KEY_EXPIRES_AT_MS] is Long &&
            data[UpdateHintProtocol.KEY_DISPLAY_ID] is Int && data[UpdateHintProtocol.KEY_PREFERRED_SIZE_PERCENT] is Int &&
            data[UpdateHintProtocol.KEY_PREFERRED_WIDTH_PX] is Int && data[UpdateHintProtocol.KEY_PREFERRED_HEIGHT_PX] is Int
        if (!typesValid) null else
            UpdateHintRecord(
                protocolVersion = data[UpdateHintProtocol.KEY_PROTOCOL_VERSION] as Int,
                ownerPackage = data[UpdateHintProtocol.KEY_OWNER_PACKAGE] as String,
                processSessionId = data[UpdateHintProtocol.KEY_PROCESS_SESSION_ID] as String,
                revision = data[UpdateHintProtocol.KEY_REVISION] as Long,
                eventId = data[UpdateHintProtocol.KEY_EVENT_ID] as String,
                phase = UpdateHintPhase.valueOf(data[UpdateHintProtocol.KEY_PHASE] as String),
                requestedAtElapsedNanos = data[UpdateHintProtocol.KEY_REQUESTED_AT_NANOS] as Long,
                expiresAtElapsedMs = data[UpdateHintProtocol.KEY_EXPIRES_AT_MS] as Long,
                displayId = data[UpdateHintProtocol.KEY_DISPLAY_ID] as Int,
                preferredSizePercent = data[UpdateHintProtocol.KEY_PREFERRED_SIZE_PERCENT] as Int,
                preferredWidthPx = data[UpdateHintProtocol.KEY_PREFERRED_WIDTH_PX] as Int,
                preferredHeightPx = data[UpdateHintProtocol.KEY_PREFERRED_HEIGHT_PX] as Int
            ).takeIf { it.isValid() }
    } catch (_: RuntimeException) { null }

    fun authenticated(context: Context, sendingUid: Int, claimedOwner: String): Boolean =
        authenticatedPackages(context.packageManager.getPackagesForUid(sendingUid), claimedOwner)

    internal fun authenticatedPackages(packagesForUid: Array<String>?, claimedOwner: String): Boolean =
        claimedOwner in UpdateHintProtocol.OWNERS && packagesForUid?.contains(claimedOwner) == true

    private val KEYS = setOf(
        UpdateHintProtocol.KEY_PROTOCOL_VERSION, UpdateHintProtocol.KEY_OWNER_PACKAGE,
        UpdateHintProtocol.KEY_PROCESS_SESSION_ID, UpdateHintProtocol.KEY_REVISION,
        UpdateHintProtocol.KEY_EVENT_ID, UpdateHintProtocol.KEY_PHASE,
        UpdateHintProtocol.KEY_REQUESTED_AT_NANOS, UpdateHintProtocol.KEY_EXPIRES_AT_MS,
        UpdateHintProtocol.KEY_DISPLAY_ID, UpdateHintProtocol.KEY_PREFERRED_SIZE_PERCENT,
        UpdateHintProtocol.KEY_PREFERRED_WIDTH_PX, UpdateHintProtocol.KEY_PREFERRED_HEIGHT_PX
    )
}
