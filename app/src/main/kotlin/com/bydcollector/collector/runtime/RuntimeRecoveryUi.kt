package com.bydcollector.collector.runtime

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydcollector.collector.ui.compose.ActionButton
import com.bydcollector.collector.ui.compose.BydCollectorTheme
import com.bydcollector.collector.ui.compose.DashboardSurface
import com.bydcollector.collector.ui.compose.LocalBydPalette
import com.bydcollector.collector.ui.compose.UiLanguage

internal enum class RuntimeRecoveryUiPhase {
    WAITING,
    CHECKING,
    RECOVERING,
    INSTALLING,
    FAILED
}

internal fun runtimeRecoveryPhase(
    status: RuntimeRecoveryStatus,
    previous: RuntimeRecoveryUiPhase?
): RuntimeRecoveryUiPhase? = when (status) {
    RuntimeRecoveryStatus.WAITING -> RuntimeRecoveryUiPhase.WAITING
    RuntimeRecoveryStatus.CHECKING -> if (previous in setOf(RuntimeRecoveryUiPhase.RECOVERING,
            RuntimeRecoveryUiPhase.FAILED)) RuntimeRecoveryUiPhase.RECOVERING else RuntimeRecoveryUiPhase.CHECKING
    RuntimeRecoveryStatus.RETRYING -> RuntimeRecoveryUiPhase.RECOVERING
    RuntimeRecoveryStatus.FAILED -> RuntimeRecoveryUiPhase.FAILED
    RuntimeRecoveryStatus.READY -> null
    RuntimeRecoveryStatus.BOOTSTRAPPING -> RuntimeRecoveryUiPhase.INSTALLING
}

internal data class RuntimeDialogRecoveryHost(
    val phase: RuntimeRecoveryUiPhase? = null,
    val language: UiLanguage = UiLanguage.UK,
    val darkTheme: Boolean = true,
    val onRetry: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onDialogViewChanged: (String, View?) -> Unit = { _, _ -> }
)

internal val LocalRuntimeDialogRecovery = staticCompositionLocalOf { RuntimeDialogRecoveryHost() }

/** Reports this native Compose Dialog's actual window and blocks stale controls during recovery. */
@Composable
internal fun BoxScope.RuntimeDialogRecoveryShield(dialogId: String) {
    val recovery = LocalRuntimeDialogRecovery.current
    val dialogView = LocalView.current
    DisposableEffect(dialogId, dialogView) {
        val observer = dialogView.viewTreeObserver
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { _ ->
            // MainActivity re-reads hasWindowFocus() at the eligibility decision boundary.
            recovery.onDialogViewChanged(dialogId, dialogView)
        }
        if (observer.isAlive) observer.addOnWindowFocusChangeListener(focusListener)
        recovery.onDialogViewChanged(dialogId, dialogView)
        onDispose {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(focusListener)
            recovery.onDialogViewChanged(dialogId, null)
        }
    }
    recovery.phase?.let { phase ->
        RuntimeRecoveryPanel(
            language = recovery.language,
            phase = phase,
            darkTheme = recovery.darkTheme,
            onRetry = recovery.onRetry,
            onClose = recovery.onClose,
            preserveContent = true,
            modifier = Modifier.matchParentSize()
        )
    }
}

private data class RuntimeRecoveryCopy(
    val title: String,
    val message: String,
    val retry: String,
    val close: String
)

private fun runtimeRecoveryCopy(language: UiLanguage, phase: RuntimeRecoveryUiPhase): RuntimeRecoveryCopy? =
    when (language) {
        UiLanguage.UK -> when (phase) {
            RuntimeRecoveryUiPhase.WAITING, RuntimeRecoveryUiPhase.CHECKING -> null
            RuntimeRecoveryUiPhase.RECOVERING -> RuntimeRecoveryCopy(
                "Відновлення з’єднання", "Тимчасово немає відповіді. Повторна спроба виконується автоматично.", "Повторити", "Закрити")
            RuntimeRecoveryUiPhase.INSTALLING -> RuntimeRecoveryCopy(
                "Оновлення інтерфейсу", "Зачекайте, поки завершиться відновлення BYD Collector.", "Повторити", "Закрити")
            RuntimeRecoveryUiPhase.FAILED -> RuntimeRecoveryCopy(
                "Не вдалося відновити з’єднання", "Система BYD Collector недоступна. Спробуйте ще раз або закрийте застосунок.", "Повторити", "Закрити")
        }
        UiLanguage.EN -> when (phase) {
            RuntimeRecoveryUiPhase.WAITING, RuntimeRecoveryUiPhase.CHECKING -> null
            RuntimeRecoveryUiPhase.RECOVERING -> RuntimeRecoveryCopy(
                "Restoring the connection", "There is no response yet. Another attempt will run automatically.", "Retry", "Close")
            RuntimeRecoveryUiPhase.INSTALLING -> RuntimeRecoveryCopy(
                "Updating the interface", "Wait for BYD Collector recovery to finish.", "Retry", "Close")
            RuntimeRecoveryUiPhase.FAILED -> RuntimeRecoveryCopy(
                "Could not restore the connection", "BYD Collector is unavailable. Try again or close the app.", "Retry", "Close")
        }
    }

@Composable
internal fun RuntimeRecoveryPanel(
    language: UiLanguage,
    phase: RuntimeRecoveryUiPhase,
    darkTheme: Boolean = true,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    preserveContent: Boolean = false,
    modifier: Modifier = Modifier
) {
    BydCollectorTheme(darkTheme) {
        val palette = LocalBydPalette.current
        val copy = runtimeRecoveryCopy(language, phase)
        Box(
            modifier = modifier.fillMaxSize()
                .background(if (copy == null && preserveContent) Color.Transparent else palette.background)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center
        ) {
            if (copy == null) return@Box
            DashboardSurface(Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("BYD Collector", color = palette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(copy.title, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center)
                    Text(copy.message, color = palette.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                    if (phase == RuntimeRecoveryUiPhase.FAILED) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionButton(copy.retry, onRetry, Modifier.weight(1f), primary = true)
                            ActionButton(copy.close, onClose, Modifier.weight(1f), danger = true)
                        }
                    } else {
                        Spacer(Modifier.height(2.dp))
                        CircularProgressIndicator(color = palette.accent, strokeWidth = 3.dp)
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}
