package ai.crossmeeting.app.recording

/**
 * Detecção de fala para não pagar silêncio. Porte do detector do desktop
 * (`src/renderer/utils/voiceActivity.ts`) — os parâmetros são os mesmos, e
 * mudanças precisam andar juntas nas duas plataformas.
 *
 * O Deepgram cobra por minuto de áudio transmitido, não por minuto de fala, e o
 * app mandava o stream do começo ao fim da reunião. Medido no banco em
 * 2026-09-14: de 970,9 minutos faturados, ~201 eram silêncio (20,7%). Uma
 * reunião de 60,7 minutos gerou 694 palavras — cerca de 5 minutos de fala numa
 * hora paga.
 *
 * O objetivo é cortar silêncio SEM cortar fala. Errar para o lado de enviar
 * demais custa centavos; cortar uma palavra do usuário custa a confiança dele.
 * Todo parâmetro aqui foi escolhido para falhar enviando.
 *
 * Quatro proteções:
 *
 * 1. **Piso de ruído adaptativo, que sobe e desce.** Um limiar fixo não serve
 *    entre um fone bluetooth e o microfone de um celular sobre a mesa. O piso
 *    desce rápido quando o ambiente fica quieto e sobe devagar quando o ruído
 *    persiste.
 * 2. **Histerese.** Entrar em fala exige mais que continuar nela, para que uma
 *    frase não pisque entre ligado e desligado sílaba a sílaba.
 * 3. **Hangover** — depois que a fala para, segue enviando por ~1,2 s, senão o
 *    fim das frases some nas pausas naturais.
 * 4. **Pré-roll** — guarda os últimos ~300 ms tidos como silêncio e envia junto
 *    quando a fala começa, para a primeira sílaba não se perder.
 */
class VoiceActivityDetector(
    sampleRate: Int,
    /** Amostras por bloco (metade do buffer em bytes, que é PCM 16-bit). */
    frameSamples: Int,
) {
    companion object {
        /** Margem sobre o piso para ENTRAR em fala. Baixa de propósito. */
        private const val ENTER_MARGIN = 1.4
        /** Margem para CONTINUAR em fala — menor, para não picotar a frase. */
        private const val EXIT_MARGIN = 1.1
        /** Abaixo disso é silêncio, por mais quieto que seja o ambiente. */
        private const val ABSOLUTE_FLOOR = 0.004
        /**
         * Acima disso sempre enviamos, ignorando o piso. Limita o estrago de um
         * piso mal calibrado: áudio alto nunca é descartado.
         */
        private const val ALWAYS_SEND_RMS = 0.02
        private const val HANGOVER_MS = 1200
        private const val PREROLL_MS = 300
        /** Velocidade de adaptação do piso: desce rápido, sobe devagar. */
        private const val FLOOR_FALL = 0.30
        private const val FLOOR_RISE = 0.002
    }

    private val framesPerSecond = sampleRate.toDouble() / frameSamples
    private val hangoverFrames = Math.ceil((HANGOVER_MS / 1000.0) * framesPerSecond).toInt()
    private val prerollFrames = maxOf(1, Math.ceil((PREROLL_MS / 1000.0) * framesPerSecond).toInt())

    private var noiseFloor = ABSOLUTE_FLOOR
    private var hangoverLeft = 0
    private var speaking = false
    private val preroll = ArrayDeque<ByteArray>()

    private var framesSent = 0L
    private var framesSkipped = 0L

    /**
     * Decide o que enviar. Lista vazia = silêncio, não envie nada — o
     * `KeepAlive` do [RecordingService] segura o socket aberto.
     */
    fun process(bytes: ByteArray): List<ByteArray> {
        val rms = rmsOf(bytes)

        // O piso segue o ambiente independentemente da decisão de fala:
        // descendo rápido para reagir a uma sala que silenciou, subindo devagar
        // para que uma frase longa não arraste o piso e se mascare.
        noiseFloor = if (rms < noiseFloor) {
            noiseFloor + (rms - noiseFloor) * FLOOR_FALL
        } else {
            noiseFloor + (rms - noiseFloor) * FLOOR_RISE
        }
        if (noiseFloor < ABSOLUTE_FLOOR) noiseFloor = ABSOLUTE_FLOOR

        val margin = if (speaking) EXIT_MARGIN else ENTER_MARGIN
        val loud = rms >= ALWAYS_SEND_RMS || rms > noiseFloor * margin

        if (loud) {
            speaking = true
            hangoverLeft = hangoverFrames
            val out = if (preroll.isNotEmpty()) preroll.toList() + bytes else listOf(bytes)
            preroll.clear()
            framesSent += out.size
            return out
        }

        if (hangoverLeft > 0) {
            hangoverLeft--
            framesSent++
            if (hangoverLeft == 0) speaking = false
            return listOf(bytes)
        }

        speaking = false
        preroll.addLast(bytes)
        while (preroll.size > prerollFrames) preroll.removeFirst()
        framesSkipped++
        return emptyList()
    }

    /** Proporção de blocos não enviados — para medir a economia real. */
    fun skippedRatio(): Double {
        val total = framesSent + framesSkipped
        return if (total == 0L) 0.0 else framesSkipped.toDouble() / total
    }

    fun sentFrames(): Long = framesSent
    fun skippedFrames(): Long = framesSkipped

    /** RMS normalizado em 0..1 de PCM 16-bit little-endian. */
    private fun rmsOf(bytes: ByteArray): Double {
        var sum = 0.0
        var i = 0
        var samples = 0
        while (i + 1 < bytes.size) {
            // Little-endian: byte baixo primeiro. O alto precisa do sinal.
            val sample = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort()
            val v = sample / 32768.0
            sum += v * v
            samples++
            i += 2
        }
        return if (samples == 0) 0.0 else Math.sqrt(sum / samples)
    }
}
