package ai.crossmeeting.app.home

import android.content.Context
import android.telephony.TelephonyManager
import java.time.ZoneId

/**
 * Retorna o ZoneId para exibição de horários.
 * Prioridade: Settings.System → ICU → SIM/rede → America/Sao_Paulo
 */
internal fun deviceZone(context: Context? = null): ZoneId {
    // Settings.System — reflete exatamente o que o usuário configurou em Data e Hora
    if (context != null) {
        val settingsId = runCatching {
            android.provider.Settings.System.getString(context.contentResolver, "time_zone")
        }.getOrNull()
        if (!settingsId.isNullOrBlank() && settingsId != "GMT" && settingsId != "UTC") {
            runCatching { ZoneId.of(settingsId) }.getOrNull()?.let { return it }
        }
    }

    // ICU — mais confiável que ZoneId.systemDefault()
    val icuTz = runCatching { android.icu.util.TimeZone.getDefault() }.getOrNull()
    val icuId = icuTz?.id ?: "UTC"
    if (icuId != "UTC" && icuId != "GMT" && (icuTz?.rawOffset ?: 0) != 0) {
        runCatching { ZoneId.of(icuId) }.getOrNull()?.let { return it }
    }

    // País via SIM/rede — sem permissão de localização
    if (context != null) {
        val countryZone = runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val country = (tm?.networkCountryIso?.takeIf { it.isNotBlank() }
                ?: tm?.simCountryIso?.takeIf { it.isNotBlank() })?.uppercase()
            country?.let { countryToZone(it) }
        }.getOrNull()
        if (countryZone != null) return countryZone
    }

    return ZoneId.of("America/Sao_Paulo")
}

private fun countryToZone(iso2: String): ZoneId? {
    val zones = android.icu.util.TimeZone.getAvailableIDs(iso2)
    if (zones.isNullOrEmpty()) return null
    val preferred = zones.firstOrNull { !it.startsWith("Etc/") } ?: zones.first()
    return runCatching { ZoneId.of(preferred) }.getOrNull()
}
