package com.bydcollector.collector.ui.compose

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bydcollector.collector.R

private const val SUPPORT_JAR_URL = "https://send.monobank.ua/jar/bKFV15i9e"
private const val SUPPORT_JAR_CARD = "4874 1000 3354 3078"

@Composable
internal fun SupportHeaderAction(language: UiLanguage, onClick: () -> Unit) {
    val palette = LocalBydPalette.current
    val supportPress = rememberPressFeedback(releaseHoldMillis = VISUAL_PRESS_HOLD_MS)
    Text(if (language == UiLanguage.UK) "Підтримати" else "Support",
        color = palette.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (supportPress.pressed) palette.activeSoft else Color.Transparent)
            .then(supportPress.modifier)
            .clickable(role = Role.Button, interactionSource = supportPress.interactionSource,
                indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp))
}

// Exact approved Preview interactions. BydCollectorTheme does not override Foundation's
// default indication, matching HUD for URL/Copy/card; only the footer uses press scaling.
@Composable
internal fun SupportDialog(palette: BydPalette, language: UiLanguage, onClose: () -> Unit) {
    val ukrainian = language == UiLanguage.UK
    val context = LocalContext.current
    val buttonPalette = (if (palette.dark) darkPalette() else lightPalette()).copy(
        accent = palette.accent, panelAlt = palette.panelAlt,
        borderStrong = palette.borderStrong, text = palette.text,
    )
    val description = if (ukrainian)
        "Добровільна підтримка розробки та вдосконалення застосунків для автомобілів BYD"
        else "Voluntary support for the development and improvement of apps for BYD cars"
    val cardLabel = if (ukrainian) "Номер картки Банки" else "Jar card number"
    val shareLabel = if (ukrainian) "Поділитися" else "Share"
    fun openIntent(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, if (ukrainian) "Немає застосунку для цієї дії"
                else "No app can handle this action", Toast.LENGTH_SHORT).show()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(820.dp).fillMaxHeight(0.9f).clip(RoundedCornerShape(8.dp))
            .background(palette.surface).border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp))
            .padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (ukrainian) "Підтримати розробку" else "Support development",
                color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.mono_support_qr),
                    contentDescription = if (ukrainian) "QR-код Банки для поповнення" else "Scan to support via monobank",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.weight(0.42f).fillMaxHeight())
                Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Support BYD app", color = palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(description, color = palette.text, fontSize = 16.sp)
                    Text(if (ukrainian) "Відскануйте QR-код телефоном або відкрийте посилання на Банку."
                        else "Scan the QR code with your phone or open the Jar link.",
                        color = palette.muted, fontSize = 14.sp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(SUPPORT_JAR_URL, color = palette.accent, fontSize = 15.sp,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp))
                                .clickable(role = Role.Button) { openIntent(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_JAR_URL))) }
                                .padding(vertical = 8.dp))
                        Text(if (ukrainian) "Копіювати" else "Copy",
                            color = palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                .clickable(role = Role.Button, onClickLabel = if (ukrainian) "Копіювати посилання" else "Copy link") {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Support BYD app", SUPPORT_JAR_URL))
                                    Toast.makeText(context, if (ukrainian) "Посилання скопійовано" else "Link copied",
                                        Toast.LENGTH_SHORT).show()
                                }.padding(horizontal = 6.dp, vertical = 10.dp))
                    }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(palette.field)
                        .border(1.dp, palette.border, RoundedCornerShape(7.dp))
                        .clickable(role = Role.Button, onClickLabel = if (ukrainian) "Копіювати номер картки" else "Copy card number") {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(cardLabel, SUPPORT_JAR_CARD))
                            Toast.makeText(context, if (ukrainian) "Номер картки скопійовано" else "Card number copied",
                                Toast.LENGTH_SHORT).show()
                        }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(cardLabel, color = palette.muted, fontSize = 13.sp)
                        Text(SUPPORT_JAR_CARD, color = palette.text, fontFamily = FontFamily.Monospace,
                            fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (ukrainian) "Натисніть, щоб скопіювати" else "Tap to copy",
                            color = palette.accent, fontSize = 12.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PreviewButton(shareLabel, buttonPalette, primary = true, icon = Icons.Outlined.Share,
                    modifier = Modifier.width(170.dp), onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Support BYD app")
                            putExtra(Intent.EXTRA_TEXT, "Support BYD app\n\n$description\n\n$SUPPORT_JAR_URL\n\n$cardLabel: $SUPPORT_JAR_CARD")
                        }
                        openIntent(Intent.createChooser(intent, shareLabel))
                    })
                Spacer(Modifier.width(10.dp))
                PreviewButton(if (ukrainian) "Закрити" else "Close", buttonPalette, primary = false,
                    modifier = Modifier.width(138.dp), highlightOnPress = false, onClick = onClose)
            }
        }
    }
}
