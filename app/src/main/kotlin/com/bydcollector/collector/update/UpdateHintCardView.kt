package com.bydcollector.collector.update

import android.content.Context
import com.bydcollector.collector.ui.compose.UiLanguage
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** One renderer for the live overlay and the editor's unscaled, non-interactive sample. */
internal class UpdateHintCardView @JvmOverloads constructor(
    context: Context,
    onOpen: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null
) : LinearLayout(context) {
    private val accent = View(context)
    private val message = TextView(context).apply { maxLines = 2 }
    private val versionLabel = TextView(context).apply {
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
    }
    private val body = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(message)
        addView(versionLabel)
    }
    private val close = CloseView(context)

    init {
        orientation = HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        elevation = dp(context, 10f).toFloat()
        if (onOpen != null) {
            isFocusable = true
            setOnClickListener { onOpen() }
        }
        if (onClose != null) {
            close.isFocusable = true
            close.setOnClickListener { onClose() }
        }
        addView(accent)
        addView(body, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(close)
    }

    fun bind(version: String, language: UiLanguage, darkTheme: Boolean, appearance: UpdateHintAppearance) {
        val settings = appearance.normalized()
        val scale = settings.scale
        fun scaledDp(value: Float) = dp(context, value * scale)
        val closeRed = if (darkTheme) DARK_CLOSE else LIGHT_CLOSE
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, settings.cornerRadiusDp.toFloat()).toFloat()
            setColor(if (darkTheme) DARK_PANEL else LIGHT_PANEL)
            if (settings.borderWidthDp > 0) {
                setStroke(dp(context, settings.borderWidthDp.toFloat()), settings.borderArgb)
            }
        }
        val borderInset = dp(context, settings.borderWidthDp + 2f)
        setPadding(
            maxOf(scaledDp(14f), borderInset), maxOf(scaledDp(12f), borderInset),
            maxOf(scaledDp(6f), borderInset), maxOf(scaledDp(12f), borderInset)
        )
        accent.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 2f).toFloat()
            setColor(settings.borderArgb)
        }
        // The stripe keeps its own thickness at every widget size and border width.
        accent.layoutParams = LayoutParams(dp(context, 4f), scaledDp(50f)).apply {
            marginEnd = scaledDp(14f)
        }
        body.setPadding(0, scaledDp(3f), scaledDp(8f), scaledDp(3f))
        message.apply {
            setTextColor(if (darkTheme) DARK_MUTED else LIGHT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale)
            text = if (language == UiLanguage.UK) "Доступна нова версія для оновлення" else "A new version is available to update"
        }
        versionLabel.apply {
            setTextColor(if (darkTheme) DARK_TEXT else LIGHT_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f * scale)
            val label = if (version.startsWith("v", ignoreCase = true)) version else "v$version"
            text = if (version.isBlank()) "BYD Collector" else "BYD Collector $label"
        }
        close.apply {
            contentDescription = if (language == UiLanguage.UK) "Закрити" else "Close"
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 7f).toFloat()
                setColor(0x00000000)
                setStroke(dp(context, 1f), closeRed)
            }
            layoutParams = LayoutParams(scaledDp(46f), scaledDp(46f)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            updateIcon(closeRed, scale)
        }
    }

    private class CloseView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private var halfSpan = 0f

        fun updateIcon(color: Int, scale: Float) {
            paint.color = color
            paint.strokeWidth = 2f * resources.displayMetrics.density * scale
            halfSpan = 7f * resources.displayMetrics.density * scale
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val centerX = width / 2f
            val centerY = height / 2f
            canvas.drawLine(centerX - halfSpan, centerY - halfSpan, centerX + halfSpan, centerY + halfSpan, paint)
            canvas.drawLine(centerX - halfSpan, centerY + halfSpan, centerX + halfSpan, centerY - halfSpan, paint)
        }
    }

    companion object {
        const val MARGIN_DP = 18f

        /** Preferred physical width; only the coordinator may auto-fit the whole card. */
        fun widthPx(context: Context, appearance: UpdateHintAppearance): Int =
            dp(context, 440f * appearance.scale).coerceAtLeast(1)

        private fun dp(context: Context, value: Float): Int =
            (value * context.resources.displayMetrics.density).roundToInt()

        private const val DARK_PANEL = 0xFF131B25.toInt()
        private const val DARK_TEXT = 0xFFF1F6FF.toInt()
        private const val DARK_MUTED = 0xFFAAB8CA.toInt()
        private const val LIGHT_PANEL = 0xFFFFFFFF.toInt()
        private const val LIGHT_TEXT = 0xFF121A23.toInt()
        private const val LIGHT_MUTED = 0xFF526274.toInt()
        private const val DARK_CLOSE = 0xFFFF8C8C.toInt()
        private const val LIGHT_CLOSE = 0xFFFF7C7C.toInt()
    }
}
