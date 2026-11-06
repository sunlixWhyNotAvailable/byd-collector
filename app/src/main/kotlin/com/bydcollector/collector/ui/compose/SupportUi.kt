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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.bydcollector.collector.runtime.RuntimeDialogRecoveryShield

private const val PROJECT_REPOSITORY_URL = "https://github.com/sunlixWhyNotAvailable/byd-collector"
private const val SUPPORT_PAYPAL_EMAIL = "opriva1@gmail.com"
private const val SUPPORT_JAR_URL = "https://send.monobank.ua/jar/bKFV15i9e"
private const val SUPPORT_JAR_CARD = "4874 1000 3354 3078"

@Composable
internal fun SupportHeaderAction(language: UiLanguage, onClick: () -> Unit) {
    val palette = LocalBydPalette.current
    val supportPress = rememberPressFeedback(releaseHoldMillis = VISUAL_PRESS_HOLD_MS)
    Text(if (language == UiLanguage.UK) "Проєкт і підтримка" else "Project and support",
        color = palette.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(4.dp))
            .background(if (supportPress.pressed) palette.activeSoft else Color.Transparent)
            .then(supportPress.modifier)
            .clickable(role = Role.Button, interactionSource = supportPress.interactionSource,
                indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp))
}

// Exact approved Preview interactions (HUD parity, 2026-11-05). BydCollectorTheme does not
// override Foundation's default indication, so link/copy/card targets keep it; only the
// header, selector and footer use the press hold/scale feedback.
@Composable
internal fun SupportDialog(palette: BydPalette, language: UiLanguage, onClose: () -> Unit) {
    val ukrainian = language == UiLanguage.UK
    val context = LocalContext.current
    val buttonPalette = (if (palette.dark) darkPalette() else lightPalette()).copy(
        accent = palette.accent, panelAlt = palette.panelAlt,
        borderStrong = palette.borderStrong, text = palette.text,
    )
    var paypalSelected by rememberSaveable { mutableStateOf(false) }
    val description = if (ukrainian)
        "BYD Collector — безкоштовний проєкт із відкритим вихідним кодом. Ви можете добровільно підтримати його розробку та вдосконалення."
        else "BYD Collector is a free, open-source project. You can voluntarily support its development and improvement."
    val cardLabel = if (ukrainian) "Номер картки Банки" else "Jar card number"
    val emailLabel = if (ukrainian) "Email для переказу через PayPal" else "Email for PayPal transfers"
    val shareLabel = if (ukrainian) "Поділитися" else "Share"
    fun openIntent(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, if (ukrainian) "Немає застосунку для цієї дії"
                else "No app can handle this action", Toast.LENGTH_SHORT).show()
        }
    }
    fun copy(label: String, value: String, confirmation: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(contentAlignment = Alignment.Center) {
            Column(Modifier.width(820.dp).fillMaxHeight(0.9f).clip(RoundedCornerShape(8.dp))
                .background(palette.surface).border(1.dp, palette.borderStrong, RoundedCornerShape(8.dp))
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(if (ukrainian) "Проєкт і підтримка" else "Project and support",
                        modifier = Modifier.weight(1f),
                        color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.width(280.dp).selectableGroup().clip(RoundedCornerShape(7.dp))
                        .background(palette.panelAlt).border(1.dp, palette.borderStrong, RoundedCornerShape(7.dp))
                        .padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf("monobank", "PayPal").forEachIndexed { index, label ->
                            val selected = paypalSelected == (index == 1)
                            val press = rememberPressFeedback(releaseHoldMillis = VISUAL_PRESS_HOLD_MS)
                            Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(5.dp))
                                .background(if (press.pressed) palette.accent.copy(alpha = if (palette.dark) 0.24f else 0.14f)
                                    else if (selected) palette.active else Color.Transparent)
                                .then(press.modifier)
                                .selectable(selected = selected, role = Role.Tab,
                                    interactionSource = press.interactionSource, indication = null,
                                    onClick = { paypalSelected = index == 1 }), contentAlignment = Alignment.Center) {
                                Text(label, color = if (selected) palette.text else palette.muted,
                                    fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
                Text(description, color = palette.text, fontSize = 15.sp)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button) { openIntent(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_REPOSITORY_URL))) }
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null,
                            tint = palette.accent, modifier = Modifier.size(18.dp))
                        Text(if (ukrainian) "Вихідний код на GitHub" else "Source code on GitHub",
                            color = palette.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.clip(RoundedCornerShape(7.dp)).background(palette.field)
                            .border(1.dp, palette.border, RoundedCornerShape(7.dp))
                            .clickable(role = Role.Button, onClickLabel = if (ukrainian) "Копіювати посилання на репозиторій" else "Copy repository link") {
                                copy("BYD Collector", PROJECT_REPOSITORY_URL,
                                    if (ukrainian) "Посилання скопійовано" else "Link copied")
                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val linkColor = if (palette.dark) palette.yellow else Color(0xFF8A6500)
                        Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null,
                            tint = linkColor, modifier = Modifier.size(18.dp))
                        Text(PROJECT_REPOSITORY_URL, color = linkColor, fontSize = 14.sp)
                    }
                }
                if (paypalSelected) {
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                        Text(if (ukrainian) "У PayPal виберіть «Надіслати», вставте цю адресу як одержувача та вкажіть суму переказу."
                            else "In PayPal, choose Send, paste this email as the recipient and enter the amount.",
                            color = palette.muted, fontSize = 16.sp)
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(palette.field)
                            .border(1.dp, palette.border, RoundedCornerShape(7.dp))
                            .clickable(role = Role.Button, onClickLabel = if (ukrainian) "Копіювати email" else "Copy email") {
                                copy(emailLabel, SUPPORT_PAYPAL_EMAIL, if (ukrainian) "Email скопійовано" else "Email copied")
                            }.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(emailLabel, color = palette.muted, fontSize = 14.sp)
                            Text(SUPPORT_PAYPAL_EMAIL, color = palette.text, fontFamily = FontFamily.Monospace,
                                fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (ukrainian) "Натисніть, щоб скопіювати" else "Tap to copy",
                                color = palette.accent, fontSize = 14.sp)
                        }
                    }
                } else Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.mono_support_qr),
                        contentDescription = if (ukrainian) "QR-код Банки для поповнення" else "Scan to support via monobank",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.weight(0.42f).fillMaxHeight())
                    Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Support BYD app", color = palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
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
                                        copy("Support BYD app", SUPPORT_JAR_URL,
                                            if (ukrainian) "Посилання скопійовано" else "Link copied")
                                    }.padding(horizontal = 6.dp, vertical = 10.dp))
                        }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(palette.field)
                            .border(1.dp, palette.border, RoundedCornerShape(7.dp))
                            .clickable(role = Role.Button, onClickLabel = if (ukrainian) "Копіювати номер картки" else "Copy card number") {
                                copy(cardLabel, SUPPORT_JAR_CARD,
                                    if (ukrainian) "Номер картки скопійовано" else "Card number copied")
                            }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(cardLabel, color = palette.muted, fontSize = 13.sp)
                            Text(SUPPORT_JAR_CARD, color = palette.text, fontFamily = FontFamily.Monospace,
                                fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (ukrainian) "Натисніть, щоб скопіювати" else "Tap to copy",
                                color = palette.accent, fontSize = 12.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    PreviewButton(shareLabel, buttonPalette, primary = true, icon = Icons.Outlined.Share,
                        modifier = Modifier.width(170.dp), onClick = {
                            // Only the selected method is shared; the chooser never sends automatically.
                            val paymentDetails = if (paypalSelected) "$emailLabel: $SUPPORT_PAYPAL_EMAIL"
                                else "$SUPPORT_JAR_URL\n\n$cardLabel: $SUPPORT_JAR_CARD"
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Support BYD app")
                                putExtra(Intent.EXTRA_TEXT, "BYD Collector\n\n$description\n\n$PROJECT_REPOSITORY_URL\n\n$paymentDetails")
                            }
                            openIntent(Intent.createChooser(intent, shareLabel))
                        })
                    Spacer(Modifier.width(10.dp))
                    PreviewButton(if (ukrainian) "Закрити" else "Close", buttonPalette, primary = false,
                        modifier = Modifier.width(138.dp), highlightOnPress = false, onClick = onClose)
                }
            }
            RuntimeDialogRecoveryShield("support")
        }
    }
}
