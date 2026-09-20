package com.bydcollector.collector.ui.compose

import android.view.ViewTreeObserver
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val CardShape = RoundedCornerShape(8.dp)
private val ControlShape = RoundedCornerShape(7.dp)
private val PillShape = RoundedCornerShape(50)
private const val PRESS_FEEDBACK_MS = 100L

internal data class KeyboardCommitHandle(
    val modifier: Modifier,
    val onDone: () -> Unit
)

@Composable
internal fun rememberKeyboardCommit(): KeyboardCommitHandle {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var focused by remember { mutableStateOf(false) }
    var imeWasVisible by remember { mutableStateOf(false) }
    var imeVisible by remember { mutableStateOf(false) }

    DisposableEffect(view, focused) {
        //the non-edge-to-edge window consumes Compose IME insets; observe the root only while editing
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            imeVisible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        if (focused) {
            listener.onGlobalLayout()
            observer.addOnGlobalLayoutListener(listener)
        }
        onDispose {
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
        }
    }

    fun commit() {
        if (!focused) return
        imeWasVisible = false
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    LaunchedEffect(focused, imeVisible) {
        if (!focused) {
            imeWasVisible = false
        } else if (imeVisible) {
            imeWasVisible = true
        } else if (focused && imeWasVisible) {
            commit()
        }
    }

    return KeyboardCommitHandle(
        modifier = Modifier.onFocusChanged {
            focused = it.isFocused
            if (!focused) {
                imeWasVisible = false
                imeVisible = false
            }
        },
        onDone = ::commit
    )
}

data class ForcedPressClick(
    val visualPressed: Boolean,
    val locked: Boolean,
    val onClick: () -> Unit
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

    //holds forced feedback and the repeat lock while the action runs immediately
    LaunchedEffect(clickToken) {
        if (clickToken == 0) return@LaunchedEffect
        delay(PRESS_FEEDBACK_MS)
        visualPressed = false
        locked = false
    }

    return ForcedPressClick(
        visualPressed = visualPressed,
        locked = locked,
        onClick = {
            if (!enabled || locked) return@ForcedPressClick
            locked = true
            visualPressed = true
            clickToken += 1
            latestOnClick()
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
    headerWarning: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    bodyPadding: Dp = 14.dp,
    headerHeight: Dp = 42.dp,
    headerToggle: SwitchToggle? = null,
    content: @Composable () -> Unit
) {
    val p = LocalBydPalette.current
    val headerInteraction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(p.panel)
            .border(1.dp, p.border, CardShape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .background(p.panelAlt)
                .switchRowPressFeedback(headerInteraction, headerToggle?.enabled == true)
                .switchTarget(headerToggle, headerInteraction)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = title.uppercase(),
                    color = p.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                headerWarning?.let {
                    TemplateLimitWarning(it, Modifier.weight(1f))
                }
            }
            if (trailing != null) trailing()
            headerToggle?.let {
                BydSwitch(it.checked, null, enabled = it.enabled, interactionSource = headerInteraction)
            }
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
    enabled: Boolean = true,
    listAction: Boolean = false,
    fontSize: TextUnit = 14.sp
) {
    val p = LocalBydPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val press = rememberForcedPressClick(enabled = enabled, onClick = onClick)
    val visualPressed = pressed || press.visualPressed
    val bg = when {
        !enabled -> p.disabled.copy(alpha = 0.55f)
        listAction && visualPressed -> p.accent.copy(alpha = if (p.dark) 0.24f else 0.14f)
        listAction -> p.accent.copy(alpha = if (p.dark) 0.20f else 0.04f)
        primary && visualPressed -> p.active
        primary -> p.accent
        visualPressed -> p.activeSoft
        else -> p.surface
    }
    val border = when {
        !enabled && listAction -> p.borderStrong.copy(alpha = 0.68f)
        !enabled -> p.borderStrong
        listAction || primary -> p.accent
        else -> p.borderStrong
    }
    val fg = when {
        !enabled -> p.muted.copy(alpha = 0.65f)
        primary -> p.accentText
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
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val SwitchRowPressColor = Color(0xFF2F86F6)

@Composable
private fun Modifier.switchRowPressFeedback(
    interactionSource: MutableInteractionSource,
    enabled: Boolean
): Modifier {
    if (!enabled) return this
    val p = LocalBydPalette.current
    var visualPressed by remember(interactionSource) { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        val activePresses = mutableSetOf<PressInteraction.Press>()
        var releaseJob: Job? = null
        fun release(press: PressInteraction.Press) {
            activePresses -= press
            if (activePresses.isEmpty()) {
                releaseJob?.cancel()
                releaseJob = launch {
                    delay(90L)
                    visualPressed = false
                }
            }
        }
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    releaseJob?.cancel()
                    activePresses += interaction
                    visualPressed = true
                }
                is PressInteraction.Release -> release(interaction.press)
                is PressInteraction.Cancel -> release(interaction.press)
            }
        }
    }
    val scale by animateFloatAsState(
        targetValue = if (visualPressed) 0.97f else 1f,
        label = "switchRowPressScale"
    )
    // Extend's visual hold is independent of the row's immediate toggle callback.
    return this
        .background(if (visualPressed) SwitchRowPressColor.copy(alpha = if (p.dark) 0.24f else 0.14f) else Color.Transparent)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

@Composable
fun SwitchControlRow(
    toggle: SwitchToggle,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .switchRowPressFeedback(interactionSource, toggle.enabled)
            .switchTarget(toggle, interactionSource),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
        BydSwitch(toggle.checked, null, enabled = toggle.enabled, interactionSource = interactionSource)
    }
}

data class SwitchToggle(
    val checked: Boolean,
    val onChange: (Boolean) -> Unit,
    val enabled: Boolean = true
)

private fun Modifier.switchTarget(toggle: SwitchToggle?, interactionSource: MutableInteractionSource): Modifier =
    if (toggle == null) this else toggleable(
        value = toggle.checked,
        enabled = toggle.enabled,
        role = Role.Switch,
        interactionSource = interactionSource,
        indication = null,
        onValueChange = toggle.onChange
    )

@Composable
fun BydSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    binary: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val p = LocalBydPalette.current
    val pressed by interactionSource.collectIsPressedAsState()

    val track = when {
        !enabled -> p.disabled
        binary && pressed -> p.accent.copy(alpha = 0.82f)
        pressed -> p.activeSoft
        binary -> p.accent
        checked -> p.accent
        else -> p.switchOff
    }
    val thumbSize by animateDpAsState(
        targetValue = when {
            binary -> 25.dp
            checked -> 25.dp
            else -> 19.dp
        },
        animationSpec = tween(durationMillis = 120)
    )
    val thumbOffset by animateDpAsState(
        targetValue = when {
            binary && checked -> 25.dp
            checked -> 25.dp
            else -> 0.dp
        },
        animationSpec = tween(durationMillis = 120)
    )
    Box(
        modifier = modifier
            .size(width = 56.dp, height = 32.dp)
            .clip(PillShape)
            .background(track)
            .border(1.dp, if (binary || checked) p.accent else p.border, PillShape)
            .switchTarget(onCheckedChange?.let { SwitchToggle(checked, it, enabled) }, interactionSource)
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(thumbSize)
                .clip(PillShape)
                .background(if (binary || checked) p.switchThumbOn else p.switchThumbOff)
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
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = p.pathText,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TemplateLimitWarning(text: String, modifier: Modifier = Modifier) {
    val p = LocalBydPalette.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(Modifier.size(14.dp)) {
            val triangle = Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(triangle, p.yellow)
            drawLine(
                color = p.panelAlt,
                start = Offset(size.width / 2f, size.height * 0.30f),
                end = Offset(size.width / 2f, size.height * 0.66f),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(
                color = p.panelAlt,
                radius = 0.9.dp.toPx(),
                center = Offset(size.width / 2f, size.height * 0.82f)
            )
        }
        Text(
            text = text,
            color = p.yellow,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
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
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String = "",
    showPasswordContentDescription: String? = null,
    hidePasswordContentDescription: String? = null,
    clearContentDescription: String? = null,
    onClear: (() -> Unit)? = null,
    showClearWhenEmpty: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    val p = LocalBydPalette.current
    var passwordVisible by remember { mutableStateOf(false) }
    val keyboardCommit = rememberKeyboardCommit()
    val hasVisibilityToggle = password &&
        showPasswordContentDescription != null &&
        hidePasswordContentDescription != null
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(ControlShape)
                .background(if (enabled) p.pathField else p.disabled.copy(alpha = 0.30f))
                .border(1.dp, p.pathBorder, ControlShape)
                .padding(start = 10.dp, end = if (onClear != null || hasVisibilityToggle || trailing != null) 3.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(
                    color = if (enabled) p.text else p.muted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal
                ),
                cursorBrush = SolidColor(p.accent),
                visualTransformation = if (password && !passwordVisible) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboardCommit.onDone() }),
                modifier = keyboardCommit.modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(placeholder, color = p.muted, fontSize = 14.sp)
                        }
                        innerTextField()
                    }
                }
            )
            if (hasVisibilityToggle) {
                PasswordVisibilityButton(
                    visible = passwordVisible,
                    contentDescription = if (passwordVisible) {
                        hidePasswordContentDescription!!
                    } else {
                        showPasswordContentDescription!!
                    },
                    onClick = { passwordVisible = !passwordVisible }
                )
            }
            if (onClear != null && clearContentDescription != null && (value.isNotEmpty() || showClearWhenEmpty)) {
                ClearFieldButton(clearContentDescription, onClear)
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun TextValueInput(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    multiline: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    val p = LocalBydPalette.current
    val keyboardCommit = rememberKeyboardCommit()
    Column(modifier = modifier) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = p.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = !multiline,
            minLines = if (multiline) 7 else 1,
            maxLines = if (multiline) 7 else 1,
            textStyle = TextStyle(
                color = p.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                lineHeight = 19.sp
            ),
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = if (multiline) ImeAction.Default else ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = if (multiline) null else ({ keyboardCommit.onDone() })
            ),
            modifier = Modifier
                .then(keyboardCommit.modifier)
                .fillMaxWidth()
                .then(if (multiline) Modifier else Modifier.height(38.dp))
                .clip(ControlShape)
                .background(p.pathField)
                .border(1.dp, p.pathBorder, ControlShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (value.text.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, color = p.muted, fontSize = 14.sp)
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun PasswordVisibilityButton(visible: Boolean, contentDescription: String, onClick: () -> Unit) {
    val p = LocalBydPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberForcedPressClick(enabled = true, onClick = onClick)
    Box(
        modifier = Modifier
            .size(32.dp)
            .pressScaleModifier(interactionSource, forcePressed = press.visualPressed)
            .clip(PillShape)
            .clickable(enabled = !press.locked, interactionSource = interactionSource, indication = null) {
                press.onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        SecretVisibilityIcon(visible, contentDescription, p.muted, Modifier.size(18.dp))
    }
}

@Composable
private fun ClearFieldButton(contentDescription: String, onClick: () -> Unit) {
    val p = LocalBydPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberForcedPressClick(enabled = true, onClick = onClick)
    Box(
        modifier = Modifier
            .size(32.dp)
            .pressScaleModifier(interactionSource, forcePressed = press.visualPressed)
            .clip(PillShape)
            .clickable(enabled = !press.locked, interactionSource = interactionSource, indication = null) {
                press.onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        ClearIcon(contentDescription, p.muted, Modifier.size(16.dp))
    }
}

@Composable
fun NumericInput(
    value: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    textAlign: TextAlign = TextAlign.End
) {
    val p = LocalBydPalette.current
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(ControlShape)
            .background(p.disabled.copy(alpha = 0.30f))
            .border(1.dp, p.pathBorder, ControlShape)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Text(
            text = value,
            color = if (emphasized) p.text else p.muted.copy(alpha = 0.6f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth()
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
    val press = rememberForcedPressClick(enabled = enabled, onClick = onClick)
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
    modifier: Modifier = Modifier,
    leftWidth: Dp? = null,
    rightWidth: Dp? = null,
    fontSize: TextUnit = 14.sp,
    animateSelection: Boolean = false
) {
    val p = LocalBydPalette.current
    if (leftWidth != null && rightWidth != null) {
        val contentPadding = 3.dp
        val itemSpacing = 3.dp
        val itemHeight = 36.dp
        val animationDuration = if (animateSelection) 180 else 0
        val selectionWidth by animateDpAsState(
            targetValue = if (leftSelected) leftWidth else rightWidth,
            animationSpec = tween(durationMillis = animationDuration)
        )
        val selectionOffset by animateDpAsState(
            targetValue = contentPadding + if (leftSelected) 0.dp else leftWidth + itemSpacing,
            animationSpec = tween(durationMillis = animationDuration)
        )
        Box(
            modifier = modifier
                .height(42.dp)
                .width(leftWidth + rightWidth + itemSpacing + contentPadding * 2)
                .clip(PillShape)
                .background(p.panel)
                .border(1.dp, p.borderStrong, PillShape)
        ) {
            Box(
                Modifier
                    .offset(x = selectionOffset, y = contentPadding)
                    .width(selectionWidth)
                    .height(itemHeight)
                    .clip(PillShape)
                    .background(p.accent)
            )
            Row(
                modifier = Modifier.padding(contentPadding),
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SegmentButton(left, leftSelected, onLeft, Modifier.width(leftWidth), fontSize, drawSelection = false, directClick = true)
                SegmentButton(right, !leftSelected, onRight, Modifier.width(rightWidth), fontSize, drawSelection = false, directClick = true)
            }
        }
        return
    }
    BoxWithConstraints(
        modifier = modifier
            .height(42.dp)
            .clip(PillShape)
            .background(p.panel)
            .border(1.dp, p.borderStrong, PillShape)
            .padding(3.dp)
    ) {
        // Match the existing weighted Row's pixel rounding, including odd widths.
        // Padding stays on the container, so header geometry is unchanged.
        val density = LocalDensity.current
        val leftPixels = constraints.maxWidth / 2
        val leftSize = with(density) { leftPixels.toDp() }
        val rightSize = with(density) { (constraints.maxWidth - leftPixels).toDp() }
        val animationDuration = if (animateSelection) 180 else 0
        val selectionWidth by animateDpAsState(
            targetValue = if (leftSelected) leftSize else rightSize,
            animationSpec = tween(durationMillis = animationDuration)
        )
        val selectionOffset by animateDpAsState(
            targetValue = if (leftSelected) 0.dp else leftSize,
            animationSpec = tween(durationMillis = animationDuration)
        )
        Box(
            Modifier.offset(x = selectionOffset)
                .width(selectionWidth)
                .fillMaxHeight()
                .clip(PillShape)
                .background(p.accent)
        )
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            SegmentButton(left, leftSelected, onLeft, Modifier.weight(1f), fontSize, drawSelection = false, directClick = true)
            SegmentButton(right, !leftSelected, onRight, Modifier.weight(1f), fontSize, drawSelection = false, directClick = true)
        }
    }
}

@Composable
private fun SegmentButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    fontSize: TextUnit,
    drawSelection: Boolean = true,
    directClick: Boolean = false
) {
    val p = LocalBydPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val press = if (directClick) null else rememberForcedPressClick(enabled = true, onClick = onClick)
    val visualPressed = pressed || press?.visualPressed == true
    Box(
        modifier = modifier
            .fillMaxHeight()
            .pressScaleModifier(interactionSource, forcePressed = press?.visualPressed == true)
            .clip(PillShape)
            .background(
                when {
                    selected && drawSelection -> p.accent
                    selected && visualPressed -> p.accent.copy(alpha = if (p.dark) 0.76f else 0.82f)
                    visualPressed -> p.activeSoft
                    else -> Color.Transparent
                }
            )
            .clickable(enabled = press?.locked != true, interactionSource = interactionSource, indication = null) {
                if (press == null) onClick() else press.onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) p.accentText else p.muted,
            fontSize = fontSize,
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
            .height(64.dp)
            .clip(ControlShape)
            .background(p.surface)
            .border(1.dp, p.border, ControlShape)
            .padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 7.dp)
    ) {
        Text(
            text = label,
            color = p.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart).offset(y = (-4).dp)
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
