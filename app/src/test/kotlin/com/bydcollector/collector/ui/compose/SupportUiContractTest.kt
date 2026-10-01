package com.bydcollector.collector.ui.compose

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportUiContractTest {
    @Test
    fun approvedSupportInteractionsAndAssetsStayScoped() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val compose = File(main, "kotlin/com/bydcollector/collector/ui/compose")
        val source = File(compose, "SupportUi.kt").readText()
        val dialog = source.substringAfter("internal fun SupportDialog(")
        val app = File(compose, "BydCollectorApp.kt").readText()
        val controls = File(compose, "UpdateHintUi.kt").readText()
        val footer = controls.substringAfter("internal fun PreviewButton(")
            .substringBefore("private fun PreviewIconButton(")

        assertEquals(3, Regex("\\.clickable\\(role = Role.Button").findAll(dialog).count())
        assertFalse(dialog.contains("rememberPressFeedback("))
        assertFalse(dialog.contains("indication = null"))
        assertFalse(dialog.contains("delay("))
        assertTrue(dialog.contains("highlightOnPress = false, onClick = onClose"))
        assertTrue(dialog.contains("icon = Icons.Outlined.Share"))
        assertTrue(footer.contains("highlightOnPress: Boolean = true"))
        assertTrue(footer.contains("press.pressed && highlightOnPress"))
        assertTrue(footer.contains("onClick = onClick"))
        assertFalse(footer.contains("delay("))
        // Both HUD and Production use the Foundation indication, not a Material ripple.
        for (host in listOf(app, File(compose, "BydCollectorTheme.kt").readText(),
            File(main, "kotlin/com/bydcollector/collector/MainActivity.kt").readText())) {
            assertFalse(host.contains("MaterialTheme {"))
            assertFalse(host.contains("LocalIndication provides"))
        }
        assertTrue(app.contains("var showSupportDialog by rememberSaveable"))
        assertTrue(app.contains("if (showSupportDialog && updateUiState == UpdateUiState.Hidden)"))
        assertTrue(app.contains("SupportHeaderAction(language, onSupportClick)"))
        for (token in listOf("https://send.monobank.ua/jar/bKFV15i9e", "4874 1000 3354 3078",
            "Intent.ACTION_VIEW", "Intent.ACTION_SEND", "Intent.createChooser",
            "ClipboardManager", "ActivityNotFoundException", "onDismissRequest = onClose",
            "Підтримати розробку", "Support development", "Закрити", "Close")) {
            assertTrue(source.contains(token), token)
        }
        val qr = File(main, "res/drawable-nodpi/mono_support_qr.jpg").readBytes()
        assertEquals("5e2a0637c30f24c2bc0b79488065ddd3a0ddf9d4f0b683d6b86c72104f4e2231",
            MessageDigest.getInstance("SHA-256").digest(qr).joinToString("") { "%02x".format(it) })
    }
}
