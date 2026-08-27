package com.bydcollector.collector.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.BuildConfig
import com.bydcollector.collector.data.trips.TripCompression
import com.bydcollector.collector.data.trips.TripCompressionResult
import com.bydcollector.collector.util.namedSingleThreadExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.concurrent.RejectedExecutionException

data class TripCompressionState(
    val running: Boolean = false,
    val stepIndex: Int = 0,
    val completed: Boolean = false,
    val beforeBytes: Long = 0L,
    val afterBytes: Long = 0L,
    val error: String? = null
)

/** Runs lossless Trips compression outside the UI while keeping the source database live. */
class TripCompressionService : Service() {
    private val executor = namedSingleThreadExecutor("byd-trips-compression")
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var instanceToken = 0L
    @Volatile
    private var workSubmitted = false
    @Volatile
    private var workerEntered = false
    @Volatile
    private var currentStep = FIRST_STEP

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val token = intent.getLongExtra(EXTRA_TOKEN, 0L)
        if (token == 0L || !bindToken(token)) return START_NOT_STICKY
        if (workSubmitted) return START_NOT_STICKY
        workSubmitted = true
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            acquireWakeLock()
            executor.execute { runCompression(token, startId) }
        } catch (error: RejectedExecutionException) {
            completeFailure(token, startId, error, null, SystemClock.elapsedRealtime())
        } catch (error: RuntimeException) {
            completeFailure(token, startId, error, null, SystemClock.elapsedRealtime())
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        releaseWakeLock()
        val queuedToken = synchronized(LOCK) {
            instanceToken.takeIf { it != 0L && activeToken == it && !workerEntered }
        }
        if (queuedToken != null) {
            completeFailure(
                queuedToken,
                startId = null,
                error = IllegalStateException("Trip compression service stopped before work started"),
                application = null,
                startedAt = SystemClock.elapsedRealtime()
            )
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun bindToken(token: Long): Boolean = synchronized(LOCK) {
        if (instanceToken != 0L || activeToken != token) return@synchronized false
        instanceToken = token
        true
    }

    private fun runCompression(token: Long, startId: Int) {
        val startedAt = SystemClock.elapsedRealtime()
        synchronized(LOCK) {
            if (activeToken != token || instanceToken != token) return
            workerEntered = true
            currentStep = FIRST_STEP
        }
        var application: BydCollectorApplication? = null
        var result: TripCompressionResult? = null
        var failure: Throwable? = null
        var lastPhaseElapsed = 0L
        try {
            val app = applicationContext as BydCollectorApplication
            application = app
            appendEvent(
                app,
                category = "trips_compression_started",
                message = "Trips compression started",
                detail = "elapsed_ms=0"
            )
            val store = BydCollectorApplication.trips(applicationContext)
            val compression = TripCompression(
                context = applicationContext,
                store = store,
                fileBarrier = app.tripsFileOperationLock,
                onPhase = { phase ->
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    val delta = elapsed - lastPhaseElapsed
                    lastPhaseElapsed = elapsed
                    appendEvent(
                        app,
                        category = "trips_compression_phase",
                        message = "Trips compression phase",
                        detail = "phase=${phase.name} elapsed_ms=$elapsed delta_ms=$delta"
                    )
                }
            )
            result = compression.run { step -> publishStep(token, step) }
        } catch (error: Throwable) {
            failure = error
        } finally {
            val completedResult = result
            if (completedResult != null) {
                completeSuccess(token, startId, completedResult, application, startedAt)
            } else {
                completeFailure(
                    token,
                    startId,
                    failure ?: IllegalStateException("Trips compression produced no result"),
                    application,
                    startedAt
                )
            }
        }
    }

    private fun publishStep(token: Long, step: Int) {
        if (step !in FIRST_STEP..LAST_STEP) return
        synchronized(LOCK) {
            if (activeToken == token) {
                currentStep = step
                _state.value = _state.value.copy(running = true, stepIndex = step)
            }
        }
    }

    private fun completeSuccess(
        token: Long,
        startId: Int,
        result: TripCompressionResult,
        application: BydCollectorApplication?,
        startedAt: Long
    ) {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        application?.let {
            appendEvent(
                it,
                category = "trips_compression_completed",
                message = "Trips compression completed",
                detail = "duration_ms=$elapsed before_bytes=${result.beforeBytes} after_bytes=${result.afterBytes} saved_bytes=${result.savedBytes}"
            )
        }
        finishRun(token, startId, TripCompressionState(
            stepIndex = LAST_STEP, completed = true,
            beforeBytes = result.beforeBytes, afterBytes = result.afterBytes
        ))
    }

    private fun completeFailure(
        token: Long,
        startId: Int?,
        error: Throwable,
        application: BydCollectorApplication?,
        startedAt: Long
    ) {
        val detail = formatError(error)
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        application?.let {
            appendEvent(
                it,
                category = "trips_compression_failed",
                message = "Trips compression failed",
                detail = "duration_ms=$elapsed error_class=${error::class.java.simpleName} step=$currentStep stack=${error.stackTrace.take(3).joinToString("|") { frame -> "${frame.className}.${frame.methodName}:${frame.lineNumber}" }}"
            )
        }
        finishRun(token, startId, TripCompressionState(stepIndex = currentStep, error = detail))
    }

    private fun finishRun(token: Long, startId: Int?, result: TripCompressionState) {
        // Serialize cleanup with Android's onStart/onDestroy callbacks. A new reservation
        // cannot acquire a wake lock/foreground notification that this run then removes.
        mainHandler.post {
            synchronized(LOCK) {
                if (activeToken != token) return@post
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (startId != null) stopSelf(startId)
                clearInstanceIfOwned(token)
                _state.value = result
                activeToken = null
            }
        }
    }

    private fun clearInstanceIfOwned(token: Long) {
        if (instanceToken == token) {
            instanceToken = 0L
            workSubmitted = false
            workerEntered = false
            currentStep = FIRST_STEP
        }
    }

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:trips-compression")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(BuildConfig.COLLECTOR_DISPLAY_NAME)
            .setContentText("Compressing Trips database")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                BuildConfig.COLLECTOR_DISPLAY_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun appendEvent(
        application: BydCollectorApplication,
        category: String,
        message: String,
        detail: String?
    ) {
        runCatching {
            application.operationalEventJournal.append(
                timestamp = Instant.now().toString(),
                elapsedMs = SystemClock.elapsedRealtime(),
                category = category,
                message = message,
                detail = detail
            )
        }
    }

    private fun formatError(error: Throwable): String {
        val message = error.message?.take(ERROR_MESSAGE_LIMIT).orEmpty()
        return if (message.isBlank()) error::class.java.simpleName
        else "${error::class.java.simpleName}: $message"
    }

    companion object {
        private const val ACTION_START = "${BuildConfig.ACTION_PREFIX}.action.RUN_TRIPS_COMPRESSION"
        private const val EXTRA_TOKEN = "token"
        private const val CHANNEL_ID = "trips_compression"
        private const val NOTIFICATION_ID = 1003
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60_000L
        private const val FIRST_STEP = 0
        private const val LAST_STEP = 5
        private const val ERROR_MESSAGE_LIMIT = 300
        private val LOCK = Any()
        private val _state = MutableStateFlow(TripCompressionState())
        private var activeToken: Long? = null
        private var nextToken = 0L

        val state: StateFlow<TripCompressionState> = _state.asStateFlow()

        fun start(context: Context): Boolean {
            val token = synchronized(LOCK) {
                if (activeToken != null || _state.value.running) return false
                nextToken += 1L
                activeToken = nextToken
                _state.value = TripCompressionState(running = true, stepIndex = FIRST_STEP)
                nextToken
            }
            val intent = Intent(context.applicationContext, TripCompressionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TOKEN, token)
            }
            return try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
                true
            } catch (error: RuntimeException) {
                synchronized(LOCK) {
                    if (activeToken == token) {
                        activeToken = null
                        _state.value = TripCompressionState(error = formatDispatchError(error))
                    }
                }
                (context.applicationContext as? BydCollectorApplication)?.let { application ->
                    runCatching {
                        application.operationalEventJournal.append(
                            timestamp = Instant.now().toString(),
                            elapsedMs = SystemClock.elapsedRealtime(),
                            category = "trips_compression_failed",
                            message = "Trips compression failed",
                            detail = "error_class=${error::class.java.simpleName}"
                        )
                    }
                }
                false
            }
        }

        fun dismissResult(): Boolean = synchronized(LOCK) {
            if (activeToken != null || _state.value.running) return@synchronized false
            _state.value = TripCompressionState()
            true
        }

        private fun formatDispatchError(error: Throwable): String {
            val message = error.message?.take(ERROR_MESSAGE_LIMIT).orEmpty()
            return if (message.isBlank()) error::class.java.simpleName
            else "${error::class.java.simpleName}: $message"
        }
    }
}
