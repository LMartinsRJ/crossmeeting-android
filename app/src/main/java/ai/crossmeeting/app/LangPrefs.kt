package ai.crossmeeting.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LangPrefs {
    private const val PREFS_NAME = "cm_prefs"
    private const val KEY_LANG = "ui_language"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "pt") ?: "pt"

    fun set(context: Context, lang: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang).apply()
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(if (lang == "en") "en" else "pt-BR")
        )
    }
}
