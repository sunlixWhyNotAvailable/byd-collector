package com.bydcollector.collector.ui.compose

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BydCollectorUiContractTest {
    @Test
    fun tactileControlsKeepPressedAndPendingContracts() {
        val components = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorComponents.kt").readText()
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()

        assertTrue(components.contains("height(42.dp)"))
        assertTrue(components.contains("fontSize = 12.sp"))
        assertTrue(components.contains("val pressed by interactionSource.collectIsPressedAsState()"))
        assertTrue(components.contains("primary && pressed -> p.accent.copy(alpha = 0.72f)"))
        assertTrue(components.contains("pressed -> p.activeSoft"))
        assertTrue(components.contains("pending: Boolean = false"))
        assertTrue(components.contains("animateDpAsState("))
        assertTrue(components.contains("targetValue = when {"))
        assertTrue(components.contains("pending -> 12.dp"))
        assertTrue(components.contains(".size(width = 56.dp, height = 32.dp)"))
        assertTrue(app.contains(".pressScaleModifier(interactionSource)"))
        assertTrue(app.contains(".background(if (selected) p.active else p.surface, Rounded8)"))
    }

    @Test
    fun mainTabUsesMergedStatusAndMaintenanceActions() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()

        assertFalse(app.contains("MainChannelsCard("))
        assertTrue(app.contains("ActionButton(strings.compactDatabase, actions::onOpenCompactDatabase"))
        assertTrue(app.contains("ActionButton(strings.archiveDatabase, actions::onOpenArchiveDatabase"))
        assertInOrder(app, "StatusRow(strings.mainPolling", "strings.allParameters")
        assertInOrder(app, "StatusRow(\"MQTT\"", "StatusRow(\"InfluxDB\"")
        assertInOrder(app, "ActionButton(strings.compactDatabase", "ActionButton(strings.archiveDatabase")
    }

    @Test
    fun dialogButtonOrderAndMaintenanceCopyStayFixed() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()

        assertInOrder(app, "ActionButton(strings.backgroundSetupOpen", "ActionButton(strings.backgroundSetupDismiss")
        assertInOrder(app, "ActionButton(\n                    text = strings.update", "ActionButton(strings.close")
        assertTrue(app.contains("DatabaseMaintenanceDialog("))
        assertTrue(app.contains(".width(560.dp)"))
        assertTrue(app.contains("if (!state.running && !state.completed && state.error == null) 340.dp else 300.dp"))
        assertInOrder(app, "strings.dbMaintenanceStopWarning", "strings.dbMaintenancePendingTemplate")
        assertInOrder(app, "strings.dbMaintenancePendingTemplate", "strings.dbMaintenanceArchivePendingWarning")
        assertInOrder(app, "strings.dbMaintenanceArchivePendingWarning", "strings.dbMaintenanceConfirmTemplate")
        assertInOrder(app, "strings.dbMaintenanceConfirmTemplate", "strings.operationCannotBeStopped")
        assertInOrder(app, "strings.operationCannotBeStopped", "strings.interruptionDataLossRisk")
        assertTrue(app.contains("fontSize = 15.sp"))
        assertTrue(app.contains("CircularProgressIndicator("))
        assertTrue(app.contains("Modifier.size(28.dp)"))
        assertTrue(app.contains("\"\${strings.step} \${state.stepIndex}/\${state.stepCount}\""))
        assertInOrder(app, "strings.yes", "strings.no")
    }

    @Test
    fun driverAssistCategoryIsHiddenAndHomeIconRemainsCanvas() {
        val app = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorApp.kt").readText()
        val icons = sourceFile("com/bydcollector/collector/ui/compose/BydCollectorIcons.kt").readText()

        assertFalse(app.contains("\"driver_assist\" to strings.driverAssist"))
        assertTrue(icons.contains("Canvas(modifier = modifier)"))
        assertTrue(icons.contains("BottomTabIcon.HOME ->"))
        assertTrue(icons.contains("moveTo(w * 0.18f, h * 0.48f)"))
        assertTrue(icons.contains("lineTo(w * 0.50f, h * 0.18f)"))
        assertTrue(icons.contains("drawRoundRect("))
    }

    private fun assertInOrder(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue(firstIndex >= 0, "Missing first token: $first")
        assertTrue(secondIndex >= 0, "Missing second token: $second")
        assertTrue(firstIndex < secondIndex, "Expected `$first` before `$second`")
    }

    private fun sourceFile(path: String): File {
        return listOf(
            File("src/main/kotlin/$path"),
            File("app/src/main/kotlin/$path")
        ).firstOrNull { it.isFile } ?: error("Missing source file: $path")
    }
}
