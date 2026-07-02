package com.bydcollector.collector.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class BydPalette(
    val background: Color,
    val surface: Color,
    val panel: Color,
    val panelAlt: Color,
    val field: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val muted: Color,
    val active: Color,
    val activeSoft: Color,
    val accent: Color,
    val accentText: Color,
    val green: Color,
    val greenSoft: Color,
    val yellow: Color,
    val yellowSoft: Color,
    val red: Color,
    val redSoft: Color,
    val disabled: Color,
    val switchOff: Color,
    val switchThumb: Color,
    val pathField: Color,
    val pathText: Color,
    val pathBorder: Color
)

private val DarkPalette = BydPalette(
    background = Color(0xFF080D12),
    surface = Color(0xFF0E151D),
    panel = Color(0xFF131B25),
    panelAlt = Color(0xFF172231),
    field = Color(0xFF18212C),
    border = Color(0xFF2B3847),
    borderStrong = Color(0xFF40536A),
    text = Color(0xFFF1F6FF),
    muted = Color(0xFFAAB8CA),
    active = Color(0xFF173A5C),
    activeSoft = Color(0xFF20344A),
    accent = Color(0xFF1F6FD8),
    accentText = Color(0xFFFFFFFF),
    green = Color(0xFF54D898),
    greenSoft = Color(0xFF123C2B),
    yellow = Color(0xFFF2C34E),
    yellowSoft = Color(0xFF453817),
    red = Color(0xFFFF8C8C),
    redSoft = Color(0xFF4C252A),
    disabled = Color(0xFF394453),
    switchOff = Color(0xFF374150),
    switchThumb = Color(0xFFDDEAF7),
    pathField = Color(0xFF0C1219),
    pathText = Color(0xFFA7B5C8),
    pathBorder = Color(0xFF2B3A4C)
)

private val LightPalette = BydPalette(
    background = Color(0xFFEAF1F8),
    surface = Color(0xFFFFFFFF),
    panel = Color(0xFFFFFFFF),
    panelAlt = Color(0xFFF0F5FB),
    field = Color(0xFFF7FAFE),
    border = Color(0xFFC9D6E4),
    borderStrong = Color(0xFF6D7D8F),
    text = Color(0xFF121A23),
    muted = Color(0xFF526274),
    active = Color(0xFFD9EAFE),
    activeSoft = Color(0xFFE7F1FF),
    accent = Color(0xFF1F6FD8),
    accentText = Color(0xFFFFFFFF),
    green = Color(0xFF147A55),
    greenSoft = Color(0xFFD8F4E7),
    yellow = Color(0xFF7A5A00),
    yellowSoft = Color(0xFFFFF1C9),
    red = Color(0xFFB42318),
    redSoft = Color(0xFFFFE1E1),
    disabled = Color(0xFFE1E7EF),
    switchOff = Color(0xFFDDE4EE),
    switchThumb = Color(0xFFD8E3EE),
    pathField = Color(0xFFF7FAFD),
    pathText = Color(0xFF526274),
    pathBorder = Color(0xFFC9D6E6)
)

val LocalBydPalette = staticCompositionLocalOf { DarkPalette }

@Composable
fun BydCollectorTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBydPalette provides if (dark) DarkPalette else LightPalette) {
        content()
    }
}
