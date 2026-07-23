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
    val icuId = runCatching { android.icu.util.TimeZone.getDefault().id }.getOrDefault("UTC")
    // Se o sistema está em UTC/GMT, tenta inferir pelo locale do dispositivo
    if (icuId == "UTC" || icuId == "GMT" || icuId.startsWith("Etc/")) {
        val locale = java.util.Locale.getDefault()
        if (locale.language == "pt" && locale.country == "BR") {
            return ZoneId.of("America/Sao_Paulo")
        }
    }
    return runCatching { ZoneId.of(icuId) }.getOrDefault(ZoneId.of("America/Sao_Paulo"))
}
