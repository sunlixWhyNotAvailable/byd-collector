package com.bydcollector.collector.ui.compose

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.bydcollector.collector.R

enum class BottomTabIcon {
    HOME,
    DATABASE,
    HA,
    GEAR,
    LOGS
}

@Composable
fun TabIcon(icon: BottomTabIcon, color: Color, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(id = icon.drawableRes()),
        contentDescription = null,
        tint = color,
        modifier = modifier
    )
}

@Composable
fun ShutdownIcon(color: Color, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(id = R.drawable.ic_shutdown),
        contentDescription = "Shutdown",
        tint = color,
        modifier = modifier
    )
}

@DrawableRes
private fun BottomTabIcon.drawableRes(): Int = when (this) {
    BottomTabIcon.HOME -> R.drawable.ic_tab_home
    BottomTabIcon.DATABASE -> R.drawable.ic_tab_all_data
    BottomTabIcon.HA -> R.drawable.ic_tab_ha_link
    BottomTabIcon.GEAR -> R.drawable.ic_tab_options
    BottomTabIcon.LOGS -> R.drawable.ic_tab_logs
}
