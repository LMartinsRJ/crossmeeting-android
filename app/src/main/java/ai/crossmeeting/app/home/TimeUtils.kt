package ai.crossmeeting.app.home

import android.content.Context
import java.time.ZoneId

private const val PREFS_NAME = "cm_prefs"
private const val KEY_TIMEZONE = "user_timezone"

/** Persiste o fuso escolhido pelo usuário. Vazio = auto-detect. */
fun saveUserTimezone(context: Context, zoneId: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(KEY_TIMEZONE, zoneId).apply()
}

fun loadUserTimezone(context: Context): String =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_TIMEZONE, "") ?: ""

/**
 * Retorna o ZoneId a ser usado para exibição de horários.
 * Prioridade:
 *  1. Preferência salva pelo usuário no app
 *  2. Fuso do sistema via ICU (mais confiável que ZoneId.systemDefault())
 *  3. Fallback por locale: se o sistema estiver em UTC mas o locale for pt-BR,
 *     assume America/Sao_Paulo (caso comum de dispositivo com fuso mal configurado)
 */
internal fun deviceZone(context: Context? = null): ZoneId {
    // 1. Preferência salva
    if (context != null) {
        val saved = loadUserTimezone(context)
        if (saved.isNotBlank()) return runCatching { ZoneId.of(saved) }.getOrNull() ?: autoZone()
    }
    return autoZone()
}

private fun autoZone(): ZoneId {
    val icuTz = runCatching { android.icu.util.TimeZone.getDefault() }.getOrNull()
    val icuId = icuTz?.id ?: "UTC"
    // rawOffset == 0 significa UTC/GMT — tenta inferir pelo locale
    val isUtc = (icuTz?.rawOffset ?: 0) == 0
    if (isUtc) {
        val locale = java.util.Locale.getDefault()
        // Qualquer locale de português (pt, pt-BR, pt-PT) cai em São Paulo
        // como padrão mais seguro para usuário brasileiro
        if (locale.language == "pt") {
            android.util.Log.d("CMTimezone", "ICU=$icuId offset=0, locale=${locale}, usando America/Sao_Paulo")
            return ZoneId.of("America/Sao_Paulo")
        }
    }
    android.util.Log.d("CMTimezone", "ICU=$icuId rawOffset=${icuTz?.rawOffset} locale=${java.util.Locale.getDefault()}")
    return runCatching { ZoneId.of(icuId) }.getOrDefault(ZoneId.of("America/Sao_Paulo"))
}
