package com.bydcollector.collector.ui.compose

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateHintUiContractTest {
    @Test
    fun optionsRowOwnsOneToggleAndKeepsCollectorAlignment() {
        val app = source("ui/compose/BydCollectorApp.kt")
        val row = app.substringAfter("private fun UpdateHintSettingsRow(")
            .substringBefore("private fun ArchiveShareIconButton(")

        assertTrue(row.contains("SwitchControlRow("))
        assertTrue(row.contains("toggle = SwitchToggle(enabled, onChange)"))
        assertTrue(row.contains("heightIn(min = 72.dp)"))
        assertTrue(row.contains("Arrangement.spacedBy(12.dp)"))
        assertTrue(row.contains("fontSize = 13.sp"))
        assertTrue(row.contains("fontSize = 12.sp, lineHeight = 16.sp"))
        assertTrue(row.contains("UpdateHintSettingsButton(language, darkTheme, onSettings)"))
        assertFalse(row.contains("BydSwitch("))
        assertFalse(row.contains("padding(horizontal"))
        assertTrue(app.contains("height(IntrinsicSize.Min)"))
        assertTrue(app.contains("heightIn(min = optionsCardHeight).fillMaxHeight()"))
    }

    @Test
    fun editorKeepsApprovedRangesGeometryAndNativeSample() {
        val editor = source("ui/compose/UpdateHintUi.kt")
        val app = source("ui/compose/BydCollectorApp.kt")

        listOf("TRANSPARENCY_RANGE", "CORNER_RANGE", "BORDER_RANGE", "SIZE_RANGE")
            .forEach { assertTrue(editor.contains("UpdateHintAppearance.$it"), it) }
        assertTrue(editor.contains("Modifier.weight(0.4f)"))
        assertTrue(editor.contains("Modifier.weight(0.6f)"))
        assertTrue(editor.contains("Modifier.width(560.dp).heightIn(max = 600.dp)"))
        assertTrue(editor.contains("Modifier.width(52.dp)"))
        assertTrue(editor.contains("Modifier.width(42.dp)"))
        assertTrue(editor.contains("thumbSize = DpSize(4.dp, 28.dp)"))
        assertTrue(editor.contains("UpdateHintCardView.widthPx(context, appearance)"))
        assertTrue(editor.contains("factory = { UpdateHintCardView(it) }"))
        assertTrue(editor.contains("rememberKeyboardCommit()"))
        assertTrue(app.contains("(updateUiState as? UpdateUiState.Available)?.info?.version ?: appVersionName"))
    }

    @Test
    fun liveWindowFitsTheWholeCardWithoutChangingPreferencesOrDeadlines() {
        val overlay = source("update/UpdateHintOverlay.kt")
        val renderer = source("update/UpdateHintCardView.kt")
        val refresh = overlay.substringAfter("fun refresh(").substringBefore("private fun measurePreferred(")
        val placement = overlay.substringAfter("private fun applyPlacement(").substringBefore("fun dismiss(")
        assertTrue(overlay.contains("container = FrameLayout(context)"))
        assertTrue(overlay.contains("UpdateHintCoordinator.markVisible(id, lifetime.expiresAtElapsedMs)"))
        assertTrue(overlay.contains("UpdateHintCoordinator.updateGeometry(id, preferredSize, preferredWidth, preferredHeight)"))
        assertTrue(placement.contains("view.scaleX = scale"))
        assertTrue(placement.contains("view.scaleY = scale"))
        assertTrue(placement.contains("ceil(preferredWidth * scale)"))
        assertTrue(placement.contains("ceil(preferredHeight * scale)"))
        assertFalse(refresh.contains("lifetime.shown("))
        assertFalse(placement.contains("lifetime.shown("))
        assertFalse(overlay.contains("setUpdateHintAppearance("))
        assertFalse(overlay.contains("FLAG_LAYOUT_IN_SCREEN"))
        assertFalse(renderer.contains("screenWidth"))
    }

    private fun source(relative: String): String = listOf(
        File("src/main/kotlin/com/bydcollector/collector/$relative"),
        File("app/src/main/kotlin/com/bydcollector/collector/$relative")
    ).firstOrNull { it.isFile }?.readText() ?: error("Missing source: $relative")
}
