package com.bydcollector.collector.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val CardShape = RoundedCornerShape(8.dp)
private val ControlShape = RoundedCornerShape(7.dp)
private val PillShape = RoundedCornerShape(50)
private const val FORCED_PRESS_DELAY_MS = 100L
private const val SWITCH_CENTER_DELAY_MS = 120L
private const val SWITCH_CONFIRM_TIMEOUT_MS = 2_000L

val LocalSwitchConfirmationVersion = staticCompositionLocalOf { 0 }

data class ForcedPressClick(
    val visualPressed: Boolean,
    val locked: Boolean,
    val onClick: () -> Unit
)

private data class SwitchPendingState(
    val from: Boolean,
    val target: Boolean,
    val startedAtVersion: Int? = null,
    val token: Int
)

@Composable
fun rememberForcedPressClick(
    enabled: Boolean,
    onClick: () -> Unit
): ForcedPressClick {
    val latestOnClick by rememberUpdatedState(onClick)
    var visualPressed by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var clickToken by remember { mutableStateOf(0) }

    //delays actions briefly so every button visibly acknowledges the tap first
    LaunchedEffect(clickToken) {
        if (clickToken == 0) return@LaunchedEffect
        delay(FORCED_PRESS_DELAY_MS)
        visualPressed = false
        locked = false
        latestOnClick()
    }

    return ForcedPressClick(
        visualPressed = visualPressed,
        locked = locked,
        onClick = {
            if (!enabled || locked) return@ForcedPressClick
            locked = true
            visualPressed = true
            clickToken += 1
        }
    )
}

@Composable
fun Modifier.pressScaleModifier(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    forcePressed: Boolean = false
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    //gives buttons/tabs physical feedback without changing their measured layout size
    val scale by animateFloatAsState(
        targetValue = if ((pressed || forcePressed) && enabled) 0.97f else 1f,
        animationSpec = tween(durationMillis = 90)
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.height(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = LocalBydPalette.current.text,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = subtitle,
            color = LocalBydPalette.current.muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun StatusPill(
    text: String,
    kind: StatusKind,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val p = LocalBydPalette.current
    //uses a limited status palette so repeated cards scan consistently across tabs
    val (bg, fg, border) = when (kind) {
        StatusKind.OK -> Triple(p.greenSoft, p.green, p.green.copy(alpha = 0.62f))
        StatusKind.WARNING -> Triple(p.yellowSoft, p.yellow, p.yellow.copy(alpha = 0.70f))
        StatusKind.WAITING -> Triple(p.disabled, p.muted, p.border)
        StatusKind.ERROR -> Triple(p.redSoft, p.red, p.red.copy(alpha = 0.70f))
    }
    Box(
        modifier = modifier
            .clip(PillShape)
            .background(bg)
            .border(1.dp, border, PillShape)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 5.dp else 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = if (compact) 12.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun DashboardSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val p = LocalBydPalette.current
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(p.surface)
            .border(1.dp, p.border, CardShape)
            .padding(14.dp)
    ) {
        content()
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    bodyPadding: Dp = 14.dp,
    content: @Composable () -> Unit
) {
    val p = LocalBydPalette.current
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(p.panel)
            .border(1.dp, p.border, CardShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(p.panelAlt)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title.uppercase(),
                color = p.muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (trailing != null) trailing()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bodyPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    val p = LocalBydPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val press = rememberForcedPressClick(enabled, onClick)
    val visualPressed = pressed || press.visualPressed
    val bg = when {
        !enabled -> p.disabled.copy(alpha = 0.55f)
        primary && visualPressed -> p.active
        primary -> p.activeSoft
        visualPressed -> p.activeSoft
        else -> p.surface
    }
    val border = if (primary && enabled) p.accent else p.borderStrong
    val fg = when {
        !enabled -> p.muted.copy(alpha = 0.65f)
        primary -> p.text
        else -> p.text
    }
    Box(
        modifier = modifier
            .height(42.dp)
            .pressScaleModifier(interactionSource, enabled, forcePressed = press.visualPressed)
            .clip(ControlShape)
            .background(bg)
            .border(1.dp, border, ControlShape)
            .clickable(enabled = enabled && !press.locked, interactionSource = interactionSource, indication = null) {
                press.onClick()
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun BydSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pending: Boolean = false
) {
    val p = LocalBydPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val confirmationVersion = LocalSwitchConfirmationVersion.current
    val latestConfirmationVersion by rememberUpdatedState(confirmationVersion)
    var localPending by remember { mutableStateOf<SwitchPendingState?>(null) }
    var token by remember { mutableStateOf(0) }
    val pendingState = localPending
    val visuallyPending = pending || pendingState != null
    val visualChecked = pendingState?.from ?: checked

    //centers the knob before executing the setting change, then waits for a real refreshed state
    LaunchedEffect(pendingState?.token) {
        val current = pendingState ?: return@LaunchedEffect
        delay(SWITCH_CENTER_DELAY_MS)
        localPending = current.copy(startedAtVersion = latestConfirmationVersion)
        val applied = runCatching { onCheckedChange(current.target) }.isSuccess
        if (!applied) {
            localPending = null
            return@LaunchedEffect
        }
        delay(SWITCH_CONFIRM_TIMEOUT_MS)
        if (localPending?.token == current.token) {
            localPending = null
        }
    }

    //confirms only when refreshed backing state reaches the requested target
    LaunchedEffect(confirmationVersion, checked) {
        val current = localPending ?: return@LaunchedEffect
        val startedAt = current.startedAtVersion ?: return@LaunchedEffect
        if (confirmationVersion <= startedAt) return@LaunchedEffect
        if (checked != current.target) return@LaunchedEffect
        localPending = null
    }

    val track = when {
        !enabled -> p.disabled
        pressed -> p.activeSoft
        pending -> p.activeSoft
        pendingState != null -> p.activeSoft
        checked -> p.accent
        else -> p.switchOff
    }
    val thumbOffset by animateDpAsState(
        targetValue = when {
            pending -> 12.dp
            pendingState != null -> 12.dp
            visualChecked -> 25.dp
            else -> 0.dp
        },
        animationSpec = tween(durationMillis = 120)
    )
    Box(
        modifier = modifier
            .size(width = 56.dp, height = 32.dp)
            .clip(PillShape)
            .background(track)
            .border(1.dp, if (checked) p.accent else p.border, PillShape)
            .clickable(enabled = enabled && localPending == null, interactionSource = interactionSource, indication = null) {
                val target = !checked
                token += 1
                localPending = SwitchPendingState(from = checked, target = target, token = token)
            }
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(25.dp)
                .clip(PillShape)
                .background(if (visualChecked || visuallyPending) p.switchThumbOn else p.switchThumbOff)
        )
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueWeight: Float = 0.95f,
    divider: Boolean = true
) {
    val p = LocalBydPalette.current
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = p.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                color = p.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(valueWeight),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (divider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(p.border)
            )
        }
    }
}

@Composable
fun StatusRow(
    label: String,
    pill: String,
    kind: StatusKind,
    modifier: Modifier = Modifier
) {
    val p = LocalBydPalette.current
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = p.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            StatusPill(text = pill, kind = kind, compact = true)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(p.border)
        )
    }
}

@Composable
fun ReadOnlyPathField(text: String, modifier: Modifier = Modifier) {
    val p = LocalBydPalette.current
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(ControlShape)
            .background(p.pathField)
            .border(1.dp, p.pathBorder, ControlShape)
            .padding(10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            color = p.pathText,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TextInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    val p = LocalBydPalette.current
    Column(modifier = modifier) {
        //keeps labeled inputs compact so ha settings fit the tablet viewport
        Text(
            text = label,
            color = p.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                color = if (enabled) p.text else p.muted.copy(alpha = 0.6f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal
            ),
            cursorBrush = SolidColor(p.accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(ControlShape)
                .background(if (enabled) p.pathField else p.disabled.copy(alpha = 0.30f))
                .border(1.dp, p.pathBorder, ControlShape)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun NumericInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalBydPalette.current
    //filters to digits at entry so batch-size parsing never sees symbols or whitespace
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(ControlShape)
            .background(p.pathField)
            .border(1.dp, p.pathBorder, ControlShape)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        BasicTextField(
            value = value,
            onValueChange = { candidate ->
                onValueChange(candidate.filter { it.isDigit() })
            },
            singleLine = true,
            textStyle = TextStyle(
                color = p.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End
            ),
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    innerTextField()
                }
            }
        )
    }
}

@Composable
fun CategoryChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalBydPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val press = rememberForcedPressClick(enabled, onClick)
    val visualPressed = pressed || press.visualPressed
    val bg = when {
        !enabled -> p.disabled.copy(alpha = 0.35f)
        selected -> p.active
        visualPressed -> p.activeSoft
        else -> p.surface
    }
    val fg = when {
        !enabled -> p.muted.copy(alpha = 0.55f)
        selected -> p.text
        else -> p.muted
    }
    Box(
        modifier = modifier
            .height(38.dp)
            .pressScaleModifier(interactionSource, enabled, forcePressed = press.visualPressed)
            .clip(ControlShape)
            .background(bg)
            .border(1.dp, if (selected) p.accent else p.border, ControlShape)
            .clickable(enabled = enabled && !press.locked, interactionSource = interactionSource, indication = null) {
                press.onClick()
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SegmentedControl(
    left: String,
    right: String,
    leftSelected: Boolean,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalBydPalette.current
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(PillShape)
            .background(p.panel)
            .border(1.dp, p.borderStrong, PillShape)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SegmentButton(left, selected = leftSelected, onClick = onLeft, Modifier.weight(1f))
        SegmentButton(right, selected = !leftSelected, onClick = onRight, Modifier.weight(1f))
    }
}

@Composable
private fun SegmentButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val p = LocalBydPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val press = rememberForcedPressClick(enabled = true, onClick = onClick)
    val visualPressed = pressed || press.visualPressed
    Box(
        modifier = modifier
            .fillMaxHeight()
            .pressScaleModifier(interactionSource, forcePressed = press.visualPressed)
            .clip(PillShape)
            .background(
                when {
                    selected -> p.accent
                    visualPressed -> p.activeSoft
                    else -> Color.Transparent
                }
            )
            .clickable(enabled = !press.locked, interactionSource = interactionSource, indication = null) {
                press.onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) p.accentText else p.muted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun KpiTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val p = LocalBydPalette.current
    Box(
        modifier = modifier
            .height(76.dp)
            .clip(ControlShape)
            .background(p.surface)
            .border(1.dp, p.border, ControlShape)
            .padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 8.dp)
    ) {
        Text(
            text = label,
            color = p.muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart)
        )
        Text(
            text = value,
            color = p.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
fun RowScope.EqualSpacer(width: Dp = 12.dp) {
    Spacer(Modifier.width(width))
}
