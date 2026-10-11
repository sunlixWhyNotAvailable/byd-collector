package com.bydcollector.collector

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.AbstractComposeView
import androidx.core.view.children

// Manually created Activities are not owned/disposed by createEmptyComposeRule.
internal fun ComponentActivity.disposeTestComposition() {
    findViewById<ViewGroup>(android.R.id.content).children
        .filterIsInstance<AbstractComposeView>().forEach { it.disposeComposition() }
}
