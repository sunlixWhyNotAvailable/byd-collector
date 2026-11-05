package com.bydcollector.collector.ui.compose

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

data class TailscaleStrings(
    val toggle: String, val title: String, val preparing: String, val signIn: String,
    val openLink: String, val approval: String, val switching: String, val failed: String, val qrUnavailable: String
)

data class TailscaleUiState(
    val known: Boolean = false, val enabled: Boolean = false, val pending: Boolean = false,
    val phase: String = "off", val loginUrl: String? = null, val error: String? = null, val operation: Long = 0
) {
    companion object {
        fun from(bundle: Bundle?) = if (bundle == null) TailscaleUiState() else TailscaleUiState(
            known = true, enabled = bundle.getBoolean("enabled"), pending = bundle.getBoolean("pending"),
            phase = bundle.getString("phase", "off"), loginUrl = bundle.getString("loginUrl"),
            error = bundle.getString("error"), operation = bundle.getLong("operation")
        )
    }
}

@Composable
internal fun TailscaleAuthorization(state: TailscaleUiState, strings: UiStrings, actions: BydCollectorActions) {
    var dismissed by rememberSaveable { mutableStateOf<Long?>(null) }
    val failed = !state.enabled && !state.pending && state.error != null && state.operation != dismissed
    if ((!state.pending || state.enabled) && !failed) return
    val p = LocalBydPalette.current
    val s = strings.tailscale
    val uriHandler = LocalUriHandler.current
    var browserError by remember(state.operation) { mutableStateOf<String?>(null) }
    val dismiss = {
        if (state.pending) actions.onCancelHaTailscale(state.operation) else dismissed = state.operation
    }
    Dialog(onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(p.background.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(0.9f).heightIn(max = 560.dp)
                .background(p.panel, RoundedCornerShape(8.dp)).border(1.dp, p.border, RoundedCornerShape(8.dp))
                .padding(18.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (failed) s.failed else s.title, color = p.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                val url = state.loginUrl?.takeIf { it.startsWith("https://") }
                if (url != null && !failed) {
                    Text(s.signIn, color = p.text, fontSize = 14.sp)
                    val qr = remember(url) { runCatching {
                        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 320, 320)
                        Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).apply {
                            val pixels = IntArray(320 * 320) { i -> if (matrix[i % 320, i / 320]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                            setPixels(pixels, 0, 320, 0, 0, 320, 320)
                        }.asImageBitmap()
                    }.onFailure { android.util.Log.e("CollectorTailscale", "Login QR encoding failed", it) } }
                    qr.getOrNull()?.let { Image(it, s.openLink, Modifier.size(230.dp).align(Alignment.CenterHorizontally)) }
                    if (qr.isFailure) Text(s.qrUnavailable, color = p.red, fontSize = 13.sp)
                    Text(url, color = p.accent, fontSize = 14.sp, textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            try { uriHandler.openUri(url); browserError = null }
                            catch (error: Exception) { browserError = "${error::class.java.simpleName}: ${error.message}" }
                        })
                } else if (!failed) {
                    Text(when (state.phase) {
                        "approval_pending" -> s.approval
                        "switching" -> s.switching
                        else -> s.preparing
                    }, color = p.text, fontSize = 14.sp)
                }
                (state.error ?: browserError)?.let { Text(it, color = p.red, fontSize = 13.sp) }
                ActionButton(if (state.pending) strings.cancel else strings.close, dismiss,
                    modifier = Modifier.align(Alignment.End).height(42.dp).widthIn(min = 140.dp))
            }
        }
    }
}
