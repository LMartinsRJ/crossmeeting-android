package ai.crossmeeting.app

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime

/**
 * Cache do briefing diário.
 *
 * Cada geração custa uma chamada ao Claude, então o texto é guardado e reusado
 * dentro do mesmo dia — mesmo comportamento do desktop.
 *
 * A chave inclui o e-mail do usuário: sem isso, a próxima conta a logar neste
 * aparelho leria o briefing da anterior, que contém nomes e pendências pessoais.
 * Foi exatamente o vazamento corrigido no desktop em 2026-08-12.
 */
object BriefingCache {
    private const val PREFS_NAME = "cm_prefs"
    private const val KEY_PREFIX = "briefing_"

    /** O briefing matinal vale a partir das 08h locais. */
    private val MORNING = LocalTime.of(8, 0)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun keyFor(email: String) = "$KEY_PREFIX${email.lowercase()}"

    /** Texto guardado para hoje, ou null se não houver ou estiver desatualizado. */
    fun get(context: Context, email: String): String? {
        val raw = prefs(context).getString(keyFor(email), null) ?: return null
        // Formato: "AAAA-MM-DD|HH:mm|texto"
        val parts = raw.split("|", limit = 3)
        if (parts.size < 3) return null

        val (dateStr, timeStr, text) = Triple(parts[0], parts[1], parts[2])
        if (dateStr != LocalDate.now().toString()) return null
        if (text.isBlank()) return null

        // Gerado antes das 08h e já passou das 08h: refaz para o usuário
        // receber a versão do dia, não a da madrugada.
        val generatedAt = runCatching { LocalTime.parse(timeStr) }.getOrNull()
        if (generatedAt != null && generatedAt.isBefore(MORNING) && LocalTime.now().isAfter(MORNING)) {
            return null
        }
        return text
    }

    fun put(context: Context, email: String, text: String) {
        val stamp = "${LocalDate.now()}|${LocalTime.now().withSecond(0).withNano(0)}|$text"
        prefs(context).edit().putString(keyFor(email), stamp).apply()
    }

    /** Chamado no logout — o briefing tem dados pessoais e não pode sobreviver à troca de conta. */
    fun clearAll(context: Context) {
        val p = prefs(context)
        val editor = p.edit()
        p.all.keys.filter { it.startsWith(KEY_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }
}

@kotlinx.serialization.Serializable
data class BriefingRequest(val firstName: String, val dateLabel: String)

@kotlinx.serialization.Serializable
data class BriefingResponse(val text: String? = null, val error: String? = null)
