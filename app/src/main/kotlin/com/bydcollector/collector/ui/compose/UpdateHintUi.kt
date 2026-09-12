package com.bydcollector.collector.ui.compose

// Widget-scoped port of BYD HUD Preview's editor and controls; no changes to Collector's general palette.
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import com.bydcollector.collector.R
import com.bydcollector.collector.update.UpdateHintAppearance
import com.bydcollector.collector.update.UpdateHintCardView

private data class HintCopy(val language: UiLanguage)

@Composable
internal fun UpdateHintSettingsButton(
    language: UiLanguage,
    darkTheme: Boolean,
    onSettings: () -> Unit
) {
    val palette = if (darkTheme) darkPalette() else lightPalette()
    val press = rememberPressFeedback(releaseHoldMillis = VISUAL_PRESS_HOLD_MS)
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(7.dp))
            .background(if (press.pressed) palette.accent.copy(alpha = 0.88f) else palette.accent.copy(alpha = if (palette.dark) 0.20f else 0.12f))
            .border(1.dp, palette.accent.copy(alpha = 0.85f), RoundedCornerShape(7.dp))
            .then(press.modifier)
            .clickable(interactionSource = press.interactionSource, indication = null, onClick = onSettings)
            .semantics { contentDescription = language.choose("Налаштування віджету-підказки", "Update hint widget settings", "") },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painterResource(R.drawable.ic_tab_options),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (press.pressed) Color.White else palette.accent),
            modifier = Modifier.size(28.dp)
        )
    }
}

private data class Palette(
    val dark: Boolean,
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
    val disabled: Color
)

private fun darkPalette() = Palette(
    dark = true,
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
    accent = Color(0xFF2F86F6),
    accentText = Color.White,
    green = Color(0xFF54D898),
    greenSoft = Color(0xFF123C2B),
    yellow = Color(0xFFF2C34E),
    yellowSoft = Color(0xFF453817),
    red = Color(0xFFFF8C8C),
    redSoft = Color(0xFF4C252A),
    disabled = Color(0xFF394453)
)

private fun lightPalette() = Palette(
    dark = false,
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
    accent = Color(0xFF2F86F6),
    accentText = Color(0xFF101820),
    green = Color(0xFF36CF88),
    greenSoft = Color(0xFFD8F4E7),
    yellow = Color(0xFFF1C04C),
    yellowSoft = Color(0xFFFFF1C9),
    red = Color(0xFFFF7C7C),
    redSoft = Color(0xFFFFE1E1),
    disabled = Color(0xFFE1E7EF)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetNumberLine(
    title: String,
    hint: String,
    value: Int,
    range: IntRange,
    unit: String,
    palette: Palette,
    enabled: Boolean,
    showTicks: Boolean = true,
    onChange: (Int) -> Unit
) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    val sliderInteraction = remember { MutableInteractionSource() }
    val defaultSliderColors = SliderDefaults.colors(
        thumbColor = if (palette.dark) Color(0xFFD9ECFF) else Color.White,
        activeTrackColor = palette.accent,
        inactiveTrackColor = palette.disabled,
        disabledThumbColor = palette.muted.copy(alpha = 0.72f),
        disabledActiveTrackColor = palette.borderStrong,
        disabledInactiveTrackColor = palette.disabled.copy(alpha = 0.72f)
    )
    val sliderColors = if (showTicks) defaultSliderColors else defaultSliderColors.copy(
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
        disabledActiveTickColor = Color.Transparent,
        disabledInactiveTickColor = Color.Transparent
    )
    Column {
        ActionRow(title, hint, palette, enabled = enabled) {
            Row(Modifier.width(190.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                PreviewIntegerStepper(
                    draft,
                    { text ->
                        draft = text
                        text.toIntOrNull()?.takeIf { it in range }?.let(onChange)
                    },
                    palette, enabled, range.first, range.last, value,
                    allowDraftBelowMinimum = true
                )
                Text(unit, color = palette.muted, fontSize = 13.sp, modifier = Modifier.width(30.dp), textAlign = TextAlign.End)
            }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = {
                val next = it.roundToInt().coerceIn(range)
                draft = next.toString()
                onChange(next)
            },
            enabled = enabled,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            colors = sliderColors,
            interactionSource = sliderInteraction,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = sliderInteraction, colors = sliderColors,
                    enabled = enabled, thumbSize = DpSize(4.dp, 28.dp)
                )
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(48.dp)
        )
    }
}

@Composable
private fun WidgetColorLine(title: String, argb: Int, palette: Palette, enabled: Boolean, onHelp: (() -> Unit)? = null, onPick: () -> Unit) {
    ActionRow(title, "", palette, enabled = enabled, onHelp = onHelp) {
        Row(Modifier.width(106.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(52.dp).height(44.dp).clip(RoundedCornerShape(7.dp))
                    .background(palette.field).border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(28.dp).background(Color(argb), RoundedCornerShape(4.dp)).border(1.dp, palette.borderStrong, RoundedCornerShape(4.dp)))
            }
            PreviewIconButton(PaletteIcon, title, palette, palette.accent, Modifier.size(44.dp), enabled, onPick)
        }
    }
}

@Composable
internal fun UpdateHintSettingsDialog(
    language: UiLanguage,
    darkTheme: Boolean,
    version: String,
    appearance: UpdateHintAppearance,
    onAppearanceChange: (UpdateHintAppearance) -> Unit,
    onClose: () -> Unit
) {
    val copy = HintCopy(language)
    val palette = if (darkTheme) darkPalette() else lightPalette()
    var choosingColor by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(8.dp)).background(palette.surface)
                    .border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    language.choose("Налаштування віджету-підказки", "Update hint widget settings", "Настройки виджета-подсказки"),
                    color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold
                )
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(0.4f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        WidgetNumberLine(
                            language.choose("Прозорість", "Transparency", "Прозрачность"),
                            language.choose("0% — видимий, 100% — невидимий", "0% visible, 100% invisible", "0% — видимый, 100% — невидимый"),
                            appearance.transparencyPercent, UpdateHintAppearance.TRANSPARENCY_RANGE, "%", palette, true, showTicks = false
                        ) { onAppearanceChange(appearance.copy(transparencyPercent = it)) }
                        WidgetNumberLine(
                            language.choose("Заокруглення країв", "Corner rounding", "Скругление краёв"), "",
                            appearance.cornerRadiusDp, UpdateHintAppearance.CORNER_RANGE, "dp", palette, true
                        ) { onAppearanceChange(appearance.copy(cornerRadiusDp = it)) }
                        WidgetNumberLine(
                            language.choose("Ширина рамки", "Border width", "Ширина рамки"),
                            language.choose("0 — прибрати рамку", "0 removes the border", "0 — убрать рамку"),
                            appearance.borderWidthDp, UpdateHintAppearance.BORDER_RANGE, "dp", palette, true
                        ) { onAppearanceChange(appearance.copy(borderWidthDp = it)) }
                        WidgetColorLine(
                            language.choose("Колір рамки", "Border color", "Цвет рамки"),
                            appearance.borderArgb, palette, true
                        ) { choosingColor = true }
                        WidgetNumberLine(
                            language.choose("Розмір віджету", "Widget size", "Размер виджета"), "",
                            appearance.sizePercent, UpdateHintAppearance.SIZE_RANGE, "%", palette, true, showTicks = false
                        ) { onAppearanceChange(appearance.copy(sizePercent = it)) }
                    }
                    Column(Modifier.weight(0.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            language.choose("Передпоказ · 1:1", "Preview · 1:1", "Предпросмотр · 1:1"),
                            color = palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                        )
                        UpdateHintSettingsSample(language, palette, appearance, version, Modifier.weight(1f).fillMaxWidth())
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PreviewButton(language.choose("Закрити", "Close", "Закрыть"), palette, primary = false, modifier = Modifier.width(160.dp), onClick = onClose)
                }
            }
        }
    }
    if (choosingColor) {
        WidgetColorPicker(
            appearance.borderArgb, language.choose("Колір рамки", "Border color", "Цвет рамки"), copy, palette,
            onDismiss = { choosingColor = false },
            onSelect = { onAppearanceChange(appearance.copy(borderArgb = it)); choosingColor = false }
        )
    }
}

@Composable
private fun UpdateHintSettingsSample(
    language: UiLanguage,
    palette: Palette,
    appearance: UpdateHintAppearance,
    version: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val width = with(LocalDensity.current) { UpdateHintCardView.widthPx(context, appearance).toDp() }
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(palette.background)
            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
    ) {
        // Scroll overflow instead of fitting/scaling the widget to this pane.
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
            AndroidView(
                factory = { UpdateHintCardView(it) },
                update = { card ->
                    card.bind(version, language, palette.dark, appearance)
                    card.alpha = appearance.alpha
                },
                modifier = Modifier.padding(18.dp).width(width)
            )
        }
    }
}

@Composable
private fun WidgetColorPicker(
    initialArgb: Int,
    title: String,
    copy: HintCopy,
    palette: Palette,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    val uiLanguage = copy.language
    val keyboardCommit = rememberKeyboardCommit()
    var draft by remember(initialArgb) { mutableIntStateOf(initialArgb) }
    var hexDraft by remember(initialArgb) { mutableStateOf(String.format(Locale.ROOT, "%06X", initialArgb and 0xFFFFFF)) }
    fun updateDraftColor(value: Int) {
        draft = value or 0xFF000000.toInt()
        hexDraft = String.format(Locale.ROOT, "%06X", draft and 0xFFFFFF)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(560.dp).heightIn(max = 600.dp).clip(RoundedCornerShape(8.dp))
                .background(palette.surface).border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).background(Color(draft), RoundedCornerShape(7.dp)).border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp)))
                BasicTextField(
                    value = hexDraft,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboardCommit.onDone() }),
                    onValueChange = { raw ->
                        val cleaned = sanitizeWidgetHex(raw)
                        hexDraft = cleaned
                        parseWidgetHexArgb(cleaned)?.let { draft = it }
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = palette.text, fontSize = 16.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.then(keyboardCommit.modifier).width(112.dp).height(44.dp).clip(RoundedCornerShape(7.dp))
                        .background(palette.field).border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
                        .onFocusChanged {
                            if (!it.isFocused && hexDraft.length != 6) {
                                hexDraft = String.format(Locale.ROOT, "%06X", draft and 0xFFFFFF)
                            }
                        }
                        .padding(horizontal = 10.dp),
                    decorationBox = { field ->
                        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                            Text("#", color = palette.muted, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                            field()
                        }
                    }
                )
                Spacer(Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                listOf(palette.accent, palette.green, palette.yellow, palette.red, Color.Gray, Color.White, Color.Black).forEach { color ->
                    val press = rememberPressFeedback(true)
                    Box(
                        Modifier.size(36.dp).then(press.modifier).clip(RoundedCornerShape(6.dp)).background(color)
                            .border(if (draft == color.toArgb()) 3.dp else 1.dp, palette.borderStrong, RoundedCornerShape(6.dp))
                            .semantics { contentDescription = String.format(Locale.ROOT, "#%06X", color.toArgb() and 0xFFFFFF) }
                            .clickable(interactionSource = press.interactionSource, indication = null) { updateDraftColor(color.toArgb()) }
                    )
                }
            }
            listOf(16 to "R", 8 to "G", 0 to "B").forEach { (shift, label) ->
                WidgetNumberLine(label, "", (draft ushr shift) and 0xFF, 0..255, "", palette, true) { value ->
                    updateDraftColor((draft and (0xFF shl shift).inv()) or (value shl shift))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PreviewButton(uiLanguage.choose("Обрати", "Select", "Выбрать"), palette, primary = true, modifier = Modifier.weight(1f)) { onSelect(draft) }
                PreviewButton(uiLanguage.choose("Скасувати", "Cancel", "Отмена"), palette, primary = false, modifier = Modifier.weight(1f), onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun PreviewIntegerStepper(
    value: String,
    onValueChange: (String) -> Unit,
    palette: Palette,
    enabled: Boolean,
    minValue: Int = 1,
    maxValue: Int? = 10,
    fallbackValue: Int = 5,
    allowDraftBelowMinimum: Boolean = false
) {
    val current = value.toIntOrNull()
        ?.takeIf { isValidPreviewInteger(it, minValue, maxValue) }
        ?: fallbackValue
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PreviewButton(
            text = "-",
            palette = palette,
            primary = false,
            modifier = Modifier.width(42.dp),
            enabled = enabled && current > minValue,
            onClick = { onValueChange((current - 1).toString()) }
        )
        PreviewIntegerField(
            value,
            onValueChange,
            palette,
            enabled,
            minValue,
            maxValue,
            fallbackValue,
            allowDraftBelowMinimum
        )
        PreviewButton(
            text = "+",
            palette = palette,
            primary = false,
            modifier = Modifier.width(42.dp),
            enabled = enabled && current < (maxValue ?: Int.MAX_VALUE),
            onClick = { onValueChange((current + 1).toString()) }
        )
    }
}

@Composable
private fun PreviewIntegerField(
    value: String,
    onValueChange: (String) -> Unit,
    palette: Palette,
    enabled: Boolean,
    minValue: Int,
    maxValue: Int?,
    fallbackValue: Int,
    allowDraftBelowMinimum: Boolean = false
) {
    val keyboardCommit = rememberKeyboardCommit()
    BasicTextField(
        value = value,
        onValueChange = { rawValue ->
            val candidate = rawValue.filter(Char::isDigit)
            if (candidate.isEmpty() || isValidPreviewInteger(
                    candidate.toIntOrNull(), if (allowDraftBelowMinimum) 0 else minValue, maxValue
                )) {
                onValueChange(candidate)
            }
        },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboardCommit.onDone() }),
        textStyle = TextStyle(
            color = if (enabled) palette.text else palette.muted.copy(alpha = 0.62f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        ),
        modifier = Modifier
            .width(52.dp)
            .then(keyboardCommit.modifier)
            .onFocusChanged { focusState ->
                if (enabled && !focusState.isFocused && !isValidPreviewInteger(
                        value.toIntOrNull(), minValue, maxValue
                    )) {
                    onValueChange(fallbackValue.toString())
                }
            },
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .border(1.dp, if (enabled) palette.borderStrong else palette.border, RoundedCornerShape(7.dp))
                    .background(if (enabled) palette.field else palette.panelAlt),
                contentAlignment = Alignment.Center
            ) {
                innerTextField()
            }
        }
    )
}

private fun isValidPreviewInteger(value: Int?, minValue: Int, maxValue: Int?): Boolean =
    value != null && value >= minValue && (maxValue == null || value <= maxValue)

@Composable
private fun ActionRow(
    title: String,
    hint: String,
    palette: Palette,
    verticalPadding: Dp = 12.dp,
    enabled: Boolean = true,
    onHelp: (() -> Unit)? = null,
    toggleChecked: Boolean? = null,
    onToggle: ((Boolean) -> Unit)? = null,
    action: @Composable () -> Unit
) {
    val toggle = onToggle
    val checked = toggleChecked
    val press = if (toggle != null && checked != null) {
        rememberPressFeedback(enabled, releaseHoldMillis = VISUAL_PRESS_HOLD_MS)
    } else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (press != null) {
                    Modifier
                        .background(pressBackground(Color.Transparent, palette, press.pressed))
                        .then(press.modifier)
                        .toggleable(
                            value = checked!!,
                            enabled = enabled,
                            role = Role.Switch,
                            interactionSource = press.interactionSource,
                            indication = null,
                            onValueChange = toggle!!
                        )
                } else Modifier
            )
            .padding(horizontal = 14.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) palette.text else palette.muted.copy(alpha = 0.62f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            if (hint.isNotBlank()) {
                Text(rowExplanation(hint), color = palette.muted.copy(alpha = if (enabled) 1f else 0.52f), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        action()
    }
}

@Composable
private fun PreviewButton(
    text: String,
    palette: Palette,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    onClick: () -> Unit = {}
) {
    val press = rememberPressFeedback(enabled, releaseHoldMillis = VISUAL_PRESS_HOLD_MS)
    val baseBackground = when {
        !enabled -> palette.disabled
        destructive -> palette.red.copy(alpha = if (palette.dark) 0.78f else 0.84f)
        primary -> palette.accent.copy(alpha = if (palette.dark) 0.78f else 0.08f)
        else -> palette.panelAlt
    }
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(
                1.dp,
                when {
                    enabled && destructive -> palette.red
                    enabled && primary -> palette.accent
                    else -> palette.borderStrong
                },
                RoundedCornerShape(7.dp)
            )
            .background(
                if (press.pressed) {
                    if (destructive) palette.red else palette.accent.copy(alpha = if (palette.dark) 0.78f else 0.20f)
                } else baseBackground
            )
            .then(press.modifier)
            .clickable(
                enabled = enabled,
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        val foreground = when {
            !enabled -> palette.muted.copy(alpha = 0.62f)
            destructive -> Color.White
            primary && palette.dark -> Color.White
            else -> palette.text
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = foreground)
            Text(
                text = text,
                color = foreground,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PreviewIconButton(
    icon: ImageVector,
    contentDescription: String,
    palette: Palette,
    tint: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    val press = rememberPressFeedback(enabled, releaseHoldMillis = VISUAL_PRESS_HOLD_MS)
    val baseBackground = if (enabled) {
        tint.copy(alpha = if (palette.dark) 0.20f else 0.12f)
    } else {
        Color.Transparent
    }
    val pressedBackground = tint.copy(alpha = if (palette.dark) 0.88f else 0.72f)
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(7.dp))
            .border(
                1.dp,
                if (enabled) tint.copy(alpha = 0.85f) else palette.borderStrong,
                RoundedCornerShape(7.dp)
            )
            .background(if (press.pressed) pressedBackground else baseBackground)
            .then(press.modifier)
            .clickable(
                enabled = enabled,
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> palette.muted.copy(alpha = 0.62f)
                press.pressed -> Color.White
                else -> tint
            },
            modifier = Modifier.size(28.dp)
        )
    }
}

private data class PressFeedback(
    val interactionSource: MutableInteractionSource,
    val pressed: Boolean,
    val modifier: Modifier
)

private const val VISUAL_PRESS_HOLD_MS = 90L

@Composable
private fun rememberPressFeedback(
    enabled: Boolean = true,
    releaseHoldMillis: Long = 0L
): PressFeedback {
    val interactionSource = remember { MutableInteractionSource() }
    var visualPressed by remember { mutableStateOf(false) }

    LaunchedEffect(interactionSource) {
        val activePresses = mutableSetOf<PressInteraction.Press>()
        var releaseGeneration = 0

        visualPressed = false
        interactionSource.interactions.collect { interaction ->
            fun holdAfterFinalReleaseOrCancel() {
                val generation = ++releaseGeneration
                if (releaseHoldMillis == 0L) {
                    visualPressed = false
                } else {
                    // Callbacks dispatch immediately; only visual release is held.
                    // A stale timer must not clear a newer press.
                    visualPressed = true
                    launch {
                        delay(releaseHoldMillis)
                        if (generation == releaseGeneration) visualPressed = false
                    }
                }
            }

            when (interaction) {
                is PressInteraction.Press -> {
                    activePresses += interaction
                    releaseGeneration++
                    visualPressed = true
                }
                is PressInteraction.Release -> {
                    activePresses -= interaction.press
                    if (activePresses.isEmpty()) holdAfterFinalReleaseOrCancel()
                }
                is PressInteraction.Cancel -> {
                    activePresses -= interaction.press
                    if (activePresses.isEmpty()) holdAfterFinalReleaseOrCancel()
                }
            }
        }
    }

    val renderedPressed = if (releaseHoldMillis > 0L) visualPressed else enabled && visualPressed
    val scale by animateFloatAsState(
        targetValue = if (renderedPressed) 0.97f else 1.0f,
        label = "pressScale"
    )
    return PressFeedback(
        interactionSource = interactionSource,
        // `enabled` rejects new input at the clickable/toggleable site. Scoped
        // controls opt into the 90-ms hold so it does not erase an already-
        // started visual frame when enabled changes after dispatch. The
        // default-zero path intentionally preserves the old gating behavior.
        pressed = renderedPressed,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    )
}

private fun pressBackground(base: Color, palette: Palette, pressed: Boolean): Color {
    return if (pressed) palette.accent.copy(alpha = if (palette.dark) 0.24f else 0.14f) else base
}

private fun rowExplanation(text: String): String = text.trimEnd().removeSuffix(".")
internal fun sanitizeWidgetHex(raw: String): String = raw.removePrefix("#")
    .filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.take(6).uppercase()
internal fun parseWidgetHexArgb(raw: String): Int? = sanitizeWidgetHex(raw)
    .takeIf { it.length == 6 }?.toIntOrNull(16)?.or(0xFF000000.toInt())

// Material Icons outlined Palette (Google, Apache-2.0), kept local instead of bundling all extended icons.
private val PaletteIcon = ImageVector.Builder("Palette", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(PathParser().parsePathString("M12,22C6.49,22,2,17.51,2,12S6.49,2,12,2s10,4.04,10,9c0,3.31-2.69,6-6,6h-1.77c-0.28,0-0.5,0.22-0.5,0.5c0,0.12,0.05,0.23,0.13,0.33c0.41,0.47,0.64,1.06,0.64,1.67C14.5,20.88,13.38,22,12,22z M12,4c-4.41,0-8,3.59-8,8s3.59,8,8,8c0.28,0,0.5-0.22,0.5-0.5c0-0.16-0.08-0.28-0.14-0.35c-0.41-0.46-0.63-1.05-0.63-1.65c0-1.38,1.12-2.5,2.5-2.5H16c2.21,0,4-1.79,4-4C20,7.14,16.41,4,12,4z M8,11.5a1.5,1.5 0,1 0,-3,0a1.5,1.5 0,1 0,3,0 M11,7.5a1.5,1.5 0,1 0,-3,0a1.5,1.5 0,1 0,3,0 M16,7.5a1.5,1.5 0,1 0,-3,0a1.5,1.5 0,1 0,3,0 M19,11.5a1.5,1.5 0,1 0,-3,0a1.5,1.5 0,1 0,3,0").toNodes(), fill = SolidColor(Color.Black))
}.build()


private fun <T> UiLanguage.choose(ukrainian: T, english: T, @Suppress("UNUSED_PARAMETER") russian: T): T =
    if (this == UiLanguage.UK) ukrainian else english
