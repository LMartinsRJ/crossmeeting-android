package ai.crossmeeting.app.home

import java.time.ZoneId

/**
 * Lê o fuso horário diretamente da ICU do Android — a mesma fonte que o relógio do
 * sistema usa. ZoneId.systemDefault() pode retornar um valor em cache do JVM após o
 * usuário mudar o fuso nas Configurações do Android (sem reiniciar o processo),
 * enquanto android.icu.util.TimeZone.getDefault() sempre reflete o valor atual.
 */
internal fun deviceZone(): ZoneId =
    ZoneId.of(android.icu.util.TimeZone.getDefault().id)
