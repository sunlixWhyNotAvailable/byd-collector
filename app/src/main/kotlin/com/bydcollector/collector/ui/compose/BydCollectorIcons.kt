package com.bydcollector.collector.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

enum class BottomTabIcon {
    HOME,
    DATABASE,
    HA,
    GEAR,
    LOGS
}

@Composable
fun TabIcon(icon: BottomTabIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        when (icon) {
            BottomTabIcon.HOME -> {
                val roof = Path().apply {
                    moveTo(w * 0.22f, h * 0.46f)
                    lineTo(w * 0.50f, h * 0.22f)
                    lineTo(w * 0.78f, h * 0.46f)
                }
                drawPath(roof, color, style = stroke)
                drawRoundRect(
                    color,
                    topLeft = Offset(w * 0.30f, h * 0.44f),
                    size = Size(w * 0.40f, h * 0.34f),
                    style = stroke
                )
            }

            BottomTabIcon.DATABASE -> {
                drawOval(
                    color,
                    topLeft = Offset(w * 0.22f, h * 0.14f),
                    size = Size(w * 0.56f, h * 0.26f),
                    style = stroke
                )
                drawArc(
                    color,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.22f, h * 0.36f),
                    size = Size(w * 0.56f, h * 0.26f),
                    style = stroke
                )
                drawArc(
                    color,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.22f, h * 0.58f),
                    size = Size(w * 0.56f, h * 0.26f),
                    style = stroke
                )
                drawLine(color, Offset(w * 0.22f, h * 0.27f), Offset(w * 0.22f, h * 0.70f), strokeWidth = stroke.width)
                drawLine(color, Offset(w * 0.78f, h * 0.27f), Offset(w * 0.78f, h * 0.70f), strokeWidth = stroke.width)
            }

            BottomTabIcon.HA -> {
                val hub = Offset(w * 0.50f, h * 0.45f)
                val left = Offset(w * 0.23f, h * 0.67f)
                val right = Offset(w * 0.77f, h * 0.67f)
                val top = Offset(w * 0.50f, h * 0.18f)
                drawLine(color, hub, left, strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, hub, right, strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, hub, top, strokeWidth = stroke.width, cap = StrokeCap.Round)
                listOf(hub, left, right, top).forEach { node ->
                    drawCircle(color, radius = w * 0.09f, center = node, style = stroke)
                }
            }

            BottomTabIcon.GEAR -> {
                drawCircle(color, radius = w * 0.26f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                drawCircle(color, radius = w * 0.08f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble()).toFloat()
                    val inner = Offset(w * (0.5f + kotlin.math.cos(a) * 0.32f), h * (0.5f + kotlin.math.sin(a) * 0.32f))
                    val outer = Offset(w * (0.5f + kotlin.math.cos(a) * 0.42f), h * (0.5f + kotlin.math.sin(a) * 0.42f))
                    drawLine(color, inner, outer, strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
            }

            BottomTabIcon.LOGS -> {
                drawRoundRect(
                    color,
                    topLeft = Offset(w * 0.26f, h * 0.16f),
                    size = Size(w * 0.46f, h * 0.68f),
                    style = stroke
                )
                drawLine(color, Offset(w * 0.36f, h * 0.36f), Offset(w * 0.62f, h * 0.36f), strokeWidth = stroke.width)
                drawLine(color, Offset(w * 0.36f, h * 0.52f), Offset(w * 0.62f, h * 0.52f), strokeWidth = stroke.width)
                drawLine(color, Offset(w * 0.36f, h * 0.68f), Offset(w * 0.56f, h * 0.68f), strokeWidth = stroke.width)
            }
        }
    }
}
