package com.bydcollector.collector.update

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.view.doOnPreDraw
import com.bydcollector.collector.BydCollectorApplication
import com.bydcollector.collector.MainActivity
import com.bydcollector.collector.service.CollectorSettings
import com.bydcollector.collector.ui.compose.AppTab
import com.bydcollector.collector.ui.compose.UiLanguage
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Owns only Collector's native window; coordination never owns checks or vehicle work. */
internal class UpdateHintOverlay(private val app: BydCollectorApplication) {
    private val handler = Handler(Looper.getMainLooper())
    private val settings = CollectorSettings(app)
    private val lifetime = UpdateHintLifetime()
    private var manager: WindowManager? = null
    private var card: UpdateHintCardView? = null
    private var container: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var info: UpdateInfo? = null
    private var eventId: String? = null
    private var attached = false
    private var preferredWidth = 0
    private var preferredHeight = 0
    private var preferredSize = 100
    private var targetPlacement: UpdateHintPlacement? = null
    private var geometryAnimation: ValueAnimator? = null
    private var darkTheme = true
    private var pendingOpenResultId: Long? = null
    private val expire = Runnable {
        if (lifetime.isExpired(SystemClock.elapsedRealtime())) dismiss("expired")
    }
    private val drawTimeout = Runnable {
        if (lifetime.expiresAtElapsedMs == 0L) dismiss("not_drawn")
    }

    val activeResultId: Long? get() = lifetime.activeResultId

    fun show(resultId: Long, available: UpdateInfo) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (lifetime.activeResultId == resultId) return
        dismiss("replaced")
        try {
            if (!settings.isUpdateHintEnabled() || !Settings.canDrawOverlays(app)) {
                app.recordUpdateEvent("hint_unavailable", "overlay_allowed=${Settings.canDrawOverlays(app)}")
                return
            }
            val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            if (display == null) {
                app.recordUpdateEvent("hint_unavailable", "reason=no_main_display")
                return
            }
            val requestedAt = SystemClock.elapsedRealtimeNanos()
            val displayContext = app.createDisplayContext(display)
            val context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else displayContext
            val appearance = settings.updateHintAppearance()
            val view = UpdateHintCardView(context, onOpen = ::open, onClose = { dismiss("closed") }).apply {
                bind(available.version, UiLanguage.fromCode(settings.uiLanguageCode()), darkTheme, appearance)
                pivotX = 0f
                pivotY = 0f
            }
            card = view
            info = available
            manager = context.getSystemService(WindowManager::class.java)
            container = FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                addView(view)
            }
            measurePreferred(appearance)
            params = WindowManager.LayoutParams(
                preferredWidth, preferredHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags(appearance), PixelFormat.TRANSLUCENT
            ).apply {
                // Coordinates are relative to WindowManager's decor-adjusted main display,
                // exactly as in the shared coordinator. Do not add system insets twice.
                gravity = Gravity.TOP or Gravity.LEFT
                alpha = appearance.alpha
                title = "BYD Collector update hint"
            }
            val id = resultId.toString()
            eventId = id
            lifetime.begin(resultId)
            view.doOnPreDraw {
                if (card !== view || activeResultId != resultId || !attached) return@doOnPreDraw
                lifetime.shown(SystemClock.elapsedRealtime())
                handler.removeCallbacks(drawTimeout)
                handler.postDelayed(expire, (lifetime.expiresAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                UpdateHintCoordinator.markVisible(id, lifetime.expiresAtElapsedMs)
                app.recordUpdateEvent("hint_shown", "result_id=$resultId version=${available.version}")
            }
            // Bounded safety net even if no window can be admitted/drawn.
            handler.postDelayed(drawTimeout, UpdateHintProtocol.INITIAL_EXCHANGE_MS + DRAW_TIMEOUT_MS)
            UpdateHintCoordinator.request(
                context,
                UpdateHintRequest(id, requestedAt, preferredSize, preferredWidth, preferredHeight),
                onPlacement = { placement -> if (eventId == id) applyPlacement(placement) },
                onUnavailable = { reason -> if (eventId == id) dismiss(reason) }
            )
        } catch (error: RuntimeException) {
            app.recordUpdateEvent("hint_window_failed", error::class.java.simpleName)
            dismiss("window_failed")
        }
    }

    fun refresh(dark: Boolean = darkTheme) {
        darkTheme = dark
        if (!settings.isUpdateHintEnabled()) {
            dismiss("disabled")
            return
        }
        val view = card ?: return
        val layout = params ?: return
        val available = info ?: return
        val id = eventId ?: return
        try {
            val oldWidth = preferredWidth
            val oldHeight = preferredHeight
            val oldSize = preferredSize
            val previousTarget = targetPlacement
            val renderedWidth = preferredWidth * view.scaleX
            val appearance = settings.updateHintAppearance()
            view.bind(available.version, UiLanguage.fromCode(settings.uiLanguageCode()), darkTheme, appearance)
            measurePreferred(appearance)
            val geometryChanged = oldWidth != preferredWidth || oldHeight != preferredHeight || oldSize != preferredSize
            if (geometryChanged) {
                geometryAnimation?.cancel()
                view.scaleX = renderedWidth / preferredWidth
                view.scaleY = view.scaleX
            }
            layout.alpha = appearance.alpha
            layout.flags = flags(appearance)
            if (attached) resizeWindow(layout.x, layout.y, view.scaleX)
            // Only preferred geometry is exchanged; automatic shrink is never fed back.
            UpdateHintCoordinator.updateGeometry(id, preferredSize, preferredWidth, preferredHeight)
            // A new preference can retain the same auto-fitted placement. Finish
            // its animation even when the coordinator correctly suppresses an identical callback.
            if (geometryChanged && attached && targetPlacement === previousTarget) {
                previousTarget?.let(::applyPlacement)
            }
        } catch (error: RuntimeException) {
            app.recordUpdateEvent("hint_window_failed", error::class.java.simpleName)
            dismiss("refresh_failed")
        }
    }

    private fun measurePreferred(appearance: UpdateHintAppearance) {
        val view = checkNotNull(card)
        preferredSize = appearance.normalized().sizePercent
        preferredWidth = UpdateHintCardView.widthPx(view.context, appearance)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(preferredWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        preferredHeight = view.measuredHeight.coerceAtLeast(1)
        view.layoutParams = FrameLayout.LayoutParams(preferredWidth, preferredHeight)
    }

    private fun applyPlacement(placement: UpdateHintPlacement) {
        val host = container ?: return
        val view = card ?: return
        val layout = params ?: return
        val windows = manager ?: return
        targetPlacement = placement
        val scale = placement.effectiveSizePercent / preferredSize
        try {
            geometryAnimation?.cancel()
            if (!attached) {
                layout.x = placement.xPx
                layout.y = placement.yPx
                layout.width = placement.widthPx
                layout.height = placement.heightPx
                view.scaleX = scale
                view.scaleY = scale
                windows.addView(host, layout)
                attached = true
                view.translationX = -preferredWidth.toFloat()
                view.animate().translationX(0f).setDuration(ANIMATION_MS).setInterpolator(DecelerateInterpolator()).start()
                handler.removeCallbacks(drawTimeout)
                handler.postDelayed(drawTimeout, DRAW_TIMEOUT_MS)
                return
            }
            val fromX = layout.x
            val fromY = layout.y
            val fromScale = view.scaleX
            geometryAnimation = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ANIMATION_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { animation ->
                    if (container !== host) return@addUpdateListener
                    val fraction = animation.animatedValue as Float
                    resizeWindow(
                        (fromX + (placement.xPx - fromX) * fraction).roundToInt(),
                        (fromY + (placement.yPx - fromY) * fraction).roundToInt(),
                        fromScale + (scale - fromScale) * fraction
                    )
                }
                start()
            }
        } catch (error: RuntimeException) {
            app.recordUpdateEvent("hint_window_failed", error::class.java.simpleName)
            dismiss("placement_failed")
        }
    }

    private fun resizeWindow(x: Int, y: Int, scale: Float) {
        val view = card ?: return
        val host = container ?: return
        val layout = params ?: return
        view.scaleX = scale
        view.scaleY = scale
        layout.x = x
        layout.y = y
        // Ceil matches shared layout and leaves no clipped final pixel at fractional scales.
        layout.width = ceil(preferredWidth * scale).toInt().coerceAtLeast(1)
        layout.height = ceil(preferredHeight * scale).toInt().coerceAtLeast(1)
        try {
            manager?.updateViewLayout(host, layout)
        } catch (error: RuntimeException) {
            app.recordUpdateEvent("hint_window_failed", error::class.java.simpleName)
            dismiss("relayout_failed")
        }
    }

    fun dismiss(reason: String) {
        val host = container
        val windows = manager
        val resultId = activeResultId
        val id = eventId
        handler.removeCallbacks(expire)
        handler.removeCallbacks(drawTimeout)
        geometryAnimation?.cancel()
        geometryAnimation = null
        card?.animate()?.cancel()
        card = null
        container = null
        manager = null
        params = null
        info = null
        eventId = null
        targetPlacement = null
        attached = false
        lifetime.clear()
        if (host != null && windows != null) {
            runCatching { windows.removeViewImmediate(host) }
                .onFailure { if (host.isAttachedToWindow) app.recordUpdateEvent("hint_remove_failed", it::class.java.simpleName) }
        }
        if (id != null) {
            runCatching { UpdateHintCoordinator.release(id, reason) }
                .onFailure { app.recordUpdateEvent("hint_release_failed", it::class.java.simpleName) }
        }
        if (resultId != null) app.recordUpdateEvent("hint_removed", "result_id=$resultId reason=$reason")
    }

    fun consumeOpenRequest(): Boolean {
        val resultId = pendingOpenResultId ?: return false
        pendingOpenResultId = null
        return app.updateChecks.snapshot().availableResultId == resultId
    }

    fun shutdown() {
        pendingOpenResultId = null
        dismiss("shutdown")
    }

    private fun open() {
        val resultId = activeResultId ?: return
        if (app.updateChecks.snapshot().availableResultId != resultId) {
            dismiss("stale")
            return
        }
        pendingOpenResultId = resultId
        app.navigationSession.selectTab(AppTab.EXTRA)
        dismiss("opened")
        try {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ))
        } catch (error: RuntimeException) {
            pendingOpenResultId = null
            app.recordUpdateEvent("hint_open_failed", error::class.java.simpleName)
        }
    }

    private fun flags(appearance: UpdateHintAppearance) =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            if (appearance.alpha == 0f) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0

    companion object {
        private const val ANIMATION_MS = 220L
        private const val DRAW_TIMEOUT_MS = 500L
    }
}
