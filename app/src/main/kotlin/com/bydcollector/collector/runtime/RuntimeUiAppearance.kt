package com.bydcollector.collector.runtime

import android.content.Context
import android.content.Intent
import com.bydcollector.collector.ui.compose.UiLanguage

/** Only presentation preferences are cached locally, so recovery never needs working owner IPC. */
internal object RuntimeUiAppearance {
    private const val LANGUAGE = "runtimeUiLanguage"
    private const val DARK = "runtimeUiDark"
    private fun preferences(context: Context) = context.getSharedPreferences("runtime_ui_appearance", Context.MODE_PRIVATE)

    fun language(context: Context): UiLanguage = UiLanguage.fromCode(preferences(context).getString(LANGUAGE, "uk").orEmpty())
    fun dark(context: Context): Boolean = preferences(context).getBoolean(DARK, true)

    fun save(context: Context, language: UiLanguage? = null, dark: Boolean? = null) {
        val prefs = preferences(context)
        val editor = prefs.edit()
        if (language != null && prefs.getString(LANGUAGE, null) != language.code) editor.putString(LANGUAGE, language.code)
        if (dark != null && prefs.getBoolean(DARK, true) != dark) editor.putBoolean(DARK, dark)
        editor.apply()
    }

    fun forLaunch(intent: Intent, language: UiLanguage, dark: Boolean): Intent =
        intent.putExtra(LANGUAGE, language.code).putExtra(DARK, dark)

    fun acceptLaunch(context: Context, intent: Intent) {
        // MainActivity is protected by the runtime signature permission.
        save(context, intent.getStringExtra(LANGUAGE)?.let(UiLanguage::fromCode),
            if (intent.hasExtra(DARK)) intent.getBooleanExtra(DARK, true) else null)
        intent.removeExtra(LANGUAGE)
        intent.removeExtra(DARK)
    }
}
