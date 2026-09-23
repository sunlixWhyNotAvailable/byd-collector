package com.bydcollector.collector.update

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
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
internal class UpdateHintOverlay(
    private val app: BydCollectorApplication,
    private val attachWindow: (WindowManager, View, WindowManager.LayoutParams) -> Unit =
        { windows, host, layout -> windows.addView(host, layout) }
) {
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
    private var attemptResultId: Long? = null
    private var attemptToken: Long? = null
    private val expire = Runnable {
        if (lifetime.isExpired(SystemClock.elapsedRealtime())) dismiss("expired")
    }
    private val drawTimeout = Runnable {
        if (lifetime.expiresAtElapsedMs == 0L) {
            if (dismissIfDisplayNotReady("first_draw_timeout")) return@Runnable
            dismiss("not_drawn")
        }
    }

    val activeResultId: Long? get() = lifetime.activeResultId

    fun show(resultId: Long, attemptToken: Long, available: UpdateInfo) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!app.updateRuntime.canAttachHint(resultId, attemptToken)) {
            app.updateRuntime.onHintCreationSkipped(resultId, attemptToken, "not_eligible")
            return
        }
        if (this.attemptResultId == resultId && this.attemptToken == attemptToken && card != null) return
        dismiss("replaced")
        this.attemptResultId = resultId
        this.attemptToken = attemptToken
        try {
            if (!settings.isUpdateHintEnabled()) {
                app.recordUpdateEvent("hint_unavailable", "overlay_allowed=false reason=disabled")
                app.updateRuntime.onHintCreationSkipped(resultId, attemptToken, "disabled")
                dismiss("disabled")
                return
            }
            if (!Settings.canDrawOverlays(app)) {
                app.recordUpdateEvent("hint_unavailable", "overlay_allowed=false reason=permission_lost")
                app.updateRuntime.onHintCreationSkipped(resultId, attemptToken, "permission_lost")
                dismiss("permission_lost")
                return
            }
            val display = mainDisplayForUpdateHintIfReady(app)
            if (display == null) {
                app.updateRuntime.onHintCreationSkipped(resultId, attemptToken, "display_not_ready")
                dismissIfDisplayNotReady("show")
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
            manager = checkNotNull(context.getSystemService(WindowManager::class.java))
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
            val id = eventId(resultId, attemptToken)
            eventId = id
            lifetime.begin(resultId)
            view.doOnPreDraw {
                if (card !== view || !isCurrentAttempt(resultId, attemptToken) || activeResultId != resultId || !attached) return@doOnPreDraw
                if (!app.updateRuntime.canMaintainHint(resultId, attemptToken) ||
                    !app.updateRuntime.onHintPresented(resultId, attemptToken)) {
                    if (card === view && activeResultId == resultId && attached) {
                        dismiss("presentation_rejected")
                    }
                    return@doOnPreDraw
                }
                if (card !== view || !isCurrentAttempt(resultId, attemptToken) || activeResultId != resultId || !attached) return@doOnPreDraw
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
                onPlacement = { placement -> if (eventId == id && isCurrentAttempt(resultId, attemptToken)) applyPlacement(placement) },
                onUnavailable = { reason ->
                    if (eventId == id && isCurrentAttempt(resultId, attemptToken)) {
                        if (!app.updateRuntime.onHintCreationSkipped(resultId, attemptToken, "coordinator_unavailable")) {
                            app.updateRuntime.onHintCreationUnavailable(resultId, attemptToken, "coordinator_unavailable")
                        }
                        dismiss(reason)
                    }
                }
            )
        } catch (error: RuntimeException) {
            if (app.updateRuntime.canAttachHint(resultId, attemptToken)) {
                app.recordUpdateEvent("hint_window_failed", error::class.java.simpleName)
                app.updateRuntime.onHintCreationFailed(resultId, attemptToken, "preparation", error::class.java.simpleName)
                dismiss("window_failed")
            } else {
                app.updateRuntime.onHintCreationSkipped(resultId, attemptToken, "eligibility_changed_during_preparation")
                dismissIfDisplayNotReady("preparation")
                dismiss("creation_cancelled")
            }
        }
    }

    fun refresh(dark: Boolean = darkTheme) {
        darkTheme = dark
        if (!settings.isUpdateHintEnabled()) {
            dismiss("disabled")
            return
        }
        val view = card ?: return
        if (dismissIfDisplayNotReady("refresh")) return
        val layout = params ?: return
        val available = info ?: return
        val id = eventId ?: return
        val resultId = attemptResultId ?: return
        val token = attemptToken ?: return
        if (!app.updateRuntime.canMaintainHint(resultId, token)) {
            dismiss("unavailable")
            return
        }
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
            if (!dismissIfDisplayNotReady("refresh")) {
                app.recordUpdateEvent("hint_window_failed", error::class.java.simpleName)
                dismiss("refresh_failed")
            }
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
        val resultId = attemptResultId ?: return
        val token = attemptToken ?: return
        val eligible = if (attached) app.updateRuntime.canMaintainHint(resultId, token)
            else app.updateRuntime.canAttachHint(resultId, token)
        if (!eligible) {
            if (!attached) app.updateRuntime.onHintCreationSkipped(resultId, token, "eligibility_changed_before_attachment")
            else app.updateRuntime.onHintCreationUnavailable(resultId, token, "eligibility_changed_while_attached")
            dismiss("unavailable")
            return
        }
        if (dismissIfDisplayNotReady("placement")) return
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
                if (!app.updateRuntime.canAttachHint(resultId, token) || dismissIfDisplayNotReady("attachment")) {
                    app.updateRuntime.onHintCreationSkipped(resultId, token, "eligibility_changed_at_attachment")
                    dismiss("attachment_cancelled")
                    return
                }
                attachWindow(windows, host, layout)
                if (!app.updateRuntime.onHintCreationAttached(resultId, token)) {
                    runCatching { windows.removeViewImmediate(host) }
                    dismiss("stale_attachment")
                    return
                }
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
            if (app.updateRuntime.canAttachHint(resultId, token) && !dismissIfDisplayNotReady("attachment")) {
                app.recordUpdateEvent("hint_window_failed", error::class.java.simpleName)
                if (!attached) app.updateRuntime.onHintCreationFailed(resultId, token, "attachment", error::class.java.simpleName)
                dismiss("placement_failed")
            } else {
                app.updateRuntime.onHintCreationSkipped(resultId, token, "eligibility_changed_during_attachment")
                dismissIfDisplayNotReady("attachment")
                dismiss("attachment_cancelled")
            }
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
            if (!dismissIfDisplayNotReady("relayout")) {
                app.recordUpdateEvent("hint_window_failed", error::class.java.simpleName)
                dismiss("relayout_failed")
            }
        }
    }

    fun dismiss(reason: String) {
        val host = container
        val windows = manager
        val resultId = attemptResultId ?: activeResultId
        val token = attemptToken
        val id = eventId
        if (resultId != null && token != null) app.updateRuntime.onHintCreationUnavailable(resultId, token, reason)
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
        attemptResultId = null
        attemptToken = null
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

    private fun dismissIfDisplayNotReady(phase: String): Boolean {
        if (mainDisplayForUpdateHintIfReady(app) != null) return false
        app.recordUpdateEvent("hint_unavailable", "reason=display_not_ready phase=$phase")
        dismiss("display_not_ready")
        return true
    }

    private fun isCurrentAttempt(resultId: Long, token: Long): Boolean =
        attemptResultId == resultId && attemptToken == token

    private fun eventId(resultId: Long, token: Long): String = "$resultId-$token"

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
